package com.gios.brightmailbox.mail

import com.gios.brightmailbox.sort.Envelope

/** A message as both services can describe it. */
/**
 * Which folder issued a message's UID.
 *
 * A UID means nothing without it. Everything this app stores came from INBOX, which is why
 * the inbox tag is the empty string — every id written before v2.32 is still read
 * correctly, and no migration was needed to introduce this. Anything from elsewhere says
 * so in its own id, and [Imap] opens that folder rather than INBOX to fetch the body.
 *
 * The alternative was a second column, which would have had to be threaded through every
 * query, every cache filename and both directions of the sync. The id is the one string
 * that already travels everywhere a message goes.
 */
enum class Box(val tag: String) {
    INBOX(""),
    SENT("SENT:"),
    ARCHIVE("ARCH:"),
    TRASH("TRASH:"),
    JUNK("JUNK:"),
    DRAFTS("DRAFT:");

    companion object {
        /** Which folder an id belongs to. Untagged means INBOX, which is most of them. */
        fun of(id: String): Box =
            entries.firstOrNull { it.tag.isNotEmpty() && id.startsWith(it.tag) } ?: INBOX
    }
}

/** Kept for readability at the call sites that only ever mean sent mail. */
const val SENT = "SENT:"

/**
 * What the server currently believes about a message we hold.
 *
 * Was a bare `Boolean` for unread, which is the shape that made the star one-way: the app
 * pushed `\Flagged` on every hold and never once asked what the flag said coming back. A
 * named pair rather than a second call, because both bits arrive in the same FLAGS fetch
 * and asking twice would double the only expensive part of reconciliation.
 */
data class State(val unread: Boolean, val flagged: Boolean)

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
    /**
     * The raw `text/calendar` part, when the message carries one.
     *
     * Captured separately from [attachments] because an invitation's calendar part usually
     * has no filename at all — it is the message, not a file hung off it — so the
     * attachment walk skips it entirely.
     */
    val calendar: String? = null,
)

/**
 * A file going out with a message.
 *
 * Bytes, not a path or a content URI: by the time SMTP runs, the screen that picked the
 * file is gone and a URI permission granted to an activity may have gone with it. Reading
 * once, at the moment the user picks, is the only version of this that cannot fail late.
 */
data class Outfile(val name: String, val mime: String, val bytes: ByteArray) {
    // A data class over a ByteArray gets identity equals/hashCode, which is wrong and
    // surprising; these are compared by what they are, not by which array they are.
    override fun equals(other: Any?): Boolean =
        other is Outfile && name == other.name && mime == other.mime &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int =
        31 * (31 * name.hashCode() + mime.hashCode()) + bytes.contentHashCode()
}

/** A message to send. */
data class Outgoing(
    val to: List<String>,
    val cc: List<String> = emptyList(),
    val subject: String,
    val body: String,
    /** Set when replying, so the thread stays intact on both services. */
    val inReplyTo: String? = null,
    /**
     * An iCalendar REPLY body, for answering an invitation.
     *
     * When present the message goes out as `multipart/alternative` with the text part
     * first and this second — which is the shape every calendar client sends and every
     * organizer's server expects. A reply sent as a plain message with the calendar
     * attached is read by a human and ignored by the calendar.
     */
    val calendarReply: String? = null,
    val references: String? = null,
    val threadId: String? = null,
    /** Files to send with it. Empty for most messages. */
    val files: List<Outfile> = emptyList(),
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
    suspend fun states(ids: List<String>): Map<String, State>

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

    /**
     * Ask the SERVER to find messages this app has never downloaded.
     *
     * **INBOX only, and that is not a shortcut.** [content] fetches a body by UID inside
     * INBOX, so a message found anywhere else would be listed and then fail to open — the
     * search would produce rows that look like mail and behave like a broken app. Finding
     * archived mail needs the Message-ID route [unarchive] uses, which is a different
     * feature.
     *
     * On Gmail this still reaches years back: INBOX holds everything never archived,
     * whatever its age, and what the app is missing is simply everything past the depth
     * the first sync walked.
     */
    suspend fun search(query: String, limit: Int, box: Box = Box.INBOX): List<Message>

    suspend fun send(msg: Outgoing)

    /**
     * Addresses this user has written TO, newest first.
     *
     * The single most valuable signal the app has: someone you have replied to is a
     * person, whatever headers their mail carries. Read once at setup and refreshed on
     * a slow schedule, because it changes slowly and costs a full folder scan.
     */
    suspend fun sentTo(limit: Int): List<String>

    /**
     * Mail this account has sent, newest first.
     *
     * Read live off the server every time the screen opens and **never stored**, which is
     * deliberate. Sent mail in the `messages` table would be caught by the inbox queries
     * that drive ARCHIVE ALL and reconciliation — both of which address a message by its
     * INBOX UID — and a bulk archive would try to move messages out of a folder that has
     * never held them.
     *
     * The ids carry a "SENT:" prefix so [content] and [attachment] know to open the sent
     * folder rather than INBOX. A UID means nothing without the folder it was issued in.
     */
    suspend fun sent(limit: Int, offset: Int = 0): List<Message>

    /**
     * Move messages into [box] — the general form of [archive].
     *
     * Trash and Junk are the two that matter: this app has never had a delete, so the only
     * way to get rid of a message was to archive it, which on Gmail means keeping it for
     * ever under a different label. Junk is a move as well rather than a flag, because
     * that is what every provider's spam filter actually learns from.
     */
    suspend fun moveTo(ids: List<String>, box: Box)

    /**
     * Move a message found by its Message-ID, rather than by a UID.
     *
     * The route for anything whose UID is not in the folder that holds it. Every message
     * stored before v2.32 carries an INBOX-relative id, so a message archived at any point
     * in the app's history has an id that addresses nothing: a server-side SEARCH on
     * Message-ID is the only handle left on it.
     *
     * The [unarchive] this was generalized out of is `moveFound(id, ARCHIVE, INBOX)`.
     *
     * @return false when the folder does not exist or the message is not in it — never an
     *   exception, because every caller wants the boolean.
     */
    suspend fun moveFound(messageId: String, from: Box, to: Box): Boolean

    /**
     * How full the mailbox is, or null when the server does not say.
     *
     * The IMAP QUOTA extension is optional and plenty of servers omit it — Gmail answers,
     * a small Dovecot often does not. Null is the ordinary case, not an error, and the one
     * screen that shows this simply leaves the line out.
     */
    suspend fun quota(): Quota?

    /**
     * Drafts sitting in the server's drafts folder.
     *
     * Read-only, and deliberately so. Uploading this app's drafts would mean APPEND, a
     * second copy of every half-written message, and a reconciliation problem between two
     * places that both think they own the text. Reading is the half with all the value:
     * a message begun at a desk can be finished on the phone.
     */
    suspend fun serverDrafts(limit: Int): List<Message>

    /**
     * Hold the connection open and call [onMail] when the server says something changed.
     *
     * Suspends until cancelled. IMAP IDLE (RFC 2177) is the protocol telling you rather
     * than you asking every fifteen minutes, and the cost is one small exchange every
     * twenty-odd minutes against a full connect-login-fetch per poll — **cheaper than the
     * polling it replaces**, as long as it is not reconnecting in a loop.
     *
     * Returns without error on a server that has no IDLE capability; the poll is still
     * running underneath and is the floor this sits on top of, never a replacement for it.
     */
    suspend fun watch(onMail: suspend () -> Unit)
}

/** Bytes used and bytes allowed, from the IMAP QUOTA extension. */
data class Quota(val usedBytes: Long, val limitBytes: Long) {
    val fraction: Float get() =
        if (limitBytes <= 0) 0f else (usedBytes.toDouble() / limitBytes).toFloat().coerceIn(0f, 1f)
}
