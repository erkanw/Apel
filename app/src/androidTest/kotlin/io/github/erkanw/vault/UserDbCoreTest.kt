package io.github.erkanw.vault

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.erkanw.vault.userdb.Access
import io.github.erkanw.vault.userdb.Actor
import io.github.erkanw.vault.userdb.ColumnDef
import io.github.erkanw.vault.userdb.DbAudit
import io.github.erkanw.vault.userdb.DbException
import io.github.erkanw.vault.userdb.DbRegistry
import io.github.erkanw.vault.userdb.UserDb
import io.github.erkanw.vault.userdb.UserDbCore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.StringWriter

/**
 * The adversarial cases of PLAN §9 on the phone's real SQLite. Each test works in its own folder under the app's cache
 * with a registry in memory, so the app's own databases and Room are never touched. Run with `am instrument` (see
 * private/notes/build-and-emulator.md), not connectedAndroidTest, which uninstalls the app afterwards.
 */
@RunWith(AndroidJUnit4::class)
class UserDbCoreTest {
    private class MemoryRegistry : DbRegistry {
        val dbs = LinkedHashMap<String, UserDb>()
        val log = ArrayList<DbAudit>()
        override fun all() = dbs.values.toList()
        override fun get(name: String) = dbs[name]
        override fun put(db: UserDb) { dbs[db.name] = db }
        override fun remove(name: String) { dbs.remove(name) }
        override fun audit(entry: DbAudit) { log += entry }
        override fun recentAudit(db: String?, limit: Int) = log.filter { db == null || it.db == db }.reversed().take(limit)
    }

    private lateinit var home: File
    private lateinit var reg: MemoryRegistry
    private lateinit var core: UserDbCore
    private var now = 1_800_000_000_000L

    @Before
    fun setUp() {
        home = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "userdb-test-${System.nanoTime()}")
        reg = MemoryRegistry()
        core = UserDbCore(home, reg) { now }
    }

    @After
    fun tearDown() {
        home.deleteRecursively()
    }

    private fun refused(block: () -> Unit, contains: String? = null) {
        try {
            block()
            fail("should be refused")
        } catch (e: DbException) {
            if (contains != null) assertTrue(e.message, e.message!!.contains(contains))
        }
    }

    /** A database "notes" with a table t(id, title, n) and 3 rows. */
    private fun fresh(access: Access = Access.None, ddl: Boolean = false) {
        core.create("notes", "test db")
        core.createTable("notes", "t", listOf(ColumnDef("id", "INTEGER", primaryKey = true), ColumnDef("title", "TEXT"), ColumnDef("n", "INTEGER")))
        for (i in 0 until 3) core.insertRow("notes", "t", mapOf("title" to "row$i", "n" to i.toLong()))
        core.setMeta("notes", access = access, mayCreateTables = ddl)
    }

    private fun count(sql: String = "SELECT COUNT(*) FROM t") = core.query("notes", sql, actor = Actor.Page).rows[0][0]

    // ---- registry ----

    @Test fun namesAreChecked() {
        for (bad in listOf("", "Bad", "1abc", "a-b", "a".repeat(40), "../x", "a.db")) refused({ core.create(bad) })
    }

    @Test fun archiveAndRestore() {
        fresh()
        core.archive("notes")
        assertFalse(File(home, "notes.db").exists())
        assertTrue(core.list().none { it.name == "notes" })
        assertTrue(File(home, "archive").listFiles()!!.any { it.name.startsWith("notes-") })
        refused({ core.create("notes") })
        core.restoreArchived("notes")
        assertEquals(3L, count())
    }

    @Test fun agentVisibilityFollowsTheSetting() {
        fresh(Access.None)
        assertTrue(core.agentVisible().isEmpty())
        refused({ core.query("notes", "SELECT 1") })
        core.setMeta("notes", access = Access.Read)
        assertEquals(listOf("notes"), core.agentVisible().map { it.name })
    }

    // ---- agent reads ----

    @Test fun selectWorksWithTypedParams() {
        fresh(Access.Read)
        val r = core.query("notes", "SELECT title FROM t WHERE n >= ? ORDER BY n", listOf(1))
        assertEquals(listOf(listOf<Any?>("row1"), listOf<Any?>("row2")), r.rows)
        assertEquals(listOf("title"), r.columns)
        assertEquals(1L, core.query("notes", "SELECT ? + 0", listOf(1)).rows[0][0])
    }

    @Test fun nothingThatWritesOrEscapes() {
        fresh(Access.Read)
        for (sql in listOf("INSERT INTO t(title) VALUES('x')", "UPDATE t SET n=9", "DELETE FROM t", "DROP TABLE t", "CREATE TABLE z(a)",
            "ATTACH DATABASE '/etc/passwd' AS e", "PRAGMA table_info(t)", "SELECT load_extension('x')", "VACUUM", "BEGIN",
            "SELECT 1; DROP TABLE t", "REPLACE INTO t(id,title) VALUES(1,'x')")) refused({ core.query("notes", sql) })
        assertEquals(3L, count())
    }

    @Test fun aReadDatabaseRefusesWrites() {
        fresh(Access.Read)
        refused({ core.execute("notes", "INSERT INTO t(title) VALUES('x')") }, "isn’t open for changes")
        assertEquals(3L, count())
    }

    @Test fun badParameters() {
        fresh(Access.Read)
        refused({ core.query("notes", "SELECT ?", listOf(mapOf("a" to 1))) })
        refused({ core.query("notes", "SELECT ?", (0 until 200).toList()) })
    }

    @Test fun rowCapTruncates() {
        fresh(Access.Read)
        val r = core.query("notes", "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<2000) SELECT x FROM c")
        assertTrue(r.truncated)
        assertEquals(UserDbCore.MAX_ROWS, r.rows.size)
    }

    @Test fun hugeValuesAreRefused() {
        fresh(Access.Read)
        refused({ core.query("notes", "SELECT zeroblob(50000000)") })
        refused({ core.query("notes", "SELECT hex(zeroblob(5000000))") })
    }

    @Test fun runawayStatementIsStopped() {
        fresh(Access.Read)
        core.querySeconds = 0.5
        refused({ core.query("notes", "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c) SELECT COUNT(*) FROM c") }, "too long")
    }

    @Test fun blobsAreANote() {
        fresh(Access.Read)
        assertEquals("<blob 10 bytes>", core.query("notes", "SELECT zeroblob(10)").rows[0][0])
    }

    // ---- agent writes ----

    @Test fun insertUpdateDeleteWithABackup() {
        fresh(Access.ReadWrite)
        val r = core.execute("notes", "INSERT INTO t(title, n) VALUES(?, ?)", listOf("from agent", 7))
        assertEquals(1, r.changed)
        assertTrue(r.applied)
        assertTrue(core.backups("notes").isNotEmpty())
        core.execute("notes", "UPDATE t SET n = n + 1 WHERE title = ?", listOf("from agent"))
        assertEquals(8L, core.query("notes", "SELECT n FROM t WHERE title='from agent'").rows[0][0])
        core.execute("notes", "DELETE FROM t WHERE title = ?", listOf("from agent"))
        assertEquals(3L, count())
        assertTrue(core.recentAudit("notes").any { it.actor == "agent" && it.action == "execute" })
    }

    @Test fun tooManyChangesRollBack() {
        fresh(Access.ReadWrite)
        core.execute("notes", "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<300) INSERT INTO t(title, n) SELECT 'bulk', x FROM c", actor = Actor.Page)
        refused({ core.execute("notes", "UPDATE t SET n = 0") }, "limit")
        assertNotEquals(0L, count("SELECT SUM(n) FROM t"))
    }

    @Test fun structureNeedsPermissionAndDropIsNeverAllowed() {
        fresh(Access.ReadWrite, ddl = false)
        refused({ core.execute("notes", "CREATE TABLE z(a)") })
        core.setMeta("notes", mayCreateTables = true)
        core.execute("notes", "CREATE TABLE z(a)")
        core.execute("notes", "ALTER TABLE z ADD COLUMN b TEXT")
        for (sql in listOf("DROP TABLE z", "DROP INDEX x", "CREATE VIEW v AS SELECT 1", "CREATE TRIGGER g AFTER INSERT ON t BEGIN SELECT 1; END",
            "ATTACH DATABASE 'x' AS y", "PRAGMA user_version=5")) refused({ core.execute("notes", sql) })
        assertTrue(core.schema("notes").tables.any { it.name == "z" && it.columns.size == 2 })
    }

    @Test fun noTransactionTricks() {
        fresh(Access.ReadWrite)
        for (sql in listOf("COMMIT", "ROLLBACK", "BEGIN", "SAVEPOINT a", "RELEASE a", "INSERT INTO t(title) VALUES('a'); COMMIT")) {
            refused({ core.execute("notes", sql) })
        }
        assertEquals(3L, count())
    }

    @Test fun dryRunChangesNothing() {
        fresh(Access.ReadWrite)
        val r = core.execute("notes", "DELETE FROM t", actor = Actor.Page, apply = false)
        assertEquals(3, r.changed)
        assertFalse(r.applied)
        assertEquals(3L, count())
    }

    @Test fun growthCapRollsBack() {
        fresh(Access.ReadWrite)
        val big = "x".repeat(900_000)
        refused({
            core.execute("notes", "WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<15) INSERT INTO t(title) SELECT ? FROM c", listOf(big))
        }, "MB")
        assertEquals(3L, count())
    }

    // ---- the app's own editing ----

    @Test fun browseSortSearchAndPaging() {
        fresh()
        for (i in 0 until 120) core.insertRow("notes", "t", mapOf("title" to "item %03d".format(i), "n" to i.toLong()))
        val b = core.browse("notes", "t", page = 0, size = 50)
        assertEquals(50, b.rows.size); assertEquals(123, b.total); assertTrue(b.editable)
        assertEquals(23, core.browse("notes", "t", page = 2, size = 50).rows.size)
        val s = core.browse("notes", "t", sort = "n", descending = true, size = 3)
        assertEquals(listOf<Any?>(119L, 118L, 117L), s.rows.map { it.values[2] })
        assertEquals(10, core.browse("notes", "t", search = "item 05").total)
        assertEquals(0, core.browse("notes", "t", search = "100%").total)
    }

    @Test fun rowEditFlow() {
        fresh()
        val r = core.browse("notes", "t").rows[0]
        core.updateRow("notes", "t", r.rowid!!, mapOf("title" to "changed"))
        assertEquals("changed", core.browse("notes", "t").rows[0].values[1])
        core.deleteRow("notes", "t", r.rowid!!)
        assertEquals(2, core.browse("notes", "t").total)
        refused({ core.deleteRow("notes", "t", 99999) })
        refused({ core.updateRow("notes", "t", 2, mapOf("nope" to 1)) })
    }

    @Test fun namesCannotInjectSql() {
        fresh()
        for (table in listOf("t\"; DROP TABLE t; --", "t) --", "sqlite_master", "nonexistent")) refused({ core.browse("notes", table) })
        refused({ core.browse("notes", "t", sort = "n\"; DROP TABLE t; --") })
        refused({ core.insertRow("notes", "t", mapOf("title\"); DROP TABLE t; --" to 1)) })
        for (bad in listOf("x\"y", "1x", "", "a b", "sqlite_x")) refused({ core.createTable("notes", bad, listOf(ColumnDef("a", "TEXT"))) })
        refused({ core.createTable("notes", "ok", listOf(ColumnDef("a\"b", "TEXT"))) })
        refused({ core.createTable("notes", "ok", listOf(ColumnDef("a", "TEXT; DROP"))) })
        assertEquals(3, core.browse("notes", "t").total)
    }

    @Test fun structureHelpers() {
        fresh()
        core.createTable("notes", "people", listOf(ColumnDef("id", "INTEGER", primaryKey = true), ColumnDef("name", "TEXT", notNull = true)))
        core.addColumn("notes", "people", "age", "INTEGER")
        core.createIndex("notes", "people", listOf("name"), unique = true)
        val people = core.schema("notes").tables.first { it.name == "people" }
        assertEquals(listOf("id", "name", "age"), people.columns.map { it.name })
        assertTrue(people.indexes.isNotEmpty())
        core.dropTable("notes", "people")
        assertTrue(core.schema("notes").tables.none { it.name == "people" })
    }

    @Test fun consoleCanWriteButNotEscape() {
        fresh()
        core.execute("notes", "UPDATE t SET n = 5", actor = Actor.Page)
        for (sql in listOf("ATTACH DATABASE '/tmp/x.db' AS e", "PRAGMA journal_mode=DELETE", "SELECT load_extension('x')", "VACUUM INTO '/tmp/x.db'")) {
            refused({ core.execute("notes", sql, actor = Actor.Page) })
        }
    }

    // ---- backups and CSV ----

    @Test fun restoreRoundTrip() {
        fresh()
        core.backup("notes", "test", force = true)
        val before = core.backups("notes")[0].file
        now += 5_000
        core.execute("notes", "DELETE FROM t", actor = Actor.Page)
        assertEquals(0, core.browse("notes", "t").total)
        core.restore("notes", before)
        assertEquals(3, core.browse("notes", "t").total)
        refused({ core.restore("notes", "../../etc/passwd") })
        refused({ core.restore("notes", "missing.db") })
    }

    @Test fun csvExportNeutralisesFormulas() {
        fresh()
        core.insertRow("notes", "t", mapOf("title" to "=HYPERLINK(\"http://evil\",\"x\")", "n" to 1L))
        core.insertRow("notes", "t", mapOf("title" to "-5", "n" to 2L))
        val out = StringWriter()
        assertEquals(5, core.exportCsv("notes", "t", out))
        val text = out.toString()
        assertTrue(text.contains("'=HYPERLINK"))
        assertFalse(text.contains(",=HYPERLINK"))
        assertTrue(text.contains(",-5,"))
    }

    @Test fun csvImportCreatesAndTypes() {
        fresh()
        val (n, created) = core.importCsv("notes", "scores", "name,score,ratio\nann,10,1.5\nbob,20,2.25\n")
        assertEquals(2, n); assertTrue(created)
        val cols = core.schema("notes").tables.first { it.name == "scores" }.columns.associate { it.name to it.type }
        assertEquals(mapOf("name" to "TEXT", "score" to "INTEGER", "ratio" to "REAL"), cols)
        assertFalse(core.importCsv("notes", "scores", "name,score\ncy,30\n").second)
        assertEquals(3, core.browse("notes", "scores").total)
        refused({ core.importCsv("notes", "t", "title,nope\nx,y\n") })
        refused({ core.importCsv("notes", "bad", "a;b\n1;2\n") })
        assertTrue(core.schema("notes").tables.none { it.name == "bad" })
    }

    @Test fun auditRecordsWrites() {
        fresh()
        core.execute("notes", "UPDATE t SET n = 1", actor = Actor.Page)
        assertTrue(core.recentAudit().any { it.action == "execute" && it.db == "notes" && it.actor == "you" })
    }
}
