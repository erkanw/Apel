package io.github.erkanw.vault.tools

import okhttp3.MediaType.Companion.toMediaType
import io.github.erkanw.vault.provider.textAtMost
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A tool a server offers: what the model reads, and whether the server says it only reads. */
data class McpToolInfo(val name: String, val description: String, val schema: JSONObject, val readOnly: Boolean) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("description", description).put("inputSchema", schema).put("readOnly", readOnly)

    companion object {
        fun from(o: JSONObject): McpToolInfo? {
            val name = o.optString("name").takeIf { it.isNotBlank() } ?: return null
            val schema = o.optJSONObject("inputSchema") ?: JSONObject()
            if (!schema.has("type")) schema.put("type", "object")
            if (!schema.has("properties")) schema.put("properties", JSONObject())
            val readOnly = o.optBoolean("readOnly", false) || o.optJSONObject("annotations")?.optBoolean("readOnlyHint", false) == true
            return McpToolInfo(name, o.optString("description").trim(), schema, readOnly)
        }

        fun listFrom(json: String): List<McpToolInfo> = try {
            val a = JSONArray(json)
            List(a.length()) { a.optJSONObject(it)?.let(::from) }.filterNotNull()
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class McpException(message: String) : Exception(message)

/** A tool's name made readable: "browser_take_screenshot" → "Take screenshot" (the server's prefix dropped). */
fun readableToolName(tool: String): String {
    val words = tool.split('_', '-').filter { it.isNotBlank() }
    val trimmed = if (words.size > 1 && words.first() in setOf("browser", "db")) words.drop(1) else words
    return trimmed.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { tool }
}

/**
 * The pure part of MCP over Streamable HTTP (spec 2025-06-18): the JSON-RPC messages and reading a reply, which is
 * either one JSON object or a stream of server-sent events holding it. Tested on the JVM.
 */
object McpProtocol {
    const val VERSION = "2025-06-18"

    fun request(id: Int, method: String, params: JSONObject? = null): String =
        JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).apply { if (params != null) put("params", params) }.toString()

    fun notification(method: String): String = JSONObject().put("jsonrpc", "2.0").put("method", method).toString()

    fun initialize(id: Int, appName: String, appVersion: String): String = request(
        id, "initialize",
        JSONObject().put("protocolVersion", VERSION).put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", appName).put("version", appVersion)),
    )

    /**
     * The JSON-RPC response with [id] in [body]: the body itself when it is JSON, or the event that carries it when it
     * is an event stream (other events, such as progress notifications, are skipped). Throws [McpException] with the
     * server's own message for an error reply.
     */
    fun response(body: String, contentType: String?, id: Int): JSONObject {
        val messages = if (contentType?.contains("text/event-stream") == true || body.trimStart().startsWith("event:") || body.trimStart().startsWith("data:")) {
            events(body)
        } else {
            listOfNotNull(parse(body))
        }
        val reply = messages.firstOrNull { it.has("id") && it.opt("id")?.toString() == id.toString() }
            ?: throw McpException("The server answered without a reply to this request.")
        reply.optJSONObject("error")?.let { e -> throw McpException(e.optString("message").ifBlank { "The server said no (${e.optInt("code")})." }) }
        return reply.optJSONObject("result") ?: JSONObject()
    }

    /** The JSON messages in an event stream: each event's data lines joined. */
    fun events(body: String): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        val data = StringBuilder()
        fun flush() {
            if (data.isNotEmpty()) parse(data.toString())?.let { out += it }
            data.clear()
        }
        for (line in body.lineSequence()) {
            when {
                line.isBlank() -> flush()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        flush()
        return out
    }

    private fun parse(text: String): JSONObject? = try { JSONObject(text.trim()) } catch (e: Exception) { null }

    /** The tools in a `tools/list` result, and the cursor for the next page (null: none). */
    fun tools(result: JSONObject): Pair<List<McpToolInfo>, String?> {
        val a = result.optJSONArray("tools") ?: JSONArray()
        val tools = List(a.length()) { a.optJSONObject(it)?.let(McpToolInfo::from) }.filterNotNull()
        return tools to result.optString("nextCursor").takeIf { it.isNotBlank() }
    }

    /**
     * A `tools/call` result as text for the model: its text parts joined, other parts named; an error result starts with
     * "Error:". At most [max] characters.
     */
    fun callText(result: JSONObject, max: Int = 16_000): String {
        val parts = result.optJSONArray("content") ?: JSONArray()
        val text = buildString {
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                if (isNotEmpty()) append("\n\n")
                when (p.optString("type")) {
                    "text" -> append(p.optString("text"))
                    "image" -> append("[An image (${p.optString("mimeType")}) the app can’t show the model here.]")
                    "audio" -> append("[A sound clip, left out.]")
                    "resource_link" -> append("[A link: ${p.optString("name")} ${p.optString("uri")}]")
                    "resource" -> p.optJSONObject("resource")?.let { r -> append(r.optString("text").ifBlank { "[A resource: ${r.optString("uri")}]" }) }
                    else -> append("[A ${p.optString("type")} part, left out.]")
                }
            }
            if (isEmpty()) result.optJSONObject("structuredContent")?.let { append(it.toString()) }
        }.ifBlank { "(The tool returned nothing.)" }
        val capped = if (text.length > max) text.take(max) + "\n…(cut)" else text
        return if (result.optBoolean("isError", false)) "Error: $capped" else capped
    }

    /** The name the model calls a server's tool by: `mcp_<server>__<tool>`, letters, digits, _ and - only, at most 64. */
    fun exposedName(serverSlug: String, tool: String): String {
        fun clean(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val head = "mcp_${clean(serverSlug).take(20)}__"
        return head + clean(tool).take(64 - head.length)
    }

    /** A short id for a server from its name: "DeepWiki" → "deepwiki". */
    fun slug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(20).ifBlank { "server" }
}

/**
 * Talks to one tool server: `initialize` (keeping its session id), then `tools/list` or `tools/call`. The token, when
 * there is one, goes as a bearer token to this address only: redirects are not followed. Blocking: call it off the main
 * thread. A new session per use keeps it simple; servers are cheap to greet.
 */
class McpClient(private val url: String, private val token: String?, private val appVersion: String = "1") {
    private var sessionId: String? = null
    private var protocol = McpProtocol.VERSION
    private var nextId = 1

    private fun send(body: String, expectReply: Boolean, id: Int = 0): JSONObject? {
        val request = Request.Builder().url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", protocol)
            .apply {
                sessionId?.let { header("Mcp-Session-Id", it) }
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }
            .post(body.toRequestBody(JSON))
            .build()
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw McpException("Couldn’t reach the server (${e.message ?: "no connection"}).")
        }
        response.use { r ->
            r.header("Mcp-Session-Id")?.let { sessionId = it }
            when {
                r.code == 401 || r.code == 403 -> throw McpException(
                    if (token.isNullOrBlank()) "The server wants a token. Add one and test again." else "The server didn’t accept the token.",
                )
                r.code in 300..399 -> throw McpException("The server sent the app elsewhere (${r.code}). Use the address it moved to.")
                r.code == 404 && sessionId != null -> throw McpException("The server ended the session. Try again.")
                r.code !in 200..299 -> throw McpException("The server answered HTTP ${r.code}. Check that this is its MCP address (often ending in /mcp).")
            }
            if (!expectReply) return null
            // Read up to the limit, never the whole answer first.
            val text = r.body?.let { it.textAtMost(MAX_BYTES.toLong()) ?: throw McpException("The server’s answer was too large.") }.orEmpty()
            return McpProtocol.response(text, r.header("Content-Type"), id)
        }
    }

    private fun call(method: String, params: JSONObject? = null): JSONObject {
        val id = nextId++
        return send(McpProtocol.request(id, method, params), expectReply = true, id = id)!!
    }

    /** Greets the server; its name, as it gives it. */
    fun initialize(): String {
        val id = nextId++
        val result = send(McpProtocol.initialize(id, "Apel", appVersion), expectReply = true, id = id)!!
        result.optString("protocolVersion").takeIf { it.isNotBlank() }?.let { protocol = it }
        send(McpProtocol.notification("notifications/initialized"), expectReply = false)
        return result.optJSONObject("serverInfo")?.let { it.optString("title").ifBlank { it.optString("name") } }.orEmpty()
    }

    /** Every tool, page after page (at most 10 pages). */
    fun listTools(): List<McpToolInfo> {
        val all = ArrayList<McpToolInfo>()
        var cursor: String? = null
        for (page in 0 until 10) {
            val (tools, next) = McpProtocol.tools(call("tools/list", cursor?.let { JSONObject().put("cursor", it) }))
            all += tools
            cursor = next ?: break
        }
        return all.distinctBy { it.name }
    }

    fun callTool(name: String, arguments: JSONObject): String =
        McpProtocol.callText(call("tools/call", JSONObject().put("name", name).put("arguments", arguments)))

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val MAX_BYTES = 4 * 1024 * 1024
        private val http = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
