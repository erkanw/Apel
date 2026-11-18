package io.github.erkanw.vault.userdb

import android.content.Context
import io.github.erkanw.vault.store.AppDatabase
import io.github.erkanw.vault.store.DbAuditEntity
import io.github.erkanw.vault.store.UserDbEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

/** The registry and the activity log in Room (`user_dbs`, `db_audit`). */
class RoomDbRegistry(context: Context) : DbRegistry {
    private val dao = AppDatabase.get(context).userDbs()

    override fun all() = dao.all().map { it.toUserDb() }
    override fun get(name: String) = dao.get(name)?.toUserDb()
    override fun put(db: UserDb) {
        dao.put(UserDbEntity(db.name, db.description, db.access.id, db.mayCreateTables, db.created, db.archivedFile))
        UserDbs.changed()
    }
    override fun remove(name: String) {
        dao.remove(name)
        UserDbs.changed()
    }
    override fun audit(entry: DbAudit) {
        dao.audit(DbAuditEntity(time = entry.time, db = entry.db, actor = entry.actor, action = entry.action, statement = entry.statement, rows = entry.rows))
        if (entry.time % 50L == 0L) dao.trimAudit()
        UserDbs.changed()
    }
    override fun recentAudit(db: String?, limit: Int) = dao.recentAudit(db, limit).map { DbAudit(it.time, it.db, it.actor, it.action, it.statement, it.rows) }

    private fun UserDbEntity.toUserDb() = UserDb(name, description, Access.of(agentAccess), mayCreateTables, created, archivedFile)
}

/** The app's user databases: files in `files/dbs/` (outside the workspace, so file tools can't reach them). */
object UserDbs {
    @Volatile private var core: UserDbCore? = null

    fun core(context: Context): UserDbCore = core ?: synchronized(this) {
        core ?: UserDbCore(File(context.applicationContext.filesDir, "dbs"), RoomDbRegistry(context.applicationContext)).also { core = it }
    }

    /** Ticks on every change, so pages showing databases read them again. */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version.asStateFlow()
    fun changed() = _version.update { it + 1 }
}
