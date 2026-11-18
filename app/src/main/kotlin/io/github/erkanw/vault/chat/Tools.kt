package io.github.erkanw.vault.chat

/** A built-in tool: its id ([path]), the words the app shows, and whether it is on. */
data class BuiltInTool(
    val path: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    /** Only matters when this other tool is on. */
    val needs: String? = null,
    /** Its level (Allowed, Ask first, Off); [enabled] is "not Off". */
    val level: io.github.erkanw.vault.tools.ToolLevel = if (enabled) io.github.erkanw.vault.tools.ToolLevel.Allowed else io.github.erkanw.vault.tools.ToolLevel.Off,
    /** False when the provider can’t run it (web search on your own server). */
    val available: Boolean = true,
    /** The levels it can have. */
    val levels: List<io.github.erkanw.vault.tools.ToolLevel> = io.github.erkanw.vault.tools.ToolLevel.entries,
)

/** A tool server the agent is connected to, with all its tools and the ones that are on. */
data class McpServer(
    val name: String,
    val title: String,
    val tools: List<String>,
    /** As stored: `["*"]` for all, or the names that are on. */
    val enabledTools: List<String>,
    val available: Boolean,
    val status: String,
    /** The server's address, as shown under its name in the sheet. */
    val url: String = "",
    /** Each tool's description, from the server. */
    val descriptions: Map<String, String> = emptyMap(),
) {
    val allOn: Boolean get() = "*" in enabledTools
    fun isOn(tool: String): Boolean = allOn || tool in enabledTools
    val onCount: Int get() = if (allOn) tools.size else tools.count { it in enabledTools }
    val anyOn: Boolean get() = allOn || enabledTools.isNotEmpty()

    /** What to send to turn [tool] on or off: `["*"]` once every tool is on, the names otherwise. */
    fun withTool(tool: String, on: Boolean): List<String> {
        val next = (if (allOn) tools else enabledTools).toMutableList()
        if (on) { if (tool !in next) next += tool } else next.remove(tool)
        return if (tools.isNotEmpty() && tools.all { it in next }) listOf("*") else next.filter { it in tools || tools.isEmpty() }
    }
}

/** What a test of a tool server found: the address as it will be kept, its name, its tools, and a warning for plain http. */
data class McpTest(val url: String, val name: String, val tools: List<String>, val warning: String?)

/** A tool server in a line: whether it answers, and how many of its tools are on. */
fun serverSubtitle(s: McpServer): String = when {
    !s.available -> "Not reachable"
    s.tools.isEmpty() -> if (s.allOn) "All tools on" else if (s.anyOn) "${s.enabledTools.size} tools on" else "Off"
    s.onCount == 0 -> "Off"
    else -> "${s.onCount} of ${s.tools.size} tools on"
}
