package com.gios.brightmailbox.text

/**
 * Find the verification code in a notice, if there is one.
 *
 * Most of what lands in Notices that anybody needs *right now* is a six-digit number
 * from a login page, and the page is on another screen. Reading the number off a 3.9"
 * panel and typing it somewhere else is the whole interaction; a COPY on the row makes
 * it one tap.
 *
 * **Conservative on purpose.** A wrong COPY is worse than no COPY: it puts the wrong
 * thing on the clipboard and the person pastes it before they look. So a number is only
 * a code when the notice talks about codes at all, and never when it is shaped like a
 * year, a price, a phone number, a percentage or an order reference. Missing a real code
 * costs a tap on the row; copying an order number costs a wrong paste and a moment of
 * doubt about every COPY after it.
 *
 * Plain Kotlin — this is the kind of thing that is only right because of its tests.
 */
object Codes {

    /** The words that make a number a code. Whole words, any case. */
    private val WORDS = Regex(
        "\\b(code|codes|verification|verify|verifying|otp|one[- ]time|pin|passcode|pass code|" +
            "password|security|2fa|two[- ]factor|authentication|authenticator|confirm|" +
            "confirmation|login|log in|sign[- ]in|token)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** A number sitting right after one of these is a reference, not a secret. */
    private val REFERENCE = Regex(
        "\\b(order|orders|invoice|receipt|ref|reference|ticket|tracking|case|account|acct|" +
            "number|item|sku|flight|booking|room|seat|ext|extension|zip|postal|" +
            "customer|member|id|no)\\.?\\s*[:#№]?\\s*#?$",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Candidates.
     *
     * Four to eight digits, bounded so that no piece of a longer number, a decimal, a
     * date or a dashed phone number can qualify on its own: not touching a word
     * character, not preceded by a digit and a separator, not followed by a separator
     * and a digit. Or two groups of three to four capitals or digits joined by one dash
     * (`AB12-CD34`, `123-456`), bounded the same way.
     */
    private val DIGITS = Regex("(?<!\\w)(?<!\\d[.,:/-])(?<![+])(\\d{4,8})(?!\\w)(?![.,:/-]\\d)")
    private val GROUPED = Regex("(?<![\\w-])([A-Z0-9]{3,4}-[A-Z0-9]{3,4})(?![\\w-])")

    private val CURRENCY = setOf('$', '€', '£', '¥')

    /**
     * The code in [subject] and [snippet], or null.
     *
     * Both are searched together. Ties go to the number nearest a code word: "482913 is
     * your code" beats a support line in the footer, and "code" in the subject next to
     * the number beats anything the snippet quotes.
     */
    fun find(subject: String, snippet: String = ""): String? {
        val text = listOf(subject, snippet).filter { it.isNotBlank() }.joinToString("  ")
        if (text.isBlank()) return null
        val words = WORDS.findAll(text).map { it.range.first }.toList()
        if (words.isEmpty()) return null

        var best: String? = null
        var bestDistance = Int.MAX_VALUE
        for (m in candidates(text)) {
            val value = m.groupValues[1]
            if (rejected(text, m.range, value)) continue
            val d = words.minOf { kotlin.math.abs(it - m.range.first) }
            if (d < bestDistance) {
                best = value
                bestDistance = d
            }
        }
        return best
    }

    private fun candidates(text: String): List<MatchResult> =
        (GROUPED.findAll(text) + DIGITS.findAll(text)).sortedBy { it.range.first }.toList()

    private fun rejected(text: String, range: IntRange, value: String): Boolean {
        val before = text.substring(0, range.first)
        val after = text.substring(range.last + 1)
        val digitsOnly = value.all { it.isDigit() }

        // A year, unless the sentence says it is a code: "your code is 2024" is rare
        // but real, "renews in 2024" is the common case.
        if (digitsOnly && value.length == 4 && value.toInt() in 1900..2099 &&
            !codeWordBeside(before, after)
        ) return true
        // A price, a percentage, a temperature.
        if (before.trimEnd().lastOrNull()?.let { it in CURRENCY } == true) return true
        if (after.trimStart().firstOrNull()?.let { it == '%' || it == '°' } == true) return true
        // The open bracket or plus of a phone number.
        if (before.trimEnd().lastOrNull()?.let { it == '(' || it == '+' } == true) return true
        // A short group standing in a longer run of short groups is a phone number:
        // `555 123 4567`, `(800) 555-1234`. A six-digit code never joins such a run.
        val digits = value.count { it.isDigit() }
        if (value.all { it.isDigit() || it == '-' } && digits <= 7 &&
            phoneRun(before, after) + digits >= 10
        ) return true
        // "Order #12345678", "ref: 4412".
        if (REFERENCE.containsMatchIn(before.takeLast(32))) return true
        // A dashed pair with no digit in it is a hyphenated word.
        if (value.contains('-') && value.none { it.isDigit() }) return true
        return false
    }

    /**
     * How many digits sit in short groups (1–4 digits, one space, dash or dot between)
     * directly on either side of a candidate. Four digits with six more beside them in
     * groups of three is a phone number; four digits next to a date is not, because a
     * six-digit code never joins the run and a date's groups sum to eight at most.
     */
    private fun phoneRun(before: String, after: String): Int = runLeft(before) + runRight(after)

    private fun runLeft(before: String): Int {
        var s = before
        var total = 0
        val step = Regex("(\\d{1,4})\\)?[ .-]$")
        while (true) {
            val m = step.find(s) ?: break
            total += m.groupValues[1].length
            s = s.substring(0, m.range.first).trimEnd('(')
        }
        return total
    }

    private fun runRight(after: String): Int {
        var s = after
        var total = 0
        val step = Regex("^[ .-]\\(?(\\d{1,4})(?!\\d)")
        while (true) {
            val m = step.find(s) ?: break
            total += m.groupValues[1].length
            s = s.substring(m.range.last + 1).trimStart(')')
        }
        return total
    }

    /** The nouns that name the code itself, as opposed to the sentence around it. */
    private val NOUNS =
        Regex("^(code|codes|pin|passcode|otp|password|token)\\b", RegexOption.IGNORE_CASE)

    /**
     * "code 2024", "code: 2024", "2024 is your code": the number IS the code. Only the
     * nouns count here — "2025 security review" has a code word beside a year too, and
     * is a newsletter.
     */
    private fun codeWordBeside(before: String, after: String): Boolean {
        val lead = before.trimEnd().removeSuffix(":").trimEnd().removeSuffix("is").trimEnd()
        if (NOUNS.matches(lead.substringAfterLast(' '))) return true
        val tail = after.trimStart()
            .replace(Regex("^(is|:|-)\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^(your|the)\\s+", RegexOption.IGNORE_CASE), "")
        return NOUNS.containsMatchIn(tail)
    }
}
