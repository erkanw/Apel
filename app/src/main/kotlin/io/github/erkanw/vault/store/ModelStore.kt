package io.github.erkanw.vault.store

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.ModelPreset
import org.json.JSONArray
import org.json.JSONObject

/**
 * The model presets on the phone (PLAN §7.3): each a model id with a short name, its context window and output limit;
 * the call order is the main model first, then the ones a turn falls back on. Kept as JSON in preferences.
 */
class ModelStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("models", Context.MODE_PRIVATE)

    data class Saved(val presets: List<ModelPreset>, val callOrder: List<String>)

    @Synchronized
    fun load(): Saved = parseModels(prefs.getString(KEY, null))

    @Synchronized
    private fun save(saved: Saved) = prefs.edit { putString(KEY, modelsToJson(saved)) }

    /** The choices as the screens use them: [ModelChoices.active] is the main model (first in the call order). */
    fun choices(): ModelChoices {
        val s = load()
        val main = s.callOrder.firstOrNull { name -> s.presets.any { it.name == name } } ?: s.presets.firstOrNull()?.name
        return ModelChoices(s.presets.map { it.copy(active = it.name == main, isDefault = false) }, main, s.callOrder, callOrderEditable = true)
    }

    fun preset(name: String?): ModelPreset? = name?.let { n -> load().presets.firstOrNull { it.name == n } }

    /** The main model, then its fallbacks, as presets. */
    fun callChain(): List<ModelPreset> {
        val s = load()
        val ordered = s.callOrder.mapNotNull { n -> s.presets.firstOrNull { it.name == n } }
        return ordered.ifEmpty { s.presets.take(1) }
    }

    fun add(preset: ModelPreset, asMain: Boolean = false) {
        val s = load()
        if (s.presets.any { it.name == preset.name }) throw IllegalArgumentException("There is already a model called ${preset.name}.")
        val order = when {
            asMain -> listOf(preset.name) + s.callOrder
            s.callOrder.isEmpty() -> listOf(preset.name)
            else -> s.callOrder
        }
        save(Saved(s.presets + preset, order))
    }

    fun update(name: String, maxTokens: Long, contextTokens: Long) {
        val s = load()
        save(s.copy(presets = s.presets.map { if (it.name == name) it.copy(maxTokens = maxTokens, contextTokens = contextTokens) else it }))
    }

    fun remove(name: String) {
        val s = load()
        save(Saved(s.presets.filterNot { it.name == name }, s.callOrder - name))
    }

    fun setOrder(order: List<String>) {
        val s = load()
        save(s.copy(callOrder = order.filter { n -> s.presets.any { it.name == n } }))
    }

    /** Signing out of one provider and into another: the old models don't run there. */
    fun clear() = prefs.edit { remove(KEY) }

    private companion object {
        const val KEY = "presets"
    }
}

fun modelsToJson(saved: ModelStore.Saved): String = JSONObject()
    .put("presets", JSONArray().also { a ->
        saved.presets.forEach { p ->
            a.put(JSONObject().put("name", p.name).put("label", p.label).put("model", p.model).put("ctx", p.contextTokens).put("max", p.maxTokens))
        }
    })
    .put("order", JSONArray(saved.callOrder))
    .toString()

fun parseModels(json: String?): ModelStore.Saved {
    if (json.isNullOrBlank()) return ModelStore.Saved(emptyList(), emptyList())
    val root = try { JSONObject(json) } catch (e: Exception) { return ModelStore.Saved(emptyList(), emptyList()) }
    val a = root.optJSONArray("presets") ?: JSONArray()
    val presets = List(a.length()) { i ->
        val o = a.optJSONObject(i) ?: JSONObject()
        ModelPreset(o.optString("name"), o.optString("label").ifBlank { o.optString("name") }, o.optString("model"), false,
            contextTokens = o.optLong("ctx", 0L), maxTokens = o.optLong("max", 0L))
    }.filter { it.name.isNotBlank() && it.model.isNotBlank() }
    val order = root.optJSONArray("order")?.let { o -> List(o.length()) { o.optString(it) } }.orEmpty().filter { n -> presets.any { it.name == n } }
    return ModelStore.Saved(presets, order)
}
