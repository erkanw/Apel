package io.github.erkanw.vault.store

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

// The chats on the phone (PLAN §5, "Room tables"). Later phases add channels, automations, skills, tools, databases,
// usage and approvals as new tables with migrations.

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val created: Long,
    val updated: Long,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    /** The user named it: the automatic title no longer changes it. */
    @ColumnInfo(name = "title_set") val titleSet: Boolean = false,
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    /** The model preset this session runs with; null: the main model. */
    @ColumnInfo(name = "model_override") val modelOverride: String? = null,
    /** Messages up to this id are covered by [summary] (PLAN §7.6): they stay on screen but aren’t sent again. */
    @ColumnInfo(name = "compacted_through") val compactedThrough: Long = 0L,
    /** The summary of the messages up to [compactedThrough]. */
    val summary: String? = null,
    /** For a fork: how many messages were copied from the session it came from. */
    @ColumnInfo(name = "fork_boundary") val forkBoundary: Int? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("session_id")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** "user" or "assistant". */
    val role: String,
    val text: String,
    val created: Long,
    val model: String? = null,
    @ColumnInfo(name = "tokens_in") val tokensIn: Long = 0,
    @ColumnInfo(name = "tokens_out") val tokensOut: Long = 0,
    /** Dollars, when the provider says (OpenRouter does). */
    val cost: Double? = null,
    /** "ok", "error" or "stopped". */
    val status: String = "ok",
    @ColumnInfo(name = "forked_from") val forkedFrom: Long? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("message_id")],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "message_id") val messageId: Long,
    /** "image", "video" or "file". */
    val kind: String,
    val name: String,
    val mime: String,
    /** The copy on the phone. */
    val path: String?,
    val size: Long,
)

@Entity(
    tableName = "steps",
    primaryKeys = ["message_id", "index"],
    foreignKeys = [ForeignKey(entity = MessageEntity::class, parentColumns = ["id"], childColumns = ["message_id"], onDelete = ForeignKey.CASCADE)],
)
data class StepEntity(
    @ColumnInfo(name = "message_id") val messageId: Long,
    val index: Int,
    /** "tool", "progress" or "reasoning". */
    val kind: String,
    val title: String,
    val detail: String? = null,
)

/** A message with its files and steps. */
data class MessageWithParts(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "message_id") val attachments: List<AttachmentEntity>,
    @Relation(parentColumn = "id", entityColumn = "message_id") val steps: List<StepEntity>,
)

/** A session row for the drawer: the session and its latest message's text. */
data class SessionRow(
    @Embedded val session: SessionEntity,
    val preview: String?,
)

@Dao
interface ChatDao {
    @Query(
        """SELECT s.*, (SELECT m.text FROM messages m WHERE m.session_id = s.id AND m.role = 'assistant' AND m.status != 'error'
           ORDER BY m.id DESC LIMIT 1) AS preview FROM sessions s ORDER BY s.updated DESC""",
    )
    suspend fun sessions(): List<SessionRow>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun session(id: String): SessionEntity?

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Query("UPDATE sessions SET title = :title, title_set = :userSet WHERE id = :id")
    suspend fun setTitle(id: String, title: String, userSet: Boolean)

    @Query("UPDATE sessions SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE sessions SET archived = :archived, pinned = CASE WHEN :archived THEN 0 ELSE pinned END WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("UPDATE sessions SET model_override = :preset WHERE id = :id")
    suspend fun setModel(id: String, preset: String?)

    @Query("UPDATE sessions SET summary = :summary, compacted_through = :through WHERE id = :id")
    suspend fun setCompaction(id: String, summary: String, through: Long)

    @Query("UPDATE sessions SET updated = :at WHERE id = :id")
    suspend fun touch(id: String, at: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Transaction
    @Query("SELECT * FROM messages WHERE session_id = :sessionId ORDER BY id")
    suspend fun messages(sessionId: String): List<MessageWithParts>

    @Insert
    suspend fun insertMessage(message: MessageEntity): Long

    @Query("UPDATE messages SET text = :text, model = :model, tokens_in = :tokensIn, tokens_out = :tokensOut, cost = :cost, status = :status WHERE id = :id")
    suspend fun finishMessage(id: Long, text: String, model: String?, tokensIn: Long, tokensOut: Long, cost: Double?, status: String)

    @Insert
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Insert
    suspend fun insertSteps(steps: List<StepEntity>)

    @Query("DELETE FROM steps WHERE message_id = :messageId")
    suspend fun clearSteps(messageId: Long)

    @Transaction
    suspend fun replaceSteps(messageId: Long, steps: List<StepEntity>) {
        clearSteps(messageId)
        if (steps.isNotEmpty()) insertSteps(steps)
    }
}

/** One answer's cost (PLAN §5, `usage`): what it used and what it cost, for the Usage page and the budget (Phase 7). */
@Entity(tableName = "usage", indices = [Index("time")])
data class UsageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val model: String,
    /** "user" or "automation". */
    val source: String,
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "automation_id") val automationId: String? = null,
    @ColumnInfo(name = "tokens_in") val tokensIn: Long,
    @ColumnInfo(name = "tokens_out") val tokensOut: Long,
    val cost: Double?,
    val ok: Boolean,
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(row: UsageEntity)

    @Query("SELECT * FROM usage WHERE time >= :since ORDER BY time")
    suspend fun since(since: Long): List<UsageEntity>
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, AttachmentEntity::class, StepEntity::class, UsageEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun usage(): UsageDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /** Phase 4: a session’s compaction summary. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `summary` TEXT")
            }
        }

        /** Phase 3: the usage log. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `usage` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, " +
                        "`model` TEXT NOT NULL, `source` TEXT NOT NULL, `session_id` TEXT, `automation_id` TEXT, `tokens_in` INTEGER NOT NULL, " +
                        "`tokens_out` INTEGER NOT NULL, `cost` REAL, `ok` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_usage_time` ON `usage` (`time`)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }
    }
}
