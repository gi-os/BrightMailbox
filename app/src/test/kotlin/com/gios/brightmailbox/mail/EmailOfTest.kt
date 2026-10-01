package com.gios.brightmailbox.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Sign-in hands this whatever was typed in the address field. Blank and malformed both
 * come back null, which is why the caller has to tell them apart before it asks.
 */
class EmailOfTest {

    @Test fun `an empty field is not an address`() {
        assertNull(Imap.emailOf(""))
    }

    @Test fun `whitespace alone is not an address`() {
        assertNull(Imap.emailOf("   "))
    }

    @Test fun `words without an at sign are not an address`() {
        assertNull(Imap.emailOf("not an address"))
    }

    @Test fun `a plain address comes back as typed`() {
        assertEquals("user@example.com", Imap.emailOf("user@example.com"))
    }

    @Test fun `an address is lowercased`() {
        assertEquals("user@example.com", Imap.emailOf("USER@Example.COM"))
    }

    @Test fun `a display name is dropped`() {
        assertEquals("jane@example.com", Imap.emailOf("Jane Doe <jane@example.com>"))
    }

    @Test fun `surrounding spaces are trimmed`() {
        assertEquals("user@example.com", Imap.emailOf("  user@example.com  "))
    }
}
