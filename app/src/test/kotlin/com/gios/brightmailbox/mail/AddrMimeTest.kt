package com.gios.brightmailbox.mail

import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** The envelope is parsed by other people's servers, so its shape is worth asserting. */
class AddrMimeTest {

    private val plain = Outgoing(to = listOf("a@x.com"), subject = "Hi", body = "Line one")

    @Test fun `a plain message stays single-part`() {
        val raw = Addr.rfc5322("me@x.com", plain)
        assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8"))
        assertTrue(!raw.contains("multipart"))
    }

    @Test fun `files make it multipart mixed, not alternative`() {
        val raw = Addr.rfc5322(
            "me@x.com",
            plain.copy(files = listOf(Outfile("notes.txt", "text/plain", "hello".toByteArray()))),
        )
        assertTrue(raw.contains("Content-Type: multipart/mixed;"))
        assertTrue(raw.contains("Content-Disposition: attachment; filename=\"notes.txt\""))
        assertTrue(raw.contains("Content-Transfer-Encoding: base64"))
        assertTrue(raw.contains("aGVsbG8="))          // "hello"
        assertTrue(raw.trimEnd().endsWith("--"))       // closing boundary
    }

    @Test fun `an rsvp stays alternative`() {
        val raw = Addr.rfc5322("me@x.com", plain.copy(calendarReply = "BEGIN:VCALENDAR\r\n"))
        assertTrue(raw.contains("Content-Type: multipart/alternative;"))
        assertTrue(raw.contains("text/calendar; method=REPLY"))
    }

    @Test fun `base64 wraps at 76 and uses CRLF`() {
        val big = ByteArray(1000) { 'A'.code.toByte() }
        val raw = Addr.rfc5322("me@x.com", plain.copy(files = listOf(Outfile("b", "application/octet-stream", big))))
        // The blank line is after Content-Disposition, which comes AFTER the encoding
        // header - the first version of this test split on the wrong one, found nothing,
        // and then asserted against the headers themselves.
        val body = raw.substringAfterLast("Content-Disposition: attachment; filename=\"b\"\r\n\r\n")
        val lines = body.split("\r\n").filter { it.isNotBlank() && !it.startsWith("--") }
        assertTrue("expected several wrapped lines, got ${lines.size}", lines.size > 1)
        assertTrue("longest was ${lines.maxOf { it.length }}", lines.all { it.length <= 76 })
        // 1000 bytes base64 is 1336 characters; at 76 per line that is 18 lines.
        assertEquals(18, lines.size)
    }

    @Test fun `a quote in a filename cannot break the header`() {
        val raw = Addr.rfc5322(
            "me@x.com",
            plain.copy(files = listOf(Outfile("we\"ird\r\n.pdf", "application/pdf", byteArrayOf(1)))),
        )
        assertTrue(raw.contains("filename=\"we_ird.pdf\""))
        // One Content-Disposition line, not two spliced by the newline.
        assertEquals(1, Regex("Content-Disposition:").findAll(raw).count())
    }

    @Test fun `cc and references are written when present`() {
        val raw = Addr.rfc5322(
            "me@x.com",
            plain.copy(cc = listOf("c@x.com"), inReplyTo = "<1@x>", references = "<0@x>"),
        )
        assertTrue(raw.contains("Cc: c@x.com"))
        assertTrue(raw.contains("References: <0@x> <1@x>"))
    }
}
