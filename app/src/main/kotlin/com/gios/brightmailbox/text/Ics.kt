package com.gios.brightmailbox.text

/**
 * Just enough iCalendar to answer an invitation.
 *
 * Not a calendar library and deliberately not trying to be one — this app has no calendar
 * and [com.gios.brightmailbox.sort.Headers] already routes invites to Notices because
 * nothing here can show you a week. What it *can* do is the one thing a mail client is
 * uniquely able to do: send the reply, which is an ordinary email carrying a
 * `text/calendar; method=REPLY` part.
 *
 * No Android imports, so it unit tests on the JVM like the rest of `text/` and `sort/`.
 */
object Ics {

    /** What a reply says about you. The three RFC 5545 values anyone actually sends. */
    enum class Answer(val partStat: String, val word: String) {
        YES("ACCEPTED", "Accepted"),
        NO("DECLINED", "Declined"),
        MAYBE("TENTATIVE", "Tentative"),
    }

    /**
     * The parts of a VEVENT a reply has to echo back.
     *
     * [uid] and [sequence] are what let the organizer's calendar match the reply to the
     * right event and the right revision of it; getting either wrong produces a reply that
     * is silently filed as being about nothing.
     */
    data class Invite(
        val uid: String,
        val organizer: String,
        val summary: String,
        val start: String,
        val sequence: String,
        /** True when the message is an invitation rather than a reply or a cancellation. */
        val isRequest: Boolean,
    )

    /**
     * Unfold, then split.
     *
     * RFC 5545 folds long lines by breaking them and starting the next with one space or
     * tab — a UID routinely runs past 75 octets, so a parser that reads lines naively gets
     * half an identifier and matches nothing. Unfolding first is not optional.
     */
    internal fun lines(ics: String): List<String> {
        val out = ArrayList<String>()
        for (raw in ics.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.substring(1)
            } else {
                out.add(raw)
            }
        }
        return out
    }

    /**
     * The name half of a property line, without its parameters.
     *
     * `DTSTART;TZID=Europe/Paris:20260912T090000` is the DTSTART property; everything
     * between the name and the colon is parameters, and a parameter value may itself
     * contain a colon inside quotes — which is why this splits on the name rather than on
     * the first colon.
     */
    private fun name(line: String): String =
        line.substringBefore(':').substringBefore(';').trim().uppercase()

    private fun value(line: String): String = line.substringAfter(':', "").trim()

    /** The first VEVENT's fields, or null if this is not a calendar object we can answer. */
    fun parse(ics: String): Invite? {
        if (!ics.contains("BEGIN:VCALENDAR", ignoreCase = true)) return null
        val all = lines(ics)

        val method = all.firstOrNull { name(it) == "METHOD" }?.let { value(it).uppercase() }

        var inEvent = false
        var uid = ""
        var organizer = ""
        var summary = ""
        var start = ""
        var sequence = "0"
        for (line in all) {
            when {
                name(line) == "BEGIN" && value(line).equals("VEVENT", true) -> inEvent = true
                name(line) == "END" && value(line).equals("VEVENT", true) -> if (inEvent) break
                !inEvent -> Unit
                name(line) == "UID" -> uid = value(line)
                name(line) == "SUMMARY" -> summary = value(line)
                name(line) == "DTSTART" -> start = value(line)
                name(line) == "SEQUENCE" -> sequence = value(line).ifBlank { "0" }
                name(line) == "ORGANIZER" ->
                    // `ORGANIZER;CN=Jo:mailto:jo@x.com` — the address is after the last
                    // `mailto:`, not after the first colon, which is where the CN ends.
                    organizer = value(line).substringAfter("mailto:", value(line)).trim()
            }
        }
        if (uid.isBlank()) return null
        return Invite(
            uid = uid,
            organizer = organizer,
            summary = summary,
            start = start,
            sequence = sequence,
            // No METHOD at all is how plenty of senders ship an invitation; only an
            // explicit REPLY or CANCEL means it is not one.
            isRequest = method == null || method == "REQUEST",
        )
    }

    /**
     * The reply body: a whole VCALENDAR with one VEVENT and one ATTENDEE — you.
     *
     * An organizer's calendar reads three things and ignores the rest: the UID, the
     * SEQUENCE, and the ATTENDEE's PARTSTAT. DTSTART is echoed because Exchange has been
     * known to discard a VEVENT without one, and DTSTAMP is required by the specification.
     *
     * CRLF line endings throughout — RFC 5545 says so, and Exchange is one of the
     * implementations that means it.
     */
    fun reply(invite: Invite, me: String, myName: String, answer: Answer, now: String): String {
        val name = myName.ifBlank { me }.replace("\"", "")
        val body = listOf(
            "BEGIN:VCALENDAR",
            "PRODID:-//gi-os//BrightMailbox//EN",
            "VERSION:2.0",
            "METHOD:REPLY",
            "BEGIN:VEVENT",
            "UID:${invite.uid}",
            "SEQUENCE:${invite.sequence}",
            "DTSTAMP:$now",
        ) + listOfNotNull(
            invite.start.takeIf { it.isNotBlank() }?.let { "DTSTART:$it" },
            invite.summary.takeIf { it.isNotBlank() }?.let { "SUMMARY:${escape(it)}" },
            invite.organizer.takeIf { it.isNotBlank() }?.let { "ORGANIZER:mailto:$it" },
        ) + listOf(
            "ATTENDEE;PARTSTAT=${answer.partStat};CN=\"${escape(name)}\":mailto:$me",
            "END:VEVENT",
            "END:VCALENDAR",
        )
        return body.joinToString("\r\n") + "\r\n"
    }

    /**
     * RFC 5545 text escaping: backslash, semicolon, comma and newline.
     *
     * A summary is user-written and routinely contains a comma, which is a value separator
     * in this format — unescaped, "Lunch, then review" becomes two values and the property
     * is malformed.
     */
    private fun escape(s: String): String = s
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")

    /** `Accepted: Lunch` — the subject every calendar client writes for a reply. */
    fun subject(invite: Invite, answer: Answer): String =
        "${answer.word}: ${invite.summary.ifBlank { "(no subject)" }}"
}
