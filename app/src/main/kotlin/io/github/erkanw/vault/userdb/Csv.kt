package io.github.erkanw.vault.userdb

/**
 * CSV in and out of user databases (PLAN §6.10): a comma-separated file with a header line, quotes as RFC 4180 has them.
 * Import is capped at 5 MB and 50,000 rows; export makes cells that a spreadsheet would read as a formula plain text.
 */
object Csv {
    const val MAX_BYTES = 5 * 1024 * 1024
    const val MAX_ROWS = 50_000

    /** The rows of [text], each a list of cells; throws when a quote is never closed. */
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        var any = false
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { cell.append('"'); i++ } else quoted = false
                } else cell.append(c)
            } else when (c) {
                '"' -> { quoted = true; any = true }
                ',' -> { row += cell.toString(); cell.clear(); any = true }
                '\r' -> Unit
                '\n' -> {
                    if (any || cell.isNotEmpty()) { row += cell.toString(); rows += row }
                    row = ArrayList(); cell.clear(); any = false
                }
                else -> { cell.append(c); any = true }
            }
            i++
        }
        if (quoted) throw DbException("The CSV has a quote that is never closed.")
        if (any || cell.isNotEmpty()) { row += cell.toString(); rows += row }
        return rows
    }

    /** A checked import: the header, the rows padded or cut to its width, and a column type for each (for a new table). */
    class Import(val header: List<String>, val rows: List<List<String>>, val types: List<String>)

    fun prepare(text: String): Import {
        if (text.isEmpty() || text.toByteArray().size > MAX_BYTES) throw DbException("The CSV is empty or larger than ${MAX_BYTES / 1024 / 1024} MB.")
        val all = parse(text)
        if (all.size < 2) throw DbException("The CSV needs a header line and at least one row.")
        if (all.size - 1 > MAX_ROWS) throw DbException("The CSV has more than $MAX_ROWS rows.")
        val header = all[0].map { it.trim() }
        if (header.isEmpty() || header.map { it.lowercase() }.toSet().size != header.size ||
            header.any { runCatching { SqlRules.checkIdent(it) }.isFailure }
        ) throw DbException("Column names in the first line must be unique and use letters, digits and _ (comma-separated).")
        val body = all.drop(1).map { r -> (r + List((header.size - r.size).coerceAtLeast(0)) { "" }).take(header.size) }
        val types = header.indices.map { i ->
            val vals = body.map { it[i] }.filter { it.isNotEmpty() }
            when {
                vals.isNotEmpty() && vals.all { INT.matches(it) } -> "INTEGER"
                vals.isNotEmpty() && vals.all { REAL.matches(it) } -> "REAL"
                else -> "TEXT"
            }
        }
        return Import(header, body, types)
    }

    private val INT = Regex("^-?[0-9]+$")
    private val REAL = Regex("^-?[0-9]*\\.?[0-9]+$")
    private val NUMBER = Regex("^-?[0-9.]+$")

    /** A cell for export: one that starts like a formula (= + - @) gets a ' in front, unless it is a plain number. */
    fun safe(value: String): String = if (value.isNotEmpty() && value[0] in "=+-@" && !NUMBER.matches(value)) "'$value" else value

    /** One CSV line of [cells], quoted where needed. */
    fun line(cells: List<String>): String = cells.joinToString(",") { c ->
        if (c.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + c.replace("\"", "\"\"") + "\"" else c
    }
}
