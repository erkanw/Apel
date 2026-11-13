package io.github.erkanw.vault.chat

import org.json.JSONArray
import org.json.JSONObject

// What the agent does before and while it answers, kept with the answer so it can be looked at afterwards: the tools it
// called ("fetch example.com", "cron add …"), the progress notes it sends, and the thinking it streams if the model
// does. The server's frames carry these live; a chat reopened later gets them from the thread if the server keeps them.

enum class StepKind {
    /** A tool call (the server's `tool_hint`). */
    Tool,

    /** A progress note (`progress`). */
    Note,

    /** The model's reasoning, if it streams it. */
    Thinking,
}

data class AgentStep(val kind: StepKind, val text: String)

/** Longest text kept for one step, and most steps kept for one answer: a runaway tool output can't fill the phone. */
const val MAX_STEP_CHARS = 4_000
const val MAX_STEPS = 200

/** A step with its text tidied and capped, or null if there is nothing in it. */
fun agentStep(kind: StepKind, text: String): AgentStep? {
    val clean = text.replace("\r", "").trim()
    if (clean.isEmpty()) return null
    return AgentStep(kind, if (clean.length > MAX_STEP_CHARS) clean.take(MAX_STEP_CHARS) + "…" else clean)
}

/** [steps] plus [step], within the limits (the newest are kept). */
fun withStep(steps: List<AgentStep>, step: AgentStep): List<AgentStep> = (steps + step).takeLast(MAX_STEPS)

fun stepsToJson(steps: List<AgentStep>): JSONArray = JSONArray().apply {
    steps.forEach { put(JSONObject().put("k", it.kind.name).put("x", it.text)) }
}

fun stepsFromJson(array: JSONArray?): List<AgentStep> =
    if (array == null) emptyList() else List(array.length()) { i ->
        array.optJSONObject(i)?.let { o ->
            val kind = StepKind.entries.firstOrNull { it.name == o.optString("k") } ?: StepKind.Note
            agentStep(kind, o.optString("x"))
        }
    }.filterNotNull()

/**
 * A step from an entry of a thread (`GET …/webui-thread`) that isn't a plain message, or null if it isn't one worth
 * showing (the "compaction" summary is not a step). The server's exact shape isn't documented, so this takes what is
 * there: the entry's `kind` says whether it is a tool or reasoning, and the text is the first of `content`, `text`,
 * `hint`, or a tool name with its arguments.
 */
fun stepFromEntry(o: JSONObject): AgentStep? {
    val kind = o.str("kind").lowercase()
    if (kind.isBlank() || kind == "compaction") return null
    val stepKind = when {
        "reason" in kind || "think" in kind -> StepKind.Thinking
        "tool" in kind || o.has("tool_name") || o.has("tool_calls") || o.has("tool_call") -> StepKind.Tool
        else -> StepKind.Note
    }
    val direct = listOf("content", "text", "hint", "summary").firstNotNullOfOrNull { key ->
        when (val v = o.opt(key)) {
            is String -> v.takeIf { it.isNotBlank() }
            is JSONArray -> (0 until v.length()).joinToString("") { v.optJSONObject(it)?.str("text").orEmpty() }.takeIf { it.isNotBlank() }
            else -> null
        }
    }
    val named = listOf("tool_name", "name", "tool").firstNotNullOfOrNull { key -> o.str(key).takeIf { it.isNotBlank() } }
    val args = o.opt("arguments") ?: o.opt("args") ?: o.opt("input")
    val text = direct ?: when {
        named != null && args != null -> "$named ${args.toString().take(400)}"
        named != null -> named
        else -> return null
    }
    return agentStep(stepKind, text)
}

/**
 * The tool calls an assistant entry carries in OpenAI's shape (`tool_calls: [{function: {name, arguments}}]`), as steps
 * ("fetch {"url": …}"). None for an entry that has no such list.
 */
fun toolCallSteps(o: JSONObject): List<AgentStep> {
    val calls = o.optJSONArray("tool_calls") ?: return emptyList()
    return (0 until calls.length()).mapNotNull { i ->
        val call = calls.optJSONObject(i) ?: return@mapNotNull null
        val fn = call.optJSONObject("function") ?: call
        val name = fn.str("name").ifBlank { return@mapNotNull null }
        val args = when (val a = fn.opt("arguments")) {
            null, JSONObject.NULL -> ""
            is String -> a
            else -> a.toString()
        }.replace(Regex("\\s+"), " ").trim()
        agentStep(StepKind.Tool, if (args.isEmpty() || args == "{}") name else "$name $args")
    }
}

/** "3 tools" / "2 steps" / "2 tools · thinking": what the line above an answer says, for [steps]. */
fun stepsSummary(steps: List<AgentStep>): String {
    val tools = steps.count { it.kind == StepKind.Tool }
    val thinking = steps.any { it.kind == StepKind.Thinking }
    val others = steps.size - tools
    val parts = mutableListOf<String>()
    if (tools > 0) parts += "$tools tool${if (tools == 1) "" else "s"}"
    if (others > 0 && tools == 0) parts += "$others step${if (others == 1) "" else "s"}"
    if (thinking && tools > 0) parts += "thinking"
    return parts.joinToString(" · ").ifEmpty { "steps" }
}
