package io.github.erkanw.vault.store

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Why a backup can't be read or restored; the screen says it in words (PLAN §6.12). */
enum class BackupProblem { NotABackup, OtherApp, Newer, Damaged, TooBig, NoSpace, Unreadable, Failed }

class BackupException(val problem: BackupProblem, cause: Throwable? = null) : Exception(problem.name, cause)

/**
 * The layout of a backup zip, pure so it is tested on the JVM. A backup holds everything in the app except keys and
 * tokens: the database (`db/`), the app's files (`files/`: the workspace, the agent's rules and memory backups, the user
 * databases, the Trash), the pictures sent in chats (`cache/sent/`) and the settings (`prefs/<name>.json`, typed values). `apel-backup.json` says what it is.
 */
object BackupFormat {
    const val FORMAT = 1
    const val MANIFEST = "apel-backup.json"

    /** Folders and files under the app's files folder that a backup holds. */
    val FILE_ROOTS = listOf("workspace", "agent", "dbs", "trash")
    val FILE_SINGLES = listOf("chat.json")

    /** Pictures sent in chats are kept in the cache folder under this name; messages point at them. */
    const val SENT = "sent"

    /** The database and its write-ahead log (the shared-memory file is rebuilt by SQLite). */
    val DB_FILES = listOf("app.db", "app.db-wal")

    /**
     * The settings that go in a backup. Never the sealed keys (`*_secret`, `mcp_*`), nor `openrouter`, which says
     * whether this phone holds a key: a restore keeps the phone's own sign-in.
     */
    val PREFS = listOf(
        "agent", "automations_ui", "budget", "channel_looks", "look", "model_prefs", "models", "provider", "seen",
        "settings", "skills", "tool_levels", "tools", "widget", "workspace", "agents", "channel_agents",
    )

    /** At most this much unpacked: a phone backup bigger than this is not one Apel made. */
    const val MAX_TOTAL_BYTES = 8L * 1024 * 1024 * 1024
    const val MAX_ENTRIES = 200_000

    data class Manifest(
        val app: String,
        val format: Int,
        val versionName: String,
        val versionCode: Int,
        val dbVersion: Int,
        val created: Long,
        val chats: Int,
        val files: Int,
    )

    fun manifestJson(m: Manifest): String = JSONObject()
        .put("app", m.app).put("format", m.format).put("versionName", m.versionName).put("versionCode", m.versionCode)
        .put("dbVersion", m.dbVersion).put("created", m.created).put("chats", m.chats).put("files", m.files)
        .toString(2)

    /** Reads and checks a manifest for [appId] whose database is at most [dbVersion]. Throws [BackupException]. */
    fun parseManifest(text: String, appId: String, dbVersion: Int): Manifest {
        val o = try { JSONObject(text) } catch (e: Exception) { throw BackupException(BackupProblem.NotABackup, e) }
        val m = Manifest(
            app = o.optString("app"),
            format = o.optInt("format", -1),
            versionName = o.optString("versionName"),
            versionCode = o.optInt("versionCode"),
            dbVersion = o.optInt("dbVersion", -1),
            created = o.optLong("created"),
            chats = o.optInt("chats"),
            files = o.optInt("files"),
        )
        when {
            m.format < 1 || m.dbVersion < 1 -> throw BackupException(BackupProblem.NotABackup)
            m.app != appId -> throw BackupException(BackupProblem.OtherApp)
            m.format > FORMAT || m.dbVersion > dbVersion -> throw BackupException(BackupProblem.Newer)
        }
        return m
    }

    /**
     * Where an entry of the zip goes, relative to the restore folder, or null when it doesn't belong in a backup: only
     * the manifest, `db/` with the database files, `prefs/<known>.json`, and `files/` under the known folders, with no
     * `..`, no absolute path and no backslash.
     */
    fun target(entry: String): String? {
        if (entry.isEmpty() || entry.startsWith("/") || '\\' in entry || '\u0000' in entry) return null
        val parts = entry.split('/')
        if (parts.any { it == ".." || it == "." }) return null
        if (entry == MANIFEST) return entry
        return when (parts.first()) {
            "db" -> entry.takeIf { parts.size == 2 && parts[1] in DB_FILES }
            "prefs" -> entry.takeIf { parts.size == 2 && parts[1].removeSuffix(".json") in PREFS && parts[1].endsWith(".json") }
            "cache" -> entry.takeIf { parts.size >= 3 && parts[1] == SENT && parts.last().isNotEmpty() }
            "files" -> entry.takeIf {
                parts.size >= 2 && parts[1].isNotEmpty() &&
                    ((parts[1] in FILE_ROOTS) || (parts.size == 2 && parts[1] in FILE_SINGLES))
            }
            else -> null
        }
    }

    /** A settings file as typed JSON: `{"key": {"t": "s", "v": "…"}}`; t is s, b, i, l, f or set. */
    fun prefsToJson(values: Map<String, *>): JSONObject {
        val o = JSONObject()
        for ((key, value) in values.toSortedMap()) {
            val typed = when (value) {
                is String -> JSONObject().put("t", "s").put("v", value)
                is Boolean -> JSONObject().put("t", "b").put("v", value)
                is Int -> JSONObject().put("t", "i").put("v", value)
                is Long -> JSONObject().put("t", "l").put("v", value)
                is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
                is Set<*> -> JSONObject().put("t", "set").put("v", JSONArray(value.filterIsInstance<String>().sorted()))
                else -> null
            }
            if (typed != null) o.put(key, typed)
        }
        return o
    }

    /** The values of [prefsToJson]'s output, typed again; unknown entries are skipped. */
    fun prefsFromJson(o: JSONObject): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        for (key in o.keys()) {
            val typed = o.optJSONObject(key) ?: continue
            val value: Any = when (typed.optString("t")) {
                "s" -> typed.optString("v")
                "b" -> typed.optBoolean("v")
                "i" -> typed.optInt("v")
                "l" -> typed.optLong("v")
                "f" -> typed.optDouble("v").toFloat()
                "set" -> typed.optJSONArray("v")?.let { a -> List(a.length()) { a.optString(it) }.toSet() } ?: emptySet<String>()
                else -> continue
            }
            out[key] = value
        }
        return out
    }

    /** "apel-backup-2026-11-18-2130.zip" in the phone's time. */
    fun fileName(time: Long, locale: Locale = Locale.ROOT): String =
        "apel-backup-" + SimpleDateFormat("yyyy-MM-dd-HHmm", locale).format(Date(time)) + ".zip"
}
