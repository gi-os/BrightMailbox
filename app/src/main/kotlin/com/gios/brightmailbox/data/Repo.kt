package com.gios.brightmailbox.data

import android.content.Context
import androidx.room.Room
import com.gios.brightmailbox.auth.AuthManager
import com.gios.brightmailbox.mail.Addr
import com.gios.brightmailbox.mail.Attachment
import com.gios.brightmailbox.mail.Box
import com.gios.brightmailbox.mail.Content
import com.gios.brightmailbox.mail.Imap
import com.gios.brightmailbox.mail.MailService
import com.gios.brightmailbox.mail.Message
import com.gios.brightmailbox.mail.Outgoing
import com.gios.brightmailbox.mail.Unsub
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.gios.brightmailbox.notify.Chime
import com.gios.brightmailbox.sort.Envelope
import com.gios.brightmailbox.sort.Learner
import com.gios.brightmailbox.sort.Pile
import com.gios.brightmailbox.sort.Sorter
import com.gios.brightmailbox.text.Clean
import com.gios.brightmailbox.text.Ics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
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
/**
 * What a sideways swipe on a row does.
 *
 * Two of these — one per direction — and both are settings because the right answer
 * depends on what somebody's mail is like. A person whose Notices pile is all newsletters
 * wants a fast delete; a person using the ration properly wants archive and hold, and
 * nothing that removes mail without asking.
 *
 * [word] is what shows behind the row as it moves. Empty for [NOTHING], which is not a
 * disabled gesture but the absence of one: the row simply does not move.
 */
enum class Swipe(val key: String, val label: String, val word: String) {
    NOTHING("none", "Nothing", ""),
    ARCHIVE("archive", "Archive", "ARCHIVE"),
    HOLD("hold", "Hold", "HOLD"),
    READ("read", "Mark read", "READ"),
    /*
     * The two that take mail away have no confirmation on a swipe, and cannot have one —
     * a gesture that opens a dialog is slower than the button it was meant to beat. They
     * are off by default and the setting says what they do, which is the honest trade:
     * anybody who turns one on has read the sentence under it.
     */
    DELETE("delete", "Delete", "DELETE"),
    JUNK("junk", "Junk", "JUNK");

    companion object {
        fun of(k: String?, fallback: Swipe) = entries.firstOrNull { it.key == k } ?: fallback
    }
}

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
        .addMigrations(
            MailDb.MIGRATION_1_2,
            MailDb.MIGRATION_2_3,
            MailDb.MIGRATION_3_4,
            MailDb.MIGRATION_4_5,
            MailDb.MIGRATION_5_6,
            MailDb.MIGRATION_6_7,
        )
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
     * A line of the message's own text under each Letter row. **Off by default.**
     *
     * Off because of what this app is. A ration exists so the day's mail is a short list
     * you finish, and a preview is the start of reading a message from the list — the
     * habit every other mail client is built around and the one this one was made to
     * break. Sender and subject are enough to decide; a third of the first paragraph is
     * enough to start skimming.
     *
     * On for anyone who wants it, and it costs nothing either way: the text is written
     * into the row when the body is prefetched regardless of this setting, so turning it
     * on fills the list immediately rather than waiting for a sync.
     */
    /** Leftward, the direction that has always archived. */
    var swipeLeft: Swipe
        get() = Swipe.of(prefs.getString("swipeLeft", null), Swipe.ARCHIVE)
        set(v) = prefs.edit().putString("swipeLeft", v.key).apply()

    /**
     * Rightward, which did nothing at all before v2.39.
     *
     * Defaults to holding rather than to nothing: it is the one action with no
     * consequence, so discovering the gesture by accident teaches you it exists instead of
     * costing you a message.
     */
    var swipeRight: Swipe
        get() = Swipe.of(prefs.getString("swipeRight", null), Swipe.HOLD)
        set(v) = prefs.edit().putString("swipeRight", v.key).apply()

    var previews: Boolean
        get() = prefs.getBoolean("previews", false)
        set(v) = prefs.edit().putBoolean("previews", v).apply()

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
    fun archived(page: Int): Flow<List<Msg>> = dao.archived(ARCHIVE_PAGE, page * ARCHIVE_PAGE)
    fun archivedTotal(): Flow<Int> = dao.archivedTotal()

    /**
     * The rest of [msg]'s conversation, oldest first, itself excluded.
     *
     * A message with no thread root — one that started nothing and answered nothing — has
     * `threadId` equal to its own id, so this correctly returns nothing for the great
     * majority of mail rather than needing a special case.
     */
    suspend fun thread(msg: Msg): List<Msg> = withContext(Dispatchers.IO) {
        if (msg.threadId.isBlank()) return@withContext emptyList()
        dao.thread(msg.threadId, msg.accountId, msg.key)
    }

    suspend fun search(query: String): List<Msg> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext emptyList()
        // Escape the wildcards, or a stray % matches the whole mailbox.
        dao.search("%" + q.replace("%", "\\%").replace("_", "\\_") + "%")
    }

    /** Everything in the inbox, both piles, minus what is held. For ARCHIVE ALL. */
    suspend fun archiveInbox(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) { archiveMany(dao.inboxList(), onProgress) }

    /**
     * Files this app has saved to the phone, newest first.
     *
     * Kept as our own short list rather than read back out of MediaStore: the Downloads
     * collection holds everything every app has ever saved, and a mail client has no
     * business listing the rest of it. Stored as `uri\tname\tmime` lines in preferences,
     * because it is three fields and a dependency for that would be silly.
     */
    fun downloads(): List<Triple<String, String, String>> =
        prefs.getString("downloads", "").orEmpty()
            .lineSequence()
            .mapNotNull { line ->
                val p = line.split('\t')
                if (p.size < 3) null else Triple(p[0], p[1], p[2])
            }
            .toList()

    private fun rememberDownload(uri: String, name: String, mime: String) {
        // Newest first, de-duplicated by uri, and capped — this is a convenience list,
        // not a record of everything that ever happened.
        val kept = (listOf(Triple(uri, name, mime)) + downloads())
            .distinctBy { it.first }
            .take(60)
        prefs.edit()
            .putString("downloads", kept.joinToString("\n") { "${it.first}\t${it.second}\t${it.third}" })
            .apply()
    }
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
        // Where this mailbox lives is a property of the ACCOUNT now, not of the provider:
        // Service.IMAP has no hosts of its own. See AuthManager.servers.
        return Imap(id, auth, acct.service, auth.servers(id))
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

        /*
         * Anything waiting to go, before anything coming in.
         *
         * The sync is the only thing in this app that runs on its own schedule and knows
         * the network is up, which makes it the right place — and going out first means a
         * message queued in a tunnel leaves the moment the phone can see a server again,
         * rather than one fetch later.
         */
        runCatching { flushOutbox() }
        // Drafts written elsewhere, in the same pass. Cheap: header batch plus a body for
        // each one that is genuinely new, which is nearly always none.
        runCatching { importDrafts() }

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
             * The other direction — see [reconcile] for what it agrees to.
             *
             * Failures here are swallowed on purpose. Reconciliation is a nicety and the
             * mail that just arrived is not; a server that will not answer this must not
             * cost the fetch that already worked.
             */
            runCatching { reconcile(svc, account.id) }
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
                /*
                 * Only rows that are NEW.
                 *
                 * `put` is REPLACE, so writing every fetched message back would reset
                 * `readHere`, `starred`, `readDay` and `archived` on everything already
                 * held — asking for more history would silently mark the whole mailbox
                 * unread and un-archive it. A deeper sync is meant to ADD the past, not
                 * rewrite the present.
                 */
                val rows = messages
                    .filter { dao.get("${'$'}{it.accountId}/${'$'}{it.id}") == null }
                    .map { classify(it, sorter, mine) }
                if (rows.isNotEmpty()) dao.put(rows)
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

    /**
     * Walk the whole inbox again, thoroughly, and reconcile everything held.
     *
     * The ordinary sync reads the newest thirty per account — enough to notice new mail
     * and nothing more. This pages back through INBOX as far as the depth setting allows,
     * adds anything missing, and then asks the server about every non-archived row the app
     * holds, so reads and archives done elsewhere land here in one pass.
     *
     * **It never touches the archive.** Reconciliation already asks only about
     * non-archived rows, and nothing here re-adds an archived one: a message put away is a
     * decision, and a refresh that undid it would make the archive untrustworthy. The
     * ARCHIVE folder is not even opened.
     *
     * @return how many messages were added.
     */
    suspend fun deepSync(onProgress: (Int) -> Unit = {}): Int = withContext(Dispatchers.IO) {
        val mine = myAddresses()
        val overrides = dao.allRules().associate { it.address to Pile.valueOf(it.pile) }
        val replies = dao.correspondents().associate { it.address to it.replies }
        val sorter = Sorter(learner, overrides, replies)
        var added = 0

        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            var token: String? = null
            var seen = 0
            while (seen < depth.perAccount) {
                val (messages, next) = runCatching { svc.list(50, token) }.getOrNull() ?: break
                if (messages.isEmpty()) break
                seen += messages.size
                val rows = messages
                    .filter { dao.get("${'$'}{account.id}/${'$'}{it.id}") == null }
                    .map { classify(it, sorter, mine) }
                if (rows.isNotEmpty()) {
                    dao.put(rows)
                    added += rows.size
                }
                onProgress(seen)
                token = next ?: break
            }

            // The other direction, over everything held rather than the newest page.
            runCatching { reconcile(svc, account.id) }
        }
        lastSync = System.currentTimeMillis()
        added
    }

    /**
     * Ask the server to find mail the app never downloaded, and keep what it finds.
     *
     * The rows are stored, so a result is a real message that opens, threads and can be
     * replied to — not a preview that would need fetching again. They land in whichever
     * pile the sorter puts them in, which is the same treatment a message gets when it
     * arrives normally.
     *
     * @return how many were new.
     */
    suspend fun searchServer(query: String, limit: Int = 40): Int = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.length < 2) return@withContext 0
        val mine = myAddresses()
        val overrides = dao.allRules().associate { it.address to Pile.valueOf(it.pile) }
        val replies = dao.correspondents().associate { it.address to it.replies }
        val sorter = Sorter(learner, overrides, replies)
        var added = 0

        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            /*
             * The inbox and the archive, as two searches.
             *
             * IMAP SEARCH is per-folder, and the archive is where most of a mailbox's
             * history actually lives — on Gmail it is All Mail, which holds everything the
             * account has ever received. Searching only INBOX meant "further back" reached
             * further back through the one folder people empty.
             *
             * An archive hit is stored `archived = true`, which keeps it out of the two
             * piles and out of the reconciliation that asks INBOX about every live row.
             * Its id carries the ARCH: tag, so opening it fetches from the right folder.
             */
            for (box in listOf(Box.INBOX, Box.ARCHIVE)) {
                val found = runCatching { svc.search(q, limit, box) }.getOrDefault(emptyList())
                val rows = found
                    .filter { dao.get("${'$'}{account.id}/${'$'}{it.id}") == null }
                    .map { classify(it, sorter, mine) }
                    .map { if (box == Box.ARCHIVE) it.copy(archived = true) else it }
                if (rows.isNotEmpty()) {
                    dao.put(rows)
                    added += rows.size
                }
            }
        }
        added
    }

    /* ---------------------------------------------------------------- unsubscribe */

    /** What happened, and what the screen has to do about it. */
    sealed interface Left {
        /** Done, with nothing more to do. */
        data object Done : Left
        /** The only way out is a web page. The screen hands this to a browser. */
        data class Page(val url: String) : Left
        /*
         * Named None, not Nothing.
         *
         * `Nothing` is a real Kotlin type — the bottom type — and declaring an object with
         * that name inside this class shadows it for every line in the file. The compiler
         * allows it and the next person to write a `Nothing` return type here would spend
         * an afternoon on the error message.
         */
        data object None : Left
        data class Failed(val why: String) : Left
    }

    /**
     * Stop a sender sending.
     *
     * Three routes, in the order of how little they ask of the person:
     *
     * 1. **RFC 8058 one-click** — a POST with a fixed body, when the sender published
     *    `List-Unsubscribe-Post`. That header is a promise that this single request is the
     *    whole transaction, and it is the only route that finishes without leaving the app.
     * 2. **mailto** — an email, sent from the account the message arrived at, because a
     *    list keyed the address it mails and a reply from a different mailbox matches
     *    nothing.
     * 3. **A web page**, handed back for a browser. Last because it is a page load, a
     *    cookie banner and often a sign-in on a phone with a 3.9" screen.
     *
     * A GET is never issued. Mail clients that prefetched unsubscribe URLs are the reason
     * RFC 8058 had to exist: a link that unsubscribes on GET will be followed by a scanner
     * eventually, and the person never pressed anything.
     */
    suspend fun unsubscribe(msg: Msg): Left = withContext(Dispatchers.IO) {
        val ways = Unsub.parse(msg.unsubscribe)
        if (!ways.any) return@withContext Left.None

        if (msg.oneClick && ways.http != null) {
            val ok = runCatching {
                val body = Unsub.ONE_CLICK_BODY.toRequestBody(
                    "application/x-www-form-urlencoded".toMediaType(),
                )
                http.newCall(Request.Builder().url(ways.http).post(body).build())
                    .execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) return@withContext Left.Done
            // Fall through rather than fail: the other routes are still there.
        }

        ways.mailto?.let { to ->
            val sent = runCatching {
                serviceFor(msg.accountId)?.send(
                    Outgoing(
                        to = listOf(to),
                        subject = ways.subject ?: "unsubscribe",
                        // Some list managers read the body, most read the address they
                        // were written to. One word satisfies both and says nothing else.
                        body = "unsubscribe",
                    ),
                ) != null
            }.getOrDefault(false)
            if (sent) return@withContext Left.Done
        }

        ways.http?.let { return@withContext Left.Page(it) }
        Left.Failed("that sender's unsubscribe did not answer")
    }

    /* --------------------------------------------------------------- throwing away */

    /**
     * Delete, and it is the only verb here that takes mail away.
     *
     * A move to Trash, never a `\Deleted` flag and an expunge — an expunged message is
     * gone from the server with nothing to undo it from, and the point of Trash is that a
     * provider keeps it for a month. The local row is dropped rather than marked, because
     * a row marked deleted would be a second kind of archive with no screen to show it on.
     *
     * Server first: a row that stays on the phone after a failed delete is a message you
     * can still read, which is the safe direction. See [unarchive] for the same ordering
     * and the same reason.
     */
    suspend fun delete(msg: Msg): Boolean = withContext(Dispatchers.IO) {
        move(msg, Box.TRASH)
    }

    /**
     * Report as junk.
     *
     * A move into the Junk folder, which is what every provider's filter actually learns
     * from — flagging is a client-side opinion nobody reads. Also writes a local rule, so
     * the sorter agrees with the decision even for mail that arrives before the server has
     * caught up.
     */
    suspend fun junk(msg: Msg): Boolean = withContext(Dispatchers.IO) {
        val ok = move(msg, Box.JUNK)
        if (ok && msg.sender.isNotBlank()) {
            runCatching {
                dao.putRule(SenderRule(msg.sender, Pile.NOTICE.name, System.currentTimeMillis()))
            }
        }
        ok
    }

    private suspend fun move(msg: Msg, box: Box): Boolean {
        val svc = serviceFor(msg.accountId) ?: return false
        /*
         * An archived message carrying an untagged id has no UID worth using.
         *
         * Everything stored before v2.32 is addressed relative to INBOX, so a message
         * archived at any point in this app's history has an id that resolves to nothing
         * — and archiving one in the web client does the same to a fresh row. The
         * Message-ID search is the only handle left on it, which is exactly why
         * `unarchive` has always used one.
         */
        val tagged = Box.of(msg.providerId) != Box.INBOX
        val moved = if (msg.archived && !tagged) {
            val id = msg.messageId?.takeIf { it.isNotBlank() } ?: return false
            runCatching { svc.moveFound(id, Box.ARCHIVE, box) }.getOrDefault(false)
        } else {
            runCatching { svc.moveTo(listOf(msg.providerId), box) }.isSuccess
        }
        if (moved) {
            dao.forget(msg.key)
            runCatching { bodyFile(msg.key).delete(); htmlFile(msg.key).delete() }
        }
        return moved
    }

    /**
     * What this mailbox has sent, newest first, across every account.
     *
     * Fetched fresh and returned rather than stored — see [MailService.sent] for why the
     * database is the wrong place for it. The rows are ordinary [Msg] objects so the same
     * list row and the same reader draw them, but nothing will ever find them in a query:
     * they exist for as long as the screen does.
     *
     * `pile` is "SENT", which no query in this app matches. That is the point.
     */
    suspend fun sent(limit: Int = SENT_PAGE, offset: Int = 0): List<Msg> =
        withContext(Dispatchers.IO) {
        val out = ArrayList<Msg>()
        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            val found = runCatching { svc.sent(limit, offset) }.getOrDefault(emptyList())
            for (m in found) {
                /*
                 * A sent message is shown by who it went TO.
                 *
                 * Every row in this app puts the other person on the left, and on a sent
                 * message the other person is the recipient — a column of your own name
                 * would be a list of nothing. The rest of the recipients are counted into
                 * the line under it rather than listed, which on a 3.9" panel is the
                 * difference between a row and a paragraph.
                 */
                val first = m.to.firstOrNull().orEmpty()
                val others = m.to.size + m.cc.size - 1
                out.add(
                    Msg(
                        key = "${'$'}{m.accountId}/${'$'}{m.id}",
                        accountId = m.accountId,
                        providerId = m.id,
                        threadId = m.threadId,
                        sender = first,
                        senderName = first.substringBefore('@').ifBlank { "(no recipient)" },
                        subject = m.subject.ifBlank { "(no subject)" },
                        snippet = if (others > 0) "and $others more" else "",
                        receivedAt = m.receivedAt,
                        // Nothing you wrote is unread, and nothing here is rationed.
                        unread = false,
                        pile = "SENT",
                        reason = "",
                        rule = "sent",
                        score = 0.0,
                        messageId = m.messageId,
                        references = m.references,
                        hasAttachments = m.hasAttachments,
                        readHere = true,
                        toAddrs = m.to.joinToString(","),
                        ccAddrs = m.cc.joinToString(","),
                    ),
                )
            }
        }
        /*
         * Distinct by key before anything else sees it.
         *
         * A LazyColumn throws when two rows share a key, and it throws at the moment the
         * list draws rather than at the moment the duplicate was made — so a fetch that
         * quietly produced two rows with the same id looked like a crash on opening the
         * screen. Nothing here should produce one; this is the guard that keeps a
         * transport surprise from being a crash.
         */
        out.distinctBy { it.key }.sortedByDescending { it.receivedAt }
    }

    /**
     * Ask the server what became of the messages this app holds, and agree with it.
     *
     * One copy, called from both [sync] and [deepSync] — it was written twice, and the
     * second copy was already a line behind the first. Everything here is the **inbound**
     * half of a state the app also pushes outward, so each direction has to be able to
     * lose gracefully.
     *
     * Three answers, from one FLAGS fetch over exactly the rows held (so the cost scales
     * with the phone, not with the size of the mailbox):
     *
     * - **Absent from the map** — the message has left INBOX: archived, filed or deleted
     *   somewhere else. Archiving locally is the safe echo of all three, because it
     *   removes the row from view and touches nothing on the server.
     * - **`\Seen`** — read at a desk, so grey here. `m.unread` alone is the wrong test:
     *   it only says what the server said last time, while `readHere` is what decides how
     *   the row draws, so a message marked seen by an earlier sync would never pick up
     *   the grey.
     * - **`\Flagged`** — starred at a desk, so held here, and **unstarred there means
     *   unstarred here**. That last direction is the one worth being careful about: a
     *   star overrides the ration, survives the day rollover and is skipped by ARCHIVE
     *   ALL, so removing one changes what the phone shows. It is applied only to rows the
     *   server actually answered about — a missing row means "gone", which the first case
     *   already handles, and never means "no longer starred".
     */
    private suspend fun reconcile(svc: MailService, accountId: String) {
        val live = dao.liveFor(accountId)
        if (live.isEmpty()) return
        val states = svc.states(live.map { it.providerId })

        val gone = live.filter { it.providerId !in states }.map { it.key }
        if (gone.isNotEmpty()) dao.archiveAll(gone)

        val readElsewhere = live.filter { m ->
            (m.unread || !m.readHere) && states[m.providerId]?.unread == false
        }.map { it.key }
        if (readElsewhere.isNotEmpty()) dao.markSeen(readElsewhere, today())

        val held = live.filter { !it.starred && states[it.providerId]?.flagged == true }
            .map { it.key }
        if (held.isNotEmpty()) dao.setStarredAll(held, true)

        val released = live.filter { it.starred && states[it.providerId]?.flagged == false }
            .map { it.key }
        if (released.isNotEmpty()) dao.setStarredAll(released, false)
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
            toAddrs = m.to.joinToString(","),
            ccAddrs = m.cc.joinToString(","),
            unsubscribe = m.headers["list-unsubscribe"].orEmpty(),
            // Presence is the promise; the value is always the same token.
            oneClick = m.headers.containsKey("list-unsubscribe-post"),
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
    /**
     * @return the text, or **null when it could not be fetched** — which is not the same
     *   thing as a message with nothing in it, and used to be conflated with it.
     *
     * This returned `Clean.Body(msg.snippet, 0)` on every failure, and **IMAP carries no
     * snippet**, so a failure produced a perfectly valid Body containing an empty string.
     * The reader's `body?.text ?: "getting the text…"` then saw a non-null Body and printed
     * the empty string: a black screen, for ever, with nothing on it and no way to tell
     * whether the message was empty or the fetch had failed.
     *
     * Worse, the empty result was **written to the cache**, so one dropped connection
     * blanked that message permanently — every later open read the empty file back and
     * showed the same black screen without going near the network.
     */
    suspend fun body(msg: Msg): Clean.Body? = withContext(Dispatchers.IO) {
        val f = bodyFile(msg.key)
        if (f.exists()) return@withContext Clean.body(f.readText())

        val svc = serviceFor(msg.accountId) ?: return@withContext null
        val c: Content = runCatching { svc.content(msg.providerId) }
            .getOrElse { return@withContext null }
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
            ?: c.html?.takeIf { it.isNotBlank() }?.let { Clean.fromHtml(it) }
            ?: ""
        /*
         * Cache a real answer, never an empty one.
         *
         * A message that genuinely has no text is rare; a fetch that came back with
         * nothing because something went wrong mid-transfer is not. Writing the empty
         * string here is what made a transient failure permanent, so an empty result is
         * simply not written and the next open tries again.
         */
        if (raw.isNotBlank()) runCatching { f.writeText(raw) }
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
                val have = bodyFile(m.key).exists()
                if (have && m.snippet.isNotBlank()) continue
                /*
                 * The preview line, taken from the body the prefetch was fetching anyway.
                 *
                 * **IMAP carries no snippet** and asking for one per message costs a round
                 * trip each, which is the whole reason this transport is fast — so for
                 * seven versions a row was sender and subject and nothing else. But the
                 * prefetch already downloads the bodies of everything on screen, for the
                 * unrelated reason that a tapped message should open instantly. The text
                 * is sitting in a file by the time the row is drawn; it just was not being
                 * read back.
                 *
                 * Nothing extra goes over the network. A message whose body has already
                 * been fetched and already has a preview is skipped before the disk read.
                 */
                val text = if (have) {
                    runCatching { Clean.body(bodyFile(m.key).readText()).text }.getOrNull()
                } else {
                    runCatching { body(m)?.text }.getOrNull()
                }
                if (m.snippet.isBlank()) {
                    preview(text).takeIf { it.isNotBlank() }?.let { dao.setSnippet(m.key, it) }
                }
            }
        }

    /**
     * The first line of a message, as one line.
     *
     * Newlines out, runs of space collapsed, and cut at a word rather than mid-syllable —
     * a row is one line at `superfine` and about 90 characters is what fits on a 3.9"
     * panel before the ellipsis does the rest. Leading blank lines are extremely common in
     * mail that came from HTML, which is why the trim happens first.
     */
    private fun preview(raw: String?): String {
        val flat = raw.orEmpty().replace(Regex("\\s+"), " ").trim()
        if (flat.length <= 90) return flat
        val cut = flat.take(90)
        return cut.substringBeforeLast(' ', cut).trimEnd(',', '.', ';', ':', '-') + "…"
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
        rememberDownload(uri.toString(), name, att.mime)
        name
    }

    /** A filename a filesystem will accept, keeping the extension so the mime survives. */
    private fun safeName(name: String): String =
        name.replace(Regex("""[^A-Za-z0-9._-]"""), "_").takeLast(80).ifBlank { "file" }

    /* -------------------------------------------------------------------- verbs */

    /** Read, without the learning. The swipe is not a vote that it was worth reading. */
    suspend fun markRead(msg: Msg) = withContext(Dispatchers.IO) {
        dao.markRead(msg.key, today())
        runCatching { serviceFor(msg.accountId)?.markRead(listOf(msg.providerId)) }
    }

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
     * Put an archived message back in the inbox. See [MailboxViewModel.unarchive].
     *
     * Server first, and only on success is the local row dropped — the opposite order to
     * every other verb here. Archiving optimistically is safe because a failed move leaves
     * the message in the inbox, where it already was; un-archiving optimistically is not,
     * because a failed move would leave a row claiming to be in an inbox that has never
     * heard of it.
     *
     * Needs the Message-ID. A message that predates v2.0, or one whose sender omitted the
     * header, cannot be found again and is refused rather than half-moved.
     */
    suspend fun unarchive(msg: Msg): Boolean = withContext(Dispatchers.IO) {
        val id = msg.messageId?.takeIf { it.isNotBlank() } ?: return@withContext false
        val svc = serviceFor(msg.accountId) ?: return@withContext false
        val moved = runCatching { svc.unarchive(id) }.getOrDefault(false)
        if (moved) dao.forget(msg.key)
        moved
    }

    /**
     * The invitation in this message, if it holds one we can answer.
     *
     * Fetched rather than cached: an invite is rare, the calendar part is small, and
     * caching it would mean another file per message keyed the same way as the body — a
     * fourth copy of the same fetch for something most mail does not have.
     */
    suspend fun invite(msg: Msg): Ics.Invite? = withContext(Dispatchers.IO) {
        val svc = serviceFor(msg.accountId) ?: return@withContext null
        val c = runCatching { svc.content(msg.providerId) }.getOrNull() ?: return@withContext null
        val ics = c.calendar?.takeIf { it.isNotBlank() } ?: return@withContext null
        Ics.parse(ics)?.takeIf { it.isRequest && it.organizer.isNotBlank() }
    }

    /**
     * Answer an invitation: an ordinary email carrying a `text/calendar; method=REPLY`.
     *
     * Sent from the account the invitation arrived at, which is not always the first one —
     * replying to a work invitation from a personal address tells the organizer's calendar
     * about an attendee it has never heard of, and it files the answer against nobody.
     */
    suspend fun rsvp(msg: Msg, invite: Ics.Invite, answer: Ics.Answer): Boolean =
        withContext(Dispatchers.IO) {
            val account = auth.accounts().firstOrNull { it.id == msg.accountId }
                ?: return@withContext false
            val stamp = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .format(java.util.Date())
            val body = Ics.reply(invite, account.email, account.name, answer, stamp)
            runCatching {
                serviceFor(msg.accountId)?.send(
                    Outgoing(
                        to = listOf(invite.organizer),
                        subject = Ics.subject(invite, answer),
                        // The sentence a person reads. The calendar reads the part below it.
                        body = "${answer.word}.",
                        inReplyTo = msg.messageId,
                        references = msg.references,
                        calendarReply = body,
                    ),
                ) ?: return@withContext false
            }.isSuccess
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
    suspend fun archiveMany(rows: List<Msg>, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val keep = rows.filterNot { it.starred }
            if (keep.isEmpty()) return@withContext 0
            // Local first and all at once: the screen must empty immediately, whatever the
            // network does next.
            dao.archiveAll(keep.map { it.key })

            /*
             * The server move, in chunks of twenty-five.
             *
             * Not one command per message — that would be hundreds of round trips — and
             * not one command for everything either, which gives no progress to report and
             * hands the server a MOVE with two thousand UIDs in it. Twenty-five is a
             * reasonable command and a reasonable tick of a progress bar.
             */
            var done = 0
            keep.groupBy { it.accountId }.forEach { (acct, list) ->
                val svc = serviceFor(acct)
                list.chunked(25).forEach { chunk ->
                    runCatching { svc?.archive(chunk.map { it.providerId }) }
                    done += chunk.size
                    onProgress(done, keep.size)
                }
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
    suspend fun archiveAllNotices(onProgress: (Int, Int) -> Unit = { _, _ -> }): Int =
        withContext(Dispatchers.IO) {
            val rows = dao.noticeList()
            if (rows.isEmpty()) return@withContext 0
            dao.archiveAllNotices()
            var done = 0
            rows.groupBy { it.accountId }.forEach { (acct, list) ->
                val svc = serviceFor(acct)
                list.chunked(25).forEach { chunk ->
                    runCatching { svc?.archive(chunk.map { it.providerId }) }
                    done += chunk.size
                    onProgress(done, rows.size)
                }
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

    /* -------------------------------------------------------------------- drafts */

    fun drafts(): Flow<List<Draft>> = dao.drafts()

    /**
     * Keep what is being written, or drop it when it is empty.
     *
     * The table and the DAO have existed since v1 and nothing ever called them, so closing
     * the compose screen threw the message away — on a phone, where writing anything is
     * slow and an interruption is a phone call. The one place a draft absolutely must
     * survive is the moment somebody leaves the screen, so that is exactly where this is
     * called from.
     *
     * An empty draft is deleted rather than stored. A list of blank drafts you opened and
     * closed is worse than no list.
     *
     * @return the row id, so the screen can keep updating the same draft rather than
     *   writing a new one each time.
     */
    suspend fun keepDraft(d: Draft): Long = withContext(Dispatchers.IO) {
        val empty = d.to.isBlank() && d.subject.isBlank() && d.body.isBlank()
        if (empty) {
            if (d.id != 0L) dao.dropDraft(d.id)
            return@withContext 0L
        }
        dao.putDraft(d.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun dropDraft(id: Long) = withContext(Dispatchers.IO) {
        if (id != 0L) dao.dropDraft(id)
    }

    /* ------------------------------------------------------------------- outbox */

    /* ------------------------------------------------------------------- storage */

    /**
     * How full the first mailbox is, or null when the server does not say.
     *
     * One account's worth. Showing four progress lines for four mailboxes on a 3.9" panel
     * is a screen about storage rather than a settings screen with a fact on it.
     */
    suspend fun quota(): com.gios.brightmailbox.mail.Quota? = withContext(Dispatchers.IO) {
        val account = auth.accounts().firstOrNull() ?: return@withContext null
        runCatching { serviceFor(account.id)?.quota() }.getOrNull()
    }

    /* ------------------------------------------------------- drafts from the server */

    /**
     * Pull drafts written elsewhere into the drafts list.
     *
     * **One way only.** Uploading this app's drafts would mean an APPEND per keystroke
     * pause, a second copy of every half-written message, and two places that both believe
     * they own the text — with no way to tell an edit from a conflict. Reading is the half
     * that carries the value: a message begun at a desk can be finished on the phone.
     *
     * Matched on `remoteId`, so a folder read on every sync imports each draft once rather
     * than ninety-six times a day. An imported draft that has since disappeared from the
     * server is dropped, which is what "I sent it from my laptop" looks like from here.
     *
     * @return how many arrived that were not already here.
     */
    suspend fun importDrafts(limit: Int = 25): Int = withContext(Dispatchers.IO) {
        var added = 0
        val known = dao.importedDrafts().associateBy { it.remoteId }
        val seen = HashSet<String>()
        for (account in auth.accounts()) {
            val svc = serviceFor(account.id) ?: continue
            val found = runCatching { svc.serverDrafts(limit) }.getOrDefault(emptyList())
            for (m in found) {
                val key = "${'$'}{account.id}/${'$'}{m.id}"
                seen.add(key)
                if (known.containsKey(key)) continue
                /*
                 * The body is fetched one at a time, which is why the limit is small.
                 * Headers come in one batch; bodies cannot, and a drafts folder with two
                 * hundred abandoned messages in it is somebody's normal.
                 */
                val body = runCatching { svc.content(m.id) }.getOrNull()
                val text = body?.text?.takeIf { it.isNotBlank() }
                    ?: body?.html?.takeIf { it.isNotBlank() }?.let { Clean.fromHtml(it) }
                    ?: ""
                dao.putDraft(
                    Draft(
                        accountId = account.id,
                        to = m.to.joinToString(", "),
                        cc = m.cc.joinToString(", "),
                        subject = m.subject,
                        body = text,
                        inReplyTo = null,
                        references = m.references,
                        threadId = m.threadId,
                        updatedAt = m.receivedAt,
                        remoteId = key,
                        remoteAccount = account.id,
                    ),
                )
                added++
            }
        }
        // Gone from the server means sent or thrown away there; either way it is finished.
        for (d in known.values) if (d.remoteId !in seen) dao.dropDraft(d.id)
        added
    }

    /* --------------------------------------------------------------------- watch */

    /**
     * Hold IMAP IDLE open and call [onMail] when the server says something arrived.
     *
     * Suspends until cancelled, which is what makes it safe to tie to a screen being on:
     * the caller cancels and the connection goes with it.
     *
     * **Reconnects with a backoff, and that is the whole safety story.** A watch that
     * retries immediately turns a flapping connection into a loop that opens a TLS session
     * several times a second — far more expensive than the fifteen-minute poll it is
     * meant to improve on. Five seconds, doubling to five minutes, reset on a connection
     * that lasted.
     *
     * The poll underneath is untouched. This is a way to hear sooner, never the only way
     * to hear at all.
     */
    suspend fun watch(onMail: suspend () -> Unit) = withContext(Dispatchers.IO) {
        val account = auth.accounts().firstOrNull() ?: return@withContext
        var backoff = 5_000L
        while (isActive) {
            val opened = System.currentTimeMillis()
            runCatching { serviceFor(account.id)?.watch(onMail) }
            // A connection that stayed up for a while and then dropped is not a failing
            // server, it is an ordinary IDLE timeout — so it does not earn a penalty.
            if (System.currentTimeMillis() - opened > 60_000) backoff = 5_000L
            if (!isActive) break
            kotlinx.coroutines.delay(backoff)
            backoff = (backoff * 2).coerceAtMost(300_000L)
        }
    }

    fun flagged(): Flow<List<Msg>> = dao.flagged()

    fun flaggedCount(): Flow<Int> = dao.flaggedCount()

    fun queuedCount(): Flow<Int> = dao.queuedCount()

    /** Everyone this mailbox has written to or heard from, most recent first. */
    suspend fun addressBook(): List<String> = withContext(Dispatchers.IO) {
        dao.recentCorrespondents(400).map { it.address }
    }

    /**
     * Keep a message that would not go, and mean it.
     *
     * "Not sent. Your draft is still here." was true but not enough: on a phone that walks
     * into a subway, pressing send and then having to remember to press it again later is
     * the app asking the person to be its retry loop. A queued draft goes out on the next
     * sync, which is on open and every fifteen minutes.
     *
     * @return the row id it was kept under.
     */
    suspend fun queue(d: Draft): Long = withContext(Dispatchers.IO) {
        dao.putDraft(d.copy(queued = true, updatedAt = System.currentTimeMillis()))
    }

    /**
     * Try the outbox.
     *
     * **Five attempts, then it stops and stays a draft.** A message refused because the
     * address does not exist will be refused for ever, and a queue that retries for ever
     * is a queue that sends the same failure notification twice an hour until somebody
     * uninstalls the app. Five is enough to cross a tunnel and not enough to nag.
     *
     * Called from the sync, so a phone that reconnects sends without being told to.
     *
     * @return how many went.
     */
    suspend fun flushOutbox(): Int = withContext(Dispatchers.IO) {
        var sent = 0
        for (d in dao.queuedDrafts()) {
            val out = Outgoing(
                to = Addr.addresses(d.to),
                cc = Addr.addresses(d.cc),
                subject = d.subject,
                body = d.body,
                inReplyTo = d.inReplyTo,
                references = d.references,
                threadId = d.threadId,
            )
            /*
             * Attachments do not survive the queue, and that is on purpose.
             *
             * An Outfile holds the bytes, and storing a queued message's files would mean
             * putting somebody's 8 MB deck in the database — where it would be backed up,
             * synced, and kept long after the message went. A send with files that fails
             * stays a plain draft and says so; the person re-attaches. Rare, and the
             * alternative is worse.
             */
            val ok = runCatching { send(d.accountId, out) }.isSuccess
            if (ok) {
                dao.dropDraft(d.id)
                sent++
            } else {
                dao.setQueued(d.id, true, d.tries + 1)
            }
        }
        sent
    }

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
        /** Rows per page in the archive. */
        const val ARCHIVE_PAGE = 100

        /**
         * How many sent messages arrive at a time.
         *
         * Twenty, not sixty. Sixty meant a header fetch over a folder with no local cache
         * behind it every time the screen opened — long enough that leaving a sent message
         * and coming back looked like the app had hung. Twenty fills the screen twice over
         * and the rest arrives as you reach it.
         */
        const val SENT_PAGE = 20

        @Volatile private var instance: Repo? = null

        fun get(context: Context): Repo =
            instance ?: synchronized(this) {
                instance ?: Repo(context.applicationContext).also { instance = it }
            }
    }
}
