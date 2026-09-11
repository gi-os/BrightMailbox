package com.gios.brightmailbox.data

import android.content.Context
import androidx.room.Room
import com.gios.brightmailbox.auth.AuthManager
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
 * Everything above the network and below the UI.
 */
class Repo private constructor(private val app: Context) {

    val auth = AuthManager(app)

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val db = Room.databaseBuilder(app, MailDb::class.java, "mailbox.db")
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
    var showImages: Boolean
        get() = prefs.getBoolean("images", true)
        set(v) = prefs.edit().putBoolean("images", v).apply()

    var lastSync: Long
        get() = prefs.getLong("last_sync", 0L)
        private set(v) = prefs.edit().putLong("last_sync", v).apply()

    /** Letters read past the ration today, unlocked one at a time by a wheel hold. */
    var extraToday: Int
        get() = if (prefs.getInt("extra_day", 0) == today()) prefs.getInt("extra_n", 0) else 0
        set(v) = prefs.edit().putInt("extra_day", today()).putInt("extra_n", v).apply()

    /* --------------------------------------------------------------------- feeds */

    fun letters(): Flow<List<Msg>> = dao.letters()
    fun notices(): Flow<List<Msg>> = dao.notices()
    fun unreadNotices(): Flow<Int> = dao.unreadNotices()
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

    data class SyncResult(val fetched: Int, val newLetters: Int, val firstLetter: Msg?)

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

        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            val newest = dao.newestFor(account.id) ?: 0L
            val (messages, _) = runCatching { svc.list(limit, null) }.getOrNull() ?: continue

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
        }
        lastSync = System.currentTimeMillis()
        SyncResult(fetched, newLetters, first)
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
        serviceFor(accountId)?.send(msg) ?: error("no such account")
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

    private fun today(): Int {
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
