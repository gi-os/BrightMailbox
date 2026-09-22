package com.gios.brightmailbox.sync

import com.gios.brightmailbox.sync.Catchup.Ask
import com.gios.brightmailbox.sync.Catchup.Checkpoint
import com.gios.brightmailbox.sync.Catchup.Page
import com.gios.brightmailbox.sync.Catchup.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatchupTest {

    @Test
    fun `checkpoint round trips through text`() {
        val cp = Checkpoint(1700000000L, 4213L)
        assertEquals(cp, Checkpoint.decode(cp.encode()))
        assertNull(Checkpoint.decode(null))
        assertNull(Checkpoint.decode(""))
        assertNull(Checkpoint.decode("garbage"))
        assertNull(Checkpoint.decode("12-"))
    }

    @Test
    fun `highest stored ignores other validities and tagged ids`() {
        val ids = listOf("7-10", "7-42", "8-900", "SENT:7-5000", "junk", "7-x", "7-41")
        assertEquals(42L, Catchup.highestStored(ids, 7L))
        assertEquals(900L, Catchup.highestStored(ids, 8L))
        assertNull(Catchup.highestStored(ids, 9L))
        assertNull(Catchup.highestStored(emptyList(), 7L))
    }

    @Test
    fun `first ask continues above the bookmark`() {
        val run = Catchup.Run(Checkpoint(7L, 100L), pageSize = 20)
        assertEquals(Ask(100L, 20), run.first())
    }

    @Test
    fun `first ask bootstraps with no bookmark`() {
        val run = Catchup.Run(null, pageSize = 20)
        assertEquals(Ask(null, 20), run.first())
    }

    @Test
    fun `a full page asks for the next one above what it saw`() {
        val run = Catchup.Run(Checkpoint(7L, 100L), pageSize = 20)
        val ask = run.first()
        val step = run.advance(ask, Page(validity = 7L, lowest = 101L, highest = 120L, more = true))
        assertEquals(Step.More(Ask(120L, 20)), step)
        assertEquals(Checkpoint(7L, 120L), run.checkpoint)
    }

    @Test
    fun `a short page means caught up`() {
        val run = Catchup.Run(Checkpoint(7L, 100L), pageSize = 20)
        val step = run.advance(run.first(), Page(7L, lowest = 101L, highest = 108L, more = false))
        assertEquals(Step.Done(caughtUp = true), step)
        assertEquals(Checkpoint(7L, 108L), run.checkpoint)
    }

    @Test
    fun `an empty page keeps the bookmark and stops`() {
        val run = Catchup.Run(Checkpoint(7L, 100L), pageSize = 20)
        val step = run.advance(run.first(), Page(7L, lowest = null, highest = null, more = false))
        assertEquals(Step.Done(caughtUp = true), step)
        assertEquals(Checkpoint(7L, 100L), run.checkpoint)
    }

    @Test
    fun `eighty messages in a drawer are all fetched in one run`() {
        // Twenty a page, eighty waiting: four full pages then nothing. The old sync
        // stopped after the first and never came back for the rest.
        val run = Catchup.Run(Checkpoint(7L, 1000L), pageSize = 20)
        var ask = run.first()
        var uid = 1000L
        var pages = 0
        while (true) {
            uid += 20
            pages++
            val step = run.advance(ask, Page(7L, lowest = uid - 19, highest = uid, more = uid < 1080L))
            if (step is Step.Done) {
                assertTrue(step.caughtUp)
                break
            }
            ask = (step as Step.More).ask
            assertEquals(uid, ask.after)
        }
        assertEquals(4, pages)
        assertEquals(Checkpoint(7L, 1080L), run.checkpoint)
    }

    @Test
    fun `the per-run cap stops the walk and remembers where`() {
        val run = Catchup.Run(Checkpoint(7L, 0L), pageSize = 50, maxPages = 3)
        var ask = run.first()
        var step: Step? = null
        for (i in 1..3) {
            step = run.advance(ask, Page(7L, lowest = i * 50L - 49, highest = i * 50L, more = true))
            if (step is Step.More) ask = step.ask
        }
        assertEquals(Step.Done(caughtUp = false), step)
        assertEquals(Checkpoint(7L, 150L), run.checkpoint)
        // The next run picks up exactly there.
        val next = Catchup.Run(run.checkpoint, pageSize = 50, maxPages = 3)
        assertEquals(Ask(150L, 50), next.first())
    }

    @Test
    fun `a bootstrap page on an empty database sets the bookmark at the top and stops`() {
        val run = Catchup.Run(null, pageSize = 20)
        val ask = run.first()
        val step = run.advance(ask, Page(9L, lowest = 4981L, highest = 5000L, more = false), known = null)
        assertEquals(Step.Done(caughtUp = true), step)
        assertEquals(Checkpoint(9L, 5000L), run.checkpoint)
    }

    @Test
    fun `a bootstrap page never lowers a bookmark the rows already justify`() {
        val run = Catchup.Run(null, pageSize = 20)
        run.advance(run.first(), Page(9L, lowest = 4981L, highest = 5000L, more = false), known = 6000L)
        assertEquals(Checkpoint(9L, 6000L), run.checkpoint)
    }

    @Test
    fun `the first run after the update walks up from what the rows knew`() {
        // No bookmark stored, a year of mail in the database that stops at 4900, and a
        // newest page that starts at 4981: eighty messages nobody has asked for.
        val run = Catchup.Run(null, pageSize = 20)
        val ask = run.first()
        assertEquals(Ask(null, 20), ask)
        val step = run.advance(ask, Page(9L, lowest = 4981L, highest = 5000L, more = false), known = 4900L)
        assertEquals(Step.More(Ask(4900L, 20)), step)
        assertEquals(Checkpoint(9L, 4900L), run.checkpoint)
    }

    @Test
    fun `a bootstrap page that overlaps the rows is the top`() {
        val run = Catchup.Run(null, pageSize = 20)
        val step = run.advance(run.first(), Page(9L, lowest = 4981L, highest = 5000L, more = false), known = 4990L)
        assertEquals(Step.Done(caughtUp = true), step)
        assertEquals(Checkpoint(9L, 5000L), run.checkpoint)
    }

    @Test
    fun `needsKnown only for a bootstrap or a stale bookmark`() {
        assertTrue(Catchup.needsKnown(null, Ask(null, 20), 9L))
        assertTrue(Catchup.needsKnown(Checkpoint(7L, 100L), Ask(100L, 20), 8L))
        assertFalse(Catchup.needsKnown(Checkpoint(7L, 100L), Ask(100L, 20), 7L))
    }

    @Test
    fun `a validity change throws the bookmark away and restarts from the rows`() {
        val run = Catchup.Run(Checkpoint(7L, 4000L), pageSize = 20)
        val step = run.advance(run.first(), Page(validity = 8L, lowest = 11L, highest = 30L, more = true), known = 12L)
        // Not "carry on from 4000 under the new numbering" — that would skip everything.
        assertEquals(Step.More(Ask(12L, 20)), step)
        assertEquals(Checkpoint(8L, 12L), run.checkpoint)
    }

    @Test
    fun `a validity change with nothing stored bootstraps`() {
        val run = Catchup.Run(Checkpoint(7L, 4000L), pageSize = 20)
        val step = run.advance(run.first(), Page(validity = 8L, lowest = 11L, highest = 30L, more = true), known = null)
        assertEquals(Step.More(Ask(null, 20)), step)
        assertNull(run.checkpoint)
        val after = run.advance(Ask(null, 20), Page(8L, lowest = 12L, highest = 31L, more = false), known = null)
        assertEquals(Step.Done(caughtUp = true), after)
        assertEquals(Checkpoint(8L, 31L), run.checkpoint)
    }

    @Test
    fun `the bookmark never goes backwards`() {
        val run = Catchup.Run(Checkpoint(7L, 500L), pageSize = 20)
        run.advance(run.first(), Page(7L, lowest = 390L, highest = 400L, more = false))
        assertEquals(Checkpoint(7L, 500L), run.checkpoint)
        assertFalse(run.pages == 0)
    }
}
