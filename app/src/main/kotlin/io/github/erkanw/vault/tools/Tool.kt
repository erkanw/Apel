package io.github.erkanw.vault.tools

import android.content.Context
import org.json.JSONObject

/** How freely the agent may use a tool (PLAN §7.5). */
enum class ToolLevel(val id: String, val label: String) {
    Allowed("allowed", "Allowed"), Ask("ask", "Ask first"), Off("off", "Off");

    companion object {
        fun of(id: String?): ToolLevel? = entries.firstOrNull { it.id == id }
    }
}

/** Where a tool runs: the app, the session it runs for, and what this turn has done so far. */
class ToolContext(
    val app: Context,
    val sessionId: String,
    /** The automation running this turn, if one is: approvals then always come as a notification. */
    val automation: String? = null,
    /** The channel this chat is, if it is one (a new automation made here posts here). */
    val channelId: String? = null,
    /** The agent this turn runs as: what it may use, and where its memory is. */
    val agent: io.github.erkanw.vault.agent.AgentScope = io.github.erkanw.vault.agent.AgentScope(
        io.github.erkanw.vault.agent.AgentPresets.APEL, "", "",
    ),
) {
    /** The turn read the web (a page or a search): what it keeps in memory now asks first (PLAN §10). */
    @Volatile var readWeb = false

    /** The addresses this turn has seen ([UrlTrail]): after reading the web, a new one asks before it is opened. */
    private val seenUrls: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    /** Notes the addresses in [text] (your message, a search's sources, a tool's result). */
    fun see(text: String) { seenUrls += UrlTrail.find(text) }

    /** Whether opening [url] now needs your OK: the turn read something untrusted, and [url] wasn't in anything it saw. */
    fun mustAskToOpen(url: String): Boolean = readWeb && UrlTrail.key(url)?.let { it !in seenUrls } != false

    /** Time spent waiting for the user’s OK, which doesn’t count against the turn’s limit. */
    @Volatile var waitedMs = 0L

    /** The tool running now, as its step reads, so a Stop can keep it as "Stopped". */
    @Volatile var inFlight: String? = null
}

/** What a tool wants approved before it runs: one line, and the details (a path, a text) for the monospace well. */
class ApprovalAsk(val summary: String, val detail: String)

/**
 * One built-in tool. [schema] is its JSON Schema for the model; [run] does the work and returns text for the model
 * (the runtime frames it as data and caps it). A tool throws [ToolException] with a sentence the model can act on.
 */
interface Tool {
    /** The name the model calls, and the id of its level. */
    val name: String

    /** For the Tools page: a short title and one line on what it does. */
    val title: String
    val description: String

    /** What the model reads about it. */
    val instructions: String
    val schema: JSONObject

    val defaultLevel: ToolLevel

    /** Shown on the working line while it runs ("Reading example.com"). */
    fun hint(args: JSONObject): String

    /** How the call is kept in the steps panel: the tool and its main argument. */
    fun step(args: JSONObject): String = name

    /** Asked even when the tool is Allowed (overwriting a file the user made, say); null when nothing needs asking. */
    fun mustAsk(args: JSONObject, ctx: ToolContext): ApprovalAsk? = null

    /** Why this call can't run at all, checked before anyone is asked (a database it may only read); null when it can. */
    fun precheck(args: JSONObject, ctx: ToolContext): String? = null

    /** What the approval card says when the tool is set to Ask first. */
    fun ask(args: JSONObject): ApprovalAsk = ApprovalAsk(hint(args), step(args))

    suspend fun run(args: JSONObject, ctx: ToolContext): String
}

class ToolException(message: String) : Exception(message)

/** A function tool's parameters: an object of named properties, the [required] ones listed. */
fun params(vararg props: Pair<String, JSONObject>, required: List<String> = props.map { it.first }): JSONObject =
    JSONObject().put("type", "object").put("properties", JSONObject().apply { props.forEach { put(it.first, it.second) } })
        .put("required", org.json.JSONArray(required))

fun str(description: String) = JSONObject().put("type", "string").put("description", description)
fun enumOf(description: String, vararg values: String) = str(description).put("enum", org.json.JSONArray(values.toList()))

/** A required string argument, or a sentence the model can fix its call from. */
fun JSONObject.need(key: String): String = optString(key).takeIf { has(key) && !isNull(key) && it.isNotBlank() }
    ?: throw ToolException("Missing “$key”.")
