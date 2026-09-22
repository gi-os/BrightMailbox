package com.gios.brightmailbox.data

/**
 * Where an outgoing message is on its way out.
 *
 * Until v2.68 the outbox was one boolean on the drafts row — `queued` — and a boolean
 * cannot say why a message is still here. A draft that was never sent, one waiting for
 * the next check, one that a flush is holding right now, and one that was refused five
 * times all looked the same on the list: "waiting to send", or nothing. The person
 * looking at that list wants to know one thing, which is whether they need to do
 * anything, and that is a question about state, not about a flag.
 *
 * Stored as text so a schema dump reads as words.
 */
enum class SendState {
    /** Written here and never sent. Nothing will try. */
    DRAFT,

    /** Waiting for the next check to carry it out. */
    QUEUED,

    /**
     * A flush has it right now.
     *
     * A row found in this state at the start of a flush was left there by a process that
     * died mid-send, and is treated as [QUEUED] again: the send was never confirmed, and
     * an unconfirmed send is an unsent one. The rare cost is a duplicate when the server
     * accepted the message in the instant before the process went; the alternative is a
     * message that silently never goes.
     */
    SENDING,

    /**
     * Given up on. Stays a draft, says why, and waits for a person.
     *
     * Opening it and pressing SEND starts the count again — the person has looked at the
     * reason and decided, which is the one thing a retry loop cannot do.
     */
    FAILED,

    /**
     * Accepted by the server, on its way to being deleted.
     *
     * A tombstone, not a state anybody sees: it is written the moment SMTP says yes and
     * the row is removed right after. It exists for the crash between those two writes —
     * a row that says SENT is never sent again and never shown, whereas a row still
     * saying SENDING would be picked up and sent twice.
     */
    SENT;

    /** True for the ones a flush will or is trying to carry. */
    val inOutbox: Boolean get() = this == QUEUED || this == SENDING

    companion object {
        fun of(s: String?): SendState = entries.firstOrNull { it.name == s } ?: DRAFT
    }
}

/**
 * The transitions, in one place, with nothing Android in them.
 *
 * The rule that matters is the five: a message refused because the address does not
 * exist will be refused for ever, and a queue that retries for ever sends the same
 * failure notification twice an hour until somebody uninstalls the app. Five is enough
 * to cross a tunnel and not enough to nag.
 */
object Outbox {

    const val MAX_TRIES = 5

    /** After an attempt: what the row should say now. */
    data class After(val state: SendState, val tries: Int, val error: String)

    /** The server took it. */
    fun sent(): After = After(SendState.SENT, 0, "")

    /**
     * The server, or the network, did not.
     *
     * [why] is kept on the row so a FAILED item can say what went wrong; it is the last
     * error, not the first, because the last one is the one still true.
     */
    fun failed(triesSoFar: Int, why: String, max: Int = MAX_TRIES): After {
        val tries = triesSoFar + 1
        val state = if (tries >= max) SendState.FAILED else SendState.QUEUED
        return After(state, tries, why.take(200))
    }

    /**
     * What a flush should pick up.
     *
     * QUEUED with tries to spare, and SENDING left behind by a dead process — see
     * [SendState.SENDING] for why that one counts as unsent.
     */
    fun shouldTry(state: SendState, tries: Int, max: Int = MAX_TRIES): Boolean =
        (state == SendState.QUEUED || state == SendState.SENDING) && tries < max

    /**
     * The person pressed SEND on it again.
     *
     * The count restarts: they have read the reason and decided it is worth another go,
     * which is a decision a retry loop is not allowed to make on its own.
     */
    fun requeued(): After = After(SendState.QUEUED, 0, "")
}
