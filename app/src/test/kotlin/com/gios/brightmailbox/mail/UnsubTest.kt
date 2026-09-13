package com.gios.brightmailbox.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The failure mode of getting this wrong is emailing a stranger, so it is tested before it
 * is shipped rather than after somebody reports it.
 */
class UnsubTest {

    @Test fun `both forms, order ignored`() {
        val w = Unsub.parse("<mailto:u-9@list.example?subject=unsubscribe>, <https://x.example/u?id=9>")
        assertEquals("u-9@list.example", w.mailto)
        assertEquals("unsubscribe", w.subject)
        assertEquals("https://x.example/u?id=9", w.http)
    }

    @Test fun `url first still parses both`() {
        val w = Unsub.parse("<https://x.example/u?id=9>,<mailto:u-9@list.example>")
        assertEquals("https://x.example/u?id=9", w.http)
        assertEquals("u-9@list.example", w.mailto)
        assertNull(w.subject)
    }

    /** An identifier for one recipient must not travel in clear text. */
    @Test fun `plain http is refused`() {
        val w = Unsub.parse("<http://x.example/u?id=9>")
        assertNull(w.http)
        assertFalse(w.any)
    }

    @Test fun `http refused but mailto still offered`() {
        val w = Unsub.parse("<http://x.example/u>, <mailto:leave@list.example>")
        assertNull(w.http)
        assertEquals("leave@list.example", w.mailto)
        assertTrue(w.any)
    }

    @Test fun `percent-encoded subject is decoded`() {
        val w = Unsub.parse("<mailto:l@x.example?subject=Unsubscribe%20from%20News>")
        assertEquals("Unsubscribe from News", w.subject)
    }

    /** URLDecoder throws on this. A hand-written header is where it lives. */
    @Test fun `stray percent does not crash`() {
        val w = Unsub.parse("<mailto:l@x.example?subject=50%%20off>")
        assertEquals("l@x.example", w.mailto)
        assertTrue(w.subject!!.isNotBlank())
    }

    @Test fun `plus is a space`() {
        val w = Unsub.parse("<mailto:l@x.example?subject=stop+it>")
        assertEquals("stop it", w.subject)
    }

    @Test fun `other query parameters are not mistaken for the subject`() {
        val w = Unsub.parse("<mailto:l@x.example?body=unsub&subject=Leave>")
        assertEquals("Leave", w.subject)
    }

    @Test fun `case is not significant in the scheme`() {
        val w = Unsub.parse("<MAILTO:l@x.example>, <HTTPS://x.example/u>")
        assertEquals("l@x.example", w.mailto)
        assertEquals("HTTPS://x.example/u", w.http)
    }

    @Test fun `a mailto with no address is not an address`() {
        val w = Unsub.parse("<mailto:?subject=unsubscribe>")
        assertNull(w.mailto)
        assertFalse(w.any)
    }

    @Test fun `entries outside angle brackets are ignored`() {
        val w = Unsub.parse("https://x.example/u")
        assertFalse(w.any)
    }

    @Test fun `empty and blank`() {
        assertFalse(Unsub.parse(null).any)
        assertFalse(Unsub.parse("").any)
        assertFalse(Unsub.parse("   ").any)
    }

    @Test fun `only the first of each kind is taken`() {
        val w = Unsub.parse("<https://a.example/1>, <https://b.example/2>")
        assertEquals("https://a.example/1", w.http)
    }
}
