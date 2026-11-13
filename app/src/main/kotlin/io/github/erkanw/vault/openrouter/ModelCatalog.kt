package io.github.erkanw.vault.openrouter

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A model OpenRouter offers, for the "Add model" page. Prices are dollars per million tokens. [zdr] says that at least one
 * provider of it keeps no data (zero data retention); [zdrIn] and [zdrOut] are what the cheapest such provider asks.
 */
data class CatalogModel(
    val id: String,
    val name: String,
    val contextTokens: Long,
    val promptPerM: Double,
    val completionPerM: Double,
    val tools: Boolean,
    val reasoning: Boolean,
    val created: Long,
    val zdr: Boolean,
    val zdrIn: Double = 0.0,
    val zdrOut: Double = 0.0,
) {
    val free: Boolean get() = id.endsWith(":free") || (promptPerM == 0.0 && completionPerM == 0.0)

    /** What it costs at best: the cheapest zero-retention provider if it has one, else the listed price. */
    val bestIn: Double get() = if (zdr) zdrIn else promptPerM
    val bestOut: Double get() = if (zdr) zdrOut else completionPerM
}

enum class CatalogSort(val label: String) { Cheapest("Cheapest"), Newest("Newest"), Context("Context") }

/**
 * Reads the two public lists: `GET /models` (every model) and `GET /endpoints/zdr` (every provider endpoint that keeps no
 * data). A model with an entry in the second has [CatalogModel.zdr]. Neither needs a key.
 */
fun parseCatalog(modelsBody: String, zdrBody: String): List<CatalogModel> {
    val cheapest = HashMap<String, Pair<Double, Double>>()
    val zdrList = try { JSONObject(zdrBody).optJSONArray("data") } catch (e: Exception) { null } ?: JSONArray()
    for (i in 0 until zdrList.length()) {
        val o = zdrList.optJSONObject(i) ?: continue
        val id = o.optString("model_id")
        val price = o.optJSONObject("pricing") ?: continue
        val prompt = price.optString("prompt").toDoubleOrNull() ?: continue
        val completion = price.optString("completion").toDoubleOrNull() ?: continue
        if (id.isBlank()) continue
        val now = prompt * 1e6 to completion * 1e6
        val before = cheapest[id]
        if (before == null || now.first + now.second < before.first + before.second) cheapest[id] = now
    }
    val models = try { JSONObject(modelsBody).optJSONArray("data") } catch (e: Exception) { null } ?: return emptyList()
    val out = ArrayList<CatalogModel>(models.length())
    for (i in 0 until models.length()) {
        val o = models.optJSONObject(i) ?: continue
        val id = o.optString("id")
        if (id.isBlank()) continue
        val price = o.optJSONObject("pricing")
        val params = o.optJSONArray("supported_parameters")
        val supported = if (params == null) emptySet() else (0 until params.length()).map { params.optString(it) }.toSet()
        val zdrPrice = cheapest[id]
        out.add(
            CatalogModel(
                id = id,
                name = o.optString("name").ifBlank { id },
                contextTokens = o.optLong("context_length", 0L),
                promptPerM = (price?.optString("prompt")?.toDoubleOrNull() ?: 0.0) * 1e6,
                completionPerM = (price?.optString("completion")?.toDoubleOrNull() ?: 0.0) * 1e6,
                tools = "tools" in supported,
                reasoning = "reasoning" in supported,
                created = o.optLong("created", 0L),
                zdr = zdrPrice != null,
                zdrIn = zdrPrice?.first ?: 0.0,
                zdrOut = zdrPrice?.second ?: 0.0,
            ),
        )
    }
    return out
}

/** The models that match: by words of [query] in the name or id, then the switches, in the order asked for. */
fun filterCatalog(
    all: List<CatalogModel>,
    query: String,
    zdrOnly: Boolean,
    toolsOnly: Boolean,
    freeOnly: Boolean,
    sort: CatalogSort,
): List<CatalogModel> {
    val words = query.lowercase().split(' ', '-', '/').filter { it.isNotBlank() }
    val kept = all.filter { m ->
        (!zdrOnly || m.zdr) && (!toolsOnly || m.tools) && (!freeOnly || m.free) &&
            words.all { w -> m.id.lowercase().contains(w) || m.name.lowercase().contains(w) }
    }
    return when (sort) {
        CatalogSort.Cheapest -> kept.sortedWith(compareBy({ it.bestIn + it.bestOut }, { it.id }))
        CatalogSort.Newest -> kept.sortedWith(compareByDescending<CatalogModel> { it.created }.thenBy { it.id })
        CatalogSort.Context -> kept.sortedWith(compareByDescending<CatalogModel> { it.contextTokens }.thenBy { it.id })
    }
}

/**
 * A short name for a new preset, from the model's id ("openai/gpt-oss-120b" is "gpt-oss-120b"), made unique among [taken]
 * ("gpt-oss-120b-2"). Letters, digits, dots, dashes and underscores only: it is what `/model` takes.
 */
fun presetNameFor(model: String, taken: Set<String>): String {
    val base = model.substringAfterLast('/').substringBefore(':').lowercase()
        .replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty { "model" }.take(32)
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

private fun usdPerM(v: Double) = when {
    v == 0.0 -> "$0"
    v < 0.01 -> String.format(java.util.Locale.US, "$%.3f", v)
    v < 10 -> String.format(java.util.Locale.US, "$%.2f", v)
    else -> String.format(java.util.Locale.US, "$%.0f", v)
}

/** "$0.03 / $0.15 per M": what a million tokens in and out cost. */
fun priceLine(inPerM: Double, outPerM: Double): String = "${usdPerM(inPerM)} / ${usdPerM(outPerM)} per M"

/** The catalogue entry for a model id: the exact id, else the same model without a variant suffix ("openai/x:nitro" is "openai/x"). */
fun findCatalogModel(models: List<CatalogModel>, id: String): CatalogModel? {
    if (id.isBlank() || models.isEmpty()) return null
    return models.firstOrNull { it.id == id } ?: models.firstOrNull { it.id == id.substringBefore(':') }
}

/** What a model costs, for the model list in the chat: "In $0.03 · Out $0.15 per 1M tokens", or "Free". */
fun priceDetail(m: CatalogModel): String =
    if (m.free) "Free" else "In ${usdPerM(m.bestIn)} · Out ${usdPerM(m.bestOut)} per 1M tokens"

/** Fetches the two lists. OpenRouter's own public address, fixed here; no key is sent. Blocking: call it off the main thread. */
object ModelCatalogClient {
    private const val BASE = "https://openrouter.ai/api/v1"
    private const val MAX_BYTES = 12L * 1024 * 1024

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private fun get(path: String): String {
        val request = Request.Builder().url("$BASE/$path").header("Accept", "application/json").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("OpenRouter answered HTTP ${response.code}.")
            val body = response.body ?: throw IOException("OpenRouter sent nothing.")
            if (body.contentLength() > MAX_BYTES) throw IOException("OpenRouter's list is too large.")
            return body.string()
        }
    }

    fun fetch(): List<CatalogModel> {
        val models = get("models")
        val zdr = get("endpoints/zdr")
        return parseCatalog(models, zdr).also { if (it.isEmpty()) throw IOException("OpenRouter's list was empty or not readable.") }
    }
}

/** The catalogue as it is kept on the phone between visits (a few hours), so the page doesn't download it each time. */
fun catalogToJson(models: List<CatalogModel>, at: Long): String = JSONObject().put("at", at).put(
    "models",
    JSONArray().also { a ->
        models.forEach { m ->
            a.put(
                JSONObject().put("id", m.id).put("name", m.name).put("ctx", m.contextTokens).put("in", m.promptPerM)
                    .put("out", m.completionPerM).put("tools", m.tools).put("reasoning", m.reasoning).put("created", m.created)
                    .put("zdr", m.zdr).put("zin", m.zdrIn).put("zout", m.zdrOut),
            )
        }
    },
).toString()

fun parseCachedCatalog(json: String?): Pair<List<CatalogModel>, Long>? {
    if (json.isNullOrBlank()) return null
    val root = try { JSONObject(json) } catch (e: Exception) { return null }
    val a = root.optJSONArray("models") ?: return null
    val list = List(a.length()) { i ->
        val o = a.optJSONObject(i) ?: JSONObject()
        CatalogModel(
            id = o.optString("id"), name = o.optString("name"), contextTokens = o.optLong("ctx"),
            promptPerM = o.optDouble("in", 0.0), completionPerM = o.optDouble("out", 0.0),
            tools = o.optBoolean("tools"), reasoning = o.optBoolean("reasoning"), created = o.optLong("created"),
            zdr = o.optBoolean("zdr"), zdrIn = o.optDouble("zin", 0.0), zdrOut = o.optDouble("zout", 0.0),
        )
    }.filter { it.id.isNotBlank() }
    return if (list.isEmpty()) null else list to root.optLong("at", 0L)
}
