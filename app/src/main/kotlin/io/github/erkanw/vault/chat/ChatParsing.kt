package io.github.erkanw.vault.chat

import org.json.JSONObject

// Pure helpers for the OpenAI chat-completions wire format, kept apart from the
// network code so they can be unit-tested on the JVM.

sealed interface SseChunk {
    data class Text(val text: String) : SseChunk
    data class Error(val message: String) : SseChunk
    data object Done : SseChunk
    data object Skip : SseChunk
}

/** The payload of one `data:` line of a streamed answer. */
fun parseSseData(payload: String): SseChunk {
    if (payload == "[DONE]") return SseChunk.Done
    if (payload.isEmpty()) return SseChunk.Skip
    val obj = try { JSONObject(payload) } catch (e: Exception) { return SseChunk.Skip }
    obj.optJSONObject("error")?.let { return SseChunk.Error(it.optString("message", "The server reported an error.")) }
    val delta = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta") ?: return SseChunk.Skip
    if (delta.isNull("content")) return SseChunk.Skip
    val text = delta.optString("content", "")
    return if (text.isEmpty()) SseChunk.Skip else SseChunk.Text(text)
}

/** The answer of a server that ignored `stream: true` and replied with one JSON body. */
fun parseCompletion(body: String): String {
    val obj = try { JSONObject(body) } catch (e: Exception) { throw ChatException("Unexpected reply from the server.") }
    obj.optJSONObject("error")?.let { throw ChatException(it.optString("message", "The server reported an error.")) }
    val message = obj.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
    val content = if (message == null || message.isNull("content")) "" else message.optString("content", "")
    return content
}

/** The ids of a `GET /models` reply, without the models that embed or rank rather than chat. */
fun parseModels(body: String): List<String> {
    val data = (try { JSONObject(body).optJSONArray("data") } catch (e: Exception) { null }) ?: return emptyList()
    val ids = ArrayList<String>()
    for (i in 0 until data.length()) {
        val raw = data.optJSONObject(i)?.optString("id", "").orEmpty()
        val id = raw.removePrefix("models/")
        val lower = id.lowercase()
        if (id.isEmpty() || NOT_CHAT.any { lower.contains(it) } || !isChatModel(id)) continue
        ids.add(id)
    }
    return ids
}

private val NOT_CHAT = listOf("embed", "bge-", "all-minilm", "clip", "rerank")

/** The message that tells the user what to do about an HTTP error. */
fun statusError(status: Int, detail: String): String = when (status) {
    401, 403 -> "The key was refused ($status). Check it in Settings."
    404 -> "Not found (404). Check the address and the model in Settings. $detail".trim()
    429 -> "The server is rate limiting you (429). $detail".trim()
    else -> "Server error $status. $detail".trim()
}

/** The error text inside a JSON error body, or the start of the body itself. */
fun errorDetail(body: String): String {
    val text = body.trim()
    if (text.isEmpty()) return ""
    try {
        val obj = JSONObject(text)
        obj.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("error").takeIf { it.isNotBlank() }?.let { return it }
        obj.optString("message").takeIf { it.isNotBlank() }?.let { return it }
    } catch (e: Exception) {
        // Not JSON: fall through.
    }
    return text.take(300)
}

/** Reasoning models put their thinking in `<think>` tags: hidden while open, dropped once closed. */
object ThinkFilter {
    fun visible(raw: String): String {
        val out = StringBuilder()
        var rest = raw
        while (true) {
            val open = rest.indexOf("<think>")
            if (open < 0) {
                out.append(rest)
                break
            }
            out.append(rest, 0, open)
            val close = rest.indexOf("</think>", open)
            if (close < 0) break
            rest = rest.substring(close + "</think>".length)
        }
        return out.toString().trimStart()
    }
}

/** [fatal]: retrying cannot help (a rejected secret, a missing setting), so don't. */
class ChatException(message: String, val fatal: Boolean = false) : Exception(message)
