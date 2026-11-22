package io.github.erkanw.vault.agent

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.tools.Similar
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What you allowed before (PLAN §7.5). Every request you allow is noted by its [Similar] key (the same site, folder,
 * table, tool server tool or agent). "Don’t ask again" marks it [Entry.always]: calls like it then run without asking.
 * The experimental "Approve what you’ve allowed before" goes further: any call like one you allowed once runs without
 * asking, and only a kind of request never seen before waits for you. Requests with no [Similar] (a schedule, a skill, a
 * memory note) always ask. Kept on the phone; listed and forgotten on the Tools page.
 */
object ApprovalMemory {
    private const val PREFS = "approval_memory"
    private const val KEY_ENTRIES = "entries"
    private const val KEY_AUTO = "auto"

    /** The most kept; past it the oldest that aren't "Don’t ask again" go first. */
    const val MAX_ENTRIES = 300

    data class Entry(val key: String, val label: String, val tool: String, val always: Boolean, val time: Long)

    private val _entries = MutableStateFlow<List<Entry>?>(null)
    private val _auto = MutableStateFlow<Boolean?>(null)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Everything noted, newest first. */
    fun entries(context: Context): StateFlow<List<Entry>?> {
        load(context)
        return _entries.asStateFlow()
    }

    private fun load(context: Context): List<Entry> = _entries.value ?: parse(prefs(context).getString(KEY_ENTRIES, null)).also { _entries.value = it }

    fun auto(context: Context): Boolean = _auto.value ?: prefs(context).getBoolean(KEY_AUTO, false).also { _auto.value = it }

    fun autoFlow(context: Context): StateFlow<Boolean?> {
        auto(context)
        return _auto.asStateFlow()
    }

    fun setAuto(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO, on) }
        _auto.value = on
    }

    /** Whether a call with [key] runs without asking now. */
    @Synchronized
    fun approves(context: Context, key: String): Boolean = decide(load(context).firstOrNull { it.key == key }, auto(context))

    /** Notes an allowed request; [always]: "Don’t ask again" (kept even if it was only allowed before). */
    @Synchronized
    fun remember(context: Context, similar: Similar, tool: String, always: Boolean, now: Long = System.currentTimeMillis()) {
        save(context, added(load(context), similar, tool, always, now))
    }

    @Synchronized
    fun forget(context: Context, key: String) = save(context, load(context).filterNot { it.key == key })

    @Synchronized
    fun forgetAll(context: Context) = save(context, emptyList())

    private fun save(context: Context, list: List<Entry>) {
        prefs(context).edit { putString(KEY_ENTRIES, toJson(list)) }
        _entries.value = list
    }

    // ---- the rules, kept apart so they can be tested without a phone ----

    /** "Don’t ask again" always lets it run; anything allowed before does only with the experimental switch on. */
    fun decide(entry: Entry?, auto: Boolean): Boolean = entry != null && (entry.always || auto)

    /** [list] with [similar] noted at the top (an "always" stays always), cut to [MAX_ENTRIES]. */
    fun added(list: List<Entry>, similar: Similar, tool: String, always: Boolean, now: Long): List<Entry> {
        val old = list.firstOrNull { it.key == similar.key }
        val entry = Entry(similar.key, similar.label, tool, always || old?.always == true, now)
        val all = listOf(entry) + list.filterNot { it.key == similar.key }
        if (all.size <= MAX_ENTRIES) return all
        // Over the limit: drop the oldest that were only allowed before, then the oldest of the rest.
        val drop = all.size - MAX_ENTRIES
        val seenOnly = all.withIndex().filter { !it.value.always }.sortedBy { it.value.time }.take(drop).map { it.index }.toSet()
        val kept = all.filterIndexed { i, _ -> i !in seenOnly }
        return kept.take(MAX_ENTRIES)
    }

    fun toJson(list: List<Entry>): String = JSONArray().apply {
        list.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("tool", it.tool).put("always", it.always).put("time", it.time)) }
    }.toString()

    fun parse(raw: String?): List<Entry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                val key = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Entry(key, o.optString("label", key), o.optString("tool"), o.optBoolean("always"), o.optLong("time"))
            }
        }.getOrDefault(emptyList())
    }
}
