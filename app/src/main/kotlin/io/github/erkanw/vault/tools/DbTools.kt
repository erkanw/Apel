package io.github.erkanw.vault.tools

import io.github.erkanw.vault.userdb.Access
import io.github.erkanw.vault.userdb.DbException
import io.github.erkanw.vault.userdb.UserDbs
import org.json.JSONArray
import org.json.JSONObject

/**
 * The user's databases for the agent (PLAN §7.4, §9): db_list, db_schema, db_query (reads, Allowed) and db_execute
 * (writes, Ask first). A database is only visible when its access isn't None; writes need Read and write, and table
 * changes the "may create tables" setting. Everything runs through [io.github.erkanw.vault.userdb.UserDbCore]'s rules.
 */
object DbTools {
    private fun core(ctx: ToolContext) = UserDbs.core(ctx.app)

    private inline fun <T> guard(block: () -> T): T = try { block() } catch (e: DbException) { throw ToolException(e.message ?: "That didn’t work.") }

    /** The databases this agent may see, with the access it has there (PLAN §6.16: the agent can only narrow it). */
    private fun forAgent(ctx: ToolContext) =
        core(ctx).agentVisible().map { it.copy(access = ctx.agent.access(it.name, it.access)) }.filter { it.access != Access.None }

    /** The database [name] as the agent may see it, or a sentence the model can act on. */
    private fun visible(ctx: ToolContext, name: String) =
        forAgent(ctx).firstOrNull { it.name == name } ?: throw ToolException("No database “$name” is available. Call db_list to see them.")

    private fun params(args: JSONObject): List<Any?> {
        val a = args.optJSONArray("params") ?: return emptyList()
        return (0 until a.length()).map { i ->
            when (val v = a.opt(i)) {
                null, JSONObject.NULL -> null
                is Int -> v.toLong()
                is Long, is Double, is String, is Boolean -> v
                is Number -> v.toDouble()
                else -> throw ToolException("Parameters must be text, numbers or null.")
            }
        }
    }

    private val sqlParams = arrayOf(
        "database" to str("The database name, from db_list."),
        "sql" to str("One SQL statement. Use ? for values and pass them in params."),
        "params" to JSONObject().put("type", "array").put("description", "Values for the ? placeholders, in order: text, numbers or null.")
            .put("items", JSONObject().put("type", JSONArray(listOf("string", "number", "null")))),
    )

    object ListTool : Tool {
        override val name = "db_list"
        override val title = "See databases"
        override val description = "Lists the databases you let it use."
        override val instructions = "List the user's databases you may use: name, description, access (read, or read and write), and whether you may create tables."
        override val schema = params()
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Checking the databases"
        override fun step(args: JSONObject) = "db_list"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val dbs = forAgent(ctx)
            if (dbs.isEmpty()) return "No databases are open to you. The user can make one and set its access in the app (Databases)."
            return dbs.joinToString("\n") { d ->
                "- ${d.name}: ${d.description.ifBlank { "(no description)" }} · access ${if (d.access == Access.ReadWrite) "read and write" else "read only"}" +
                    if (d.access == Access.ReadWrite && d.mayCreateTables) " · may create tables" else ""
            }
        }
    }

    object SchemaTool : Tool {
        override val name = "db_schema"
        override val title = "Read database structure"
        override val description = "Reads a database’s tables and columns."
        override val instructions = "The tables of a database: columns with types, row counts and indexes. Read it before querying."
        override val schema = params("database" to str("The database name, from db_list."))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Reading ${args.optString("database")}’s structure"
        override fun step(args: JSONObject) = "db_schema ${args.optString("database")}"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val name = args.need("database")
            visible(ctx, name)
            val s = guard { core(ctx).schema(name) }
            if (s.tables.isEmpty()) return "“$name” has no tables yet."
            return s.tables.joinToString("\n\n") { t ->
                "${t.name} (${t.rows} row${if (t.rows == 1) "" else "s"})\n" + t.columns.joinToString("\n") { c ->
                    "  ${c.name} ${c.type}${if (c.primaryKey) " PRIMARY KEY" else ""}${if (c.notNull) " NOT NULL" else ""}"
                } + if (t.indexes.isNotEmpty()) "\n  indexes: ${t.indexes.joinToString(", ")}" else ""
            }
        }
    }

    object QueryTool : Tool {
        override val name = "db_query"
        override val title = "Read databases"
        override val description = "Runs reading queries (SELECT) in databases you opened to it."
        override val instructions = "Run one reading statement (SELECT or WITH … SELECT) in a database. At most 500 rows come back; " +
            "use WHERE, LIMIT and aggregates to keep results small."
        override val schema = params(*sqlParams, required = listOf("database", "sql"))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Reading ${args.optString("database")}"
        override fun step(args: JSONObject) = "db_query ${args.optString("database")}: ${args.optString("sql").take(200)}"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val name = args.need("database")
            visible(ctx, name)
            val r = guard { core(ctx).query(name, args.need("sql"), params(args)) }
            val out = JSONObject().put("columns", JSONArray(r.columns)).put("rows", JSONArray(r.rows.map { JSONArray(it.map { v -> v ?: JSONObject.NULL }) }))
            if (r.truncated) out.put("truncated", true)
            return "${r.rows.size} row${if (r.rows.size == 1) "" else "s"}${if (r.truncated) " (cut off: narrow the query)" else ""}\n$out"
        }
    }

    object ExecuteTool : Tool {
        override val name = "db_execute"
        override val title = "Change databases"
        override val description = "Inserts, updates and deletes rows in databases set to Read and write. Asks you first."
        override val instructions = "Run one changing statement (INSERT, UPDATE, DELETE, REPLACE; and CREATE TABLE, CREATE INDEX or " +
            "ALTER TABLE … ADD COLUMN when the database allows it) in a database with read and write access. At most 200 rows " +
            "change at once. Read the structure with db_schema first; pass values as params."
        override val schema = params(*sqlParams, required = listOf("database", "sql"))
        override val defaultLevel = ToolLevel.Ask
        override fun hint(args: JSONObject) = "Changing ${args.optString("database")}"
        override fun step(args: JSONObject) = "db_execute ${args.optString("database")}: ${args.optString("sql").take(200)}"
        override fun precheck(args: JSONObject, ctx: ToolContext): String? {
            val name = args.optString("database")
            val db = forAgent(ctx).firstOrNull { it.name == name } ?: return "No database “$name” is available. Call db_list to see them."
            return if (db.access != Access.ReadWrite) "“$name” is read only for you. The user can change that in the app." else null
        }

        override fun ask(args: JSONObject) = ApprovalAsk(
            "Change the database “${args.optString("database")}”",
            // All of it: what runs is what you read (the card scrolls).
            args.optString("sql") + (args.optJSONArray("params")?.takeIf { it.length() > 0 }?.let { "\n\nValues: $it" } ?: ""),
        )

        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val name = args.need("database")
            val db = visible(ctx, name)
            if (db.access != Access.ReadWrite) throw ToolException("“$name” is read only for you. The user can change that in the app.")
            val r = guard { core(ctx).execute(name, args.need("sql"), params(args)) }
            val returned = if (r.rows.isNotEmpty()) "\n" + JSONObject().put("columns", JSONArray(r.columns)).put("rows", JSONArray(r.rows.map { JSONArray(it.map { v -> v ?: JSONObject.NULL }) })) else ""
            return "Done: ${r.changed} row${if (r.changed == 1) "" else "s"} changed.$returned"
        }
    }
}
