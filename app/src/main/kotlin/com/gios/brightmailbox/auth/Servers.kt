package com.gios.brightmailbox.auth

/**
 * Where an account's mail actually lives.
 *
 * Split out of [Service] in v2.28. The hosts used to be constants on the enum, which was
 * honest while there were exactly two providers and became the only thing stopping the app
 * from talking to the rest of the world — the transport underneath has been plain IMAP
 * since v2.0 and never cared who was answering.
 *
 * A built-in service supplies these from its own constants; an account added by hand
 * carries its own, stored beside the password.
 */
data class Servers(
    val imapHost: String,
    val imapPort: Int,
    /** Tried in order. Microsoft needs two; everyone else has one. */
    val smtpHosts: List<String>,
    val smtpPort: Int,
    /** True for implicit TLS on connect (465); false for STARTTLS (587). */
    val smtpSsl: Boolean,
    /** Fallbacks for when the server does not advertise SPECIAL-USE. */
    val archiveNames: List<String> = listOf("Archive", "Archives"),
    val sentNames: List<String> = listOf("Sent", "Sent Items", "Sent Messages"),
) {
    val valid: Boolean get() = imapHost.isNotBlank() && imapPort in 1..65535

    companion object {
        /**
         * A sensible guess from an IMAP host.
         *
         * Almost every provider names the pair `imap.x` / `smtp.x`, and port 465 with
         * implicit TLS is the modern default for submission. A guess that is wrong is
         * fixable in the field beneath it; a form that demands five answers before it will
         * try anything is one people abandon.
         */
        fun guess(imapHost: String): Servers {
            val host = imapHost.trim().lowercase().removePrefix("imap://")
            val smtp = when {
                host.startsWith("imap.") -> "smtp." + host.removePrefix("imap.")
                host.startsWith("mail.") -> host
                else -> host
            }
            return Servers(host, 993, listOf(smtp), 465, smtpSsl = true)
        }
    }
}

/**
 * A provider the setup screen can name, so nobody has to know their own hostname.
 *
 * [servers] null means "ask" — the escape hatch for a self-hosted box, a work Dovecot, or
 * anything not on the list. The named ones exist because "imap.fastmail.com" is not
 * knowledge anybody should need to have about their own mail.
 *
 * Every one of these authenticates with an **app password**, not the account password.
 * Fastmail and Zoho require one; iCloud requires one; it is the same shape Gmail already
 * uses, which is why they all fit the existing sign-in screen unchanged.
 */
data class Preset(
    val key: String,
    val label: String,
    /** Where to get the app password, or what to type. One line, shown under the field. */
    val note: String,
    val servers: Servers?,
) {
    companion object {
        val ALL: List<Preset> = listOf(
            Preset(
                "fastmail",
                "Fastmail",
                "Settings → Privacy & Security → App passwords.",
                Servers("imap.fastmail.com", 993, listOf("smtp.fastmail.com"), 465, true),
            ),
            Preset(
                "icloud",
                "iCloud",
                "appleid.apple.com → Sign-In and Security → App-Specific Passwords.",
                Servers(
                    "imap.mail.me.com", 993, listOf("smtp.mail.me.com"), 587, false,
                    archiveNames = listOf("Archive"),
                    sentNames = listOf("Sent Messages", "Sent"),
                ),
            ),
            Preset(
                "zoho",
                "Zoho",
                "Settings → Security → App Passwords.",
                Servers("imap.zoho.com", 993, listOf("smtp.zoho.com"), 465, true),
            ),
            Preset(
                "other",
                "Something else",
                "Any IMAP server — a mailbox at work, your own, or a bridge.",
                null,
            ),
        )

        fun of(key: String?): Preset? = ALL.firstOrNull { it.key == key }
    }
}
