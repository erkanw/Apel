package io.github.erkanw.vault.openrouter

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.data.SecretStore
import io.github.erkanw.vault.widget.OpenRouterWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The key (encrypted with a Keystore key, like the other secrets) and the last answer (numbers only, private to the app). */
class OpenRouterStore(context: Context) {
    private val prefs = context.getSharedPreferences("openrouter", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context, "openrouter_secret", "vault_openrouter")

    /** Whether a key is saved, without unlocking it: the widget and the page ask often. */
    fun hasKey(): Boolean = prefs.getBoolean("has_key", false)

    fun key(): String? = secrets.tokenFor(OpenRouterClient.BASE)

    /** A new key starts clean: what the old one could read isn't shown under the new one. */
    fun saveKey(key: String) {
        removeKey()
        secrets.save(OpenRouterClient.BASE, key)
        prefs.edit { putBoolean("has_key", true) }
    }

    fun removeKey() {
        secrets.clear()
        prefs.edit { clear() }
    }

    fun snapshot(): OrSnapshot? = prefs.getString("snapshot", null)?.let(::parseSnapshot)

    fun saveSnapshot(snapshot: OrSnapshot) = prefs.edit { putString("snapshot", snapshot.toJson()) }
}

/** What the OpenRouter page and widget show: whether there is a key, the last numbers, and what went wrong. */
data class OrState(
    val hasKey: Boolean = false,
    val snapshot: OrSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * One place that fetches, remembers and shares the OpenRouter numbers, for the page (through the view model) and for the
 * widget (which asks from its own thread). The numbers it keeps are the last good ones: a failed refresh says why but
 * doesn't wipe them.
 */
object OpenRouterHub {
    private val _state = MutableStateFlow(OrState())
    val state: StateFlow<OrState> = _state.asStateFlow()

    @Volatile private var loaded = false
    private val fetching = Any()

    /** Reads what was kept, once. Cheap (no Keystore). */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val store = OpenRouterStore(context.applicationContext)
            _state.value = OrState(hasKey = store.hasKey(), snapshot = store.snapshot())
            loaded = true
        }
    }

    /** Whether the numbers are older than [maxAgeMs] (or there are none). */
    fun isStale(maxAgeMs: Long, now: Long = System.currentTimeMillis()): Boolean {
        val at = _state.value.snapshot?.at ?: return true
        return now - at > maxAgeMs
    }

    /** Asks OpenRouter. Blocking, one at a time; true if it worked. [full] false skips the per-model detail (the widget). */
    fun refresh(context: Context, full: Boolean = true): Boolean = synchronized(fetching) { refreshLocked(context.applicationContext, full) }

    private fun refreshLocked(app: Context, full: Boolean): Boolean {
        ensureLoaded(app)
        val store = OpenRouterStore(app)
        val key = store.key()
        if (key == null) {
            _state.update { it.copy(hasKey = false, loading = false) }
            return false
        }
        _state.update { it.copy(hasKey = true, loading = true, error = null) }
        return when (val result = OpenRouterClient.fetch(key, _state.value.snapshot, full)) {
            is OpenRouterClient.Result.Ok -> {
                store.saveSnapshot(result.snapshot)
                _state.value = OrState(hasKey = true, snapshot = result.snapshot)
                OpenRouterWidget.updateAll(app)
                true
            }
            is OpenRouterClient.Result.Failed -> {
                _state.update { it.copy(loading = false, error = result.message) }
                false
            }
        }
    }

    fun saveKey(context: Context, key: String) {
        val app = context.applicationContext
        ensureLoaded(app)
        OpenRouterStore(app).saveKey(key)
        _state.value = OrState(hasKey = true)
    }

    fun removeKey(context: Context) {
        val app = context.applicationContext
        ensureLoaded(app)
        OpenRouterStore(app).removeKey()
        _state.value = OrState()
        OpenRouterWidget.updateAll(app)
    }
}
