package io.github.erkanw.vault.tools

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.provider.ProviderKind
import org.json.JSONArray
import org.json.JSONObject

/**
 * The built-in tools and their levels (PLAN §7.4 and §7.5). Levels are kept in SharedPreferences: a short map that the
 * Tools page and the runtime both read. Web search isn't a tool the app runs: on OpenRouter it is OpenRouter's own
 * server tool, sent with the request, so it has a level but no [Tool].
 */
object ToolRegistry {
    private const val PREFS = "tool_levels"
    const val WEB_SEARCH = "web_search"

    val tools: List<Tool> = listOf(
        WebFetchTool, FileTools.List, FileTools.Read, FileTools.Write, FileTools.Edit, FileTools.Move, FileTools.Delete,
        FileTools.Search, MemoryTools.Read, MemoryTools.Write, TimeTool, NotifyTool,
    )

    fun tool(name: String): Tool? = tools.firstOrNull { it.name == name }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun level(context: Context, name: String): ToolLevel {
        val default = if (name == WEB_SEARCH) ToolLevel.Allowed else tool(name)?.defaultLevel ?: ToolLevel.Off
        val stored = ToolLevel.of(prefs(context).getString(name, null)) ?: default
        // OpenRouter runs the search itself, so the app can’t ask first: it is on or off.
        return if (name == WEB_SEARCH && stored == ToolLevel.Ask) ToolLevel.Off else stored
    }

    fun setLevel(context: Context, name: String, level: ToolLevel) = prefs(context).edit { putString(name, level.id) }

    /** Whether web search can be offered: only OpenRouter runs it (another search service joins later). */
    fun searchAvailable(kind: ProviderKind) = kind == ProviderKind.OpenRouter

    /** The tools array for a request: every tool that isn't Off, and OpenRouter's web search when it is on. */
    fun specs(context: Context, kind: ProviderKind): JSONArray = JSONArray().apply {
        for (t in tools) {
            if (level(context, t.name) == ToolLevel.Off) continue
            put(
                JSONObject().put("type", "function").put(
                    "function",
                    JSONObject().put("name", t.name).put("description", t.instructions).put("parameters", t.schema),
                ),
            )
        }
        if (searchAvailable(kind) && level(context, WEB_SEARCH) != ToolLevel.Off) {
            // Results come back inside the answer, with citations; a few results keep the cost and the context small.
            put(JSONObject().put("type", "openrouter:web_search").put("parameters", JSONObject().put("max_results", 5)))
        }
    }

    /** For the Tools page: each tool's title, one line, and level, web search first. */
    fun entries(context: Context, kind: ProviderKind): List<Entry> = buildList {
        add(
            Entry(
                WEB_SEARCH, "Web search",
                if (searchAvailable(kind)) "Searches the web through OpenRouter. Each search costs about a cent."
                else "Needs OpenRouter. Your own server can’t search the web yet.",
                level(context, WEB_SEARCH), available = searchAvailable(kind), levels = listOf(ToolLevel.Allowed, ToolLevel.Off),
            ),
        )
        tools.forEach { add(Entry(it.name, it.title, it.description, level(context, it.name))) }
    }

    data class Entry(
        val name: String, val title: String, val description: String, val level: ToolLevel, val available: Boolean = true,
        /** The levels it can have (web search has no Ask first). */
        val levels: List<ToolLevel> = ToolLevel.entries,
    )
}
