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
    val archived: Boolean = false,
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

    @Query("UPDATE messages SET starred = :on WHERE key = :key")
    suspend fun setStarred(key: String, on: Boolean)

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

    /** Everything put away, newest first. Archive is a place, not a deletion. */
    @Query("SELECT * FROM messages WHERE archived ORDER BY receivedAt DESC LIMIT :limit")
    fun archived(limit: Int = 500): Flow<List<Msg>>

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
     * Everything that came to one mailbox, for when that mailbox is removed.
     *
     * Without this the rows outlive the credential: they still draw in both piles, and
     * opening one asks a connection that no longer exists for a body.
     */
    @Query("DELETE FROM messages WHERE accountId = :accountId")
    suspend fun deleteAccount(accountId: String)

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

    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun drafts(): Flow<List<Draft>>

    @Query("DELETE FROM drafts WHERE id = :id")
    suspend fun dropDraft(id: Long)
}

@Database(
    entities = [Msg::class, SenderRule::class, Correspondent::class, Draft::class],
    version = 3,
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
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN readDay INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE messages SET readDay = rationDay WHERE readHere != 0")
            }
        }
    }
}
