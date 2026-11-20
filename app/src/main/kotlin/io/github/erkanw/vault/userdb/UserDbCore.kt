package io.github.erkanw.vault.userdb

import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.os.CancellationSignal
import android.os.OperationCanceledException
import java.io.File
import java.io.Writer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** How far the agent may go in a database (PLAN §6.10). */
enum class Access(val id: String, val label: String) {
    None("none", "None"), Read("read", "Read"), ReadWrite("write", "Read and write");

    companion object {
        fun of(id: String?): Access = entries.firstOrNull { it.id == id } ?: None
    }
}

/** A database in the registry. [archivedFile] is set while it is archived (its file then sits in `archive/`). */
data class UserDb(
    val name: String,
    val description: String,
    val access: Access,
    val mayCreateTables: Boolean,
    val created: Long,
    val archivedFile: String? = null,
)

/** One change made to a database, by the agent or by you, for its activity list. */
data class DbAudit(val time: Long, val db: String, val actor: String, val action: String, val statement: String, val rows: Int)

/** Where the registry and the activity log are kept: Room in the app, memory in tests. */
interface DbRegistry {
    fun all(): List<UserDb>
    fun get(name: String): UserDb?
    fun put(db: UserDb)
    fun remove(name: String)
    fun audit(entry: DbAudit)
    fun recentAudit(db: String?, limit: Int): List<DbAudit>
}

/** Who runs a call: the agent (strict) or you in the app. */
enum class Actor(val id: String) { Agent("agent"), Page("you") }

class QueryResult(val columns: List<String>, val rows: List<List<Any?>>, val truncated: Boolean)
class ExecResult(val changed: Int, val applied: Boolean, val columns: List<String>, val rows: List<List<Any?>>)
class BrowseRow(val rowid: Long?, val values: List<Any?>)
class BrowseResult(val columns: List<Column>, val rows: List<BrowseRow>, val total: Int, val editable: Boolean)
class Column(val name: String, val type: String, val notNull: Boolean, val primaryKey: Boolean)
class Table(val name: String, val columns: List<Column>, val rows: Int, val indexes: List<String>, val withoutRowid: Boolean)
class Schema(val tables: List<Table>, val bytes: Long)
class ColumnDef(val name: String, val type: String, val primaryKey: Boolean = false, val notNull: Boolean = false)
class Backup(val file: String, val time: Long, val bytes: Long)

/**
 * The user's own SQLite databases (PLAN §9): files in [home], the registry in [registry]. Every call is blocking (run it
 * off the main thread) and throws [DbException] with a sentence to show. Agent reads run on a read-only connection with
 * `query_only`, one statement, 500 rows, 200 KB, 3 seconds; agent writes are checked by [SqlRules], run in a transaction,
 * rolled back past 200 changed rows or 10 MB of growth, stopped after 8 seconds, backed up first (once per 10 minutes) and
 * logged. You have full control in the app, with a backup before every change.
 */
class UserDbCore(val home: File, private val registry: DbRegistry, private val clock: () -> Long = System::currentTimeMillis) {
    companion object {
        const val MAX_ROWS = 500
        const val MAX_OUT_BYTES = 200_000
        const val AGENT_MAX_CHANGES = 200
        const val MAX_PARAMS = 100
        const val MAX_VALUE = 1_000_000
        const val KEEP_BACKUPS = 40
        const val AGENT_BACKUP_GAP_MS = 10 * 60_000L
        const val ROW_EDIT_BACKUP_GAP_MS = 60_000L
        const val MAX_GROWTH_BYTES = 10L * 1024 * 1024
        private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "userdb-timer").apply { isDaemon = true } }
    }

    /** Seconds before a statement is stopped; tests shorten them. */
    var querySeconds = 3.0
    var writeSeconds = 8.0

    private val locks = ConcurrentHashMap<String, Any>()
    private fun lock(name: String) = locks.getOrPut(name) { Any() }

    // Every path is built from a checked name: registry rows can come from a restored backup, so they are never trusted.
    private fun file(name: String) = File(home, "${SqlRules.checkDbName(name)}.db")
    private val archiveDir get() = File(home, "archive")
    private fun backupDir(name: String) = File(home, "backups/${SqlRules.checkDbName(name)}").apply { mkdirs() }
    private fun archivedFile(name: String): File {
        val stored = registry.get(name)?.archivedFile ?: throw DbException("“$name” isn’t archived.")
        if (!SqlRules.isArchivedFileName(stored)) throw DbException("The archived file of “$name” has a name this app doesn’t make.")
        return File(archiveDir, stored)
    }

    // ---- the registry ----

    fun list(includeArchived: Boolean = false): List<UserDb> = registry.all()
        .filter { SqlRules.isDbName(it.name) && (it.archivedFile == null || SqlRules.isArchivedFileName(it.archivedFile)) }
        .filter { includeArchived || it.archivedFile == null }
        .sortedBy { it.name }

    fun agentVisible(): List<UserDb> = list().filter { it.access != Access.None }

    private fun entry(name: String): UserDb {
        SqlRules.checkDbName(name)
        val db = registry.get(name)?.takeIf { it.archivedFile == null } ?: throw DbException("There is no database called “$name”.")
        if (!file(name).isFile) throw DbException("The file of “$name” is missing.")
        return db
    }

    fun create(name: String, description: String = ""): UserDb {
        SqlRules.checkDbName(name)
        registry.get(name)?.let { throw DbException(if (it.archivedFile != null) "An archived database is called “$name”: restore it or delete it first." else "There is already a database called “$name”.") }
        home.mkdirs()
        val f = file(name)
        if (f.exists()) throw DbException("A file called “$name.db” is already there.")
        SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use { it.enableWriteAheadLogging() }
        val db = UserDb(name, description.trim().take(200), Access.None, false, clock())
        registry.put(db)
        registry.audit(DbAudit(clock(), name, Actor.Page.id, "create", "", 0))
        return db
    }

    fun setMeta(name: String, description: String? = null, access: Access? = null, mayCreateTables: Boolean? = null): UserDb {
        val db = entry(name)
        val next = db.copy(
            description = description?.trim()?.take(200) ?: db.description,
            access = access ?: db.access,
            mayCreateTables = mayCreateTables ?: db.mayCreateTables,
        )
        registry.put(next)
        return next
    }

    /** Moves the database aside (restorable): its file to `archive/`, its name kept in the registry as archived. */
    fun archive(name: String) = synchronized(lock(name)) {
        val db = entry(name)
        checkpoint(name)
        archiveDir.mkdirs()
        val target = File(archiveDir, "$name-${stamp()}.db")
        if (!file(name).renameTo(target)) throw DbException("Couldn’t move “$name” aside.")
        listOf("-wal", "-shm").forEach { File(home, "$name.db$it").delete() }
        registry.put(db.copy(archivedFile = target.name, access = Access.None))
        registry.audit(DbAudit(clock(), name, Actor.Page.id, "archive", "", 0))
    }

    fun restoreArchived(name: String) = synchronized(lock(name)) {
        SqlRules.checkDbName(name)
        val db = registry.get(name)?.takeIf { it.archivedFile != null } ?: throw DbException("“$name” isn’t archived.")
        val from = archivedFile(name)
        if (!from.isFile) throw DbException("The archived file of “$name” is missing.")
        if (file(name).exists()) throw DbException("A database file called “$name.db” is already there.")
        if (!from.renameTo(file(name))) throw DbException("Couldn’t restore “$name”.")
        registry.put(db.copy(archivedFile = null))
        registry.audit(DbAudit(clock(), name, Actor.Page.id, "restore", "", 0))
    }

    /** Deletes an archived database for good: its file, its backups and its registry entry. */
    fun deleteArchived(name: String) = synchronized(lock(name)) {
        SqlRules.checkDbName(name)
        registry.get(name)?.takeIf { it.archivedFile != null } ?: throw DbException("Only an archived database can be deleted.")
        archivedFile(name).delete()
        backupDir(name).deleteRecursively()
        registry.remove(name)
    }

    // ---- connections ----

    private fun open(name: String, readOnly: Boolean): SQLiteDatabase {
        val db = try {
            SQLiteDatabase.openDatabase(file(name).path, null, (if (readOnly) SQLiteDatabase.OPEN_READONLY else SQLiteDatabase.OPEN_READWRITE) or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e: SQLiteException) {
            throw DbException("Couldn’t open “$name”: ${e.message?.take(200)}")
        }
        if (readOnly) db.rawQuery("PRAGMA query_only = ON", null).use { it.count } else {
            db.enableWriteAheadLogging()
            db.setForeignKeyConstraintsEnabled(true)
        }
        return db
    }

    private inline fun <T> withTimer(seconds: Double, block: (CancellationSignal) -> T): T {
        val signal = CancellationSignal()
        val task = timer.schedule({ signal.cancel() }, (seconds * 1000).toLong(), TimeUnit.MILLISECONDS)
        try {
            return block(signal)
        } finally {
            task.cancel(false)
        }
    }

    private fun cleanParams(params: List<Any?>?): List<Any?> {
        if (params == null) return emptyList()
        if (params.size > MAX_PARAMS) throw DbException("Parameters must be a list of at most $MAX_PARAMS values.")
        return params.map { p ->
            when (p) {
                null, is String, is Long, is Double -> p
                is Int -> p.toLong()
                is Boolean -> if (p) 1L else 0L
                is Number -> p.toDouble()
                else -> throw DbException("Parameters must be text, numbers or null.")
            }.also { if (it is String && it.length > MAX_VALUE) throw DbException("A parameter is longer than ${MAX_VALUE / 1000} KB.") }
        }
    }

    /** A cursor for [sql] with typed [params], stoppable with [signal]. Running it (moving or counting) runs the statement. */
    private fun cursor(db: SQLiteDatabase, sql: String, params: List<Any?>, signal: CancellationSignal?): Cursor =
        db.rawQueryWithFactory({ _, driver, editTable, query ->
            params.forEachIndexed { i, p ->
                when (p) {
                    null -> query.bindNull(i + 1)
                    is Long -> query.bindLong(i + 1, p)
                    is Double -> query.bindDouble(i + 1, p)
                    else -> query.bindString(i + 1, p.toString())
                }
            }
            SQLiteCursor(driver, editTable, query)
        }, sql, null, "", signal)

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_NULL -> null
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).let { if (it.isNaN() || it.isInfinite()) it.toString() else it }
        Cursor.FIELD_TYPE_BLOB -> "<blob ${c.getBlob(i).size} bytes>"
        else -> c.getString(i)
    }

    private fun collect(c: Cursor): QueryResult {
        val cols = c.columnNames.toList()
        val rows = ArrayList<List<Any?>>()
        var size = 0
        var truncated = false
        while (c.moveToNext()) {
            val row = (0 until c.columnCount).map { value(c, it) }
            size += row.sumOf { (it?.toString()?.length ?: 4) + 3 }
            if (rows.size >= MAX_ROWS || size > MAX_OUT_BYTES) { truncated = true; break }
            rows += row
        }
        return QueryResult(cols, rows, truncated)
    }

    private fun translate(e: Exception): DbException = when {
        e is DbException -> e
        e is OperationCanceledException -> DbException("The statement took too long and was stopped.")
        e.message?.contains("locked", true) == true || e.message?.contains("busy", true) == true -> DbException("The database is busy. Try again in a moment.")
        e.message?.contains("CursorWindow", true) == true || e.message?.contains("too big", true) == true || e is OutOfMemoryError ->
            DbException("A value is too big to read here.")
        else -> DbException("SQL error: ${e.message?.substringBefore(" (code")?.take(300) ?: e.javaClass.simpleName}")
    }

    // ---- reads and writes ----

    /** One reading statement. For the agent the database must allow at least Read. */
    fun query(name: String, sql: String, params: List<Any?>? = null, actor: Actor = Actor.Agent): QueryResult {
        val db = entry(name)
        if (actor == Actor.Agent && db.access == Access.None) throw DbException("The database “$name” isn’t available to you.")
        SqlRules.check(sql, SqlRules.Mode.Read)
        val args = cleanParams(params)
        val conn = open(name, readOnly = true)
        try {
            return withTimer(querySeconds) { signal -> cursor(conn, sql.trim(), args, signal).use { collect(it) } }
        } catch (e: Exception) {
            throw translate(e)
        } catch (e: OutOfMemoryError) {
            throw DbException("A value is too big to read here.")
        } finally {
            conn.close()
        }
    }

    /**
     * One changing statement. [apply] false runs it and rolls back (to see how many rows it would change). From the app
     * every change backs up first, except [quick] row edits, which share one backup a minute.
     */
    fun execute(name: String, sql: String, params: List<Any?>? = null, actor: Actor = Actor.Agent, apply: Boolean = true, quick: Boolean = false): ExecResult {
        val db = entry(name)
        val kind = if (actor == Actor.Agent) {
            if (db.access != Access.ReadWrite) throw DbException("The database “$name” isn’t open for changes by you.")
            SqlRules.check(sql, SqlRules.Mode.AgentWrite, db.mayCreateTables)
        } else SqlRules.check(sql, SqlRules.Mode.PageWrite)
        val args = cleanParams(params)
        synchronized(lock(name)) {
            if (apply) backup(name, "before a change by ${actor.id}", force = actor == Actor.Page && !quick, gapMs = if (quick) ROW_EDIT_BACKUP_GAP_MS else AGENT_BACKUP_GAP_MS)
            val conn = open(name, readOnly = false)
            try {
                conn.beginTransaction()
                try {
                    val pagesBefore = pageCount(conn)
                    val result = withTimer(writeSeconds) { signal -> cursor(conn, sql.trim(), args, signal).use { collect(it) } }
                    val changed = if (kind == SqlRules.Kind.Read) 0 else conn.rawQuery("SELECT changes()", null).use { it.moveToFirst(); it.getInt(0) }
                    if (actor == Actor.Agent && changed > AGENT_MAX_CHANGES) {
                        throw DbException("That statement would change $changed rows; the limit for you is $AGENT_MAX_CHANGES. Nothing was changed.")
                    }
                    if (actor == Actor.Agent && (pageCount(conn) - pagesBefore) * pageSize(conn) > MAX_GROWTH_BYTES) {
                        throw DbException("That change would add more than ${MAX_GROWTH_BYTES / 1024 / 1024} MB. Nothing was changed.")
                    }
                    if (apply) conn.setTransactionSuccessful()
                    if (apply && kind != SqlRules.Kind.Read) registry.audit(DbAudit(clock(), name, actor.id, "execute", sql.trim().take(300), changed))
                    return ExecResult(changed, apply, result.columns, result.rows)
                } finally {
                    conn.endTransaction()
                }
            } catch (e: Exception) {
                throw translate(e)
            } finally {
                conn.close()
            }
        }
    }

    private fun pageCount(conn: SQLiteDatabase) = conn.rawQuery("PRAGMA page_count", null).use { it.moveToFirst(); it.getLong(0) }
    private fun pageSize(conn: SQLiteDatabase) = conn.rawQuery("PRAGMA page_size", null).use { it.moveToFirst(); it.getLong(0) }

    // ---- the app's own access (names checked, then quoted) ----

    private inline fun <T> internal(name: String, write: Boolean = false, block: (SQLiteDatabase) -> T): T {
        entry(name)
        val conn = open(name, readOnly = !write)
        try {
            return block(conn)
        } catch (e: Exception) {
            throw translate(e)
        } finally {
            conn.close()
        }
    }

    fun schema(name: String): Schema = internal(name) { conn ->
        val tables = conn.rawQuery("SELECT name, sql FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%' AND name != 'android_metadata' ORDER BY name", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0) to (c.getString(1) ?: "")) }
        }
        Schema(
            tables.map { (t, ddl) ->
                val cols = conn.rawQuery("PRAGMA table_info(${SqlRules.q(t)})", null).use { c ->
                    buildList { while (c.moveToNext()) add(Column(c.getString(1), c.getString(2) ?: "", c.getInt(3) == 1, c.getInt(5) > 0)) }
                }
                val indexes = conn.rawQuery("PRAGMA index_list(${SqlRules.q(t)})", null).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(1)) }
                }.filterNot { it.startsWith("sqlite_autoindex") }
                val rows = conn.rawQuery("SELECT COUNT(*) FROM ${SqlRules.q(t)}", null).use { it.moveToFirst(); it.getInt(0) }
                Table(t, cols, rows, indexes, Regex("WITHOUT\\s+ROWID", RegexOption.IGNORE_CASE).containsMatchIn(ddl))
            },
            file(name).length() + File(home, "$name.db-wal").length(),
        )
    }

    private fun table(name: String, table: String): Table {
        SqlRules.checkIdent(table, "table name")
        return schema(name).tables.firstOrNull { it.name == table } ?: throw DbException("There is no table “$table” in “$name”.")
    }

    /** A page of [table]: [size] rows from [page] (from 0), sorted by [sort], filtered by [search] (plain text, in any column). */
    fun browse(name: String, table: String, page: Int = 0, size: Int = 50, sort: String? = null, descending: Boolean = false, search: String = ""): BrowseResult {
        val t = table(name, table)
        val names = t.columns.map { it.name }
        if (sort != null && sort !in names) throw DbException("There is no column “$sort”.")
        val where = if (search.isBlank()) "" else " WHERE " + names.joinToString(" OR ") { "CAST(${SqlRules.q(it)} AS TEXT) LIKE ? ESCAPE '\\'" }
        val like = "%" + search.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
        val args = if (search.isBlank()) emptyList() else names.map { like }
        val editable = !t.withoutRowid
        return internal(name) { conn ->
            val total = cursor(conn, "SELECT COUNT(*) FROM ${SqlRules.q(table)}$where", args, null).use { it.moveToFirst(); it.getInt(0) }
            val order = sort?.let { " ORDER BY ${SqlRules.q(it)} ${if (descending) "DESC" else "ASC"}" } ?: if (editable) " ORDER BY rowid" else ""
            val select = (if (editable) "rowid AS __rowid, " else "") + names.joinToString(", ") { SqlRules.q(it) }
            val rows = cursor(conn, "SELECT $select FROM ${SqlRules.q(table)}$where$order LIMIT ${size.coerceIn(1, 200)} OFFSET ${(page.coerceAtLeast(0) * size.coerceIn(1, 200))}", args, null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        if (editable) add(BrowseRow(c.getLong(0), (1 until c.columnCount).map { value(c, it) }))
                        else add(BrowseRow(null, (0 until c.columnCount).map { value(c, it) }))
                    }
                }
            }
            BrowseResult(t.columns, rows, total, editable)
        }
    }

    private fun rowValues(t: Table, values: Map<String, Any?>): Map<String, Any?> {
        val have = t.columns.map { it.name }.toSet()
        values.keys.firstOrNull { it !in have }?.let { throw DbException("There is no column “$it” in “${t.name}”.") }
        if (values.isEmpty()) throw DbException("Give at least one value.")
        return values
    }

    fun insertRow(name: String, table: String, values: Map<String, Any?>) {
        val t = table(name, table)
        val v = rowValues(t, values)
        execute(name, "INSERT INTO ${SqlRules.q(table)} (${v.keys.joinToString(", ") { SqlRules.q(it) }}) VALUES (${v.keys.joinToString(", ") { "?" }})", v.values.toList(), Actor.Page, quick = true)
    }

    fun updateRow(name: String, table: String, rowid: Long, values: Map<String, Any?>) {
        val t = table(name, table)
        if (t.withoutRowid) throw DbException("Rows of this table can’t be edited here.")
        val v = rowValues(t, values)
        val r = execute(name, "UPDATE ${SqlRules.q(table)} SET ${v.keys.joinToString(", ") { "${SqlRules.q(it)} = ?" }} WHERE rowid = ?", v.values.toList() + rowid, Actor.Page, quick = true)
        if (r.changed == 0) throw DbException("That row is gone.")
    }

    fun deleteRow(name: String, table: String, rowid: Long) {
        val t = table(name, table)
        if (t.withoutRowid) throw DbException("Rows of this table can’t be deleted here.")
        val r = execute(name, "DELETE FROM ${SqlRules.q(table)} WHERE rowid = ?", listOf(rowid), Actor.Page, quick = true)
        if (r.changed == 0) throw DbException("That row is gone.")
    }

    fun createTable(name: String, table: String, columns: List<ColumnDef>) {
        SqlRules.checkIdent(table, "table name")
        if (columns.isEmpty()) throw DbException("A table needs at least one column.")
        if (columns.map { it.name.lowercase() }.toSet().size != columns.size) throw DbException("Column names must be different.")
        val pks = columns.count { it.primaryKey }
        val defs = columns.joinToString(", ") { c ->
            SqlRules.checkIdent(c.name, "column name")
            val type = SqlRules.checkType(c.type)
            buildString {
                append(SqlRules.q(c.name)).append(' ').append(type)
                if (c.primaryKey && pks == 1) append(" PRIMARY KEY")
                if (c.notNull) append(" NOT NULL")
            }
        }
        val pk = if (pks > 1) ", PRIMARY KEY (${columns.filter { it.primaryKey }.joinToString(", ") { SqlRules.q(it.name) }})" else ""
        execute(name, "CREATE TABLE ${SqlRules.q(table)} ($defs$pk)", actor = Actor.Page)
    }

    fun addColumn(name: String, table: String, column: String, type: String) {
        table(name, table)
        execute(name, "ALTER TABLE ${SqlRules.q(table)} ADD COLUMN ${SqlRules.q(SqlRules.checkIdent(column, "column name"))} ${SqlRules.checkType(type)}", actor = Actor.Page)
    }

    fun createIndex(name: String, table: String, columns: List<String>, unique: Boolean = false) {
        val t = table(name, table)
        if (columns.isEmpty()) throw DbException("Choose at least one column.")
        columns.firstOrNull { c -> t.columns.none { it.name == c } }?.let { throw DbException("There is no column “$it”.") }
        val index = SqlRules.checkIdent(("idx_${table}_${columns.joinToString("_")}").take(63), "index name")
        execute(name, "CREATE ${if (unique) "UNIQUE " else ""}INDEX ${SqlRules.q(index)} ON ${SqlRules.q(table)} (${columns.joinToString(", ") { SqlRules.q(it) }})", actor = Actor.Page)
    }

    fun dropTable(name: String, table: String) {
        table(name, table)
        execute(name, "DROP TABLE ${SqlRules.q(table)}", actor = Actor.Page)
    }

    // ---- CSV ----

    /** Writes [table] as CSV to [out]; the number of rows. */
    fun exportCsv(name: String, table: String, out: Writer): Int {
        val t = table(name, table)
        val names = t.columns.map { it.name }
        return internal(name) { conn ->
            out.write(Csv.line(names) + "\n")
            var n = 0
            conn.rawQuery("SELECT ${names.joinToString(", ") { SqlRules.q(it) }} FROM ${SqlRules.q(table)}", null).use { c ->
                while (c.moveToNext()) {
                    out.write(Csv.line((0 until c.columnCount).map { i -> value(c, i)?.let { Csv.safe(it.toString()) } ?: "" }) + "\n")
                    n++
                }
            }
            out.flush()
            n
        }
    }

    /** Adds the rows of CSV [text] to [table], making the table (TEXT, INTEGER, REAL columns) when it isn't there. */
    fun importCsv(name: String, table: String, text: String): Pair<Int, Boolean> {
        SqlRules.checkIdent(table, "table name")
        val csv = Csv.prepare(text)
        val exists = schema(name).tables.any { it.name == table }
        if (!exists) createTable(name, table, csv.header.zip(csv.types).map { (h, t) -> ColumnDef(h, t) })
        val t = table(name, table)
        val have = t.columns.map { it.name }.toSet()
        csv.header.filter { it !in have }.takeIf { it.isNotEmpty() }?.let { throw DbException("The CSV has columns the table doesn’t: ${it.joinToString(", ")}.") }
        synchronized(lock(name)) {
            backup(name, "before a CSV import", force = true)
            val conn = open(name, readOnly = false)
            try {
                conn.beginTransaction()
                try {
                    val stmt = conn.compileStatement("INSERT INTO ${SqlRules.q(table)} (${csv.header.joinToString(", ") { SqlRules.q(it) }}) VALUES (${csv.header.joinToString(", ") { "?" }})")
                    for (r in csv.rows) {
                        stmt.clearBindings()
                        r.forEachIndexed { i, v -> if (v.isEmpty()) stmt.bindNull(i + 1) else stmt.bindString(i + 1, v) }
                        stmt.executeInsert()
                    }
                    conn.setTransactionSuccessful()
                } finally {
                    conn.endTransaction()
                }
            } catch (e: Exception) {
                throw translate(e)
            } finally {
                conn.close()
            }
        }
        registry.audit(DbAudit(clock(), name, Actor.Page.id, "csv-import", "$table: ${csv.rows.size} rows", csv.rows.size))
        return csv.rows.size to !exists
    }

    // ---- backups ----

    fun backups(name: String): List<Backup> = backupDir(name).listFiles().orEmpty().filter { it.name.endsWith(".db") }
        .map { Backup(it.name, it.lastModified(), it.length()) }.sortedByDescending { it.file }

    /** Copies the database aside; [force] even if one was made within [gapMs]. Keeps the newest [KEEP_BACKUPS]. */
    fun backup(name: String, reason: String = "", force: Boolean = false, gapMs: Long = AGENT_BACKUP_GAP_MS) = synchronized(lock(name)) {
        entry(name)
        val latest = backups(name).firstOrNull()
        if (!force && latest != null && clock() - latest.time < gapMs) return@synchronized
        checkpoint(name)
        // A counter after the time, so backups made in the same second still sort newest first by name.
        var n = 1
        var target = File(backupDir(name), "$name-${stamp()}-%03d.db".format(n))
        while (target.exists()) target = File(backupDir(name), "$name-${stamp()}-%03d.db".format(++n))
        file(name).copyTo(target)
        target.setLastModified(clock())
        backups(name).drop(KEEP_BACKUPS).forEach { File(backupDir(name), it.file).delete() }
    }

    /** Puts [backupFile] back (the current state is backed up first). Only a file from this database's backup list. */
    fun restore(name: String, backupFile: String) = synchronized(lock(name)) {
        entry(name)
        val b = backups(name).firstOrNull { it.file == backupFile } ?: throw DbException("There is no backup “$backupFile”.")
        backup(name, "before a restore", force = true)
        checkpoint(name)
        File(backupDir(name), b.file).copyTo(file(name), overwrite = true)
        listOf("-wal", "-shm").forEach { File(home, "$name.db$it").delete() }
        registry.audit(DbAudit(clock(), name, Actor.Page.id, "restore-backup", b.file, 0))
    }

    /** Folds the WAL into the main file, so the file alone is the whole database. */
    private fun checkpoint(name: String) {
        if (!file(name).isFile) return
        val conn = SQLiteDatabase.openDatabase(file(name).path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        try {
            conn.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.count }
        } finally {
            conn.close()
        }
    }

    fun recentAudit(name: String? = null, limit: Int = 100): List<DbAudit> = registry.recentAudit(name, limit)

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date(clock()))
}
