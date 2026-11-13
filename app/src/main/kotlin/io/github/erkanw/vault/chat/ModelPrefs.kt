package io.github.erkanw.vault.chat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * What you chose, on this phone only, about the server's models: which ones the chat's model list shows ([hidden]
 * ones are left out of it), in what [order], and the [labels] you gave them. The server is never told.
 */
data class ModelPrefs(
    val hidden: Set<String> = emptySet(),
    val order: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = hidden.isEmpty() && order.isEmpty() && labels.isEmpty()
}

const val MODEL_LABEL_MAX = 24

/**
 * The server's models with your choices applied: your label in place of the server's, the hidden ones marked, and the
 * list in your order (a model you never placed comes after the ones you did, in the server's order).
 */
fun applyModelPrefs(choices: ModelChoices, prefs: ModelPrefs): ModelChoices {
    if (prefs.isEmpty) return choices
    val position = prefs.order.withIndex().associate { (i, name) -> name to i }
    val shown = choices.presets
        .map { p ->
            p.copy(
                label = prefs.labels[p.name]?.takeIf { it.isNotBlank() } ?: p.label,
                hidden = p.name in prefs.hidden,
            )
        }
        .sortedWith(compareBy({ position[it.name] ?: Int.MAX_VALUE }))
    return choices.copy(presets = shown)
}

/** The same list with [name] one place earlier (or later) in what you see; the rest keeps its order. */
fun movedModelOrder(current: List<String>, name: String, up: Boolean): List<String> {
    val list = current.toMutableList()
    val i = list.indexOf(name)
    if (i < 0) return current
    val j = if (up) i - 1 else i + 1
    if (j !in list.indices) return current
    list[i] = list[j].also { list[j] = list[i] }
    return list
}

fun modelPrefsToJson(prefs: ModelPrefs): String = JSONObject()
    .put("hidden", JSONArray(prefs.hidden.toList()))
    .put("order", JSONArray(prefs.order))
    .put("labels", JSONObject().also { o -> prefs.labels.forEach { (k, v) -> o.put(k, v) } })
    .toString()

fun parseModelPrefs(json: String?): ModelPrefs {
    if (json.isNullOrBlank()) return ModelPrefs()
    val root = try { JSONObject(json) } catch (e: Exception) { return ModelPrefs() }
    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else List(a.length()) { a.optString(it) }.filter { it.isNotBlank() }
    val labels = root.optJSONObject("labels")?.let { o ->
        o.keys().asSequence().associateWith { o.optString(it).take(MODEL_LABEL_MAX) }.filterValues { it.isNotBlank() }
    }.orEmpty()
    // Older versions also kept a skin per model ("skins"); it is ignored now.
    return ModelPrefs(strings(root.optJSONArray("hidden")).toSet(), strings(root.optJSONArray("order")), labels)
}

/** The choices above, kept on the phone; read by the chat's model list and by the Models page. */
object ModelPrefsStore {
    private const val PREFS = "model_prefs"
    private const val KEY = "prefs"
    private val _prefs = MutableStateFlow(ModelPrefs())
    val prefs: StateFlow<ModelPrefs> = _prefs.asStateFlow()
    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _prefs.value = parseModelPrefs(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null))
            loaded = true
        }
    }

    fun update(context: Context, change: (ModelPrefs) -> ModelPrefs) {
        ensureLoaded(context)
        val next = change(_prefs.value)
        _prefs.value = next
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, modelPrefsToJson(next)) }
    }
}
