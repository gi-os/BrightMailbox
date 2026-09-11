package com.gios.brightmailbox.auth

import java.net.URLDecoder

/**
 * The sign-in QR from the companion page at <https://gi-os.github.io/BrightMailbox/>.
 *
 * `brightmailbox://signin?s=google&e=you%40gmail.com&p=abcdefghijklmnop`
 *
 * Why a code at all: an app password is sixteen characters and the Light Phone keyboard
 * is 3.9 inches wide. The password is made on a computer anyway, so the shortest honest
 * path is to draw it there and let the camera carry it across. The page is static, runs
 * no network request, and encodes the code in its own inline JavaScript — nothing about
 * this round trip leaves the two devices.
 *
 * No Android imports, deliberately: parsing a credential off a camera is exactly the
 * kind of thing that should be unit-tested, and the `sort`/`text`/`mail.Addr` packages
 * already set the precedent that testable code stays on plain Kotlin.
 */
object QrSignIn {

    sealed interface Result {
        data class Ok(val service: Service, val email: String, val password: String) : Result

        /** [why] is shown to the user verbatim, so it is a sentence, not a code. */
        data class Bad(val why: String) : Result
    }

    private const val PREFIX = "brightmailbox://signin?"

    fun parse(raw: String): Result {
        val text = raw.trim()
        if (!text.startsWith(PREFIX, ignoreCase = true)) {
            // The likeliest wrong code by far is some other app's, or a Wi-Fi code, so
            // say what this one should be rather than "invalid".
            return Result.Bad("That is not a Mailbox sign-in code.")
        }

        val q = HashMap<String, String>()
        for (pair in text.removeRange(0, PREFIX.length).split('&')) {
            if (pair.isBlank()) continue
            val i = pair.indexOf('=')
            if (i <= 0) continue
            val k = pair.substring(0, i).lowercase()
            val v = decode(pair.substring(i + 1))
            if (v.isNotBlank()) q[k] = v
        }

        val service = Service.of(q["s"]?.lowercase())
            ?: return Result.Bad("That code names a mail service Mailbox does not know.")
        if (service.usesOAuth) {
            return Result.Bad("${service.label.replaceFirstChar { it.uppercase() }} signs in in the app, not by code.")
        }

        val email = q["e"]?.lowercase()?.takeIf { it.contains('@') && !it.contains(' ') }
            ?: return Result.Bad("That code carries no email address.")

        // Google prints app passwords in four groups of four and people paste them that
        // way. The spaces are not part of the secret.
        val password = q["p"]?.filterNot { it.isWhitespace() }?.takeIf { it.isNotEmpty() }
            ?: return Result.Bad("That code carries no app password.")

        return Result.Ok(service, email, password)
    }

    /**
     * Percent-decoding that cannot throw.
     *
     * `URLDecoder` raises on a stray `%` — and a stray `%` is precisely what a slightly
     * misread camera frame produces. A credential screen must not crash on a bad scan,
     * so a malformed escape leaves the text as it was and the value simply fails its
     * check above.
     */
    private fun decode(s: String): String =
        runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
