package com.gios.brightmailbox.mail

import com.gios.brightmailbox.sort.Envelope

/** A message as both services can describe it. */
data class Message(
    /** Provider id, unique within the account. */
    val id: String,
    /** Thread/conversation id, so a reply lands in the right place. */
    val threadId: String,
    val accountId: String,
    val from: String,
    val fromName: String,
    val to: List<String>,
    val cc: List<String>,
    val subject: String,
    val snippet: String,
    val receivedAt: Long,
    val unread: Boolean,
    val headers: Map<String, String>,
    /** RFC 5322 Message-ID, needed to thread a reply correctly. */
    val messageId: String? = null,
    val references: String? = null,
    val hasAttachments: Boolean = false,
) {
    fun envelope(mine: Set<String>, body: String = ""): Envelope = Envelope(
        from = from.lowercase(),
        fromName = fromName,
        subject = subject,
        headers = headers,
        to = to.map { it.lowercase() },
        cc = cc.map { it.lowercase() },
        mine = mine,
        body = body,
    )
}

/** A fetched body, in whichever form the message actually had. */
/**
 * One file hung off a message.
 *
 * [part] is the path through the MIME tree — "1.2" is the second part of the first part —
 * which is how the transport finds it again later without re-walking by filename. Two
 * files in one message can share a name; nothing says they cannot.
 *
 * The bytes are NOT here. A message with a 12 MB deck would otherwise be fetched in full
 * to render three lines of text, on a phone, over IMAP.
 */
data class Attachment(
    val name: String,
    val mime: String,
    val size: Long,
    val part: String,
)

data class Content(
    val text: String?,
    val html: String?,
    val attachments: List<Attachment> = emptyList(),
)

/** A message to send. */
data class Outgoing(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    /** Set when replying, so the thread stays intact on both services. */
    val inReplyTo: String? = null,
    val references: String? = null,
    val threadId: String? = null,
)

/**
 * What the app needs from a mail account, and nothing else.
 *
 * Gmail and Graph are shaped very differently — Gmail hands back base64url MIME and
 * wants an RFC 5322 blob to send, Graph hands back JSON and takes JSON — so the
 * differences are absorbed in the implementations rather than leaked into a lowest
 * common denominator here.
 */
interface MailService {
    val accountId: String

    /** Newest first. [since] is a provider cursor, or null for a first sync. */
    suspend fun list(limit: Int, pageToken: String?): Pair<List<Message>, String?>

    suspend fun content(id: String): Content

    /**
     * The bytes of one attachment, fetched on demand.
     *
     * Separate from [content] because it is the expensive call and almost never wanted:
     * most messages with a file attached are read without anybody opening it.
     */
    suspend fun attachment(id: String, part: String): ByteArray?

    /**
     * Which of these are still in the inbox, and whether they are unread there.
     *
     * The answer to "what happened while the app was not looking". A key missing from
     * the result means the message is no longer in the inbox — archived, filed or
     * deleted somewhere else — and the value is the server's read state, which also
     * moves without us.
     *
     * Deliberately not a full folder listing: this asks about the messages the app
     * already holds, so the cost is bounded by our own row count rather than by the size
     * of somebody's inbox.
     */
    suspend fun states(ids: List<String>): Map<String, Boolean>

    suspend fun markRead(ids: List<String>)

    /**
     * Set or clear IMAP's `\Flagged` on these messages.
     *
     * The same bit Gmail draws as a star and Outlook as a flag, which is why starring
     * something in this app is worth pushing at all: it is not a private annotation, it
     * shows up wherever the mailbox is open next.
     */
    suspend fun setFlagged(ids: List<String>, on: Boolean)

    suspend fun archive(ids: List<String>)

    /**
     * Move a message out of the archive and back into the inbox.
     *
     * Addressed by **Message-ID**, not by the provider id every other call uses: that id
     * carries a UID which is valid only inside INBOX, and a message that has been archived
     * is by definition not there any more. The Message-ID is written by the sender and
     * survives every move.
     *
     * @return true if the message was found and moved.
     */
    suspend fun unarchive(messageId: String): Boolean

    suspend fun send(msg: Outgoing)

    /**
     * Addresses this user has written TO, newest first.
     *
     * The single most valuable signal the app has: someone you have replied to is a
     * person, whatever headers their mail carries. Read once at setup and refreshed on
     * a slow schedule, because it changes slowly and costs a full folder scan.
     */
    suspend fun sentTo(limit: Int): List<String>
}
