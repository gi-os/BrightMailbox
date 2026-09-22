package com.gios.brightmailbox.report

/**
 * What an error is allowed to say in a report.
 *
 * A report leaves the phone. The chip that offers to send one is raised by
 * `Trouble.record`, and whatever detail goes in with it ends up in an issue tracker that
 * is not the person's mailbox. So the detail carries the shape of the failure and never
 * the correspondence: an exception's class name always, its message only when the
 * message could not be a piece of somebody's mail.
 *
 * The mail library is the reason for the caution. Angus Mail's `SendFailedException`
 * lists the addresses it could not deliver to, an SMTP 550 quotes the recipient back,
 * and a `MessagingException` wrapping a folder problem can name the folder. None of
 * that is a bug in the library; it is exactly what a developer at a terminal wants. It is
 * not what an issue on the internet should hold.
 *
 * Plain Kotlin, because the rule is small and the cost of getting it wrong is not.
 */
object Detail {

    /** The most a message contributes. Stack-shaped messages are long and say nothing. */
    private const val MAX = 160

    /**
     * Anything that could be an address, a display name in angle brackets or a quoted
     * string is enough to drop the whole message. An address always carries an `@`; a
     * name the library quotes back arrives in brackets or quotes. Cheap, and wrong only
     * in the direction of saying less — a server's own bracketed status tag goes too,
     * and the class name still says what kind of failure it was.
     */
    private val UNSAFE = Regex("[@<>\"'\\[\\]]")

    fun of(e: Throwable): String {
        val name = e::class.java.simpleName.ifBlank { "Throwable" }
        val msg = e.message?.replace('\n', ' ')?.trim().orEmpty()
        if (msg.isEmpty() || UNSAFE.containsMatchIn(msg)) return name
        return "$name: ${msg.take(MAX)}"
    }

    /**
     * The same rule for a sentence the app composed itself, such as the per-account
     * failure line the sync writes. Null when nothing survives.
     */
    fun ofText(s: String?): String? {
        val t = s?.replace('\n', ' ')?.trim().orEmpty()
        if (t.isEmpty() || UNSAFE.containsMatchIn(t)) return null
        return t.take(MAX)
    }
}
