package io.github.erkanw.vault

import io.github.erkanw.vault.userdb.Csv
import io.github.erkanw.vault.userdb.DbException
import io.github.erkanw.vault.userdb.SqlRules
import io.github.erkanw.vault.userdb.SqlRules.Kind
import io.github.erkanw.vault.userdb.SqlRules.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The statement rules of PLAN §9 (the adversarial cases that don't need SQLite) and CSV in and out. */
class SqlRulesTest {
    private fun refused(sql: String, mode: Mode, ddl: Boolean = false) {
        try {
            SqlRules.check(sql, mode, ddl)
            fail("should be refused: $sql")
        } catch (e: DbException) {
            // expected
        }
    }

    @Test
    fun `reads allow selects and nothing that writes or escapes`() {
        assertEquals(Kind.Read, SqlRules.check("SELECT title FROM t WHERE n >= ? ORDER BY n", Mode.Read))
        assertEquals(Kind.Read, SqlRules.check("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<5) SELECT x FROM c;", Mode.Read))
        assertEquals(Kind.Read, SqlRules.check("SELECT replace(title, 'a', 'b') FROM t -- a comment; DROP TABLE t", Mode.Read))
        assertEquals(Kind.Read, SqlRules.check("SELECT 'DROP TABLE t; ATTACH' AS \"insert\" FROM t", Mode.Read))
        for (sql in listOf(
            "INSERT INTO t(title) VALUES('x')", "UPDATE t SET n=9", "DELETE FROM t", "DROP TABLE t", "CREATE TABLE z(a)",
            "ATTACH DATABASE '/etc/passwd' AS e", "PRAGMA table_info(t)", "PRAGMA journal_mode=OFF", "SELECT load_extension('x')",
            "VACUUM", "BEGIN", "COMMIT", "SAVEPOINT s", "SELECT 1; SELECT 2", "SELECT 1; DROP TABLE t", "REPLACE INTO t(id,title) VALUES(1,'x')",
            "WITH c AS (SELECT 1) INSERT INTO t(n) SELECT * FROM c", "SELECT readfile('/etc/hosts')", "select 1 /* never closed",
            "SELECT 'never closed", "", "   ", "SELECT " + "1+".repeat(5000) + "1",
        )) refused(sql, Mode.Read)
    }

    @Test
    fun `agent writes are row changes only, structure only when allowed, never drop or tricks`() {
        assertEquals(Kind.Write, SqlRules.check("INSERT INTO t(title, n) VALUES(?, ?)", Mode.AgentWrite))
        assertEquals(Kind.Write, SqlRules.check("UPDATE t SET n = n + 1 WHERE title = ?", Mode.AgentWrite))
        assertEquals(Kind.Write, SqlRules.check("WITH RECURSIVE c(x) AS (SELECT 1 UNION ALL SELECT x+1 FROM c WHERE x<3) INSERT INTO t(n) SELECT x FROM c", Mode.AgentWrite))
        refused("CREATE TABLE z(a)", Mode.AgentWrite, ddl = false)
        assertEquals(Kind.Schema, SqlRules.check("CREATE TABLE z(a)", Mode.AgentWrite, mayCreateTables = true))
        assertEquals(Kind.Schema, SqlRules.check("ALTER TABLE z ADD COLUMN b TEXT", Mode.AgentWrite, mayCreateTables = true))
        assertEquals(Kind.Schema, SqlRules.check("CREATE UNIQUE INDEX i ON z(a)", Mode.AgentWrite, mayCreateTables = true))
        for (sql in listOf(
            "DROP TABLE z", "DROP INDEX x", "CREATE VIEW v AS SELECT 1", "CREATE TRIGGER g AFTER INSERT ON t BEGIN SELECT 1; END",
            "ATTACH DATABASE 'x' AS y", "PRAGMA user_version=5", "ALTER TABLE z RENAME TO y", "ALTER TABLE z DROP COLUMN a",
            "CREATE TEMP TABLE q(a)", "CREATE VIRTUAL TABLE f USING fts5(a)", "COMMIT", "ROLLBACK", "BEGIN", "SAVEPOINT a", "RELEASE a",
            "INSERT INTO t(title) VALUES('a'); COMMIT", "SELECT 1", "INSERT INTO t(title) VALUES(zeroblob(1000000000))",
            "INSERT INTO t(title) VALUES(printf('%.*c', 900000000, 'x'))", "REINDEX", "ANALYZE",
        )) refused(sql, Mode.AgentWrite, ddl = true)
    }

    @Test
    fun `the console can write and change structure but not escape`() {
        assertEquals(Kind.Write, SqlRules.check("UPDATE t SET n = 5", Mode.PageWrite))
        assertEquals(Kind.Schema, SqlRules.check("DROP TABLE t", Mode.PageWrite))
        assertEquals(Kind.Read, SqlRules.check("SELECT * FROM t", Mode.PageWrite))
        for (sql in listOf("ATTACH DATABASE '/tmp/x.db' AS e", "PRAGMA journal_mode=DELETE", "SELECT load_extension('x')", "VACUUM INTO '/tmp/x.db'", "DETACH e")) {
            refused(sql, Mode.PageWrite)
        }
    }

    @Test
    fun `quoted words are names, not keywords`() {
        val tokens = SqlRules.tokenize("SELECT \"commit\", [begin], `pragma`, 'attach' FROM t")
        assertTrue(tokens.none { it.type == SqlRules.T.Word && it.upper in setOf("COMMIT", "BEGIN", "PRAGMA", "ATTACH") })
        assertEquals(Kind.Read, SqlRules.check("SELECT \"commit\" FROM \"pragma\"", Mode.Read))
    }

    @Test
    fun `names are checked`() {
        for (bad in listOf("", "Bad", "1abc", "a-b", "a".repeat(40), "../x", "a.db")) {
            try { SqlRules.checkDbName(bad); fail(bad) } catch (e: DbException) { /* expected */ }
        }
        SqlRules.checkDbName("notes_2026")
        for (bad in listOf("x\"y", "1x", "", "a b", "sqlite_x", "t\"; DROP TABLE t; --")) {
            try { SqlRules.checkIdent(bad); fail(bad) } catch (e: DbException) { /* expected */ }
        }
        try { SqlRules.checkType("TEXT; DROP"); fail() } catch (e: DbException) { /* expected */ }
        assertEquals("INTEGER", SqlRules.checkType("integer"))
        assertEquals("\"a\"\"b\"", SqlRules.q("a\"b"))
    }

    @Test
    fun `csv import types columns and refuses bad files`() {
        val ok = Csv.prepare("name,score,ratio\nann,10,1.5\nbob,20,2.25\n")
        assertEquals(listOf("TEXT", "INTEGER", "REAL"), ok.types)
        assertEquals(2, ok.rows.size)
        assertEquals(listOf("a, \"b\"", "c"), Csv.parse("\"a, \"\"b\"\"\",c\r\n")[0])
        for (text in listOf("", "a,b\n", "a;b\n1;2\n", "a,a\n1,2\n", "a b,c\n1,2\n", "id\n" + "1\n".repeat(60000), "a\n\"open\n")) {
            try { Csv.prepare(text); fail(text.take(20)) } catch (e: DbException) { /* expected */ }
        }
    }

    @Test
    fun `csv export neutralises formulas but keeps numbers`() {
        assertEquals("'=HYPERLINK(\"http://evil\",\"x\")", Csv.safe("=HYPERLINK(\"http://evil\",\"x\")"))
        assertEquals("-5", Csv.safe("-5"))
        assertEquals("'@cmd", Csv.safe("@cmd"))
        assertEquals("\"a,b\",c", Csv.line(listOf("a,b", "c")))
        assertFalse(Csv.line(listOf(Csv.safe("+1+1"))).startsWith("+"))
    }
}
