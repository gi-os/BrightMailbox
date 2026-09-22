package com.gios.brightmailbox.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboxTest {

    @Test
    fun `states read back from text and unknown text is a plain draft`() {
        assertEquals(SendState.QUEUED, SendState.of("QUEUED"))
        assertEquals(SendState.DRAFT, SendState.of(null))
        assertEquals(SendState.DRAFT, SendState.of("wat"))
    }

    @Test
    fun `only queued and sending live in the outbox`() {
        assertTrue(SendState.QUEUED.inOutbox)
        assertTrue(SendState.SENDING.inOutbox)
        assertFalse(SendState.DRAFT.inOutbox)
        assertFalse(SendState.FAILED.inOutbox)
        assertFalse(SendState.SENT.inOutbox)
    }

    @Test
    fun `a failure counts a try and stays queued until the fifth`() {
        var tries = 0
        for (i in 1..4) {
            val after = Outbox.failed(tries, "no route to host")
            assertEquals(SendState.QUEUED, after.state)
            assertEquals(i, after.tries)
            assertEquals("no route to host", after.error)
            tries = after.tries
        }
        val last = Outbox.failed(tries, "still no route")
        assertEquals(SendState.FAILED, last.state)
        assertEquals(5, last.tries)
        assertEquals("still no route", last.error)
    }

    @Test
    fun `a failed row is not tried again and a sent one is a tombstone`() {
        assertFalse(Outbox.shouldTry(SendState.FAILED, 5))
        assertFalse(Outbox.shouldTry(SendState.DRAFT, 0))
        assertFalse(Outbox.shouldTry(SendState.SENT, 0))
        assertTrue(Outbox.shouldTry(SendState.QUEUED, 4))
        assertFalse(Outbox.shouldTry(SendState.QUEUED, 5))
    }

    @Test
    fun `a sending row left by a dead process is tried again`() {
        assertTrue(Outbox.shouldTry(SendState.SENDING, 2))
    }

    @Test
    fun `pressing send again restarts the count`() {
        val again = Outbox.requeued()
        assertEquals(SendState.QUEUED, again.state)
        assertEquals(0, again.tries)
        assertEquals("", again.error)
    }

    @Test
    fun `success clears the error`() {
        val ok = Outbox.sent()
        assertEquals(SendState.SENT, ok.state)
        assertEquals("", ok.error)
    }

    @Test
    fun `the error kept is bounded`() {
        val after = Outbox.failed(0, "x".repeat(1000))
        assertEquals(200, after.error.length)
    }
}
