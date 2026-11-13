package io.github.erkanw.vault.chat

/** A built-in tool: its id ([path]), the words the app shows, and whether it is on. */
data class BuiltInTool(
    val path: String,
    val title: String,
    val subtitle: String,
    val enabled: Boolean,
    /** Only matters when this other tool is on. */
    val needs: String? = null,
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

/** A tool server in a line: whether it answers, and how many of its tools are on. */
fun serverSubtitle(s: McpServer): String = when {
    !s.available -> "Not reachable"
    s.tools.isEmpty() -> if (s.allOn) "All tools on" else if (s.anyOn) "${s.enabledTools.size} tools on" else "Off"
    s.onCount == 0 -> "Off"
    else -> "${s.onCount} of ${s.tools.size} tools on"
}
