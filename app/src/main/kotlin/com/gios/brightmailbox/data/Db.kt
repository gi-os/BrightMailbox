package com.gios.brightmailbox.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One message, as the app remembers it.
 *
 * Bodies are NOT here. They go to files under filesDir/bodies keyed by the same [key],
 * because a mailbox is mostly newsletters and a year of HTML bodies in SQLite makes
 * every query slow to pay for text that is read once.
 */
@Entity(
    tableName = "messages",
    indices = [Index("pile", "receivedAt"), Index("sender"), Index("accountId")],
)
data class Msg(
    /** "$accountId/$providerId" — unique across accounts. */
    @PrimaryKey val key: String,
    val accountId: String,
    val providerId: String,
    val threadId: String,
    val sender: String,
    val senderName: String,
    val subject: String,
    val snippet: String,
    val receivedAt: Long,
    val unread: Boolean,
    /** "LETTER" / "NOTICE". Stored as text so a schema dump is readable. */
    val pile: String,
    /** The sentence shown when the user asks why this landed where it did. */
    val reason: String,
    val rule: String,
    /** Learned importance, 0..1. Ranks Letters; meaningless for Notices. */
    val score: Double,
    val messageId: String? = null,
    val references: String? = null,
    val hasAttachments: Boolean = false,
    /** Set when the user has opened it here, independent of the server's read flag. */
    val readHere: Boolean = false,
    /**
     * Which day's ration this Letter was counted against. yyyymmdd, 0 if not yet.
     *
     * Doing double duty since v2.14: it is also the stamp that decides how long a message
     * stays on the list after it has been read. A message read today is today's, greyed
     * but present; tomorrow the same row no longer matches and the list has moved on.
     * Written by [MailDao.markRead] from the Kotlin calendar, so every comparison against
     * it must use the same clock rather than SQLite's `localtime` — the two disagree at
     * the edges, and a disagreement here either strands a read letter forever or takes it
     * away while it is still being looked at.
     */
    @ColumnInfo(defaultValue = "0") val rationDay: Int = 0,
    /**
     * The day this message was read, by anyone, anywhere. yyyymmdd.
     *
     * Split from [rationDay] in v2.18, and the split is the whole point: a message read on
     * a laptop should go grey here and leave tomorrow, exactly like one read on the phone,
     * but it must NOT spend one of the day's five. Charging the ration for mail read
     * somewhere else would let a morning at a desktop mailbox close the phone's day before
     * it started — the app would be "five of five" every time it was opened, which is the
     * opposite of what a ration is for.
     *
     * So: reading here stamps both. Reading elsewhere stamps only this one.
     */
    @ColumnInfo(defaultValue = "0") val readDay: Int = 0,
    /**
     * Held by hand.
     *
     * The one thing in the app that overrides every rule about what is shown: a starred
     * message ignores the ration, ignores the day rollover, and is skipped by ARCHIVE ALL.
     * Mirrored to the server as IMAP `\Flagged`, which is the same bit Gmail draws as its
     * star and Outlook as its flag — so holding something here marks it everywhere.
     */
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /**
     * The raw RFC 2369 `List-Unsubscribe` value, angle brackets and all.
     *
     * Kept raw because it is a list and the choice between its entries is a decision made
     * at the moment somebody presses the button, not at sync: a `mailto:` costs a message
     * and an `https:` costs a page load, and which is preferable depends on
     * [oneClick] — which is a different header entirely.
     */
    @ColumnInfo(defaultValue = "") val unsubscribe: String = "",
    /**
     * The sender published RFC 8058 `List-Unsubscribe-Post`.
     *
     * That is a promise that one POST is enough — no page, no form, no "manage your
     * preferences" account wall. It is the difference between a button that works and a
     * button that opens a browser, so it is worth its own column.
     */
    @ColumnInfo(defaultValue = "0") val oneClick: Boolean = false,
    /**
     * Who else was on it, comma-joined — the other recipients, and the copied ones.
     *
     * Stored because **reply-all is a question about the original message**, and the
     * original is not on the phone by the time you answer it: the body is a file and the
     * headers were parsed away at sync. Without these, replying to all would mean fetching
     * the message again over IMAP to read two header lines.
     *
     * Addresses only, already lowercased. The display names are not kept — a reply
     * addresses people, and the name a sender chose to write on an envelope six weeks ago
     * is not worth a column.
     */
    @ColumnInfo(defaultValue = "") val toAddrs: String = "",
    @ColumnInfo(defaultValue = "") val ccAddrs: String = "",
    val archived: Boolean = false,
)

/**
 * A parcel, kept.
 *
 * Until now the list was recomputed on every open: a walk over every message in the
 * mailbox, reading every cached body off disk and running the detector across all of it.
 * That is why the screen took a moment to fill, and why it filled differently depending on
 * what had been archived since. The work was the same work every time and the answer was
 * almost always the same answer.
 *
 * Storing it changes what the list *is*. It was a view over the mail; now it is a record of
 * parcels, which is the thing it was always describing. Two consequences fall straight out:
 * the screen is instant, and a parcel can be put away by hand — which it had to gain in the
 * same release, because a stored row that nothing can remove is worse than a recomputed one
 * that quietly disappears.
 */
@Entity(tableName = "parcels")
data class ParcelRow(
    /** "UPS:1Z999AA10123456784" — the carrier and its number, from `Parcels.Parcel.id`. */
    @PrimaryKey val id: String,
    val carrier: String,
    val number: String,
    val state: String,
    val merchant: String?,
    val url: String?,
    val eta: String?,
    val item: String?,
    /**
     * When the newest mail that mentioned this parcel arrived.
     *
     * The message's own timestamp, not the clock: "newest wins" has to mean the newest
     * *mail*, or a sweep that reaches into the archive would let a six-week-old "shipped"
     * overwrite yesterday's "delivered" purely by being scanned last.
     */
    val seenAt: Long,
    /** Put away by hand. 0 means not. */
    @ColumnInfo(defaultValue = "0") val dismissedAt: Long = 0,
)

/** A per-sender decision the user made. The escape hatch that makes automation safe. */
@Entity(tableName = "rules")
data class SenderRule(
    @PrimaryKey val address: String,
    val pile: String,
    val createdAt: Long,
    /** True when the app inferred it from behavior rather than an explicit move. */
    val implicit: Boolean = false,
)

/** How many times the user has written to an address. The strongest signal there is. */
@Entity(tableName = "correspondents")
data class Correspondent(
    @PrimaryKey val address: String,
    val replies: Int,
    val lastAt: Long,
)

/** A draft that survives the app closing. */
@Entity(tableName = "drafts")
data class Draft(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val accountId: String,
    val to: String,
    val cc: String,
    val subject: String,
    val body: String,
    val inReplyTo: String?,
    val references: String?,
    val threadId: String?,
    val updatedAt: Long,
    /**
     * Waiting to go out.
     *
     * The outbox is the drafts table with a flag, not a second table. A queued message IS
     * a draft in every way that matters — same fields, same screen, same edit — and the
     * only difference is that something will try to send it again without being asked. A
     * separate table would have duplicated all of that to express one boolean.
     *
     * **Since v2.68 this mirrors [state] and nothing reads it.** The boolean could not say
     * why a message was still here, so [state] took over; the column stays because
     * SQLite on this phone cannot drop one and a table rebuild to lose a flag is not
     * worth the risk to somebody's drafts. Every write sets it to `state.inOutbox` so a
     * downgrade still sees a sane outbox.
     */
    @ColumnInfo(defaultValue = "0") val queued: Boolean = false,
    /** How many attempts have failed. Stops an unsendable message retrying for ever. */
    @ColumnInfo(defaultValue = "0") val tries: Int = 0,
    /**
     * Where it is on its way out — a [SendState] name. See that enum for the story.
     *
     * Text rather than the enum so a schema dump reads as words, and so a name this
     * build does not know reads as a plain draft rather than a crash.
     */
    @ColumnInfo(defaultValue = "DRAFT") val state: String = SendState.DRAFT.name,
    /**
     * Why the last attempt did not go. Blank when it has not failed.
     *
     * The last error, not the first: the first is history and the last is what is still
     * true. Shown on the drafts list, because a row that says "could not send" and does
     * not say why is a row nobody can act on.
     */
    @ColumnInfo(defaultValue = "") val error: String = "",
    /**
     * The files that go with it, as `n\tname\tmime` lines.
     *
     * The bytes are on disk under `filesDir/drafts/<id>/<n>`, never in this row: a
     * queued message with an 8 MB deck would otherwise put the deck in SQLite, where it
     * is read on every list query. Same tab-separated shape as the attachment index a
     * received message keeps beside its body, for the same reason — three fields, one
     * writer, one reader, and a dependency for that would be silly.
     *
     * Blank for most drafts. Before v2.68 a queued send lost its files entirely and the
     * list said so; now they ride along and come back when the draft is reopened.
     */
    @ColumnInfo(defaultValue = "") val files: String = "",
    /**
     * The provider id of the server-side draft this came from, blank for a local one.
     *
     * Import is read-only and has to be idempotent: the drafts folder is read on every
     * sync, so without a key to match on, a draft written at a desk would arrive again
     * every fifteen minutes until there were ninety-six of it.
     */
    @ColumnInfo(defaultValue = "") val remoteId: String = "",
    /** Which mailbox it came from, so a reopened server draft sends from the right one. */
    @ColumnInfo(defaultValue = "") val remoteAccount: String = "",
)

@Dao
interface MailDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(messages: List<Msg>)

    @Query("SELECT * FROM messages WHERE key = :key")
    suspend fun get(key: String): Msg?

    /**
     * Today's Letters, newest first.
     *
     * **Score decides which letters are today's; time decides the order they are shown
     * in.** This query used to rank by a coarse score bucket and use recency only to
     * break ties inside it, which meant a mailbox that read as shuffled: a letter from
     * this morning could sit below one from Tuesday because the model liked it more, and
     * nothing on screen explained why. A reader cannot see a score, so a score cannot be
     * an ordering they are asked to understand.
     *
     * The ranking is not gone — it moved to [MailboxViewModel.visibleLetters], which is
     * where the ration picks the five worth reading. That picking is invisible and always
     * was; the *order* is not.
     *
     * **A read letter is not gone, it is spent.** Until v2.14 this said `AND NOT readHere`,
     * so opening a letter deleted it from the screen — the only evidence you had read
     * anything was that the list was shorter than before, and a letter opened by mistake
     * could not be found again from inside the app at all. Now a letter read *today*
     * stays, drawn in grey, and leaves on its own when [day] moves on. Starred letters
     * never leave.
     *
     * @param day today as yyyymmdd, from the same calendar that wrote `rationDay`.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE pile = 'LETTER' AND NOT archived
          AND (NOT readHere OR starred OR readDay = :day)
        ORDER BY receivedAt DESC
        """,
    )
    fun letters(day: Int): Flow<List<Msg>>

    @Query(
        """
        SELECT * FROM messages
        WHERE pile = 'NOTICE' AND NOT archived
        ORDER BY receivedAt DESC LIMIT :limit
        """,
    )
    fun notices(limit: Int = 300): Flow<List<Msg>>

    @Query("SELECT COUNT(*) FROM messages WHERE pile = 'NOTICE' AND NOT archived AND unread")
    fun unreadNotices(): Flow<Int>

    /**
     * How many notices there actually are.
     *
     * The screens were counting `notices().size`, and that query has `LIMIT 300` on it —
     * so a mailbox with more than three hundred notices reported exactly "300", forever,
     * no matter how many arrived or were cleared. A count has to be counted; it cannot be
     * the length of a page of results.
     */
    @Query("SELECT COUNT(*) FROM messages WHERE pile = 'NOTICE' AND NOT archived")
    fun noticeTotal(): Flow<Int>

    /**
     * The same rows as a one-shot list.
     *
     * Needed because "mark all read" has to tell the SERVER which ids to mark, and a
     * Flow cannot be read once from a suspend function — collecting it there either
     * hangs or silently returns the first emission before the query has settled.
     */
    @Query("SELECT * FROM messages WHERE pile = 'NOTICE' AND NOT archived AND unread")
    suspend fun unreadNoticeList(): List<Msg>

    @Query("SELECT COUNT(*) FROM messages WHERE pile = 'LETTER' AND NOT archived AND NOT readHere")
    fun waitingLetters(): Flow<Int>

    /** Letters counted against today's ration. */
    @Query("SELECT COUNT(*) FROM messages WHERE pile = 'LETTER' AND rationDay = :day")
    suspend fun readToday(day: Int): Int

    @Query(
        "UPDATE messages SET readHere = 1, unread = 0, rationDay = :day, readDay = :day " +
            "WHERE key = :key",
    )
    suspend fun markRead(key: String, day: Int)

    @Query("UPDATE messages SET archived = 1 WHERE key = :key")
    suspend fun archive(key: String)

    /**
     * The preview line, written after the body has been fetched.
     *
     * Separate from [put] because it arrives later than the row does: IMAP hands over
     * headers in one batch and bodies one at a time, so the row exists for a second or two
     * before there is anything to preview. Writing it back through `put` would need the
     * whole Msg and would clobber whatever the user did to it in the meantime.
     */
    @Query("UPDATE messages SET snippet = :snippet WHERE key = :key")
    suspend fun setSnippet(key: String, snippet: String)

    /**
     * Everything held, archived included.
     *
     * A star is the one mark in this app that means "keep this in front of me", and
     * archiving something does not stop it being held — you can put a message away and
     * still want to find it again without remembering who sent it. So this crosses the
     * archive line, which none of the other list queries do.
     */
    @Query("SELECT * FROM messages WHERE starred ORDER BY receivedAt DESC")
    fun flagged(): Flow<List<Msg>>

    @Query("SELECT COUNT(*) FROM messages WHERE starred")
    fun flaggedCount(): Flow<Int>

    @Query("UPDATE messages SET starred = :on WHERE key = :key")
    suspend fun setStarred(key: String, on: Boolean)

    /** The inbound half of the star, applied in one statement per direction. */
    @Query("UPDATE messages SET starred = :on WHERE key IN (:keys)")
    suspend fun setStarredAll(keys: List<String>, on: Boolean)

    /**
     * Drop a row entirely.
     *
     * Only for un-archiving, and the reason is UIDs: a message moved back to INBOX gets a
     * new one, so the old `providerId` points at nothing and the next sync would fetch the
     * same message again under a different key. Deleting the stale row is what stops the
     * message appearing twice.
     */
    @Query("DELETE FROM messages WHERE key = :key")
    suspend fun forget(key: String)

    /* ------------------------------------------------- reconciling with the server */

    /** Everything still in this account's inbox as far as the app knows. */
    @Query("SELECT * FROM messages WHERE accountId = :accountId AND NOT archived")
    suspend fun liveFor(accountId: String): List<Msg>

    /** Archived somewhere else. Room takes a list, so this is one statement. */
    @Query("UPDATE messages SET archived = 1 WHERE key IN (:keys)")
    suspend fun archiveAll(keys: List<String>)

    /**
     * The server's read state won — it was read somewhere else.
     *
     * Sets `readHere` despite the name, because the name is now wrong: what it really
     * means is "the user has read this", and they have, on another device. Without it a
     * message read on a laptop stayed white and bold here forever, which made the one
     * question the list is meant to answer — what have I not read — wrong on every
     * account anybody also opens elsewhere.
     *
     * `rationDay` is deliberately untouched. See [Msg.readDay].
     */
    @Query("UPDATE messages SET unread = 0, readHere = 1, readDay = :day WHERE key IN (:keys)")
    suspend fun markSeen(keys: List<String>, day: Int)

    /*
     * ARCHIVE ALL means all of them except the ones held by hand.
     *
     * A star is the user saying "not this one", and a bulk action that ignores it is a
     * bulk action nobody can safely press. Both statements carry the exclusion because
     * both run: the list is what gets moved on the server, the update is what clears the
     * screen, and they have to agree about which rows they are talking about.
     */
    @Query("UPDATE messages SET archived = 1 WHERE pile = 'NOTICE' AND NOT archived AND NOT starred")
    suspend fun archiveAllNotices()

    @Query("SELECT * FROM messages WHERE pile = 'NOTICE' AND NOT archived AND NOT starred")
    suspend fun noticeList(): List<Msg>

    /**
     * Every notice ever stored — archived, starred, read, all of it.
     *
     * Read by the parcel scan rather than [noticeList], because a parcel is not a piece of
     * mail: filing the email that announced it does not put the thing back in the
     * warehouse. See `Repo.scanParcels` for what takes a row off that list.
     */
    @Query("SELECT * FROM messages WHERE pile = 'NOTICE'")
    suspend fun noticeHistory(): List<Msg>

    /**
     * Everything the app has stored, both piles, archived and read included.
     *
     * The parcel scan reads this. A shipping mail belongs in whichever pile the sorter put
     * it in and the sorter is allowed to be wrong about a machine's mail — but a parcel
     * that announced itself in a Letter is still a parcel, and a scan that only ever looked
     * in Notices was blind to exactly the case where the sorter and the customer disagree.
     */
    @Query("SELECT * FROM messages")
    suspend fun allMessages(): List<Msg>

    /**
     * The rest of a conversation, oldest first.
     *
     * `threadId` is the RFC 5322 thread root — the first Message-ID in `References`, or
     * `In-Reply-To`, or the message's own id — computed on the way in since v2.0 and,
     * until now, read by nothing at all.
     *
     * Scoped to the account. Two people can reply to the same mailing-list message from
     * two of your mailboxes, and stitching those into one conversation would show mail
     * from one account inside another.
     *
     * Archived messages are included: the earlier half of a conversation is very often
     * already filed away, and leaving it out would make a thread look like it started in
     * the middle.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE threadId = :threadId AND accountId = :accountId AND key != :exclude
        ORDER BY receivedAt ASC LIMIT 40
        """,
    )
    suspend fun thread(threadId: String, accountId: String, exclude: String): List<Msg>

    /**
     * One page of the archive, newest first.
     *
     * Paged rather than capped. It used to be `LIMIT 500` with no offset, which is not a
     * limit anybody can see past: mail number 501 was simply not there, and nothing said
     * so — indistinguishable from mail that had been lost.
     */
    @Query(
        "SELECT * FROM messages WHERE archived ORDER BY receivedAt DESC LIMIT :limit OFFSET :offset",
    )
    fun archived(limit: Int, offset: Int): Flow<List<Msg>>

    /** How much is in the archive, so a page can say which part of it you are looking at. */
    @Query("SELECT COUNT(*) FROM messages WHERE archived")
    fun archivedTotal(): Flow<Int>

    /**
     * Search every pile, archived included.
     *
     * Sender, name and subject only — not the body, which is not in this table at all
     * (bodies are files, see [Msg]). Searching what is here is instant and needs no
     * network; searching bodies would mean reading a few hundred files per keystroke.
     *
     * The caller passes a pattern already wrapped in `%`. `LIKE` is case-insensitive for
     * ASCII in SQLite by default, which is what a mail search wants.
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE sender LIKE :q OR senderName LIKE :q OR subject LIKE :q
        ORDER BY receivedAt DESC LIMIT 200
        """,
    )
    suspend fun search(q: String): List<Msg>

    /** Everything still in the inbox, both piles, for a bulk clear. */
    @Query("SELECT * FROM messages WHERE NOT archived AND NOT starred")
    suspend fun inboxList(): List<Msg>

    @Query("UPDATE messages SET unread = 0 WHERE pile = 'NOTICE' AND NOT archived")
    suspend fun markAllNoticesRead()

    @Query("UPDATE messages SET pile = :pile, reason = :reason, rule = 'override' WHERE sender = :sender")
    suspend fun repile(sender: String, pile: String, reason: String)

    /** Everything with a body, for retraining. Bodies are added by the repository. */
    @Query("SELECT * FROM messages ORDER BY receivedAt DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<Msg>

    @Query("SELECT MAX(receivedAt) FROM messages WHERE accountId = :accountId")
    suspend fun newestFor(accountId: String): Long?

    /**
     * Every provider id this account has, archived included, for finding the highest
     * INBOX UID already stored — the sync's bookmark when none has been written yet, or
     * when the one written has gone stale. Asked rarely: once per account on the first
     * run after the update, and again only when the server renumbers the folder.
     */
    @Query("SELECT providerId FROM messages WHERE accountId = :accountId")
    suspend fun providerIds(accountId: String): List<String>

    /**
     * Everything that came to one mailbox, for when that mailbox is removed.
     *
     * Without this the rows outlive the credential: they still draw in both piles, and
     * opening one asks a connection that no longer exists for a body.
     */
    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAccount(accountId: String)


    /* --------------------------------------------------------------- parcels */

    /**
     * What is on its way, newest mail first.
     *
     * A Flow, so the screen draws whatever is stored the instant it opens and updates
     * itself when a sync brings mail that advances a parcel. Nothing asks for a scan on
     * the way in any more.
     */
    @Query("SELECT * FROM parcels WHERE dismissedAt = 0 ORDER BY seenAt DESC")
    fun parcels(): Flow<List<ParcelRow>>

    /** Everything stored, dismissed included — the merge has to see what it is updating. */
    @Query("SELECT * FROM parcels")
    suspend fun allParcels(): List<ParcelRow>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putParcels(rows: List<ParcelRow>)

    @Query("UPDATE parcels SET dismissedAt = :at WHERE id = :id")
    suspend fun dismissParcel(id: String, at: Long)

    /**
     * Forget delivered parcels nobody is looking at any more.
     *
     * The list already stops showing a delivered parcel after a few days; this is what
     * stops the table growing for ever behind it. Only delivered ones: a parcel still
     * described as on its way is either really on its way or is the exact case a person
     * wants to see and wonder about.
     */
    @Query("DELETE FROM parcels WHERE state = 'DELIVERED' AND seenAt < :before")
    suspend fun pruneParcels(before: Long)

    /* ----------------------------------------------------------------- rules */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putRule(rule: SenderRule)

    @Query("DELETE FROM rules WHERE address = :address")
    suspend fun dropRule(address: String)

    @Query("SELECT * FROM rules ORDER BY createdAt DESC")
    fun rules(): Flow<List<SenderRule>>

    @Query("SELECT * FROM rules")
    suspend fun allRules(): List<SenderRule>

    /* -------------------------------------------------------- correspondents */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCorrespondents(list: List<Correspondent>)

    @Query("SELECT * FROM correspondents")
    suspend fun correspondents(): List<Correspondent>

    @Query("SELECT * FROM correspondents ORDER BY lastAt DESC LIMIT :limit")
    suspend fun recentCorrespondents(limit: Int): List<Correspondent>

    /* ---------------------------------------------------------------- drafts */

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putDraft(d: Draft): Long

    /** Everything a person can still do something with. A SENT row is a tombstone. */
    @Query("SELECT * FROM drafts WHERE state != 'SENT' ORDER BY updatedAt DESC")
    fun drafts(): Flow<List<Draft>>

    @Query("SELECT * FROM drafts WHERE id = :id")
    suspend fun draft(id: Long): Draft?

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun dropDraft(id: Long)

    /**
     * The outbox: what is waiting to go, oldest first so a queue stays a queue.
     *
     * SENDING is included on purpose — a row still saying so at the start of a flush was
     * left by a process that died mid-send. See [SendState.SENDING].
     */
    @Query(
        "SELECT * FROM drafts WHERE state IN ('QUEUED', 'SENDING') AND tries < 5 ORDER BY updatedAt ASC",
    )
    suspend fun queuedDrafts(): List<Draft>

    /** Tombstones: accepted by the server, not yet deleted. Swept at the start of a flush. */
    @Query("SELECT * FROM drafts WHERE state = 'SENT'")
    suspend fun sentDrafts(): List<Draft>

    @Query("SELECT * FROM drafts WHERE remoteId != ''")
    suspend fun importedDrafts(): List<Draft>

    @Query("SELECT COUNT(*) FROM drafts WHERE state IN ('QUEUED', 'SENDING')")
    fun queuedCount(): Flow<Int>

    /** Given up on, waiting for a person. The menu line has to say so. */
    @Query("SELECT COUNT(*) FROM drafts WHERE state = 'FAILED'")
    fun failedCount(): Flow<Int>

    /**
     * Move a draft along. One statement, so a state and the reason for it can never be
     * written apart; `queued` rides along as the mirror the column comment describes.
     */
    @Query(
        "UPDATE drafts SET state = :state, tries = :tries, error = :error, queued = :queued " +
            "WHERE id = :id",
    )
    suspend fun setState(id: Long, state: String, tries: Int, error: String, queued: Boolean)

    @Query("UPDATE drafts SET files = :files WHERE id = :id")
    suspend fun setFiles(id: Long, files: String)
}

@Database(
    entities = [Msg::class, SenderRule::class, Correspondent::class, Draft::class, ParcelRow::class],
    version = 9,
    exportSchema = false,
)
abstract class MailDb : RoomDatabase() {
    abstract fun dao(): MailDao

    companion object {
        /**
         * v1 → v2: the `starred` column.
         *
         * Written out rather than left to `fallbackToDestructiveMigration`, which is what
         * the builder still falls back on. Destructive is tolerable for a cache and this
         * is a cache — but it is a cache of somebody's mail with a learned model hanging
         * off it, and dropping it costs a full re-sync of every account on the first
         * launch after an update, on a phone, over IMAP. One ALTER avoids all of that.
         */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * v2 → v3: `readDay`, split out of `rationDay`.
         *
         * Backfilled from `rationDay` rather than left at zero. Without the copy, every
         * letter already read today would fall out of the Letters query the moment the
         * app updated — the upgrade itself would look like the day's mail had been
         * deleted.
         */
        /**
         * v3 → v4: the other recipients, for reply-all.
         *
         * Blank for everything already stored, so reply-all on an old message answers the
         * sender alone — which is the safe direction to be wrong in. New mail carries them
         * from the next sync onwards.
         */
        /**
         * v4 → v5: what a sender said about unsubscribing.
         *
         * Stored on the row rather than re-fetched, because the header is on the message
         * and the message is on the server: offering UNSUBSCRIBE would otherwise mean a
         * round trip per row just to find out whether the button should exist. Blank for
         * everything already here, which reads as "no link" — the safe direction, and the
         * next sync fills it in for anything new.
         */
        /**
         * v8 → v9: an outgoing message has a state, a reason, and its files.
         *
         * `state` is backfilled from the two columns it replaces, so a message that was
         * waiting when the app updated is still waiting afterwards and one that had used
         * its five tries says so rather than quietly becoming a draft again. `queued`
         * stays and keeps being written — see [Draft.queued] for why a column this app
         * no longer reads is still here.
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN state TEXT NOT NULL DEFAULT 'DRAFT'")
                db.execSQL("ALTER TABLE drafts ADD COLUMN error TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE drafts ADD COLUMN files TEXT NOT NULL DEFAULT ''")
                db.execSQL(
                    """
                    UPDATE drafts SET state = CASE
                        WHEN queued != 0 AND tries >= 5 THEN 'FAILED'
                        WHEN queued != 0 THEN 'QUEUED'
                        ELSE 'DRAFT'
                    END
                    """.trimIndent(),
                )
            }
        }

        /**
         * v7 → v8: parcels become a table.
         *
         * Nothing is backfilled and nothing needs to be. The first scan after the update
         * rebuilds the list from the mail, which is where it has always come from — this
         * migration only gives the answer somewhere to live.
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS parcels (
                        id TEXT NOT NULL PRIMARY KEY,
                        carrier TEXT NOT NULL,
                        number TEXT NOT NULL,
                        state TEXT NOT NULL,
                        merchant TEXT,
                        url TEXT,
                        eta TEXT,
                        item TEXT,
                        seenAt INTEGER NOT NULL,
                        dismissedAt INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent(),
                )
            }
        }

        /** v6 → v7: where an imported draft came from. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN remoteId TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE drafts ADD COLUMN remoteAccount TEXT NOT NULL DEFAULT ''")
            }
        }

        /** v5 → v6: the outbox, which is two columns on the drafts table. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE drafts ADD COLUMN queued INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE drafts ADD COLUMN tries INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN unsubscribe TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN oneClick INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN toAddrs TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE messages ADD COLUMN ccAddrs TEXT NOT NULL DEFAULT ''")
            }
        }

        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN readDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET readDay = rationDay WHERE readHere != 0")
            }
        }
    }
}
