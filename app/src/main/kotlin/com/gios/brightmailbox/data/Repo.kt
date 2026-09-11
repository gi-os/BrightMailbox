package com.gios.brightmailbox.data

import android.content.Context
import androidx.room.Room
import com.gios.brightmailbox.auth.AuthManager
import com.gios.brightmailbox.mail.Attachment
import com.gios.brightmailbox.mail.Content
import com.gios.brightmailbox.mail.Imap
import com.gios.brightmailbox.mail.MailService
import com.gios.brightmailbox.mail.Message
import com.gios.brightmailbox.mail.Outgoing
import com.gios.brightmailbox.notify.Chime
import com.gios.brightmailbox.sort.Envelope
import com.gios.brightmailbox.sort.Learner
import com.gios.brightmailbox.sort.Pile
import com.gios.brightmailbox.sort.Sorter
import com.gios.brightmailbox.text.Clean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** How many Letters a day. Two states, styled like BrightMarket's channel switch. */
enum class Ration(val key: String, val label: String, val perDay: Int) {
    FIVE("five", "Five", 5),
    UNLIMITED("unlimited", "Unlimited", Int.MAX_VALUE);

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k } ?: FIVE
    }
}

/**
 * How far back the first sync reads, per account.
 *
 * This is history, not a cap on the mailbox: every message that arrives afterwards is
 * fetched whatever this says. It only decides how much of the past is there on day one —
 * and the past is what the sorter learns from, so a bigger number is a better sort and a
 * longer wait. 400 was hardcoded until someone asked whether it was a limit.
 *
 * [EVERYTHING] has no number, so the first-sync screen counts up with no total rather than
 * pretending to know one.
 */
enum class Depth(val key: String, val label: String, val perAccount: Int) {
    SHORT("200", "200 messages", 200),
    NORMAL("400", "400 messages", 400),
    LONG("2000", "2,000 messages", 2_000),
    EVERYTHING("all", "Everything", Int.MAX_VALUE);

    /** What to show as the total while syncing. Zero means "do not claim one". */
    val estimate: Int get() = if (this == EVERYTHING) 0 else perAccount

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k } ?: NORMAL
    }
}

/**
 * Which way a message opens.
 *
 * Not a preference about rendering so much as one about what mail is for. [FORMATTED]
 * shows the message its sender built, images, columns and all. [PLAIN] shows the words
 * and throws the rest away, which on a phone bought to be boring is a perfectly
 * reasonable thing to want every time rather than to ask for message by message.
 *
 * Either way the other view is one tap away in the reader's ··· sheet — this decides
 * where every message starts, not what is available.
 */
enum class Reading(val key: String, val label: String) {
    FORMATTED("formatted", "As sent"),
    PLAIN("plain", "Text only");

    companion object {
        fun of(k: String?) = entries.firstOrNull { it.key == k } ?: FORMATTED
    }
}

/**
 * Everything above the network and below the UI.
 */
class Repo private constructor(private val app: Context) {

    val auth = AuthManager(app)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val db = Room.databaseBuilder(app, MailDb::class.java, "mailbox.db")
        .addMigrations(MailDb.MIGRATION_1_2, MailDb.MIGRATION_2_3)
        .fallbackToDestructiveMigration()
        .build()

    val dao: MailDao get() = db.dao()

    private val prefs = app.getSharedPreferences("brightmailbox", Context.MODE_PRIVATE)
    private val bodies = File(app.filesDir, "bodies").apply { mkdirs() }
    private val modelFile = File(app.filesDir, "model.txt")

    val learner = Learner().also { l ->
        if (modelFile.exists()) runCatching { l.load(modelFile.readText()) }
    }

    /* ------------------------------------------------------------------ settings */

    var ration: Ration
        get() = Ration.of(prefs.getString("ration", null))
        set(v) = prefs.edit().putString("ration", v.key).apply()

    var depth: Depth
        get() = Depth.of(prefs.getString("depth", null))
        set(v) = prefs.edit().putString("depth", v.key).apply()

    var chime: Chime
        get() = Chime.of(prefs.getString("chime", null))
        set(v) = prefs.edit().putString("chime", v.key).apply()

    var customSound: String?
        get() = prefs.getString("custom_sound", null)
        set(v) = prefs.edit().putString("custom_sound", v).apply()

    /**
     * Remote images in a rendered message.
     *
     * On by default from v2.5, which is a deliberate reversal. A remote image is a
     * tracking pixel — it tells the sender the moment the message was opened, and
     * roughly from where — and blocking is the privacy-preserving default every careful
     * mail client picks. The cost was that half the mail the app renders arrived as a
     * column of grey boxes, which made the reader look broken rather than careful.
     * Switchable in Settings; the trade is named there rather than hidden.
     */
    /** Which view a message opens in. The ··· sheet still switches the one on screen. */
    var reading: Reading
        get() = Reading.of(prefs.getString("reading", null))
        set(v) = prefs.edit().putString("reading", v.key).apply()

    var showImages: Boolean
        get() = prefs.getBoolean("images", true)
        set(v) = prefs.edit().putBoolean("images", v).apply()

    /**
     * Appended to everything sent, after the standard "-- " separator.
     *
     * That separator is not decoration: RFC 3676 defines "-- " on a line of its own as the
     * start of a signature, and every mail client in the world uses it to fold the thing
     * away when quoting a reply. Writing the signature without it means it is quoted back
     * at you in every response.
     */
    var signature: String
        get() = prefs.getString("signature", "").orEmpty()
        set(v) = prefs.edit().putString("signature", v.trim()).apply()

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0L)
        private set(v) = prefs.edit().putLong("last_sync", v).apply()

    /**
     * Why the last check did not work, or null if it did.
     *
     * Persisted rather than held in memory: the sync that fails is usually the background
     * one, hours before anybody opens the app to wonder why there is no mail.
     */
    var lastError: String?
        get() = prefs.getString("last_error", null)
        private set(v) = prefs.edit().putString("last_error", v).apply()

    /** Letters read past the ration today, unlocked one at a time by a wheel hold. */
    var extraToday: Int
        get() = if (prefs.getInt("extra_day", 0) == today()) prefs.getInt("extra_n", 0) else 0
        set(v) = prefs.edit().putInt("extra_day", today()).putInt("extra_n", v).apply()

    /* --------------------------------------------------------------------- feeds */

    /**
     * Today's Letters, including the ones already read today.
     *
     * The day is passed in rather than read here so the caller can re-ask for it — a
     * process that lives across midnight would otherwise hold yesterday's list forever.
     * See [MailboxViewModel.letters].
     */
    fun letters(day: Int = today()): Flow<List<Msg>> = dao.letters(day)
    fun notices(): Flow<List<Msg>> = dao.notices()
    fun unreadNotices(): Flow<Int> = dao.unreadNotices()
    fun noticeTotal(): Flow<Int> = dao.noticeTotal()
    fun waitingLetters(): Flow<Int> = dao.waitingLetters()
    fun rules(): Flow<List<SenderRule>> = dao.rules()

    suspend fun allowedToday(): Int = when (ration) {
        Ration.UNLIMITED -> Int.MAX_VALUE
        Ration.FIVE -> (Ration.FIVE.perDay + extraToday - dao.readToday(today())).coerceAtLeast(0)
    }

    suspend fun readToday(): Int = dao.readToday(today())

    /* ------------------------------------------------------------------- syncing */

    /**
     * One transport for both providers now.
     *
     * v1 had a Gmail REST implementation and a Microsoft Graph one; v2 has IMAP, which
     * both speak. The instance is cheap — the expensive part, the authenticated
     * connection, is pooled inside [Imap] and survives across these.
     */
    private fun serviceFor(id: String): MailService? {
        val acct = auth.accounts().firstOrNull { it.id == id } ?: return null
        return Imap(id, auth, acct.service)
    }

    /** Every address the user owns, so "was this addressed to me" can be answered. */
    private fun myAddresses(): Set<String> =
        auth.accounts().map { it.email.lowercase() }.toSet()

    data class SyncResult(
        val fetched: Int,
        val newLetters: Int,
        val firstLetter: Msg?,
        /** One sentence per account that could not be read. Empty when all is well. */
        val failures: List<String> = emptyList(),
    )

    /**
     * Pull new mail and sort it.
     *
     * @param limit per account. 20 on a background pass so a first sync is not killed by
     *   WorkManager's ten-minute budget; the setup screen walks a bigger number itself.
     */
    suspend fun sync(limit: Int = 20): SyncResult = withContext(Dispatchers.IO) {
        val mine = myAddresses()
        val overrides = dao.allRules().associate { it.address to Pile.valueOf(it.pile) }
        val replies = dao.correspondents().associate { it.address to it.replies }
        val sorter = Sorter(learner, overrides, replies)

        var fetched = 0
        var newLetters = 0
        var first: Msg? = null
        var worked = 0
        val failures = ArrayList<String>()

        for (account in auth.accounts()) {
            val svc = serviceFor(account.id)
            if (svc == null) {
                failures.add("${account.word}: not signed in")
                continue
            }
            val newest = dao.newestFor(account.id) ?: 0L

            /*
             * Every failure here used to be swallowed — `runCatching{}.getOrNull() ?: continue`
             * — and `lastSync` was stamped afterwards regardless. An account that could not
             * connect was therefore indistinguishable from an inbox with nothing new: the
             * refresh appeared to work, Settings said "last checked just now", and no mail
             * arrived. That is the shape of the first field report this app got, and the
             * bug was not the fetch, it was that nobody could see the fetch failing.
             */
            val messages = try {
                svc.list(limit, null).first
            } catch (e: Exception) {
                failures.add("${account.word}: ${reason(e)}")
                continue
            }
            worked++

            val rows = ArrayList<Msg>(messages.size)
            for (m in messages) {
                val key = "${account.id}/${m.id}"
                if (dao.get(key) != null) continue
                val row = classify(m, sorter, mine)
                rows.add(row)
                fetched++
                if (row.pile == Pile.LETTER.name && m.receivedAt > newest) {
                    newLetters++
                    if (first == null) first = row
                }
            }
            if (rows.isNotEmpty()) dao.put(rows)

            /*
             * The other direction.
             *
             * Sync only ever added. Archive a message in Gmail on a laptop and it stayed
             * in Mailbox forever, because nothing ever asked the server what happened to
             * the messages already held — the app read the inbox as an append-only feed,
             * which it is not.
             *
             * A key missing from `states` means the message has left INBOX: archived,
             * filed or deleted elsewhere. Archiving locally is the safe echo of all
             * three — it removes the row from view and touches nothing on the server.
             * Read state is copied back the same way.
             *
             * Failures here are swallowed on purpose. Reconciliation is a nicety and the
             * mail that just arrived is not; a server that will not answer this must not
             * cost the fetch that already worked.
             */
            runCatching {
                val live = dao.liveFor(account.id)
                if (live.isNotEmpty()) {
                    val states = svc.states(live.map { it.providerId })
                    val gone = live.filter { it.providerId !in states }.map { it.key }
                    if (gone.isNotEmpty()) dao.archiveAll(gone)
                    /*
                     * Read on another device, so read here.
                     *
                     * `m.unread` is the wrong test on its own — it only asks what the
                     * server said last time. `!m.readHere` is what decides whether the
                     * row still draws as unread, so both have to be considered or a
                     * message marked seen by an earlier sync never picks up the grey.
                     */
                    val readElsewhere = live.filter { m ->
                        (m.unread || !m.readHere) && states[m.providerId] == false
                    }.map { it.key }
                    if (readElsewhere.isNotEmpty()) dao.markSeen(readElsewhere, today())
                }
            }
        }

        // Only a check that actually reached a mailbox counts as a check. Stamping the
        // clock on a total failure is what let "last checked a minute ago" sit above an
        // inbox that had not been read in a day.
        if (worked > 0) lastSync = System.currentTimeMillis()
        lastError = failures.joinToString("; ").ifBlank { null }
        SyncResult(fetched, newLetters, first, failures)
    }

    /**
     * An IMAP failure in words a person can act on.
     *
     * The raw text is kept on the end whatever happens: it is what a shake report carries
     * back, and a sentence that has been tidied into uselessness cannot be diagnosed.
     */
    private fun reason(e: Exception): String {
        val raw = (e.message ?: e.javaClass.simpleName).replace('\n', ' ').take(160)
        val lower = raw.lowercase()
        return when {
            e is com.gios.brightmailbox.auth.ReauthRequired ->
                "sign in again"
            lower.contains("authenticationfailed") || lower.contains("invalid credentials") ||
                lower.contains("[authenticationfailed]") ->
                "the password was refused — $raw"
            lower.contains("too many simultaneous") || lower.contains("exceeded the rate") ||
                lower.contains("limit exceeded") ->
                "the server is rate-limiting this account — $raw"
            lower.contains("unable to resolve host") || lower.contains("timed out") ||
                lower.contains("timeout") || lower.contains("econnrefused") ->
                "could not reach the server — $raw"
            else -> raw
        }
    }

    /**
     * A first sync: walk further back, and learn from what is there.
     *
     * @param howFar how much history to read per account. The user's setting by default —
     *   see [Depth]. This has never been a cap on the mailbox: new mail arrives regardless.
     * @param onProgress called with (done, total-ish) so the setup screen can count
     *   rather than spin. There is no spinner anywhere in this app.
     */
    suspend fun firstSync(
        howFar: Depth = depth,
        onProgress: (Int, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    ) = withContext(Dispatchers.IO) {
        val perAccount = howFar.perAccount
        val mine = myAddresses()

        // Who has the user written to? This is the strongest classification signal in
        // the app, and it is only available by reading the Sent folder once.
        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            val sent = runCatching { svc.sentTo(300) }.getOrDefault(emptyList())
            val counts = HashMap<String, Int>()
            sent.forEach { counts[it] = (counts[it] ?: 0) + 1 }
            dao.putCorrespondents(
                counts.map { (a, n) -> Correspondent(a, n, System.currentTimeMillis()) },
            )
        }

        val overrides = dao.allRules().associate { it.address to Pile.valueOf(it.pile) }
        val replies = dao.correspondents().associate { it.address to it.replies }
        val sorter = Sorter(learner, overrides, replies)

        var done = 0
        var letters = 0
        var notices = 0
        // Not `accounts * perAccount`: "Everything" is Int.MAX_VALUE and that multiplication
        // overflows into a negative total, which the screen would draw. Zero is the honest
        // answer when the size of the job is unknown, and the screen shows no total for it.
        val estimate = howFar.estimate * auth.accounts().size

        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            var token: String? = null
            var taken = 0
            while (taken < perAccount) {
                val (messages, next) = runCatching { svc.list(50, token) }.getOrNull() ?: break
                if (messages.isEmpty()) break
                val rows = messages.map { classify(it, sorter, mine) }
                dao.put(rows)
                rows.forEach { if (it.pile == Pile.LETTER.name) letters++ else notices++ }
                done += rows.size
                taken += rows.size
                onProgress(done, estimate, letters, notices)
                token = next ?: break
            }
        }

        retrain()
        lastSync = System.currentTimeMillis()
    }

    private fun classify(m: Message, sorter: Sorter, mine: Set<String>): Msg {
        val out = sorter.sort(m.envelope(mine, m.snippet))
        return Msg(
            key = "${m.accountId}/${m.id}",
            accountId = m.accountId,
            providerId = m.id,
            threadId = m.threadId,
            sender = m.from,
            senderName = m.fromName.ifBlank { m.from.substringBefore('@') },
            subject = m.subject,
            snippet = m.snippet,
            receivedAt = m.receivedAt,
            unread = m.unread,
            pile = out.verdict.pile.name,
            reason = out.verdict.reason,
            rule = out.verdict.rule,
            score = out.importance,
            messageId = m.messageId,
            references = m.references,
            hasAttachments = m.hasAttachments,
        )
    }

    /* ------------------------------------------------------------------- bodies */

    private fun bodyFile(key: String) = File(bodies, key.replace('/', '_') + ".txt")

    /**
     * The sender's own HTML, kept beside the cleaned text.
     *
     * v1 threw this away after flattening it, which made "SHOW ORIGINAL" impossible
     * without a second network round trip for a message already read.
     */
    private fun htmlFile(key: String) = File(bodies, key.replace('/', '_') + ".html")

    /** The cleaned reading text, fetched and cached on first open. */
    suspend fun body(msg: Msg): Clean.Body = withContext(Dispatchers.IO) {
        val f = bodyFile(msg.key)
        if (f.exists()) return@withContext Clean.body(f.readText())

        val svc = serviceFor(msg.accountId) ?: return@withContext Clean.Body(msg.snippet, 0)
        val c: Content = runCatching { svc.content(msg.providerId) }
            .getOrElse { return@withContext Clean.Body(msg.snippet, 0) }
        /*
         * Always write the HTML file, empty when the message had none.
         *
         * The file's existence is the cached answer to "does this message have HTML",
         * which the reader asks on every open. Writing it only when there IS html left
         * plain-text mail with no cached answer, so every open of a plain message paid a
         * fresh IMAP round trip to be told "no" again.
         */
        runCatching { htmlFile(msg.key).writeText(c.html?.takeIf { it.isNotBlank() }.orEmpty()) }
        val raw = c.text?.takeIf { it.isNotBlank() }
            ?: c.html?.let { Clean.fromHtml(it) }
            ?: msg.snippet
        runCatching { f.writeText(raw) }
        Clean.body(raw)
    }

    /**
     * What is already on disk for this message, without touching the network.
     *
     * The reason this exists separately from [body] and [original] is the animation. Those
     * two will happily go to the server, so calling them before the reader opens would
     * stall the tap for a round trip; calling them after means the reader is composed
     * before anyone knows whether the message has HTML, and **a null `html` means two
     * different things** — "there is none" and "nobody has looked yet". The reader drew
     * the plain-text layout for both, so the first letter of a session slid up as a black
     * page and then snapped to a white sheet once the fetch landed. That is the missing
     * animation: it ran, on the wrong thing.
     *
     * Everything the prefetch has already fetched — the letters on the front screen — is
     * answered from here in under a millisecond, so the reader knows what it is drawing
     * before it is on screen.
     *
     * An html file that exists and is empty is a real answer: "asked already, there is
     * none". Only a missing file means unknown.
     */
    suspend fun cached(msg: Msg): Pair<Clean.Body?, String?> = withContext(Dispatchers.IO) {
        val text = bodyFile(msg.key).takeIf { it.exists() }
            ?.let { runCatching { Clean.body(it.readText()) }.getOrNull() }
        val html = htmlFile(msg.key).takeIf { it.exists() }
            ?.let { runCatching { it.readText() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
        text to html
    }

    /**
     * Put the text of the messages about to be opened on disk, before they are opened.
     *
     * Bodies are cached by [body] on first open, which means the first open of every
     * message pays a round trip with nothing on screen: IMAP sends no snippet, so there is
     * not even a first line to show while it waits. This walks a short list, skips
     * everything already cached, and swallows every failure — it is an optimisation, and
     * an optimisation that can break the sync it rides on is not one.
     *
     * Sequential on purpose. The connection is pooled one per account, and Gmail locks a
     * mailbox for up to 24 hours past fifteen simultaneous IMAP connections.
     */
    suspend fun prefetchBodies(letters: List<Msg>, notices: List<Msg> = emptyList()) =
        withContext(Dispatchers.IO) {
            for (m in letters + notices) {
                if (bodyFile(m.key).exists()) continue
                runCatching { body(m) }
            }
        }

    /**
     * The sender's HTML, or null for a plain-text message.
     *
     * An existing but empty file means "asked already, there is none" — see [body]. Only
     * a missing file is worth a network round trip.
     */
    suspend fun original(msg: Msg): String? = withContext(Dispatchers.IO) {
        val cached = htmlFile(msg.key)
        if (cached.exists()) return@withContext cached.readText().takeIf { it.isNotBlank() }
        val svc = serviceFor(msg.accountId) ?: return@withContext null
        val c = runCatching { svc.content(msg.providerId) }.getOrNull() ?: return@withContext null
        val html = c.html?.takeIf { it.isNotBlank() }
        runCatching { cached.writeText(html.orEmpty()) }
        html
    }

    /* --------------------------------------------------------------- attachments */

    private fun attachmentIndex(key: String) =
        File(bodies, key.replace('/', '_') + ".att")

    /**
     * What is attached to a message, cached beside the body.
     *
     * A tiny hand-rolled record per line — name, mime, size, part — rather than JSON,
     * because it is written by one function and read by one function and a dependency
     * for four fields would be silly. Tab-separated: a filename can contain almost
     * anything except a tab or a newline.
     */
    suspend fun attachments(msg: Msg): List<Attachment> = withContext(Dispatchers.IO) {
        val f = attachmentIndex(msg.key)
        if (f.exists()) {
            return@withContext f.readLines().mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 4) null
                else Attachment(p[0], p[1], p[2].toLongOrNull() ?: -1L, p[3])
            }
        }
        val svc = serviceFor(msg.accountId) ?: return@withContext emptyList()
        val c = runCatching { svc.content(msg.providerId) }.getOrNull()
            ?: return@withContext emptyList()
        runCatching {
            f.writeText(c.attachments.joinToString("\n") { "${it.name}\t${it.mime}\t${it.size}\t${it.part}" })
        }
        c.attachments
    }

    /**
     * An attachment on disk, ready to hand to another app.
     *
     * Cached under the app's own cache directory rather than Downloads: the file is a
     * copy of someone's mail and it should go away with the app, not settle into the
     * phone's storage where it outlives the message it came from.
     */
    suspend fun attachmentFile(msg: Msg, att: Attachment): File? = withContext(Dispatchers.IO) {
        val dir = File(app.cacheDir, "attachments/" + msg.key.replace('/', '_'))
        dir.mkdirs()
        // The part path is in the filename, so two files with the same name do not
        // overwrite each other.
        val out = File(dir, att.part.replace('.', '_') + "-" + safeName(att.name))
        if (out.exists() && out.length() > 0) return@withContext out

        val svc = serviceFor(msg.accountId) ?: return@withContext null
        val bytes = runCatching { svc.attachment(msg.providerId, att.part) }.getOrNull()
            ?: return@withContext null
        runCatching { out.writeBytes(bytes); out }.getOrNull()
    }

    /**
     * Copy an attachment into the phone's Downloads folder, to keep.
     *
     * The cached copy under [attachmentFile] is deliberately disposable — it lives in
     * cacheDir and goes when the app does, because a cache of other people's mail should
     * not quietly become permanent storage. This is the opposite intent, stated by the
     * user, so it goes somewhere they can find it from any other app.
     *
     * MediaStore rather than a path. Since Android 10 an app cannot simply write into
     * shared storage, but it can hand a file to the Downloads collection and the system
     * files it — with no permission at all, which is why there is no runtime prompt here.
     *
     * `IS_PENDING` brackets the write so nothing else can see a half-copied file.
     *
     * @return the display name it was saved as, or null if it could not be written.
     */
    suspend fun saveToDownloads(msg: Msg, att: Attachment): String? = withContext(Dispatchers.IO) {
        val source = attachmentFile(msg, att) ?: return@withContext null
        val name = safeName(att.name)
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
            put(android.provider.MediaStore.Downloads.MIME_TYPE, att.mime.ifBlank { "application/octet-stream" })
            put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = app.contentResolver
        val uri = runCatching {
            resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        }.getOrNull() ?: return@withContext null

        val ok = runCatching {
            resolver.openOutputStream(uri)!!.use { out -> source.inputStream().use { it.copyTo(out) } }
        }.isSuccess

        if (!ok) {
            runCatching { resolver.delete(uri, null, null) }
            return@withContext null
        }
        runCatching {
            values.clear()
            values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        name
    }

    /** A filename a filesystem will accept, keeping the extension so the mime survives. */
    private fun safeName(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeLast(80).ifBlank { "file" }

    /* -------------------------------------------------------------------- verbs */

    suspend fun open(msg: Msg) = withContext(Dispatchers.IO) {
        dao.markRead(msg.key, today())
        runCatching { serviceFor(msg.accountId)?.markRead(listOf(msg.providerId)) }
        // Opening a Letter is a weak vote that it was worth reading.
        learn(msg, worthReading = true, weight = 1.0)
    }

    suspend fun archive(msg: Msg) = withContext(Dispatchers.IO) {
        dao.archive(msg.key)
        runCatching { serviceFor(msg.accountId)?.archive(listOf(msg.providerId)) }
    }

    /**
     * Hold a message, or let it go.
     *
     * Local first, server second, and the server call is best-effort: a star is a decision
     * about what this screen shows, so it has to take effect with no signal. The `\Flagged`
     * bit is a bonus — it is what makes the same message appear starred in Gmail and
     * flagged in Outlook — but nothing here depends on it landing.
     */
    suspend fun star(msg: Msg, on: Boolean) = withContext(Dispatchers.IO) {
        dao.setStarred(msg.key, on)
        runCatching { serviceFor(msg.accountId)?.setFlagged(listOf(msg.providerId), on) }
        Unit
    }

    /**
     * The most-used control in the app, on 41 rows at a time.
     *
     * The local update happens first and unconditionally, so the screen clears even with
     * no signal; the server calls are best-effort per account and a failure leaves the
     * next sync to notice the discrepancy. Doing it the other way round means a subway
     * ride where the button appears not to work.
     */
    suspend fun markAllNoticesRead() = withContext(Dispatchers.IO) {
        val rows = dao.unreadNoticeList()
        dao.markAllNoticesRead()
        rows.groupBy { it.accountId }.forEach { (acct, list) ->
            runCatching { serviceFor(acct)?.markRead(list.map { it.providerId }) }
        }
    }

    /**
     * Archive a specific set of messages — whatever was on the screen.
     *
     * Takes the rows rather than running its own query, because "all" on the Letters
     * screen means the letters you can see, not every letter in the mailbox. Tomorrow's
     * waiting mail is not on screen and must not be swept up by a button aimed at today.
     *
     * Starred rows are skipped, the same as the notices version: a star is the user
     * saying "not this one", and a bulk action that ignores it is one nobody can press
     * safely.
     */
    suspend fun archiveMany(rows: List<Msg>): Int = withContext(Dispatchers.IO) {
        val keep = rows.filterNot { it.starred }
        if (keep.isEmpty()) return@withContext 0
        dao.archiveAll(keep.map { it.key })
        keep.groupBy { it.accountId }.forEach { (acct, list) ->
            runCatching { serviceFor(acct)?.archive(list.map { it.providerId }) }
        }
        keep.size
    }

    /**
     * Clear the whole Notices pile.
     *
     * Archive, never delete — on IMAP this is a MOVE to All Mail, so a receipt cleared by
     * accident is still in the mailbox and still findable from any other client. That is
     * what makes a one-tap bulk action on somebody's mail defensible at all.
     *
     * The local rows are marked first so the list empties immediately; the server move
     * follows per account. A failed move leaves the message in the inbox on the server and
     * archived here, which the next sync does not undo (reconciliation only archives, it
     * never un-archives) — the cost of that is one notice that has to be cleared again on
     * the web, and the alternative is a button that appears to do nothing for ten seconds.
     */
    suspend fun archiveAllNotices(): Int = withContext(Dispatchers.IO) {
        val rows = dao.noticeList()
        if (rows.isEmpty()) return@withContext 0
        dao.archiveAllNotices()
        rows.groupBy { it.accountId }.forEach { (acct, list) ->
            runCatching { serviceFor(acct)?.archive(list.map { it.providerId }) }
        }
        rows.size
    }

    /**
     * Move a sender between piles, and teach the model.
     *
     * Returns the sentence to show. A correction has to say what it learned or it feels
     * like nothing happened — and the whole point is that it is permanent.
     */
    suspend fun move(msg: Msg, to: Pile): String = withContext(Dispatchers.IO) {
        dao.putRule(SenderRule(msg.sender, to.name, System.currentTimeMillis()))
        val reason = if (to == Pile.LETTER) "you moved this sender to Letters"
        else "you moved this sender to Notices"
        dao.repile(msg.sender, to.name, reason)
        // Heavily weighted: the user has looked at this and said we were wrong.
        learn(msg, worthReading = to == Pile.LETTER, weight = 8.0, repeat = 6)
        saveModel()
        val who = msg.senderName.ifBlank { msg.sender }
        if (to == Pile.LETTER) "$who will go to Letters from now on."
        else "$who will go to Notices from now on."
    }

    suspend fun dropRule(address: String) = dao.dropRule(address)

    /* ------------------------------------------------------------------- sending */

    suspend fun send(accountId: String, msg: Outgoing) = withContext(Dispatchers.IO) {
        /*
         * The signature is added here, not in the compose screen.
         *
         * Putting it in the draft would mean the writer has to type around it, can delete
         * it by accident, and sees it twice on a reply they edit. Appending at the point
         * of sending makes it a property of the message leaving rather than of the text
         * being written.
         */
        val sig = signature
        val outgoing = if (sig.isBlank()) msg else msg.copy(
            body = msg.body.trimEnd() + "\n\n-- \n" + sig,
        )
        serviceFor(accountId)?.send(outgoing) ?: error("no such account")
        // Writing to someone is the strongest evidence they are a person.
        msg.to.forEach { addr ->
            val existing = dao.correspondents().firstOrNull { it.address == addr }
            dao.putCorrespondents(
                listOf(Correspondent(addr, (existing?.replies ?: 0) + 1, System.currentTimeMillis())),
            )
        }
    }

    /* -------------------------------------------------------------------- model */

    private fun learn(msg: Msg, worthReading: Boolean, weight: Double, repeat: Int = 1) {
        val e = Envelope(
            from = msg.sender,
            fromName = msg.senderName,
            subject = msg.subject,
            mine = myAddresses(),
            body = msg.snippet,
        )
        kotlin.repeat(repeat) { learner.learn(e, worthReading, weight) }
    }

    /**
     * Retrain from the stored mailbox.
     *
     * Weak labels: the Tier 0 verdict, which is right most of the time and is all there
     * is on a fresh install. User corrections are already stored as rules and are
     * replayed at high weight afterwards, so they dominate anything the bootstrap got
     * wrong.
     */
    suspend fun retrain() = withContext(Dispatchers.Default) {
        val rows = dao.recent(1200)
        if (rows.size < 20) return@withContext
        val mine = myAddresses()
        val data = rows.map { m ->
            Envelope(
                from = m.sender,
                fromName = m.senderName,
                subject = m.subject,
                mine = mine,
                body = m.snippet,
            ) to (m.pile == Pile.LETTER.name)
        }
        learner.fit(data, epochs = 4)

        val rules = dao.allRules()
        for (r in rules) {
            val e = Envelope(from = r.address, mine = mine)
            repeat(8) { learner.learn(e, r.pile == Pile.LETTER.name, weight = 8.0) }
        }
        saveModel()
    }

    fun saveModel() = runCatching { modelFile.writeText(learner.save()) }

    /* ------------------------------------------------------------------ plumbing */

    /**
     * Today as yyyymmdd, in the phone's own calendar.
     *
     * Public because the ViewModel has to ask again — it is what decides when a read
     * letter leaves the list, and the answer changes while the app is running.
     */
    fun today(): Int {
        val c = Calendar.getInstance()
        return c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100 + c.get(Calendar.DAY_OF_MONTH)
    }

    companion object {
        @Volatile private var instance: Repo? = null

        fun get(context: Context): Repo =
            instance ?: synchronized(this) {
                instance ?: Repo(context.applicationContext).also { instance = it }
            }
    }
}
