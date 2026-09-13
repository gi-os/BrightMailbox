package com.gios.brightmailbox.mail

/**
 * Address and MIME odds and ends. No Android imports — unit-testable.
 */
object Addr {

    /**
     * Base64, wrapped at 76 characters with CRLF.
     *
     * RFC 2045 caps an encoded line at 76 and requires CRLF; a single unbroken line is
     * rejected outright by some servers and silently truncated by others. `getMimeEncoder`
     * does both, and is on every Android this app runs on.
     */
    private fun base64(bytes: ByteArray): String =
        java.util.Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(bytes)

    /** A filename safe to put inside quotes in a header. */
    private fun quotable(name: String): String =
        name.replace("\\", "_").replace("\"", "_").replace("\r", "").replace("\n", "")
            .ifBlank { "attachment" }

    /** `"Alex Mercier" <alex@x.com>, bob@y.com` -> the two addresses, lowercased. */
    fun addresses(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return split(raw).mapNotNull { one -> address(one) }
    }

    /** The bare address out of one entry, or null. */
    fun address(entry: String): String? {
        val t = entry.trim()
        if (t.isEmpty()) return null
        val lt = t.lastIndexOf('<')
        val gt = t.lastIndexOf('>')
        val inner = if (lt >= 0 && gt > lt) t.substring(lt + 1, gt) else t
        val a = inner.trim().trim('"', '\'', ',', ';').lowercase()
        return if (a.contains('@') && !a.contains(' ')) a else null
    }

    /** The display name out of one entry, falling back to the local part. */
    fun name(entry: String): String {
        val t = entry.trim()
        val lt = t.lastIndexOf('<')
        if (lt > 0) {
            val n = t.substring(0, lt).trim().trim('"').trim()
            if (n.isNotEmpty()) return n
        }
        val a = address(t) ?: return t
        // "charles.dolige" -> "Charles Dolige". Better than showing an address in a
        // column of human names, and wrong only for people with odd local parts.
        return a.substringBefore('@')
            .split('.', '_', '-')
            .filter { it.isNotBlank() && !it.all { c -> c.isDigit() } }
            .joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }
            .ifBlank { a }
    }

    /**
     * Split an address list on commas that are not inside quotes or angle brackets.
     * A naive `split(",")` breaks on `"Doe, Jane" <jane@x.com>`, which is common enough
     * in corporate mail to matter.
     */
    fun split(raw: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var inQuote = false
        var inAngle = false
        for (c in raw) {
            when {
                c == '"' -> { inQuote = !inQuote; sb.append(c) }
                c == '<' -> { inAngle = true; sb.append(c) }
                c == '>' -> { inAngle = false; sb.append(c) }
                (c == ',' || c == ';') && !inQuote && !inAngle -> {
                    if (sb.isNotBlank()) out.add(sb.toString())
                    sb.setLength(0)
                }
                else -> sb.append(c)
            }
        }
        if (sb.isNotBlank()) out.add(sb.toString())
        return out
    }

    /**
     * RFC 2047 encoded words: `=?UTF-8?B?...?=` and `=?UTF-8?Q?...?=`.
     *
     * Subjects with accents arrive encoded, and "Bozzuto deck =?UTF-8?Q?=E2=80=94?= one
     * note" on the panel is the kind of detail that makes an app feel broken.
     */
    fun decodeWords(s: String): String {
        val re = Regex("""=\?([^?]+)\?([bBqQ])\?([^?]*)\?=""")
        return re.replace(s) { m ->
            val charset = runCatching { charset(m.groupValues[1]) }.getOrNull() ?: Charsets.UTF_8
            val payload = m.groupValues[3]
            runCatching {
                when (m.groupValues[2].lowercase()) {
                    "b" -> String(java.util.Base64.getMimeDecoder().decode(payload), charset)
                    else -> String(decodeQ(payload), charset)
                }
            }.getOrDefault(m.value)
        }.replace(Regex("""\?=\s+=\?"""), "?==?")
    }

    private fun charset(name: String): java.nio.charset.Charset =
        java.nio.charset.Charset.forName(name.substringBefore('*'))

    /** Quoted-printable, with '_' meaning space as RFC 2047 requires. */
    private fun decodeQ(s: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            when {
                s[i] == '_' -> { out.write(' '.code); i++ }
                s[i] == '=' && i + 2 < s.length -> {
                    val v = s.substring(i + 1, i + 3).toIntOrNull(16)
                    if (v != null) { out.write(v); i += 3 } else { out.write(s[i].code); i++ }
                }
                else -> { out.write(s[i].code); i++ }
            }
        }
        return out.toByteArray()
    }

    /**
     * Build an RFC 5322 message. Gmail's send endpoint takes a raw MIME blob, so this is
     * the one place the app has to write mail rather than read it.
     *
     * Headers are encoded as UTF-8 base64 words when they are not plain ASCII, because a
     * bare accented character in a header is not legal and some servers reject it.
     */
    fun rfc5322(from: String, m: Outgoing): String {
        val sb = StringBuilder()
        sb.append("From: ").append(from).append("\r\n")
        sb.append("To: ").append(m.to.joinToString(", ")).append("\r\n")
        if (m.cc.isNotEmpty()) sb.append("Cc: ").append(m.cc.joinToString(", ")).append("\r\n")
        sb.append("Subject: ").append(encodeHeader(m.subject)).append("\r\n")
        m.inReplyTo?.let {
            sb.append("In-Reply-To: ").append(it).append("\r\n")
            sb.append("References: ").append(m.references?.plus(" ")?.plus(it) ?: it).append("\r\n")
        }
        sb.append("MIME-Version: 1.0\r\n")

        val body = m.body.replace("\r\n", "\n").replace("\n", "\r\n")

        /*
         * Three shapes, and which one is which matters to the receiving client.
         *
         *  - Nothing else attached: a plain text message, as it always was.
         *  - An invitation reply: multipart/ALTERNATIVE. Two renderings of one thing —
         *    the sentence a person reads and the object a calendar reads. As `mixed` the
         *    calendar part is offered as a file to download and never processed.
         *  - Files: multipart/MIXED. Separate things travelling together, which is what
         *    `mixed` means and `alternative` does not.
         *
         * A reply carrying both would need the alternative nested inside the mixed. It
         * cannot happen — an RSVP is sent by tapping a word, with no compose screen and
         * nowhere to attach anything — so the case is refused rather than half-built.
         */
        val ics = m.calendarReply
        if (ics == null && m.files.isEmpty()) {
            sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n")
            sb.append(body)
            return sb.toString()
        }

        val boundary = "bm-" + java.util.UUID.randomUUID().toString().replace("-", "")
        val subtype = if (ics != null) "alternative" else "mixed"
        sb.append("Content-Type: multipart/").append(subtype)
            .append("; boundary=\"").append(boundary).append("\"\r\n")
        sb.append("\r\n")

        sb.append("--").append(boundary).append("\r\n")
        sb.append("Content-Type: text/plain; charset=UTF-8\r\n")
        sb.append("Content-Transfer-Encoding: 8bit\r\n")
        sb.append("\r\n").append(body).append("\r\n")

        if (ics != null) {
            /*
             * `method=REPLY` on the part's own Content-Type is the half that matters most.
             * Without it Exchange treats the part as an event to add rather than an answer
             * to one, and the organizer is never told anything.
             */
            sb.append("--").append(boundary).append("\r\n")
            sb.append("Content-Type: text/calendar; method=REPLY; charset=UTF-8\r\n")
            sb.append("Content-Transfer-Encoding: 8bit\r\n")
            sb.append("\r\n").append(ics).append("\r\n")
        }

        for (f in m.files) {
            sb.append("--").append(boundary).append("\r\n")
            // The name goes in BOTH places: `name` on the type is what old clients read,
            // `filename` on the disposition is what current ones read, and a file that
            // arrives with one of them missing is saved as "noname" by somebody.
            sb.append("Content-Type: ").append(f.mime.ifBlank { "application/octet-stream" })
                .append("; name=\"").append(quotable(f.name)).append("\"\r\n")
            sb.append("Content-Transfer-Encoding: base64\r\n")
            sb.append("Content-Disposition: attachment; filename=\"")
                .append(quotable(f.name)).append("\"\r\n")
            sb.append("\r\n").append(base64(f.bytes)).append("\r\n")
        }

        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString()
    }

    fun encodeHeader(s: String): String {
        if (s.all { it.code in 32..126 }) return s
        val b = java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))
        return "=?UTF-8?B?$b?="
    }

    /** Reply subject, without stacking "Re: Re: Re:". */
    fun replySubject(s: String): String =
        if (s.trimStart().startsWith("re:", ignoreCase = true)) s else "Re: $s"

    /**
     * `Fwd: ` once, and never on top of an existing one.
     *
     * The same rule as [replySubject], for the same reason: a message forwarded three
     * times should not arrive as "Fwd: Fwd: Fwd:". Also recognises "FW:" and the French
     * "TR:", because a forward usually arrives from a client that is not this one.
     */
    fun forwardSubject(s: String): String {
        val t = s.trim()
        val already = Regex("^(fwd?|tr)\\s*:", RegexOption.IGNORE_CASE)
        return if (already.containsMatchIn(t)) t else "Fwd: " + t.ifBlank { "(no subject)" }
    }
}
