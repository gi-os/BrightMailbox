package com.gios.brightmailbox.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddrTest {

    @Test fun `plain address`() {
        assertEquals("alex@x.com", Addr.address("alex@x.com"))
    }

    @Test fun `angle bracket form`() {
        assertEquals("alex@x.com", Addr.address("Alex Mercier <alex@x.com>"))
        assertEquals("Alex Mercier", Addr.name("Alex Mercier <alex@x.com>"))
    }

    @Test fun `quoted name containing a comma does not split the list`() {
        // The classic break: a naive split(",") turns one recipient into two, and the
        // "went to 12 other people" rule then fires on a message sent to three.
        val list = """"Doe, Jane" <jane@x.com>, bob@y.com"""
        assertEquals(listOf("jane@x.com", "bob@y.com"), Addr.addresses(list))
    }

    @Test fun `semicolons separate too, because Outlook uses them`() {
        assertEquals(listOf("a@x.com", "b@x.com"), Addr.addresses("a@x.com; b@x.com"))
    }

    @Test fun `name falls back to a humanized local part`() {
        assertEquals("Charles Dolige", Addr.name("charles.dolige@lrparis.com"))
        assertEquals("Gio Lupo", Addr.name("gio_lupo@x.com"))
    }

    @Test fun `rubbish yields null rather than a fake address`() {
        assertNull(Addr.address("not an address"))
        assertNull(Addr.address(""))
        assertEquals(emptyList<String>(), Addr.addresses(null))
    }

    @Test fun `decodes RFC 2047 base64 and quoted-printable`() {
        assertEquals("Réunion", Addr.decodeWords("=?UTF-8?B?UsOpdW5pb24=?="))
        assertEquals("Réunion", Addr.decodeWords("=?UTF-8?Q?R=C3=A9union?="))
        // '_' is a space inside an encoded word, which is the rule people forget.
        assertEquals("one two", Addr.decodeWords("=?UTF-8?Q?one_two?="))
    }

    @Test fun `an undecodable word is left alone rather than mangled`() {
        val s = "=?NOSUCHSET?B?zzzz?="
        assertTrue(Addr.decodeWords(s).isNotEmpty())
    }

    @Test fun `plain subjects pass through untouched`() {
        assertEquals("Bozzuto deck — one note", Addr.decodeWords("Bozzuto deck — one note"))
    }

    @Test fun `reply subject does not stack`() {
        assertEquals("Re: hello", Addr.replySubject("hello"))
        assertEquals("Re: hello", Addr.replySubject("Re: hello"))
        assertEquals("RE: hello", Addr.replySubject("RE: hello"))
    }

    @Test fun `header encoding only kicks in for non-ascii`() {
        assertEquals("plain subject", Addr.encodeHeader("plain subject"))
        assertTrue(Addr.encodeHeader("Réunion").startsWith("=?UTF-8?B?"))
    }

    @Test fun `builds a threadable reply`() {
        val m = Outgoing(
            to = listOf("alex@x.com"),
            subject = "Re: about saturday",
            body = "yes",
            inReplyTo = "<abc@x.com>",
            references = "<first@x.com>",
        )
        val raw = Addr.rfc5322("gio@lrparis.com", m)
        assertTrue(raw.contains("In-Reply-To: <abc@x.com>"))
        // References must accumulate, not replace, or long threads split in the client.
        assertTrue(raw.contains("References: <first@x.com> <abc@x.com>"))
        assertTrue(raw.contains("\r\n\r\nyes"))
        assertTrue(raw.contains("Content-Type: text/plain; charset=UTF-8"))
    }

    @Test fun `body newlines are normalized to CRLF`() {
        val raw = Addr.rfc5322("a@b.com", Outgoing(listOf("c@d.com"), subject = "s", body = "one\ntwo"))
        assertTrue(raw.endsWith("one\r\ntwo"))
        assertTrue(!raw.contains("\n\n"))
    }
}
