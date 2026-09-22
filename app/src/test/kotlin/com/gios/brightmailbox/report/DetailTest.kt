package com.gios.brightmailbox.report

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class DetailTest {

    @Test
    fun `class and message when the message is plain`() {
        assertEquals(
            "IOException: Unable to resolve host smtp.gmail.com",
            Detail.of(IOException("Unable to resolve host smtp.gmail.com")),
        )
    }

    @Test
    fun `an address drops the message and keeps the class`() {
        assertEquals("IOException", Detail.of(IOException("550 5.1.1 alex@example.com: no such user")))
        assertEquals("IOException", Detail.of(IOException("Invalid Addresses: Alex <alex@example.com>")))
        assertEquals("IOException", Detail.of(IOException("folder 'Personal' not found")))
        assertEquals("IOException", Detail.of(IOException("[AUTHENTICATIONFAILED] Invalid credentials")))
    }

    @Test
    fun `no message is just the class`() {
        assertEquals("IOException", Detail.of(IOException()))
        assertEquals("IOException", Detail.of(IOException("")))
    }

    @Test
    fun `newlines flatten and length is bounded`() {
        val d = Detail.of(IOException("a\nb" + "c".repeat(400)))
        assertEquals(false, d.contains('\n'))
        assertEquals(true, d.length <= "IOException: ".length + 160)
    }

    @Test
    fun `text follows the same rule`() {
        assertEquals("could not reach the server", Detail.ofText("could not reach the server"))
        assertNull(Detail.ofText("Work: the password was refused — user@example.com"))
        assertNull(Detail.ofText(null))
        assertNull(Detail.ofText("  "))
    }
}
