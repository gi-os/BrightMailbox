package com.gios.brightmailbox.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply is the only thing this app sends that another machine parses.
 *
 * A human reads every other message it produces and forgives a stray character. An
 * organizer's calendar does not: a wrong UID files the reply against nothing, and the
 * person who invited you sees no answer at all while you believe you sent one. Hence the
 * tests, on a real Teams-shaped invitation rather than an idealised one.
 */
class IcsTest {

    private val teams = """
        BEGIN:VCALENDAR
        METHOD:REQUEST
        PRODID:Microsoft Exchange Server 2010
        VERSION:2.0
        BEGIN:VEVENT
        ORGANIZER;CN=Alex Doe:mailto:alex@example.com
        SUMMARY;LANGUAGE=en-GB:Weekly sync, and planning
        DTSTART;TZID=Romance Standard Time:20260915T090000
        UID:040000008200E00074C5B7101A82E00800000000B0D2
         9A1B2C3D4E5F60708090A0B0C0D0E0F00
        SEQUENCE:3
        END:VEVENT
        END:VCALENDAR
    """.trimIndent()

    @Test
    fun `folded UID is rejoined`() {
        val invite = Ics.parse(teams)
        assertNotNull(invite)
        // The continuation line began with one space; that space is a fold marker and is
        // not part of the value.
        assertEquals(
            "040000008200E00074C5B7101A82E00800000000B0D29A1B2C3D4E5F60708090A0B0C0D0E0F00",
            invite!!.uid,
        )
    }

    @Test
    fun `organizer comes from the last mailto, not the first colon`() {
        assertEquals("alex@example.com", Ics.parse(teams)!!.organizer)
    }

    @Test
    fun `parameters are not part of the property name`() {
        val i = Ics.parse(teams)!!
        assertEquals("Weekly sync, and planning", i.summary)
        assertEquals("20260915T090000", i.start)
        assertEquals("3", i.sequence)
        assertTrue(i.isRequest)
    }

    @Test
    fun `a reply is not an invitation`() {
        val reply = teams.replace("METHOD:REQUEST", "METHOD:REPLY")
        assertEquals(false, Ics.parse(reply)!!.isRequest)
    }

    @Test
    fun `no METHOD still counts as an invitation`() {
        val bare = teams.replace("METHOD:REQUEST\n", "")
        assertTrue(Ics.parse(bare)!!.isRequest)
    }

    @Test
    fun `anything that is not a calendar is refused`() {
        assertNull(Ics.parse("Dear Gio, lunch on Tuesday?"))
        assertNull(Ics.parse("BEGIN:VCALENDAR\nEND:VCALENDAR"))
    }

    @Test
    fun `the reply echoes uid and sequence and carries our partstat`() {
        val out = Ics.reply(
            Ics.parse(teams)!!,
            me = "gio@example.com",
            myName = "Giovanni",
            answer = Ics.Answer.YES,
            now = "20260912T120000Z",
        )
        assertTrue(out.contains("METHOD:REPLY"))
        assertTrue(
            out.contains(
                "UID:040000008200E00074C5B7101A82E00800000000B0D29A1B2C3D4E5F60708090A0B0C0D0E0F00",
            ),
        )
        assertTrue(out.contains("SEQUENCE:3"))
        assertTrue(
            out.contains("ATTENDEE;PARTSTAT=ACCEPTED;CN=\"Giovanni\":mailto:gio@example.com"),
        )
        assertTrue(out.contains("DTSTAMP:20260912T120000Z"))
    }

    @Test
    fun `a comma in the summary is escaped`() {
        val out = Ics.reply(
            Ics.parse(teams)!!,
            "gio@example.com",
            "Giovanni",
            Ics.Answer.NO,
            "20260912T120000Z",
        )
        // Unescaped, "Weekly sync, and planning" would be two values and the property
        // would be malformed.
        assertTrue(out.contains("SUMMARY:Weekly sync\\, and planning"))
        assertTrue(out.contains("PARTSTAT=DECLINED"))
    }

    @Test
    fun `lines end CRLF, because Exchange means it`() {
        val out = Ics.reply(
            Ics.parse(teams)!!,
            "gio@example.com",
            "",
            Ics.Answer.MAYBE,
            "20260912T120000Z",
        )
        assertTrue(out.startsWith("BEGIN:VCALENDAR\r\n"))
        assertTrue(out.endsWith("END:VCALENDAR\r\n"))
        // No name given, so the address stands in for it.
        assertTrue(out.contains("CN=\"gio@example.com\""))
    }

    @Test
    fun `subject names the answer`() {
        assertEquals(
            "Accepted: Weekly sync, and planning",
            Ics.subject(Ics.parse(teams)!!, Ics.Answer.YES),
        )
    }
}
