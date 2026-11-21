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
import androidx.room.Update

// The chats on the phone (PLAN §5, "Room tables"), the usage log, the automations and the user databases' registry.
// Approvals join later as new tables with migrations. Channels stay in SharedPreferences (ChannelLooks, AppSettings).

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
    /** The agent this chat talks to (PLAN §6.16); null: Apel, the first agent. */
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
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
    /** For an answer: the agent that wrote it (PLAN §6.16); null for the app's own answers and older ones. */
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
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

    /** The agent a chat talks to (kept from its first message; changed on the session page). */
    @Query("UPDATE sessions SET agent_id = :agent WHERE id = :id")
    suspend fun setAgent(id: String, agent: String)

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
    /** Calls to the model this answer took (one per tool round); OpenRouter counts each, free models' daily limit too. */
    @ColumnInfo(defaultValue = "1") val requests: Int = 1,
    /** The agent that answered; null: Apel (and every answer before Phase 10). */
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(row: UsageEntity)

    @Query("SELECT * FROM usage WHERE time >= :since ORDER BY time")
    suspend fun since(since: Long): List<UsageEntity>
}


/**
 * A scheduled job (PLAN §5 `automations`, §8). [kind] and its fields are the schedule ([io.github.erkanw.vault.automation.Schedule]).
 * It runs in a channel ([channelId]) or in a new chat each time (no channel). [builtin] names a system job (Tidy memory),
 * which can be turned off but not deleted.
 */
@Entity(tableName = "automations")
data class AutomationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val message: String,
    /** "every", "cron" or "at". */
    val kind: String,
    val expr: String = "",
    val tz: String = "",
    @ColumnInfo(name = "every_ms") val everyMs: Long = 0L,
    @ColumnInfo(name = "at_ms") val atMs: Long = 0L,
    val enabled: Boolean = true,
    /** The channel it posts in; null: a new chat each time. */
    @ColumnInfo(name = "channel_id") val channelId: String? = null,
    /** The model preset it runs with; null: the main model. */
    val model: String? = null,
    /** When it runs next (milliseconds); 0: not planned (off, or a one-time job that has run). */
    @ColumnInfo(name = "next_run") val nextRun: Long = 0L,
    @ColumnInfo(name = "last_run") val lastRun: Long = 0L,
    /** "", "ok", "error", "skipped" or "stopped". */
    @ColumnInfo(name = "last_status") val lastStatus: String = "",
    @ColumnInfo(name = "last_error") val lastError: String = "",
    val builtin: String? = null,
    val created: Long,
    /** The agent it runs as; null: Apel. */
    @ColumnInfo(name = "agent_id") val agentId: String? = null,
)

/** One run of an automation (PLAN §5 `automation_runs`): when, how it went, and where its answer is. */
@Entity(
    tableName = "automation_runs",
    foreignKeys = [ForeignKey(entity = AutomationEntity::class, parentColumns = ["id"], childColumns = ["automation_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("automation_id")],
)
data class AutomationRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "automation_id") val automationId: String,
    val started: Long,
    val finished: Long = 0L,
    /** "running", "ok", "error", "skipped" or "stopped". */
    val status: String = "running",
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    @ColumnInfo(name = "message_id") val messageId: Long? = null,
    /** The answer's first line, or why it failed or was skipped. */
    val summary: String = "",
)

@Dao
interface AutomationDao {
    @Query("SELECT * FROM automations ORDER BY (builtin IS NOT NULL), created")
    suspend fun all(): List<AutomationEntity>

    @Query("SELECT * FROM automations WHERE id = :id")
    suspend fun get(id: String): AutomationEntity?

    @Insert
    suspend fun insert(a: AutomationEntity)

    @Update
    suspend fun update(a: AutomationEntity)

    @Query("DELETE FROM automations WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE automations SET next_run = :next WHERE id = :id")
    suspend fun setNext(id: String, next: Long)

    @Query("UPDATE automations SET last_run = :at, last_status = :status, last_error = :error, next_run = :next, enabled = :enabled WHERE id = :id")
    suspend fun setResult(id: String, at: Long, status: String, error: String, next: Long, enabled: Boolean)

    @Query("UPDATE automations SET channel_id = NULL WHERE channel_id = :channelId")
    suspend fun forgetChannel(channelId: String)

    @Query("SELECT * FROM automation_runs WHERE automation_id = :automationId ORDER BY started DESC, id DESC LIMIT :limit")
    suspend fun runs(automationId: String, limit: Int): List<AutomationRunEntity>

    @Insert
    suspend fun insertRun(run: AutomationRunEntity): Long

    @Query("UPDATE automation_runs SET finished = :finished, status = :status, session_id = :sessionId, message_id = :messageId, summary = :summary WHERE id = :id")
    suspend fun finishRun(id: Long, finished: Long, status: String, sessionId: String?, messageId: Long?, summary: String)

    /** Runs left "running" by a process that ended (the app was closed or killed mid-run). */
    @Query("UPDATE automation_runs SET status = 'stopped', finished = :at, summary = :summary WHERE status = 'running'")
    suspend fun stopInterrupted(at: Long, summary: String)

    /** Keeps the newest [keep] runs of each automation. */
    @Query("DELETE FROM automation_runs WHERE automation_id = :automationId AND id NOT IN (SELECT id FROM automation_runs WHERE automation_id = :automationId ORDER BY started DESC, id DESC LIMIT :keep)")
    suspend fun trimRuns(automationId: String, keep: Int)
}

/** A user database in the registry (PLAN §5 `user_dbs`, §9); its file is `files/dbs/<name>.db`. */
@Entity(tableName = "user_dbs")
data class UserDbEntity(
    @PrimaryKey val name: String,
    val description: String,
    /** "none", "read" or "write". */
    @ColumnInfo(name = "agent_access") val agentAccess: String,
    @ColumnInfo(name = "may_create_tables") val mayCreateTables: Boolean,
    val created: Long,
    /** The archived file's name while archived; null when in use. */
    @ColumnInfo(name = "archived_file") val archivedFile: String? = null,
)

/** One change to a user database (PLAN §5 `db_audit`): when, which, by whom ("agent" or "you"), what, how many rows. */
@Entity(tableName = "db_audit", indices = [Index("db")])
data class DbAuditEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val db: String,
    val actor: String,
    val action: String,
    val statement: String,
    val rows: Int,
)

/** The registry's calls are plain (not suspending): the user-database code is blocking and runs off the main thread. */
@Dao
interface UserDbDao {
    @Query("SELECT * FROM user_dbs ORDER BY name")
    fun all(): List<UserDbEntity>

    @Query("SELECT * FROM user_dbs WHERE name = :name")
    fun get(name: String): UserDbEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    fun put(db: UserDbEntity)

    @Query("DELETE FROM user_dbs WHERE name = :name")
    fun remove(name: String)

    @Insert
    fun audit(row: DbAuditEntity)

    @Query("SELECT * FROM db_audit WHERE (:db IS NULL OR db = :db) ORDER BY time DESC, id DESC LIMIT :limit")
    fun recentAudit(db: String?, limit: Int): List<DbAuditEntity>

    @Query("DELETE FROM db_audit WHERE id NOT IN (SELECT id FROM db_audit ORDER BY id DESC LIMIT 2000)")
    fun trimAudit()
}

/**
 * A tool server (PLAN §5 `mcp_servers`, §7.4): MCP over Streamable HTTP. Its token is not here but in the Keystore, bound
 * to [url]. [tools] is the last `tools/list` answer as JSON (name, description, inputSchema, readOnly), so a turn doesn't
 * have to ask the server first; [enabledTools] is a JSON array, `["*"]` for all.
 */
@Entity(tableName = "mcp_servers")
data class McpServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    @ColumnInfo(name = "enabled_tools") val enabledTools: String = "[\"*\"]",
    val tools: String = "[]",
    /** When the tools were last read (0: never), and why the last attempt failed (empty: it worked). */
    @ColumnInfo(name = "checked_at") val checkedAt: Long = 0L,
    @ColumnInfo(name = "last_error") val lastError: String = "",
    val created: Long,
)

/**
 * An agent (PLAN §6.16): a preset of model, system prompt, tools, skills and database access, with its own name, look
 * and memory. Apel is the row `apel`; its name and look stay in AgentProfile and LookStore (the app's own identity).
 * Lists are JSON: what is switched off (everything is on by default), and per-database caps (a missing name: no cap).
 */
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** The mascot's look as a Studio hash; "" is the default look. */
    val look: String = "",
    /** A model preset's name; null: the main model and its fallbacks. */
    val model: String? = null,
    /** The system prompt; null: the default prompt. */
    val prompt: String? = null,
    @ColumnInfo(name = "tools_off") val toolsOff: String = "[]",
    @ColumnInfo(name = "skills_off") val skillsOff: String = "[]",
    @ColumnInfo(name = "db_caps") val dbCaps: String = "{}",
    /** The template it was made from, or "". */
    val template: String = "",
    val position: Int = 0,
    val created: Long,
)

@Dao
interface AgentDao {
    @Query("SELECT * FROM agents ORDER BY position, created")
    suspend fun all(): List<AgentEntity>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun get(id: String): AgentEntity?

    @Insert
    suspend fun insert(a: AgentEntity)

    @Update
    suspend fun update(a: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun delete(id: String)

    /** A deleted agent's chats and jobs go to Apel. */
    @Query("UPDATE sessions SET agent_id = 'apel' WHERE agent_id = :id")
    suspend fun moveSessionsToApel(id: String)

    @Query("UPDATE automations SET agent_id = NULL WHERE agent_id = :id")
    suspend fun moveAutomationsToApel(id: String)
}

@Dao
interface McpDao {
    @Query("SELECT * FROM mcp_servers ORDER BY created")
    suspend fun all(): List<McpServerEntity>

    @Query("SELECT * FROM mcp_servers WHERE id = :id")
    suspend fun get(id: String): McpServerEntity?

    @Insert
    suspend fun insert(s: McpServerEntity)

    @Update
    suspend fun update(s: McpServerEntity)

    @Query("DELETE FROM mcp_servers WHERE id = :id")
    suspend fun delete(id: String)
}

@Database(
    entities = [SessionEntity::class, MessageEntity::class, AttachmentEntity::class, StepEntity::class, UsageEntity::class,
        AutomationEntity::class, AutomationRunEntity::class, UserDbEntity::class, DbAuditEntity::class,
        McpServerEntity::class, AgentEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chats(): ChatDao
    abstract fun usage(): UsageDao
    abstract fun automations(): AutomationDao
    abstract fun userDbs(): UserDbDao
    abstract fun mcp(): McpDao
    abstract fun agents(): AgentDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        /**
         * Closes the database for a restore and runs [block] (which replaces its files and ends the process) while
         * holding the lock, so nothing opens it again in between.
         */
        fun <T> closeForRestore(block: () -> T): T = synchronized(this) {
            instance?.close()
            instance = null
            block()
        }

        /**
         * Which agent wrote each answer. A chat outside a channel already knew its agent (null was Apel); its answers
         * take that agent. A channel's chats followed the channel and stay unknown until their next message pins them.
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `messages` ADD COLUMN `agent_id` TEXT")
                db.execSQL("UPDATE `sessions` SET `agent_id` = 'apel' WHERE `agent_id` IS NULL AND `channel_id` IS NULL")
                db.execSQL(
                    "UPDATE `messages` SET `agent_id` = (SELECT s.`agent_id` FROM `sessions` s WHERE s.`id` = `messages`.`session_id`) " +
                        "WHERE `role` = 'assistant' AND `status` != 'local'",
                )
            }
        }

        /** Phase 10: agents, and which agent each chat, job and answer belongs to. */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agents` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `look` TEXT NOT NULL, `model` TEXT, " +
                        "`prompt` TEXT, `tools_off` TEXT NOT NULL, `skills_off` TEXT NOT NULL, `db_caps` TEXT NOT NULL, " +
                        "`template` TEXT NOT NULL, `position` INTEGER NOT NULL, `created` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("ALTER TABLE `sessions` ADD COLUMN `agent_id` TEXT")
                db.execSQL("ALTER TABLE `automations` ADD COLUMN `agent_id` TEXT")
                db.execSQL("ALTER TABLE `usage` ADD COLUMN `agent_id` TEXT")
            }
        }

        /** Phase 8: tool servers. */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `mcp_servers` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, " +
                        "`enabled_tools` TEXT NOT NULL, `tools` TEXT NOT NULL, `checked_at` INTEGER NOT NULL, `last_error` TEXT NOT NULL, " +
                        "`created` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
            }
        }

        /** Phase 7: how many calls each answer took. */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `usage` ADD COLUMN `requests` INTEGER NOT NULL DEFAULT 1")
            }
        }

        /** Phase 6: the user databases' registry and activity log. */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `user_dbs` (`name` TEXT NOT NULL, `description` TEXT NOT NULL, `agent_access` TEXT NOT NULL, " +
                        "`may_create_tables` INTEGER NOT NULL, `created` INTEGER NOT NULL, `archived_file` TEXT, PRIMARY KEY(`name`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `db_audit` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `time` INTEGER NOT NULL, `db` TEXT NOT NULL, " +
                        "`actor` TEXT NOT NULL, `action` TEXT NOT NULL, `statement` TEXT NOT NULL, `rows` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_db_audit_db` ON `db_audit` (`db`)")
            }
        }

        /** Phase 5: automations and their run log. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `automations` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `message` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `expr` TEXT NOT NULL, `tz` TEXT NOT NULL, `every_ms` INTEGER NOT NULL, `at_ms` INTEGER NOT NULL, " +
                        "`enabled` INTEGER NOT NULL, `channel_id` TEXT, `model` TEXT, `next_run` INTEGER NOT NULL, `last_run` INTEGER NOT NULL, " +
                        "`last_status` TEXT NOT NULL, `last_error` TEXT NOT NULL, `builtin` TEXT, `created` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `automation_runs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `automation_id` TEXT NOT NULL, " +
                        "`started` INTEGER NOT NULL, `finished` INTEGER NOT NULL, `status` TEXT NOT NULL, `session_id` TEXT, `message_id` INTEGER, " +
                        "`summary` TEXT NOT NULL, FOREIGN KEY(`automation_id`) REFERENCES `automations`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_automation_runs_automation_id` ON `automation_runs` (`automation_id`)")
            }
        }

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

        private val migrations get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9)

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app.db").addMigrations(*migrations).build().also { instance = it }
        }

        /**
         * Opens the database file [file] (a backup's, unpacked) as the app would, runs its migrations and Room's schema
         * check, and closes it. Throws when the app couldn't open it, so a foreign or damaged file is refused before it
         * replaces anything, instead of failing on every start after.
         */
        fun checkAt(context: Context, file: java.io.File) {
            val db = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, file.absolutePath)
                .addMigrations(*migrations)
                .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
                .build()
            try {
                db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM sqlite_master").use { it.moveToFirst() }
            } finally {
                db.close()
            }
        }
    }
}
