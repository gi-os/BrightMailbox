package com.gios.brightmailbox.mail

/**
 * What a sender said about how to stop sending.
 *
 * `List-Unsubscribe` (RFC 2369) has been read by the sorter since v2.0 — its presence is
 * one of the facts that makes a message a Notice rather than a Letter — and for thirty
 * releases nothing acted on it. The header names one or two ways out, in angle brackets:
 *
 * ```
 * List-Unsubscribe: <mailto:u-123@list.example?subject=unsubscribe>, <https://x/u?id=123>
 * ```
 *
 * Order in the header means nothing; a sender may publish either, both, or something this
 * app should not touch at all. Parsing is separated from acting so it can be tested
 * without a network or a mailbox — which matters, because the failure mode of getting this
 * wrong is sending a stranger an email.
 */
object Unsub {

    /**
     * The ways out this message offers.
     *
     * Both may be present and both may be null: a header can hold a `ftp:` entry, or a
     * bare word, or nothing usable, and "no way out" is a real answer the UI has to show
     * rather than a parse failure.
     */
    data class Ways(
        /** An https URL. Never http — see [parse]. */
        val http: String? = null,
        /** The address to write to. */
        val mailto: String? = null,
        /** The subject the sender asked for, if any. Usually "unsubscribe". */
        val subject: String? = null,
    ) {
        val any: Boolean get() = http != null || mailto != null
    }

    private val ENTRY = Regex("<([^>]+)>")

    /**
     * Read the header.
     *
     * **https only.** An unsubscribe URL carries an identifier for one recipient, which is
     * exactly the kind of thing that must not travel in clear text — and a sender who
     * publishes a plain-http endpoint in 2026 has told you something about how much care
     * went into the rest of it. A message offering only http falls back to the mailto, and
     * failing that offers nothing, which is the honest answer.
     *
     * Entries outside angle brackets are ignored. They are malformed, and the shapes that
     * turn up in the wild — a bare URL, a comment in parentheses — are not worth guessing
     * at when guessing wrong means a POST to something unintended.
     */
    fun parse(header: String?): Ways {
        if (header.isNullOrBlank()) return Ways()
        var http: String? = null
        var mailto: String? = null
        var subject: String? = null

        for (m in ENTRY.findAll(header)) {
            val raw = m.groupValues[1].trim()
            when {
                raw.startsWith("https://", ignoreCase = true) -> if (http == null) http = raw
                raw.startsWith("mailto:", ignoreCase = true) -> if (mailto == null) {
                    val body = raw.removePrefix("mailto:").removePrefix("MAILTO:")
                    val addr = body.substringBefore('?').trim()
                    if (addr.contains('@')) {
                        mailto = addr
                        subject = query(body.substringAfter('?', ""), "subject")
                    }
                }
            }
        }
        return Ways(http, mailto, subject)
    }

    /**
     * One parameter out of a mailto query string.
     *
     * Percent-decoded by hand rather than with `URLDecoder`, which **throws on a stray
     * `%`** — and a hand-written header is exactly where a stray `%` lives. The same trap
     * the QR sign-in parser hit in v2.1; there, a half-read camera frame produced it.
     * A subject that decodes badly is not worth a crash on a screen about leaving a
     * mailing list, so anything unparseable comes back as itself.
     */
    private fun query(q: String, name: String): String? {
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=')
            if (!k.equals(name, ignoreCase = true)) continue
            val v = pair.substringAfter('=', "")
            return decode(v).takeIf { it.isNotBlank() }
        }
        return null
    }

    private fun decode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '+' -> { out.append(' '); i++ }
                c == '%' && i + 2 < s.length -> {
                    val hex = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (hex == null) { out.append(c); i++ } else { out.append(hex.toChar()); i += 3 }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /**
     * The body of an RFC 8058 one-click POST. Fixed by the spec, both halves of it.
     *
     * The sender promises, by publishing `List-Unsubscribe-Post`, that this one request is
     * the whole transaction — no page, no form, no account to sign in to. It is the
     * difference between a button that works and a button that opens a browser.
     */
    const val ONE_CLICK_BODY = "List-Unsubscribe=One-Click"
}
