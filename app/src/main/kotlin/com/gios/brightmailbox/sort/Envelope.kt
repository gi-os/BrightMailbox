package com.gios.brightmailbox.sort

/**
 * Everything the sorter is allowed to see about a message.
 *
 * Deliberately not the Room entity, and deliberately free of Android imports: the whole
 * `sort` package is plain Kotlin so it can be unit-tested on a JVM with no emulator and
 * no Android SDK. The classification IS the product, so it is the part that has to stay
 * verifiable.
 */
data class Envelope(
    /** Lowercased `alice@example.com`, no display name. */
    val from: String,
    /** Display name as sent, or "". Used for the row, never for a rule. */
    val fromName: String = "",
    val subject: String = "",
    /** Header names lowercased; repeated headers joined with ", ". */
    val headers: Map<String, String> = emptyMap(),
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    /** Every address belonging to this user, across all connected accounts. */
    val mine: Set<String> = emptySet(),
    /** First ~2000 chars of plain-text body. Only Tier 1 reads this. */
    val body: String = "",
) {
    fun header(name: String): String? = headers[name.lowercase()]?.takeIf { it.isNotBlank() }
    fun has(name: String): Boolean = header(name) != null

    val domain: String get() = from.substringAfter('@', "")
    val localPart: String get() = from.substringBefore('@', from)

    /** Recipients who are not the user. A crowd means this was not written to you. */
    val otherRecipients: Int
        get() = (to + cc).count { it.isNotBlank() && it !in mine }

    val addressedToMe: Boolean get() = to.any { it in mine }
}

enum class Pile { LETTER, NOTICE }

/**
 * A decision plus the sentence shown when the user asks why.
 *
 * The reason is not decoration. Sorting is the whole product, so the first time it gets
 * something wrong the user has to be able to see what it was thinking — otherwise they
 * stop trusting the app, and there is no way back from that. Plain words, never a
 * percentage, never a confidence bar.
 */
data class Verdict(
    val pile: Pile,
    val reason: String,
    /** Stable identifier, for tests and the report log. */
    val rule: String,
    val fromOverride: Boolean = false,
)
