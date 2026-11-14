package io.github.erkanw.vault.provider

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.data.SecretStore
import io.github.erkanw.vault.openrouter.OpenRouterHub
import io.github.erkanw.vault.openrouter.OpenRouterStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which provider the agent talks to. */
enum class ProviderKind { None, OpenRouter, Custom }

/**
 * The provider as the screens see it: no secrets, only whether one is there. [privateOnly] adds OpenRouter's
 * zero-retention routing to every request, except for the models in [privateExempt] (free models the user chose
 * knowingly, PLAN §2.1).
 */
data class ProviderState(
    val kind: ProviderKind = ProviderKind.None,
    val customUrl: String = "",
    val customHasKey: Boolean = false,
    val privateOnly: Boolean = true,
    val privateExempt: Set<String> = emptySet(),
) {
    val connected: Boolean get() = kind != ProviderKind.None
}

/** Where the request goes and with which key. Built for each turn; never shown and never logged. */
class ProviderTarget(val kind: ProviderKind, val baseUrl: String, val key: String?)

/**
 * The provider settings, kept on the phone; the keys are in the Keystore, each bound to its host (PLAN §10): the
 * OpenRouter key in [OpenRouterStore] (only ever sent to openrouter.ai), a custom server's key bound to its exact address.
 */
object ProviderStore {
    private const val PREFS = "provider"
    private val _state = MutableStateFlow(ProviderState())
    val state: StateFlow<ProviderState> = _state.asStateFlow()

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _state.value = read(context.applicationContext)
            loaded = true
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun customSecrets(context: Context) = SecretStore(context.applicationContext, "custom_secret", "vault_custom_key")

    private fun read(context: Context): ProviderState {
        val p = prefs(context)
        val kind = ProviderKind.entries.firstOrNull { it.name == p.getString("kind", null) } ?: ProviderKind.None
        val url = p.getString("customUrl", "").orEmpty()
        val effective = when {
            kind == ProviderKind.OpenRouter && !OpenRouterStore(context).hasKey() -> ProviderKind.None
            kind == ProviderKind.Custom && url.isBlank() -> ProviderKind.None
            else -> kind
        }
        return ProviderState(
            kind = effective,
            customUrl = url,
            customHasKey = url.isNotBlank() && customSecrets(context).tokenFor(url) != null,
            privateOnly = p.getBoolean("privateOnly", true),
            privateExempt = p.getStringSet("privateExempt", emptySet()).orEmpty().toSet(),
        )
    }

    private fun refresh(context: Context) {
        _state.value = read(context.applicationContext)
    }

    /** The key from a finished OpenRouter sign-in: kept encrypted, and OpenRouter becomes the provider. */
    fun connectOpenRouter(context: Context, key: String) {
        OpenRouterHub.saveKey(context.applicationContext, key)
        prefs(context).edit { putString("kind", ProviderKind.OpenRouter.name) }
        refresh(context)
    }

    /** A custom server ([url] already checked by [ProviderEndpoints.check]); a blank [key] keeps the saved one. */
    fun connectCustom(context: Context, url: String, key: String) {
        val secrets = customSecrets(context)
        val before = prefs(context).getString("customUrl", "")
        if (before != url) secrets.clear()
        if (key.isNotBlank()) secrets.save(url, key.trim())
        prefs(context).edit {
            putString("customUrl", url)
            putString("kind", ProviderKind.Custom.name)
        }
        refresh(context)
    }

    /** Signs out: the key is forgotten on this phone. (An OpenRouter key stays in the account until deleted there.) */
    fun disconnect(context: Context) {
        when (_state.value.kind) {
            ProviderKind.OpenRouter -> OpenRouterHub.removeKey(context.applicationContext)
            ProviderKind.Custom -> customSecrets(context).clear()
            ProviderKind.None -> Unit
        }
        prefs(context).edit { putString("kind", ProviderKind.None.name) }
        refresh(context)
    }

    fun setPrivateOnly(context: Context, on: Boolean) {
        prefs(context).edit {
            putBoolean("privateOnly", on)
            if (on) remove("privateExempt")
        }
        refresh(context)
    }

    /** Lets [model] go to providers that may keep data (a free model the user chose after the warning). */
    fun exemptFromPrivate(context: Context, model: String) {
        val next = _state.value.privateExempt + model
        prefs(context).edit { putStringSet("privateExempt", next) }
        refresh(context)
    }

    /** Where to send a request now, with its key; null when nothing is connected. */
    fun target(context: Context): ProviderTarget? = when (_state.value.kind) {
        ProviderKind.OpenRouter -> OpenRouterStore(context.applicationContext).key()?.let {
            ProviderTarget(ProviderKind.OpenRouter, ProviderEndpoints.OPENROUTER, it)
        }
        ProviderKind.Custom -> _state.value.customUrl.takeIf { it.isNotBlank() }?.let { url ->
            ProviderTarget(ProviderKind.Custom, url, customSecrets(context).tokenFor(url))
        }
        ProviderKind.None -> null
    }
}
