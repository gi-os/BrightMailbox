package com.gios.brightmailbox.text

/**
 * Turn a mail body into something worth setting as a page.
 *
 * The design brief calls for a Letter with "no avatars, no reply-quote chevrons, no
 * signature blocks, no forwarded-message dividers, no 'Sent from my iPhone', no legal
 * footers". All of that is done here, at parse time, and thrown away — not hidden with
 * CSS. Hiding it means the reader still has to lay it out, and the one-line
 * "4 earlier messages" affordance in the design would be a lie about what is on screen.
 *
 * Plain Kotlin, no Android imports: this is the other half of the app that gets real
 * unit tests.
 */
object Clean {

    /** What a stripped body looks like once the quoting has been peeled off. */
    data class Body(
        val text: String,
        /** How many earlier messages were removed. Drives "4 earlier messages". */
        val quotedMessages: Int,
        /** The signature, if one was cut. Kept only so it can be shown on request. */
        val signature: String? = null,
    )

    // "On Tue, 3 Sep 2026 at 14:02, Alex <alex@x.com> wrote:" and its many dialects.
    private val ATTRIBUTION = Regex(
        """^\s*(On .{6,120}\bwrote:|El .{6,120}\bescribió:|Le .{6,120}\ba écrit\s*:|Am .{6,120}\bschrieb\s*:)\s*$""",
        setOf(RegexOption.IGNORE_CASE),
    )

    private val DIVIDER = Regex(
        """^\s*(-{2,}\s*(Original Message|Forwarded message|Message d'origine)\s*-{2,}|_{10,}|-{10,})\s*$""",
        RegexOption.IGNORE_CASE,
    )

    // Outlook's block, which has no ">" anywhere and so survives every naive stripper.
    private val OUTLOOK_HEADER = Regex(
        """^\s*(From|De|Von|Da):\s*.{3,}$""",
        RegexOption.IGNORE_CASE,
    )

    private val SENT_FROM = Regex(
        """^\s*(Sent from my \w+|Sent from Outlook.*|Get Outlook for \w+|Envoyé de mon \w+)\.?\s*$""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Footer boilerplate. Matched on a whole line, and only in the last third of the
     * message — "unsubscribe" in the middle of a paragraph is somebody talking about
     * unsubscribing.
     */
    private val FOOTER = Regex(
        """(?i)^\s*(""" +
            """unsubscribe|manage (your )?(email )?preferences|view (this|it) in (your )?browser|""" +
            """you (are )?receiv(e|ing) this (email|message)|this (e-?mail|message) (and any|is intended)|""" +
            """confidentiality notice|privacy policy|all rights reserved|""" +
            """©\s*\d{4}|\(c\)\s*\d{4}""" +
            """).{0,200}$""",
    )

    /**
     * Strip quoting, signatures and boilerplate.
     *
     * Order matters: the quote scan runs before the footer scan, because a footer
     * inside a quoted reply should be removed as part of the quote and not counted as
     * this message's own footer.
     */
    fun body(raw: String): Body {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')

        var cut = lines.size
        var quoted = 0
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            val isQuoteStart = ATTRIBUTION.matches(l) || DIVIDER.matches(l) ||
                (OUTLOOK_HEADER.matches(l) && looksLikeForwardBlock(lines, i))
            if (isQuoteStart) {
                if (cut == lines.size) cut = i
                quoted++
                i++
                continue
            }
            // A run of ">" lines is a quote too, but only count it as a separate message
            // when it isn't already inside one we've cut.
            if (l.startsWith(">") && cut == lines.size) {
                cut = i
                quoted++
            }
            i++
        }

        var kept = lines.subList(0, cut).toMutableList()

        // Signature: the RFC 3676 "-- " sentinel, else a trailing "Thanks,\nGio" block.
        var signature: String? = null
        val sigAt = kept.indexOfLast { it.trimEnd() == "--" || it == "-- " }
        if (sigAt >= 0 && kept.size - sigAt <= 12) {
            signature = kept.subList(sigAt + 1, kept.size).joinToString("\n").trim().ifBlank { null }
            kept = kept.subList(0, sigAt)
        }

        kept.removeAll { SENT_FROM.matches(it) }

        // Footers, from the end only.
        val floor = (kept.size * 2) / 3
        var end = kept.size
        for (j in kept.indices.reversed()) {
            if (j < floor) break
            val t = kept[j]
            if (t.isBlank() || FOOTER.containsMatchIn(t)) {
                if (FOOTER.containsMatchIn(t)) end = j
            } else if (j < end - 1) {
                break
            }
        }
        if (end < kept.size) kept = kept.subList(0, end)

        return Body(collapse(kept), quoted, signature)
    }

    /**
     * An Outlook forward block is `From:` followed within a few lines by `Sent:`/`To:`.
     * Requiring the companion header stops a message that merely *starts* with the word
     * "From:" from truncating the whole letter.
     */
    private fun looksLikeForwardBlock(lines: List<String>, at: Int): Boolean {
        val window = lines.subList(at, minOf(lines.size, at + 5))
        return window.any {
            Regex("""^\s*(Sent|To|Subject|Enviado|Para|Gesendet|An):\s*.+$""", RegexOption.IGNORE_CASE)
                .matches(it)
        }
    }

    /** Trim, and squeeze runs of blank lines down to one. */
    private fun collapse(lines: List<String>): String {
        val out = ArrayList<String>(lines.size)
        var blank = false
        for (l in lines) {
            val t = l.trimEnd()
            if (t.isBlank()) {
                if (!blank && out.isNotEmpty()) out.add("")
                blank = true
            } else {
                out.add(t)
                blank = false
            }
        }
        while (out.isNotEmpty() && out.last().isBlank()) out.removeAt(out.size - 1)
        return out.joinToString("\n").trim()
    }

    /**
     * A one-line summary for a Notice row.
     *
     * No model involved: the subject with the noise trimmed off. Bracketed tags, "Re:"
     * chains and trailing order numbers say nothing a person needs at a glance.
     */
    fun noticeLine(subject: String, limit: Int = 64): String {
        var s = subject.replace(Regex("""^((re|fwd?|aw|tr)\s*:\s*)+""", RegexOption.IGNORE_CASE), "")
        s = s.replace(Regex("""\[[^\]]{1,24}\]"""), " ")
        s = s.replace(Regex("""\s+"""), " ").trim()
        if (s.length <= limit) return s
        val cutAt = s.lastIndexOf(' ', limit)
        return s.take(if (cutAt > limit / 2) cutAt else limit).trimEnd() + "…"
    }

    /**
     * Very rough HTML to text, for messages with no text/plain part.
     *
     * Not a parser and not trying to be. Blocks become newlines, tags go, entities that
     * actually appear in mail are decoded. Anything more ambitious belongs in jsoup,
     * which the app already has for the reader.
     */
    fun fromHtml(html: String): String {
        var s = html
        s = s.replace(Regex("""(?is)<(script|style|head)\b.*?</\1>"""), " ")
        s = s.replace(Regex("""(?i)<br\s*/?>"""), "\n")
        s = s.replace(Regex("""(?i)</(p|div|tr|li|h[1-6]|table|blockquote)>"""), "\n\n")
        s = s.replace(Regex("""(?s)<[^>]+>"""), " ")
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&rt;", ">").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&rsquo;", "'").replace("&mdash;", "—")
            .replace("&ndash;", "–").replace("&hellip;", "…")
        s = s.replace(Regex("""[ \t]+"""), " ")
        s = s.replace(Regex("""\n{3,}"""), "\n\n")
        return s.trim()
    }
}
