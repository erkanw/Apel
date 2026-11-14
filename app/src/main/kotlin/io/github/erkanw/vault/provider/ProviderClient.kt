package io.github.erkanw.vault.provider

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** One piece of a streamed answer. */
sealed interface StreamPart {
    data class Text(val text: String) : StreamPart
    data class Reasoning(val text: String) : StreamPart
    /** The tokens and (OpenRouter) the cost of the call, at the end of the stream. */
    data class Usage(val promptTokens: Long, val completionTokens: Long, val cost: Double?) : StreamPart
}

/** What one request asks for. [messages] are OpenAI-style chat messages. */
class ChatRequest(
    val model: String,
    val messages: JSONArray,
    val maxTokens: Long = 0L,
    /** OpenRouter only: route to providers that keep no data (PLAN §2.1). */
    val privateOnly: Boolean = false,
)

/** The JSON body of a streamed chat completion for [kind]. */
fun requestBody(kind: ProviderKind, request: ChatRequest): JSONObject = JSONObject().apply {
    put("model", request.model)
    put("messages", request.messages)
    put("stream", true)
    if (request.maxTokens > 0) put("max_tokens", request.maxTokens)
    if (kind == ProviderKind.OpenRouter) {
        put("usage", JSONObject().put("include", true))
        if (request.privateOnly) put("provider", JSONObject().put("zdr", true).put("data_collection", "deny"))
    } else {
        put("stream_options", JSONObject().put("include_usage", true))
    }
}

/**
 * The parts in one server-sent event's data. Throws [ProviderException] for an error sent mid-stream. "[DONE]" and
 * keep-alive events give nothing.
 */
fun parseStreamData(data: String, model: String, openRouter: Boolean): List<StreamPart> {
    val payload = data.trim()
    if (payload.isEmpty() || payload == "[DONE]") return emptyList()
    val o = try { JSONObject(payload) } catch (e: Exception) { return emptyList() }
    o.optJSONObject("error")?.let { e ->
        val code = e.optInt("code", 0)
        throw providerError(if (code in 400..599) code else 500, e.optString("message"), openRouter, model)
    }
    val out = ArrayList<StreamPart>(2)
    val choice = o.optJSONArray("choices")?.optJSONObject(0)
    val delta = choice?.optJSONObject("delta")
    if (delta != null) {
        val reasoning = delta.optString("reasoning").takeIf { !delta.isNull("reasoning") && it.isNotEmpty() }
            ?: delta.optString("reasoning_content").takeIf { !delta.isNull("reasoning_content") && it.isNotEmpty() }
        if (reasoning != null) out += StreamPart.Reasoning(reasoning)
        val content = if (delta.isNull("content")) "" else delta.optString("content")
        if (content.isNotEmpty()) out += StreamPart.Text(content)
    }
    o.optJSONObject("usage")?.let { u ->
        val cost = if (u.has("cost") && !u.isNull("cost")) u.optDouble("cost") else null
        out += StreamPart.Usage(u.optLong("prompt_tokens", 0L), u.optLong("completion_tokens", 0L), cost)
    }
    return out
}

/** Talks to the chosen provider: OpenRouter, or any OpenAI-compatible server (PLAN §2.1). */
object ProviderClient {
    /** Attribution for OpenRouter (PLAN §7.3); the referer joins once the GitHub address exists. */
    private const val TITLE = "Apel"

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // A key never follows a redirect to another address.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun builder(target: ProviderTarget, path: String): Request.Builder {
        val url = target.baseUrl.trimEnd('/') + path
        return Request.Builder().url(url).apply {
            if (target.key != null && ProviderEndpoints.mayCarryKey(url, target.baseUrl)) header("Authorization", "Bearer ${target.key}")
            if (target.kind == ProviderKind.OpenRouter) header("X-Title", TITLE)
        }
    }

    /** Streams one chat completion. Cancelling the flow cancels the call. Errors arrive as [ProviderException]. */
    fun stream(target: ProviderTarget, request: ChatRequest): Flow<StreamPart> = flow {
        val openRouter = target.kind == ProviderKind.OpenRouter
        val host = ProviderEndpoints.hostOf(target.baseUrl) ?: "the server"
        val body = requestBody(target.kind, request).toString().toRequestBody("application/json".toMediaType())
        val call = http.newCall(builder(target, "/chat/completions").header("Accept", "text/event-stream").post(body).build())
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = try { call.execute() } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            throw networkError(e, host)
        }
        response.use {
            if (!response.isSuccessful) {
                throw providerError(response.code, errorDetailOf(response.body?.string().orEmpty()), openRouter, request.model)
            }
            val source = response.body?.source() ?: throw ProviderException("The provider sent nothing back.", fallback = true)
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    // Lines that start with ":" are comments (OpenRouter's "processing" keep-alives).
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:")
                    if (data.trim() == "[DONE]") break
                    for (part in parseStreamData(data, request.model, openRouter)) emit(part)
                }
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw networkError(e, host)
            }
        }
    }.flowOn(Dispatchers.IO)

    /** The model ids an OpenAI-compatible server offers (`GET /models`), sorted. */
    suspend fun models(target: ProviderTarget): List<String> = withContext(Dispatchers.IO) {
        val host = ProviderEndpoints.hostOf(target.baseUrl) ?: "the server"
        try {
            http.newCall(builder(target, "/models").get().build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw providerError(response.code, errorDetailOf(text), target.kind == ProviderKind.OpenRouter, "")
                val data = try { JSONObject(text).optJSONArray("data") } catch (e: Exception) { null }
                    ?: throw ProviderException("That address answered, but not like an OpenAI-compatible server. Check that it ends in /v1.")
                List(data.length()) { data.optJSONObject(it)?.optString("id").orEmpty() }.filter { it.isNotBlank() }.sorted()
            }
        } catch (e: IOException) {
            throw networkError(e, host)
        }
    }
}
