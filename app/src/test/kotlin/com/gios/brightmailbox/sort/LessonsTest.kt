package com.gios.brightmailbox.sort

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Where a training label comes from is the most consequential decision in the sorter and
 * the easiest to get silently wrong, so it is tested like the rest of this package: pure
 * Kotlin, no emulator.
 */
class LessonsTest {

    @Test
    fun `starred outranks a bootstrap that disagrees`() {
        assertEquals(
            Lesson(true, Lessons.STARRED),
            Lessons.of(starred = true, wroteToSender = false, readHere = false,
                       archived = false, tier0SaysLetter = false),
        )
    }

    @Test
    fun `writing to a sender outranks archiving their mail unread`() {
        assertEquals(
            Lesson(true, Lessons.CORRESPONDENT),
            Lessons.of(false, wroteToSender = true, readHere = false,
                       archived = true, tier0SaysLetter = false),
        )
    }

    @Test
    fun `archived without opening is a rejection`() {
        assertEquals(
            Lesson(false, Lessons.ARCHIVED_UNREAD),
            Lessons.of(false, false, readHere = false, archived = true, tier0SaysLetter = true),
        )
    }

    /** Archiving after reading is filing, not rejection, and must not train as one. */
    @Test
    fun `archived after reading is not a rejection`() {
        assertEquals(
            Lesson(true, Lessons.OPENED),
            Lessons.of(false, false, readHere = true, archived = true, tier0SaysLetter = false),
        )
    }

    @Test
    fun `with no evidence the bootstrap carries Tier 0's own answer`() {
        assertEquals(Lesson(true, Lessons.BOOTSTRAP), Lessons.of(false, false, false, false, true))
        assertEquals(Lesson(false, Lessons.BOOTSTRAP), Lessons.of(false, false, false, false, false))
    }

    /** The whole point of the change: evidence must not be drowned by guesses. */
    @Test
    fun `every form of evidence outweighs the bootstrap`() {
        for (w in listOf(Lessons.STARRED, Lessons.CORRESPONDENT,
                         Lessons.ARCHIVED_UNREAD, Lessons.OPENED)) {
            assertTrue("$w should outweigh ${Lessons.BOOTSTRAP}", w > Lessons.BOOTSTRAP)
        }
    }

    /* ------------------------------------------------------------- fitWeighted */

    private fun env() = Envelope(
        from = "alex@example.com", fromName = "Alex Doe",
        subject = "lunch tomorrow", mine = setOf("me@example.com"),
    )

    @Test
    fun `weight decides which of two contradictory labels wins`() {
        val e = env()
        val up = Learner().apply {
            fitWeighted(listOf(Triple(e, true, 8.0), Triple(e, false, 0.4)), epochs = 6)
        }
        val down = Learner().apply {
            fitWeighted(listOf(Triple(e, true, 0.4), Triple(e, false, 8.0)), epochs = 6)
        }
        assertTrue("heavy positive should score above a half", up.predict(e) > 0.5)
        assertTrue("heavy negative should score below a half", down.predict(e) < 0.5)
        assertTrue("the two should be far apart", up.predict(e) - down.predict(e) > 0.3)
    }

    /** A fixed seed, because a bug report about ranking is impossible to chase otherwise. */
    @Test
    fun `the same data and seed give the same model`() {
        val e = env()
        val data = listOf(Triple(e, true, 8.0), Triple(e, false, 0.4))
        val a = Learner().apply { fitWeighted(data, epochs = 6) }
        val b = Learner().apply { fitWeighted(data, epochs = 6) }
        assertEquals(a.predict(e), b.predict(e), 0.0)
    }

    @Test
    fun `an empty mailbox trains nothing`() {
        assertEquals(0L, Learner().apply { fitWeighted(emptyList()) }.seen)
    }

    @Test
    fun `a weighted model still survives save and load`() {
        val e = env()
        val trained = Learner().apply {
            fitWeighted(listOf(Triple(e, true, 8.0)), epochs = 4)
        }
        val restored = Learner()
        assertTrue(restored.load(trained.save()))
        assertTrue(abs(restored.predict(e) - trained.predict(e)) < 1e-9)
    }
}
