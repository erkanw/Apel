package io.github.erkanw.vault

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.userdb.Access
import io.github.erkanw.vault.userdb.Actor
import io.github.erkanw.vault.userdb.Backup
import io.github.erkanw.vault.userdb.BrowseResult
import io.github.erkanw.vault.userdb.ColumnDef
import io.github.erkanw.vault.userdb.Csv
import io.github.erkanw.vault.userdb.DbAudit
import io.github.erkanw.vault.userdb.DbException
import io.github.erkanw.vault.userdb.QueryResult
import io.github.erkanw.vault.userdb.Schema
import io.github.erkanw.vault.userdb.SqlRules
import io.github.erkanw.vault.userdb.UserDb
import io.github.erkanw.vault.userdb.UserDbs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Databases pages (PLAN §6.10): the registry, one database, a table, the SQL console, backups and activity. Every
 * call goes to [io.github.erkanw.vault.userdb.UserDbCore] off the main thread; a refusal or failure becomes [note].
 * Kept apart from [PagesViewModel], which is long already; it is the same "pages" area.
 */
class DatabasesViewModel(app: Application) : AndroidViewModel(app) {
    private val core get() = UserDbs.core(getApplication())

    /** A database with what its row on the list shows. */
    data class DbRow(val db: UserDb, val tables: Int, val bytes: Long)

    private val _dbs = MutableStateFlow<List<DbRow>?>(null)
    val dbs: StateFlow<List<DbRow>?> = _dbs.asStateFlow()
    private val _archived = MutableStateFlow<List<UserDb>>(emptyList())
    val archived: StateFlow<List<UserDb>> = _archived.asStateFlow()

    private val _schema = MutableStateFlow<Schema?>(null)
    val schema: StateFlow<Schema?> = _schema.asStateFlow()
    private val _page = MutableStateFlow<BrowseResult?>(null)
    val page: StateFlow<BrowseResult?> = _page.asStateFlow()
    /** Null until loaded, so an empty list isn't shown before the real one. */
    private val _backups = MutableStateFlow<List<Backup>?>(null)
    val backups: StateFlow<List<Backup>?> = _backups.asStateFlow()
    private val _activity = MutableStateFlow<List<DbAudit>?>(null)
    val activity: StateFlow<List<DbAudit>?> = _activity.asStateFlow()

    /** One line to show: what was done, or why it couldn't be. */
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()
    fun clearNote() { _note.value = null }

    init {
        viewModelScope.launch { UserDbs.version.collect { refreshList() } }
    }

    /** Runs [work] off the main thread; a [DbException] (or any failure) becomes the note. True when it worked. */
    private suspend fun <T> io(work: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(work())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _note.value = e.message ?: "That didn’t work."
            Result.failure(e)
        }
    }

    private fun act(done: String? = null, after: (() -> Unit)? = null, work: () -> Unit) {
        viewModelScope.launch {
            if (io(work).isSuccess) {
                done?.let { _note.value = it }
                after?.invoke()
            }
        }
    }

    fun refreshList() {
        viewModelScope.launch {
            io {
                val rows = core.list().map { db ->
                    val s = runCatching { core.schema(db.name) }.getOrNull()
                    DbRow(db, s?.tables?.size ?: 0, s?.bytes ?: 0L)
                }
                _dbs.value = rows
                _archived.value = core.list(includeArchived = true).filter { it.archivedFile != null }
            }
        }
    }

    fun create(name: String, description: String, access: Access, mayCreateTables: Boolean, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching {
                    core.create(name.trim(), description)
                    core.setMeta(name.trim(), access = access, mayCreateTables = mayCreateTables && access == Access.ReadWrite)
                }
            }
            onDone(r.exceptionOrNull()?.message)
        }
    }

    fun setAccess(name: String, access: Access, mayCreateTables: Boolean) =
        act { core.setMeta(name, access = access, mayCreateTables = mayCreateTables && access == Access.ReadWrite) }

    fun setDescription(name: String, description: String) = act { core.setMeta(name, description = description) }
    fun archive(name: String) = act("Archived $name. Restore it from the end of the list.") { core.archive(name) }
    fun restoreArchived(name: String) = act("Restored $name.") { core.restoreArchived(name) }
    fun deleteArchived(name: String) = act("Deleted $name for good.") { core.deleteArchived(name) }

    // ---- one database ----

    fun loadSchema(name: String) {
        viewModelScope.launch { io { core.schema(name) }.onSuccess { _schema.value = it } }
    }

    fun createTable(name: String, table: String, columns: List<ColumnDef>, onDone: (String?) -> Unit) = sheetCall(onDone, { loadSchema(name) }) { core.createTable(name, table, columns) }
    fun addColumn(name: String, table: String, column: String, type: String, onDone: (String?) -> Unit) = sheetCall(onDone, { loadSchema(name) }) { core.addColumn(name, table, column, type) }
    fun createIndex(name: String, table: String, columns: List<String>, unique: Boolean, onDone: (String?) -> Unit) = sheetCall(onDone, { loadSchema(name) }) { core.createIndex(name, table, columns, unique) }
    fun dropTable(name: String, table: String, onDone: () -> Unit) = act("Deleted the table $table. A backup was made first.", { loadSchema(name); onDone() }) { core.dropTable(name, table) }

    /** For a sheet: [onDone] gets null, or why it failed (the sheet shows it and stays open). */
    private fun sheetCall(onDone: (String?) -> Unit, after: () -> Unit, work: () -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { work() } }
            onDone(r.exceptionOrNull()?.message)
            if (r.isSuccess) after()
        }
    }

    // ---- a table ----

    data class PageQuery(val page: Int = 0, val sort: String? = null, val descending: Boolean = false, val search: String = "")

    fun browse(name: String, table: String, q: PageQuery) {
        viewModelScope.launch { io { core.browse(name, table, q.page, 50, q.sort, q.descending, q.search) }.onSuccess { _page.value = it } }
    }

    fun clearPage() { _page.value = null }

    /** Values typed in a row sheet: text as typed; an empty field is null. */
    private fun typed(values: Map<String, String>) = values.mapValues { (_, v) -> v.ifEmpty { null } }

    fun insertRow(name: String, table: String, values: Map<String, String>, onDone: (String?) -> Unit) = sheetCall(onDone, {}) {
        core.insertRow(name, table, typed(values).filterValues { it != null })
    }

    fun updateRow(name: String, table: String, rowid: Long, values: Map<String, String>, onDone: (String?) -> Unit) = sheetCall(onDone, {}) {
        if (values.isNotEmpty()) core.updateRow(name, table, rowid, typed(values))
    }

    fun deleteRow(name: String, table: String, rowid: Long, onDone: () -> Unit) = act("Deleted the row.", onDone) { core.deleteRow(name, table, rowid) }

    // ---- CSV ----

    /** Reads a CSV the user picked (at most 5 MB); null and a note when it can't be read. */
    suspend fun readCsv(uri: Uri): Pair<String, String>? = io {
        val app = getApplication<Application>()
        val name = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "import.csv"
        val bytes = app.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buffer.write(chunk, 0, n)
                if (buffer.size() > Csv.MAX_BYTES) throw DbException("The CSV is larger than ${Csv.MAX_BYTES / 1024 / 1024} MB.")
            }
            buffer.toByteArray()
        } ?: throw DbException("Couldn’t read that file.")
        name to String(bytes, Charsets.UTF_8).removePrefix("﻿")
    }.getOrNull()

    fun importCsv(name: String, table: String, text: String, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { core.importCsv(name, table, text) } }
            r.onSuccess { (n, created) -> _note.value = "Imported $n row${if (n == 1) "" else "s"} into ${if (created) "a new table" else "the table"} $table."; loadSchema(name) }
            onDone(r.exceptionOrNull()?.message)
        }
    }

    fun exportCsv(name: String, table: String) = act {
        val app = getApplication<Application>()
        var rows = 0
        saveInDownloads(app, "$name-$table.csv", "text/csv") { out ->
            val w = out.bufferedWriter()
            rows = core.exportCsv(name, table, w)
        }
        _note.value = "Saved $rows row${if (rows == 1) "" else "s"} as $name-$table.csv in Downloads/Apel."
    }

    // ---- the SQL console ----

    /** What the console shows after Run: a read's result, a write waiting for Confirm, or what a write did. */
    sealed interface Console {
        data class Rows(val result: QueryResult) : Console
        data class Pending(val sql: String, val changes: Int, val schema: Boolean) : Console
        data class Done(val changes: Int, val schema: Boolean) : Console
        data class Failed(val reason: String) : Console
    }

    private val _console = MutableStateFlow<Console?>(null)
    val console: StateFlow<Console?> = _console.asStateFlow()
    fun clearConsole() { _console.value = null }

    /** Runs a read at once; a change runs once without keeping it, to say how many rows it changes, and waits for [confirm]. */
    fun run(name: String, sql: String) {
        viewModelScope.launch {
            _console.value = withContext(Dispatchers.IO) {
                try {
                    when (SqlRules.check(sql, SqlRules.Mode.PageWrite)) {
                        SqlRules.Kind.Read -> Console.Rows(core.query(name, sql, actor = Actor.Page))
                        SqlRules.Kind.Write -> Console.Pending(sql, core.execute(name, sql, actor = Actor.Page, apply = false).changed, schema = false)
                        SqlRules.Kind.Schema -> Console.Pending(sql, 0, schema = true)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Console.Failed(e.message ?: "That didn’t run.")
                }
            }
        }
    }

    fun confirm(name: String) {
        val pending = _console.value as? Console.Pending ?: return
        viewModelScope.launch {
            _console.value = withContext(Dispatchers.IO) {
                try {
                    Console.Done(core.execute(name, pending.sql, actor = Actor.Page).changed, pending.schema)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Console.Failed(e.message ?: "That didn’t run.")
                }
            }
            loadSchema(name)
        }
    }

    // ---- backups and activity ----

    fun loadBackups(name: String) {
        _backups.value = null
        viewModelScope.launch { io { core.backups(name) }.onSuccess { _backups.value = it } }
    }

    fun restore(name: String, file: String) = act("Restored the backup. The state before it was backed up too.", { loadBackups(name); loadSchema(name) }) { core.restore(name, file) }

    fun loadActivity(name: String) {
        _activity.value = null
        viewModelScope.launch { io { core.recentAudit(name, 200) }.onSuccess { _activity.value = it } }
    }
}
