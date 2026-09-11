package com.gios.brightmailbox.mail

import com.gios.brightmailbox.auth.AuthManager
import com.gios.brightmailbox.auth.Service
import jakarta.mail.FetchProfile
import jakarta.mail.Flags
import jakarta.mail.Folder
import jakarta.mail.Header
import jakarta.mail.Multipart
import jakarta.mail.Part
import jakarta.mail.Session
import jakarta.mail.Store
import jakarta.mail.Transport
import jakarta.mail.UIDFolder
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPFolder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Properties
import jakarta.mail.Message as JavaMailMessage

/**
 * One mailbox over IMAP, for both providers.
 *
 * This replaces the Gmail REST and Microsoft Graph transports of v1 with a single code
 * path. The reason is not elegance, it is reach: Gmail's REST scopes are restricted and
 * capped at 100 users, so v1 could only ship by making every user register their own
 * Google Cloud project. Plain IMAP with an app password has no cap, no console and no
 * review, and the same protocol serves Outlook once Microsoft's OAuth token is handed
 * over as the password.
 *
 * **Angus Mail runs on Android now.** The comment this file replaces said it did not,
 * and that was true of JavaMail in 2017. Angus publishes an Android build (min API 19)
 * whose one documented gap is SASL — which used to be how you did OAuth2, and is no
 * longer: XOAUTH2 is built in as a plain auth mechanism. That single change is what
 * makes an IMAP client possible here at all, and it is why hand-rolling the protocol
 * was not worth it. IMAP is easy; MIME is not, and MIME is what Angus is for.
 *
 * **Why this is faster than v1 despite being older technology.** Gmail's REST API has no
 * batch metadata read, so v1 issued one HTTP GET per message — four hundred round trips
 * for a first sync. One IMAP FETCH with a HEADERS profile pulls the same four hundred
 * headers in a couple of round trips on one connection.
 */
class Imap(
    override val accountId: String,
    private val auth: AuthManager,
    private val service: Service,
) : MailService {

    /* ------------------------------------------------------------------- reading */

    override suspend fun list(limit: Int, pageToken: String?): Pair<List<Message>, String?> = io {
        withFolder("INBOX", write = false) { f ->
            val validity = f.getUIDValidity()
            val total = f.messageCount
            if (total <= 0) return@withFolder emptyList<Message>() to null

            /*
             * The cursor is a sequence number, counting DOWN from the newest.
             *
             * Sequence numbers renumber when messages are expunged, which would be a bug
             * in a long-lived cursor — but this one lives for the length of one paged
             * sync over a single open folder, and IMAP guarantees no expunge is reported
             * mid-command on a read-only folder. Anything longer would have to page by
             * UID instead.
             */
            val end = (pageToken?.toIntOrNull() ?: total).coerceAtMost(total)
            if (end < 1) return@withFolder emptyList<Message>() to null
            val start = maxOf(1, end - limit + 1)

            val msgs = f.getMessages(start, end)
            f.fetch(
                msgs,
                FetchProfile().apply {
                    add(UIDFolder.FetchProfileItem.UID)
                    // Every header in one command. This is the whole performance story.
                    add(IMAPFolder.FetchProfileItem.HEADERS)
                    add(FetchProfile.Item.FLAGS)
                    // ENVELOPE also carries INTERNALDATE, which is the received time.
                    add(FetchProfile.Item.ENVELOPE)
                },
            )

            // Newest first, and one unparseable message must not lose the whole page.
            val out = msgs.reversed().mapNotNull { m ->
                runCatching { convert(f, m, validity) }.getOrNull()
            }
            out to if (start > 1) (start - 1).toString() else null
        }
    }

    override suspend fun content(id: String): Content = io {
        withFolder("INBOX", write = false) { f ->
            val m = f.getMessageByUID(uidOf(id)) ?: return@withFolder Content(null, null)
            var text: String? = null
            var html: String? = null
            val attachments = ArrayList<Attachment>()

            /** [path] is the MIME tree address — "1.2" is part 2 inside part 1. */
            fun walk(part: Part, depth: Int, path: String) {
                if (depth > 12) return // malformed nesting; a real message never goes this deep
                val filename = runCatching { part.fileName }.getOrNull()
                val disposition = runCatching { part.disposition }.getOrNull()
                if (!filename.isNullOrBlank() &&
                    !disposition.equals(Part.INLINE, ignoreCase = true)
                ) {
                    attachments.add(
                        Attachment(
                            name = Addr.decodeWords(filename),
                            mime = runCatching {
                                part.contentType?.substringBefore(';')?.trim()?.lowercase()
                            }.getOrNull().orEmpty().ifBlank { "application/octet-stream" },
                            // -1 when the server does not say; the UI simply omits it.
                            size = runCatching { part.size.toLong() }.getOrDefault(-1L),
                            part = path,
                        ),
                    )
                    return
                }
                when {
                    part.isMimeType("text/plain") ->
                        if (text == null) text = runCatching { part.content as? String }.getOrNull()

                    part.isMimeType("text/html") ->
                        if (html == null) html = runCatching { part.content as? String }.getOrNull()

                    part.isMimeType("multipart/*") -> {
                        val mp = runCatching { part.content as? Multipart }.getOrNull() ?: return
                        for (i in 0 until mp.count) {
                            runCatching {
                                walk(mp.getBodyPart(i), depth + 1, join(path, i))
                            }
                        }
                    }

                    // A forwarded message arrives as a nested RFC 822 part.
                    part.isMimeType("message/rfc822") ->
                        runCatching { part.content as? Part }.getOrNull()
                            ?.let { walk(it, depth + 1, join(path, 0)) }
                }
            }
            walk(m, 0, "")
            Content(text, html, attachments)
        }
    }

    /**
     * One attachment's bytes, found by walking back to the part that [content] recorded.
     *
     * Re-walking rather than holding the Part: a `Part` belongs to an open folder and a
     * live connection, and both are gone by the time somebody taps the file. The path is
     * the only handle that survives.
     */
    override suspend fun attachment(id: String, part: String): ByteArray? = io {
        withFolder("INBOX", write = false) { f ->
            val m = f.getMessageByUID(uidOf(id)) ?: return@withFolder null
            var here: Part = m
            for (step in part.split('.').filter { it.isNotBlank() }) {
                val i = step.toIntOrNull() ?: return@withFolder null
                here = when {
                    here.isMimeType("multipart/*") ->
                        (here.content as? Multipart)?.getBodyPart(i) ?: return@withFolder null
                    here.isMimeType("message/rfc822") ->
                        here.content as? Part ?: return@withFolder null
                    else -> return@withFolder null
                }
            }
            // getInputStream decodes the transfer encoding; getRawInputStream would hand
            // back base64 and every file would open as gibberish.
            runCatching { here.inputStream.use { it.readBytes() } }.getOrNull()
        }
    }

    /**
     * Ask the server what became of the messages we hold.
     *
     * `getMessagesByUID` returns a null slot for every UID that is no longer in the
     * folder, which is exactly the signal wanted: a message archived in Gmail's web UI
     * has left INBOX and comes back null here. Those keys are simply absent from the map.
     *
     * One FLAGS fetch for the survivors, so a message read on a laptop stops being unread
     * here too. Cheap: flags only, no headers and no bodies.
     */
    override suspend fun states(ids: List<String>): Map<String, Boolean> = io {
        if (ids.isEmpty()) return@io emptyMap()
        withFolder("INBOX", write = false) { f ->
            val validity = f.getUIDValidity()
            /*
             * A UIDVALIDITY change means every UID we hold refers to nothing. Returning
             * an empty map would then archive the entire mailbox, so say "no news" and
             * let the next sync re-add what it finds.
             */
            val wanted = ids.mapNotNull { id ->
                val parts = id.split('-')
                val v = parts.getOrNull(0)?.toLongOrNull()
                val uid = parts.getOrNull(1)?.toLongOrNull()
                if (v != validity || uid == null) null else uid to id
            }
            if (wanted.isEmpty()) return@withFolder emptyMap()

            val msgs = f.getMessagesByUID(wanted.map { it.first }.toLongArray())
            f.fetch(
                msgs.filterNotNull().toTypedArray(),
                FetchProfile().apply { add(FetchProfile.Item.FLAGS) },
            )
            val out = HashMap<String, Boolean>(msgs.size)
            for ((i, m) in msgs.withIndex()) {
                if (m == null) continue // gone from the inbox
                val id = wanted.getOrNull(i)?.second ?: continue
                out[id] = runCatching { !m.isSet(Flags.Flag.SEEN) }.getOrDefault(true)
            }
            out
        }
    }

    /* ------------------------------------------------------------------- writing */

    override suspend fun markRead(ids: List<String>) {
        if (ids.isEmpty()) return
        io {
            withFolder("INBOX", write = true) { f ->
                val msgs = f.getMessagesByUID(uids(ids)).filterNotNull().toTypedArray()
                if (msgs.isNotEmpty()) f.setFlags(msgs, Flags(Flags.Flag.SEEN), true)
            }
        }
    }

    /**
     * `\Flagged`, on or off.
     *
     * One of the four flags every IMAP server is required to support, so there is no
     * capability to check and no per-provider fallback — unlike archive, which has to
     * find a folder first.
     */
    override suspend fun setFlagged(ids: List<String>, on: Boolean) {
        if (ids.isEmpty()) return
        io {
            withFolder("INBOX", write = true) { f ->
                val msgs = f.getMessagesByUID(uids(ids)).filterNotNull().toTypedArray()
                if (msgs.isNotEmpty()) f.setFlags(msgs, Flags(Flags.Flag.FLAGGED), on)
            }
        }
    }

    /**
     * Archive, which on IMAP means moving out of the inbox — never deleting.
     *
     * MOVE (RFC 6851) rather than copy-then-delete, because on Gmail setting `\Deleted`
     * in INBOX and expunging sends the message to Trash, not to All Mail. That is a
     * thirty-day fuse on someone's mail, and it is exactly the bug an "archive" button
     * must not have. Both Gmail and Exchange advertise MOVE; the copy fallback below
     * runs only on a server that does not, and it copies first so a failure leaves the
     * message where it was.
     */
    override suspend fun archive(ids: List<String>) {
        if (ids.isEmpty()) return
        io {
            withFolder("INBOX", write = true) { f ->
                val target = special(f.store, "\\All", "\\Archive", service.archiveNames)
                    ?: throw IOException("no archive folder on this account")
                val msgs = f.getMessagesByUID(uids(ids)).filterNotNull().toTypedArray()
                if (msgs.isEmpty()) return@withFolder
                try {
                    f.moveUIDMessages(msgs, target)
                } catch (e: Exception) {
                    // No MOVE extension. Copy FIRST so a failure leaves the message
                    // in the inbox rather than nowhere.
                    f.copyMessages(msgs, target)
                    f.setFlags(msgs, Flags(Flags.Flag.DELETED), true)
                    f.expunge()
                }
            }
        }
    }

    override suspend fun send(msg: Outgoing) {
        io {
            val me = auth.accounts().firstOrNull { it.id == accountId }?.email.orEmpty()
            val credential = auth.credential(accountId)
            var last: Exception? = null

            // Addr.rfc5322 already writes In-Reply-To and References, which is all IMAP
            // threading has. Outgoing.threadId is a Gmail/Graph concept and is ignored.
            val raw = Addr.rfc5322(me, msg).toByteArray(Charsets.UTF_8)

            for (host in service.smtpHosts) {
                try {
                    val session = Session.getInstance(smtpProps(host))
                    val mime = MimeMessage(session, ByteArrayInputStream(raw))
                    session.getTransport("smtp").use { t ->
                        t.connect(host, service.smtpPort, me, credential)
                        t.sendMessage(mime, mime.allRecipients ?: emptyArray())
                    }
                    return@io
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IOException("could not send: ${last?.message}", last)
        }
    }

    override suspend fun sentTo(limit: Int): List<String> = io {
        val name = special(store(), "\\Sent", null, service.sentNames)?.fullName
            ?: return@io emptyList()
        withFolder(name, write = false) { f ->
            val total = f.messageCount
            if (total <= 0) return@withFolder emptyList()
            val msgs = f.getMessages(maxOf(1, total - limit + 1), total)
            f.fetch(msgs, FetchProfile().apply { add(IMAPFolder.FetchProfileItem.HEADERS) })
            val out = LinkedHashSet<String>()
            for (m in msgs) {
                runCatching {
                    out.addAll(Addr.addresses(m.getHeader("To")?.joinToString(", ")))
                    out.addAll(Addr.addresses(m.getHeader("Cc")?.joinToString(", ")))
                }
            }
            out.toList()
        }
    }

    /* ----------------------------------------------------------------- conversion */

    private fun convert(f: IMAPFolder, m: JavaMailMessage, validity: Long): Message {
        val headers = HashMap<String, String>()
        runCatching {
            // getAllHeaders() is declared as a RAW Enumeration, so Kotlin types
            // nextElement() as Any and h.name would not resolve. The cast is required,
            // not defensive.
            val e = m.allHeaders
            while (e.hasMoreElements()) {
                val h = e.nextElement() as? Header ?: continue
                val k = h.name.lowercase()
                headers[k] = headers[k]?.plus(", ")?.plus(h.value) ?: h.value
            }
        }

        val fromRaw = headers["from"].orEmpty()
        val uid = f.getUID(m)
        val messageId = headers["message-id"]?.trim()
        val references = headers["references"]?.trim()

        return Message(
            id = "$validity-$uid",
            threadId = threadKey(messageId, references, headers["in-reply-to"]),
            accountId = accountId,
            from = Addr.address(fromRaw) ?: "",
            fromName = Addr.decodeWords(Addr.name(fromRaw)),
            to = Addr.addresses(headers["to"]),
            cc = Addr.addresses(headers["cc"]),
            subject = Addr.decodeWords(headers["subject"].orEmpty()),
            // IMAP has no snippet and fetching one per message would cost a round trip
            // each, which is the whole reason this transport is fast. The row shows
            // sender and subject; the body arrives when the message is opened.
            snippet = "",
            receivedAt = (runCatching { m.receivedDate }.getOrNull() ?: m.sentDate)?.time
                ?: System.currentTimeMillis(),
            unread = runCatching { !m.isSet(Flags.Flag.SEEN) }.getOrDefault(true),
            headers = headers,
            messageId = messageId,
            references = references,
            // Cheap and header-only: a real attachment forces multipart/mixed. Reading
            // BODYSTRUCTURE would be exact and would cost another fetch per page.
            hasAttachments = headers["content-type"]?.contains("multipart/mixed", true) == true,
        )
    }

    /**
     * A stable conversation key, since IMAP has no thread id.
     *
     * The root of an RFC 5322 reference chain identifies a conversation as well as
     * Gmail's threadId does, and better than Graph's conversationId does across
     * providers: the first Message-ID in `References` is the message that started it.
     * Falling back to `In-Reply-To` covers clients that omit References, and to the
     * message's own id for anything that started here.
     */
    private fun threadKey(messageId: String?, references: String?, inReplyTo: String?): String {
        references?.split(Regex("\\s+"))?.firstOrNull { it.startsWith("<") && it.endsWith(">") }
            ?.let { return it }
        inReplyTo?.trim()?.takeIf { it.startsWith("<") }?.let { return it }
        return messageId ?: ""
    }

    /* ------------------------------------------------------------------- plumbing */

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    /** "" + 2 -> "2"; "1" + 0 -> "1.0". Dotted, so it survives a round trip as a string. */
    private fun join(path: String, index: Int): String =
        if (path.isEmpty()) index.toString() else "$path.$index"

    private fun uidOf(id: String): Long =
        id.substringAfterLast('-').toLongOrNull()
            ?: throw IOException("not a UID: $id")

    private fun uids(ids: List<String>): LongArray =
        ids.mapNotNull { it.substringAfterLast('-').toLongOrNull() }.toLongArray()

    /**
     * Find a folder by SPECIAL-USE attribute, falling back to well-known names.
     *
     * SPECIAL-USE is how a server says "this one is the archive" without the client
     * guessing at localized display names, and both providers advertise it. The name
     * list is only for servers that do not.
     */
    private fun special(store: Store, attr: String, alt: String?, names: List<String>): Folder? {
        runCatching {
            for (f in store.defaultFolder.list("*")) {
                val attrs = (f as? IMAPFolder)?.attributes ?: continue
                if (attrs.any { it.equals(attr, true) || (alt != null && it.equals(alt, true)) }) {
                    return f
                }
            }
        }
        for (n in names) {
            runCatching { store.getFolder(n) }.getOrNull()?.let { if (it.exists()) return it }
        }
        return null
    }

    private suspend fun store(): Store {
        val credential = auth.credential(accountId)
        val email = auth.accounts().firstOrNull { it.id == accountId }?.email.orEmpty()
        return Pool.get(accountId) {
            val session = Session.getInstance(imapProps())
            session.getStore("imap").also {
                it.connect(service.imapHost, service.imapPort, email, credential)
            }
        }
    }

    /**
     * Open a folder, run, close the folder — but leave the Store connected.
     *
     * Repo builds a fresh MailService for every call, so without the pool each archive
     * or open would pay a TLS handshake plus a LOGIN, which on this phone is one to two
     * seconds of nothing happening. A dropped connection retries once: IMAP servers
     * close idle connections aggressively and the first command after a nap fails.
     */
    private suspend fun <T> withFolder(name: String, write: Boolean, block: (IMAPFolder) -> T): T {
        repeat(2) { attempt ->
            val store = try {
                store()
            } catch (e: Exception) {
                Pool.drop(accountId)
                if (attempt == 1) throw e
                return@repeat
            }
            val folder = try {
                store.getFolder(name) as IMAPFolder
            } catch (e: Exception) {
                Pool.drop(accountId)
                if (attempt == 1) throw e
                return@repeat
            }
            try {
                folder.open(if (write) Folder.READ_WRITE else Folder.READ_ONLY)
                return block(folder)
            } catch (e: Exception) {
                // A stale pooled connection fails here on the first command.
                Pool.drop(accountId)
                if (attempt == 1) throw e
            } finally {
                // expunge=false: closing must never delete anything.
                runCatching { if (folder.isOpen) folder.close(false) }
            }
        }
        throw IOException("imap: could not open $name")
    }

    private fun imapProps() = Properties().apply {
        put("mail.store.protocol", "imap")
        put("mail.imap.host", service.imapHost)
        put("mail.imap.port", service.imapPort.toString())
        put("mail.imap.ssl.enable", "true")
        put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3")
        put("mail.imap.connectiontimeout", "20000")
        put("mail.imap.timeout", "40000")
        put("mail.imap.writetimeout", "20000")
        // Lenient address parsing. Strict mode throws on headers that real mail contains
        // and that every other client renders without complaint.
        put("mail.mime.address.strict", "false")
        authMechanism("imap")
    }

    private fun smtpProps(host: String) = Properties().apply {
        put("mail.transport.protocol", "smtp")
        put("mail.smtp.host", host)
        put("mail.smtp.port", service.smtpPort.toString())
        put("mail.smtp.auth", "true")
        if (service.smtpSsl) {
            put("mail.smtp.ssl.enable", "true")
        } else {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
        put("mail.smtp.ssl.protocols", "TLSv1.2 TLSv1.3")
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "40000")
        put("mail.mime.address.strict", "false")
        authMechanism("smtp")
    }

    /**
     * Pin the mechanism rather than letting it negotiate.
     *
     * With OAuth the access token is passed in the password field, which is meaningless
     * to PLAIN or LOGIN — so if the negotiation picks one of those it fails with a bare
     * authentication error that looks like a wrong password. Angus supports XOAUTH2
     * directly here; the SASL route the old docs describe does not exist on Android.
     */
    private fun Properties.authMechanism(protocol: String) {
        if (service.usesOAuth) {
            put("mail.$protocol.auth.mechanisms", "XOAUTH2")
        } else {
            put("mail.$protocol.auth.mechanisms", "PLAIN LOGIN")
            put("mail.$protocol.auth.xoauth2.disable", "true")
        }
    }

    /**
     * Open IMAP connections, one per account.
     *
     * Deliberately process-wide and deliberately tiny. Gmail allows fifteen simultaneous
     * IMAP connections per account and locks the mailbox for up to a day when a client
     * exceeds it, so opening one per operation is not merely slow, it is a way to get
     * the user's mailbox suspended.
     */
    private object Pool {
        private val stores = HashMap<String, Store>()

        @Synchronized
        fun get(key: String, connect: () -> Store): Store {
            stores[key]?.let { existing ->
                if (existing.isConnected) return existing
                runCatching { existing.close() }
                stores.remove(key)
            }
            return connect().also { stores[key] = it }
        }

        @Synchronized
        fun drop(key: String) {
            stores.remove(key)?.let { runCatching { it.close() } }
        }

        @Synchronized
        fun dropAll() {
            stores.values.forEach { runCatching { it.close() } }
            stores.clear()
        }
    }

    companion object {
        /** Called when an account is removed, and when the app parks. */
        fun disconnect(accountId: String?) =
            if (accountId == null) Pool.dropAll() else Pool.drop(accountId)

        /**
         * Try a credential before storing it, so a typo is caught at the keyboard rather
         * than as a silent sync failure fifteen minutes later.
         *
         * Returns null on success, or a short sentence to show the user. Deliberately
         * not an exception: every caller wants the sentence.
         */
        suspend fun verify(service: Service, email: String, credential: String): String? =
            withContext(Dispatchers.IO) {
                val props = Properties().apply {
                    put("mail.store.protocol", "imap")
                    put("mail.imap.ssl.enable", "true")
                    put("mail.imap.ssl.protocols", "TLSv1.2 TLSv1.3")
                    put("mail.imap.connectiontimeout", "20000")
                    put("mail.imap.timeout", "20000")
                    if (service.usesOAuth) {
                        put("mail.imap.auth.mechanisms", "XOAUTH2")
                    } else {
                        put("mail.imap.auth.mechanisms", "PLAIN LOGIN")
                    }
                }
                try {
                    Session.getInstance(props).getStore("imap").use { store ->
                        store.connect(service.imapHost, service.imapPort, email, credential)
                        store.getFolder("INBOX").let {
                            if (!it.exists()) return@withContext "Signed in, but there is no inbox."
                        }
                    }
                    null
                } catch (e: jakarta.mail.AuthenticationFailedException) {
                    if (service.usesOAuth) {
                        "Outlook refused the sign-in. Try again."
                    } else {
                        "Gmail refused that. Check it is an app password, not your Google password."
                    }
                } catch (e: Exception) {
                    "Could not reach ${service.imapHost}. Check the connection."
                }
            }

        /** `alex@x.com` out of whatever the user typed, or null. */
        fun emailOf(raw: String): String? =
            InternetAddress.parse(raw.trim(), false).firstOrNull()?.address
                ?.lowercase()?.takeIf { it.contains('@') }
    }
}
