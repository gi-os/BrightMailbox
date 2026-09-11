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
    /** Which day's ration this Letter was counted against. yyyymmdd, 0 if not yet. */
    @ColumnInfo(defaultValue = "0") val rationDay: Int = 0,
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
     */
    @Query(
        """
        SELECT * FROM messages
        WHERE pile = 'LETTER' AND NOT archived AND NOT readHere
        ORDER BY receivedAt DESC
        """,
    )
    fun letters(): Flow<List<Msg>>

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

    @Query("UPDATE messages SET readHere = 1, unread = 0, rationDay = :day WHERE key = :key")
    suspend fun markRead(key: String, day: Int)

    @Query("UPDATE messages SET archived = 1 WHERE key = :key")
    suspend fun archive(key: String)

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
    version = 1,
    exportSchema = false,
)
abstract class MailDb : RoomDatabase() {
    abstract fun dao(): MailDao
}
