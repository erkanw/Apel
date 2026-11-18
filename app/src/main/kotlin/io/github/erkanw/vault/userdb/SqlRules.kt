package io.github.erkanw.vault.userdb

/** Why a database call was refused, in a sentence the agent or the user can act on. */
class DbException(message: String) : Exception(message)

/**
 * What SQL may run where (PLAN §9). Android's SQLite has no authorizer, so statements are checked in layers: this
 * tokenizer (strings, quoted identifiers and comments aware) allows one statement only, refuses words that reach outside
 * the database or around its transactions, and allows only the statements the caller may run; read-only connections,
 * transactions with change caps, timers and size checks in [UserDbCore] do the rest.
 */
object SqlRules {
    const val MAX_SQL_CHARS = 8_000

    /** Who runs it: a read (anyone), an agent's write, or the user's own SQL console. */
    enum class Mode { Read, AgentWrite, PageWrite }

    /** What a checked statement is. */
    enum class Kind { Read, Write, Schema }

    /** Never, for anyone: these reach other files, change settings, or step around the transaction a write runs in. */
    private val FORBIDDEN_WORDS = setOf(
        "ATTACH", "DETACH", "PRAGMA", "VACUUM", "BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT", "RELEASE", "REINDEX", "ANALYZE",
    )

    /** Functions that touch files or the engine. */
    private val FORBIDDEN_FUNCTIONS = setOf("load_extension", "readfile", "writefile", "edit", "fts3_tokenizer", "sqlite_compileoption_get")

    /** Functions that build big values out of nothing: not in an agent's writes. */
    private val GROWING_FUNCTIONS = setOf("zeroblob", "randomblob", "printf", "format")

    private val WRITE_VERBS = setOf("INSERT", "UPDATE", "DELETE", "REPLACE")
    private val SCHEMA_WORDS = setOf("CREATE", "DROP", "ALTER")

    enum class T { Word, Ident, Str, Num, Param, Sym, Semi }

    data class Token(val type: T, val text: String) {
        val upper: String get() = text.uppercase()
        fun isWord(w: String) = type == T.Word && upper == w
    }

    /** Splits [sql] into tokens without comments; throws when a string, identifier or comment isn't closed. */
    fun tokenize(sql: String): List<Token> {
        val out = ArrayList<Token>()
        var i = 0
        val n = sql.length
        fun closeAt(quote: Char, from: Int, what: String): Int {
            var j = from
            while (j < n) {
                if (sql[j] == quote) {
                    if (j + 1 < n && sql[j + 1] == quote && quote != ']') { j += 2; continue }
                    return j
                }
                j++
            }
            throw DbException("The statement isn’t complete: a $what is never closed.")
        }
        while (i < n) {
            val c = sql[i]
            when {
                c.isWhitespace() -> i++
                c == '-' && i + 1 < n && sql[i + 1] == '-' -> { while (i < n && sql[i] != '\n') i++ }
                c == '/' && i + 1 < n && sql[i + 1] == '*' -> {
                    val end = sql.indexOf("*/", i + 2)
                    if (end < 0) throw DbException("The statement isn’t complete: a comment is never closed.")
                    i = end + 2
                }
                c == '\'' -> { val e = closeAt('\'', i + 1, "text value"); out += Token(T.Str, sql.substring(i, e + 1)); i = e + 1 }
                (c == 'x' || c == 'X') && i + 1 < n && sql[i + 1] == '\'' -> { val e = closeAt('\'', i + 2, "blob value"); out += Token(T.Str, sql.substring(i, e + 1)); i = e + 1 }
                c == '"' -> { val e = closeAt('"', i + 1, "quoted name"); out += Token(T.Ident, sql.substring(i + 1, e).replace("\"\"", "\"")); i = e + 1 }
                c == '`' -> { val e = closeAt('`', i + 1, "quoted name"); out += Token(T.Ident, sql.substring(i + 1, e).replace("``", "`")); i = e + 1 }
                c == '[' -> { val e = closeAt(']', i + 1, "quoted name"); out += Token(T.Ident, sql.substring(i + 1, e)); i = e + 1 }
                c == ';' -> { out += Token(T.Semi, ";"); i++ }
                c == '?' || c == ':' || c == '@' || c == '$' -> {
                    var j = i + 1
                    while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                    out += Token(T.Param, sql.substring(i, j)); i = j
                }
                c.isDigit() || (c == '.' && i + 1 < n && sql[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '.' || ((sql[j] == '+' || sql[j] == '-') && (sql[j - 1] == 'e' || sql[j - 1] == 'E')))) j++
                    out += Token(T.Num, sql.substring(i, j)); i = j
                }
                c.isLetter() || c == '_' -> {
                    var j = i + 1
                    while (j < n && (sql[j].isLetterOrDigit() || sql[j] == '_' || sql[j] == '$')) j++
                    out += Token(T.Word, sql.substring(i, j)); i = j
                }
                else -> { out += Token(T.Sym, c.toString()); i++ }
            }
        }
        return out
    }

    /**
     * Checks [sql] for [mode] and says what it is, or throws [DbException] saying why it can't run. [mayCreateTables]:
     * an agent may also create tables and indexes and add columns (the database's setting).
     */
    fun check(sql: String, mode: Mode, mayCreateTables: Boolean = false): Kind {
        val text = sql.trim()
        if (text.isEmpty()) throw DbException("Give an SQL statement.")
        if (text.length > MAX_SQL_CHARS) throw DbException("The statement is longer than $MAX_SQL_CHARS characters.")
        val all = tokenize(text)
        // One statement: a trailing ";" is fine, anything after it is not.
        val firstSemi = all.indexOfFirst { it.type == T.Semi }
        if (firstSemi >= 0 && all.drop(firstSemi).any { it.type != T.Semi }) throw DbException("Run one statement at a time.")
        val tokens = if (firstSemi >= 0) all.take(firstSemi) else all
        if (tokens.isEmpty()) throw DbException("Give an SQL statement.")
        tokens.forEachIndexed { i, t ->
            if (t.type != T.Word) return@forEachIndexed
            if (t.upper in FORBIDDEN_WORDS) throw DbException("${t.upper} isn’t allowed here.")
            val call = tokens.getOrNull(i + 1)?.let { it.type == T.Sym && it.text == "(" } == true
            if (call && t.text.lowercase() in FORBIDDEN_FUNCTIONS) throw DbException("${t.text.lowercase()}() isn’t allowed here.")
            if (call && mode == Mode.AgentWrite && t.text.lowercase() in GROWING_FUNCTIONS) throw DbException("${t.text.lowercase()}() isn’t allowed in a change.")
        }
        val verb = mainVerb(tokens)
        return when (mode) {
            Mode.Read -> {
                if (verb != "SELECT" && verb != "VALUES") throw DbException("Only reading statements (SELECT) can run here.")
                if (words(tokens).any { it in WRITE_VERBS || it in SCHEMA_WORDS }) throw DbException("Only reading statements (SELECT) can run here.")
                Kind.Read
            }
            Mode.AgentWrite -> when {
                verb in WRITE_VERBS -> {
                    if (words(tokens).any { it in SCHEMA_WORDS }) throw DbException("One change at a time: insert, update or delete rows.")
                    Kind.Write
                }
                verb == "SELECT" || verb == "VALUES" -> throw DbException("That only reads: use db_query for it.")
                verb in SCHEMA_WORDS -> {
                    schemaAllowed(tokens)
                    if (!mayCreateTables) throw DbException("This database doesn’t let you change its tables. The user can allow it in the app.")
                    Kind.Schema
                }
                else -> throw DbException("Only INSERT, UPDATE, DELETE and REPLACE can change this database.")
            }
            Mode.PageWrite -> when {
                verb == "SELECT" || verb == "VALUES" -> Kind.Read
                verb in WRITE_VERBS -> Kind.Write
                verb in SCHEMA_WORDS -> Kind.Schema
                else -> throw DbException("That statement can’t run here.")
            }
        }
    }

    /** The unquoted words outside function-call position (so `replace(...)` the function isn't REPLACE the statement). */
    private fun words(tokens: List<Token>): List<String> = tokens.mapIndexedNotNull { i, t ->
        if (t.type != T.Word) null
        else if (tokens.getOrNull(i + 1)?.let { it.type == T.Sym && it.text == "(" } == true && t.upper == "REPLACE") null
        else t.upper
    }

    /** The statement's verb: its first word, or after a WITH clause, the first word outside the clause's brackets. */
    fun mainVerb(tokens: List<Token>): String {
        val first = tokens.firstOrNull() ?: return ""
        if (first.type == T.Sym && first.text == "(") return "SELECT".takeIf { tokens.any { it.isWord("SELECT") } } ?: ""
        if (first.type != T.Word) return ""
        if (first.upper != "WITH") return first.upper
        var depth = 0
        for (t in tokens.drop(1)) {
            if (t.type == T.Sym && t.text == "(") depth++
            else if (t.type == T.Sym && t.text == ")") depth--
            else if (depth == 0 && t.type == T.Word && t.upper in setOf("SELECT", "VALUES", "INSERT", "UPDATE", "DELETE", "REPLACE")) return t.upper
        }
        return "WITH"
    }

    /** The structure changes an agent may make (when allowed): CREATE TABLE, CREATE [UNIQUE] INDEX, ALTER TABLE … ADD [COLUMN]. */
    private fun schemaAllowed(tokens: List<Token>) {
        val w = tokens.filter { it.type == T.Word }.map { it.upper }
        val ok = when (w.firstOrNull()) {
            "CREATE" -> w.getOrNull(1) == "TABLE" || w.getOrNull(1) == "INDEX" || (w.getOrNull(1) == "UNIQUE" && w.getOrNull(2) == "INDEX")
            "ALTER" -> w.getOrNull(1) == "TABLE" && tokens.size > 3 && tokens[3].isWord("ADD")
            else -> false
        }
        if (!ok) throw DbException("You can create tables and indexes and add columns, but not drop, rename or make views or triggers.")
    }

    // ---- names ----

    private val DB_NAME = Regex("^[a-z][a-z0-9_]{0,30}$")
    private val IDENT = Regex("^[A-Za-z_][A-Za-z0-9_]{0,63}$")
    val COLUMN_TYPES = listOf("TEXT", "INTEGER", "REAL", "NUMERIC", "BLOB")

    fun checkDbName(name: String): String {
        if (!DB_NAME.matches(name)) throw DbException("A database name is lower-case letters, digits and _, starts with a letter, and is at most 31 characters.")
        return name
    }

    /** A table or column name the app writes into SQL itself: letters, digits and _, not starting with a digit or "sqlite_". */
    fun checkIdent(name: String, what: String = "name"): String {
        if (!IDENT.matches(name) || name.lowercase().startsWith("sqlite_")) throw DbException("That $what can only use letters, digits and _, and can’t start with a digit or “sqlite_”.")
        return name
    }

    fun checkType(type: String): String {
        val t = type.trim().uppercase()
        if (t !in COLUMN_TYPES) throw DbException("A column type is one of ${COLUMN_TYPES.joinToString(", ")}.")
        return t
    }

    /** [name] as a quoted SQL identifier (names are checked first; quoting is the second guard). */
    fun q(name: String) = "\"" + name.replace("\"", "\"\"") + "\""
}
