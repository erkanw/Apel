package io.github.erkanw.vault.chat

import android.content.Context
import io.github.erkanw.vault.CatalogState
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.openrouter.ModelCatalogClient
import io.github.erkanw.vault.openrouter.catalogToJson
import io.github.erkanw.vault.openrouter.parseCachedCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * The model presets and OpenRouter's catalogue, shared by the chat (its model picker) and the Models page, so a change
 * on one shows on the other.
 */
object ModelsHub {
    private val _choices = MutableStateFlow(ModelChoices.None)
    val choices: StateFlow<ModelChoices> = _choices.asStateFlow()

    private val _catalog = MutableStateFlow(CatalogState())
    val catalog: StateFlow<CatalogState> = _catalog.asStateFlow()

    private const val CATALOG_FRESH_MS = 6L * 60 * 60_000

    /** Reads the presets again; a failure keeps the last ones. */
    suspend fun refresh(backend: AgentBackend) {
        try {
            _choices.value = backend.models()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the last known presets.
        }
    }

    /** Shows [preset] as the one in use at once (the backend confirms on the next refresh). */
    fun markActive(preset: String) = _choices.update { it.copy(active = preset) }

    /** The OpenRouter catalogue: from the phone's copy when it is recent, else downloaded. [force] downloads it again. */
    suspend fun loadCatalog(context: Context, force: Boolean = false) {
        val state = _catalog.value
        if (state.loading || (!force && state.models.isNotEmpty() && System.currentTimeMillis() - state.at < CATALOG_FRESH_MS)) return
        _catalog.update { it.copy(loading = true, error = null) }
        val file = java.io.File(context.cacheDir, "model_catalog.json")
        try {
            val cached = if (force) null else withContext(Dispatchers.IO) { parseCachedCatalog(runCatching { file.readText() }.getOrNull()) }
            if (cached != null && System.currentTimeMillis() - cached.second < CATALOG_FRESH_MS) {
                _catalog.value = CatalogState(models = cached.first, at = cached.second)
                return
            }
            val fresh = withContext(Dispatchers.IO) { ModelCatalogClient.fetch() }
            val now = System.currentTimeMillis()
            withContext(Dispatchers.IO) { runCatching { file.writeText(catalogToJson(fresh, now)) } }
            _catalog.value = CatalogState(models = fresh, at = now)
        } catch (e: CancellationException) {
            _catalog.update { it.copy(loading = false) }
            throw e
        } catch (e: Exception) {
            _catalog.update { it.copy(loading = false, error = (e.message ?: "Couldn’t load OpenRouter’s models.") + " Tap to try again.") }
        }
    }
}
