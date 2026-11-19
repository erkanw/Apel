package io.github.erkanw.vault

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.ModelsHub
import io.github.erkanw.vault.openrouter.CatalogModel
import io.github.erkanw.vault.openrouter.OpenRouterHub
import io.github.erkanw.vault.openrouter.OrState
import io.github.erkanw.vault.openrouter.presetNameFor
import io.github.erkanw.vault.provider.OpenRouterAuth
import io.github.erkanw.vault.provider.ProviderClient
import io.github.erkanw.vault.provider.ProviderEndpoints
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.ProviderState
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.provider.ProviderTarget
import io.github.erkanw.vault.provider.RECOMMENDED_MODELS
import io.github.erkanw.vault.provider.RecommendedModel
import io.github.erkanw.vault.store.AgentProfile
import io.github.erkanw.vault.store.ModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where an OpenRouter sign-in stands. */
sealed interface SignIn {
    data object Idle : SignIn
    /** The browser is open; for [OpenRouterAuth.Method.Code] the screen asks for the code. */
    data class Waiting(val method: OpenRouterAuth.Method) : SignIn
    data object Exchanging : SignIn
    data class Failed(val message: String) : SignIn
}

/** The own-server form's progress: the models it offers once it answered. */
data class CustomServer(
    val working: Boolean = false,
    val error: String? = null,
    val warning: String? = null,
    val models: List<String> = emptyList(),
)

/** A model row in onboarding: the model, the words for its price, and whether it is free. */
data class ModelOption(val model: RecommendedModel, val price: String, val free: Boolean, val keepsData: Boolean, val catalog: CatalogModel?)

/**
 * The provider (PLAN §2.1, handbook §3 "Provider"): OpenRouter sign-in in three ways, your own server, private
 * providers, and the first model. Onboarding and the Provider page share it.
 */
class ProviderViewModel(app: Application) : AndroidViewModel(app) {
    private val models = ModelStore(app)

    init {
        ProviderStore.ensureLoaded(app)
        OpenRouterHub.ensureLoaded(app)
    }

    val state: StateFlow<ProviderState> = ProviderStore.state
    val openRouter: StateFlow<OrState> = OpenRouterHub.state

    private val _signIn = MutableStateFlow<SignIn>(SignIn.Idle)
    val signIn: StateFlow<SignIn> = _signIn.asStateFlow()

    private var attempt: OpenRouterAuth.Attempt? = null
    private var loopback: OpenRouterAuth.Loopback? = null
    private var loopbackJob: Job? = null

    /** Starts a sign-in; returns the address to open in a Custom Tab. [code] true: the paste-the-code way. */
    fun startSignIn(code: Boolean = false): String {
        cancelLoopback()
        val method = if (code) OpenRouterAuth.Method.Code else OpenRouterAuth.Method.Loopback
        val callback = when (method) {
            OpenRouterAuth.Method.Loopback -> OpenRouterAuth.Loopback().also { loopback = it }.callbackUrl
            OpenRouterAuth.Method.Code -> null
        }
        val a = OpenRouterAuth.Attempt(OpenRouterAuth.newVerifier(), OpenRouterAuth.newState(), method, callback)
        attempt = a
        _signIn.value = SignIn.Waiting(method)
        loopback?.let { server ->
            AgentService.holdForSignIn(getApplication())
            loopbackJob = viewModelScope.launch {
                try {
                    finishFromCallback(server.await())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (_signIn.value is SignIn.Waiting) _signIn.value = SignIn.Failed("The sign-in didn’t come back. Try again.")
                } finally {
                    cancelLoopback()
                }
            }
        }
        val device = listOf(Build.MANUFACTURER, Build.MODEL).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android" }
        return OpenRouterAuth.authUrl(a, "${getApplication<Application>().getString(R.string.app_name)} on $device")
    }

    /**
     * A code that came back while the app was behind the browser. Android cuts network access for apps in the
     * background, so it is exchanged once the app is in front again ([onForeground]); codes last ten minutes.
     */
    private var pendingCode: String? = null

    /** The browser came back with [url] (the loopback request). */
    fun finishFromCallback(url: String) {
        val a = attempt ?: return
        val code = OpenRouterAuth.codeFrom(url, a)
        if (code == null) {
            _signIn.value = SignIn.Failed("The sign-in came back without a code. Try again.")
            return
        }
        if (io.github.erkanw.vault.chat.ChatAlerts.appVisible) exchange(code, a) else pendingCode = code
    }

    /** The app is in front: finish a sign-in whose code arrived while it was in the background. */
    fun onForeground() {
        val code = pendingCode ?: return
        val a = attempt ?: return
        pendingCode = null
        exchange(code, a)
    }

    /** The code OpenRouter showed, typed or pasted (the headless way). */
    fun finishWithCode(code: String) {
        val a = attempt ?: return
        if (code.isBlank()) return
        exchange(code.trim(), a)
    }

    private fun exchange(code: String, a: OpenRouterAuth.Attempt) {
        _signIn.value = SignIn.Exchanging
        viewModelScope.launch {
            try {
                val key = OpenRouterAuth.exchange(code, a.verifier)
                val before = ProviderStore.state.value.kind
                if (before != ProviderKind.OpenRouter) models.clear()
                ProviderStore.connectOpenRouter(getApplication(), key)
                attempt = null
                _signIn.value = SignIn.Idle
                ModelsHub.refresh(AppGraph.backend(getApplication()))
                refreshUsage()
                ModelsHub.loadCatalog(getApplication())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _signIn.value = SignIn.Failed(e.message ?: "The sign-in didn’t finish. Try again.")
            }
        }
    }

    /** The user came back from the browser without finishing: stop waiting (the loopback stops listening). */
    fun cancelSignIn() {
        cancelLoopback()
        if (_signIn.value is SignIn.Waiting && attempt?.method != OpenRouterAuth.Method.Code) _signIn.value = SignIn.Idle
    }

    fun clearSignIn() {
        if (_signIn.value is SignIn.Failed) _signIn.value = SignIn.Idle
    }

    private fun cancelLoopback() {
        loopbackJob?.cancel()
        loopbackJob = null
        runCatching { loopback?.close() }
        if (loopback != null) AgentService.releaseSignIn(getApplication())
        loopback = null
    }

    override fun onCleared() {
        cancelLoopback()
        super.onCleared()
    }

    // ---- your own server ----

    private val _custom = MutableStateFlow(CustomServer())
    val custom: StateFlow<CustomServer> = _custom.asStateFlow()

    /** Checks the address, saves it with [key], and lists the server's models. */
    fun connectCustom(address: String, key: String) {
        val checked = try {
            ProviderEndpoints.check(address)
        } catch (e: IllegalArgumentException) {
            // A problem with the address itself shows on the Address field.
            _custom.value = CustomServer(warning = e.message)
            return
        }
        _custom.value = CustomServer(working = true, warning = checked.warning)
        viewModelScope.launch {
            try {
                val list = ProviderClient.models(ProviderTarget(ProviderKind.Custom, checked.url, key.trim().ifBlank { null }
                    ?: if (ProviderStore.state.value.customUrl == checked.url) ProviderStore.target(getApplication())?.key else null))
                if (ProviderStore.state.value.kind != ProviderKind.Custom || ProviderStore.state.value.customUrl != checked.url) models.clear()
                ProviderStore.connectCustom(getApplication(), checked.url, key)
                _custom.value = CustomServer(warning = checked.warning, models = list)
                ModelsHub.refresh(AppGraph.backend(getApplication()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _custom.value = CustomServer(error = e.message ?: "That server didn’t answer.", warning = checked.warning)
            }
        }
    }

    /** Lists a connected custom server's models again (the Provider page opens with it). */
    fun loadCustomModels() {
        val target = ProviderStore.target(getApplication()) ?: return
        if (target.kind != ProviderKind.Custom) return
        viewModelScope.launch {
            try {
                _custom.value = _custom.value.copy(models = ProviderClient.models(target), error = null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _custom.value = _custom.value.copy(error = e.message)
            }
        }
    }

    fun signOut() {
        ProviderStore.disconnect(getApplication())
        models.clear()
        _custom.value = CustomServer()
        viewModelScope.launch { ModelsHub.refresh(AppGraph.backend(getApplication())) }
    }

    /** Reads the credits and the key's limit from OpenRouter (the Provider page opens with it). */
    fun refreshUsage() {
        if (ProviderStore.state.value.kind != ProviderKind.OpenRouter) return
        viewModelScope.launch(Dispatchers.IO) { OpenRouterHub.refresh(getApplication()) }
    }

    fun setPrivateOnly(on: Boolean) = ProviderStore.setPrivateOnly(getApplication(), on)

    // ---- the first model ----

    val catalog = ModelsHub.catalog

    fun loadCatalog() {
        viewModelScope.launch { ModelsHub.loadCatalog(getApplication()) }
    }

    /** The onboarding rows, priced from the catalogue (the zero-retention price when private providers are on). */
    fun options(catalog: List<CatalogModel>, privateOnly: Boolean): List<ModelOption> = RECOMMENDED_MODELS.map { r ->
        val c = catalog.firstOrNull { it.id == r.id }
        val free = r.id.endsWith(":free") || c?.free == true
        val inP = if (c == null) 0.0 else if (privateOnly && c.zdr) c.zdrIn else c.promptPerM
        val outP = if (c == null) 0.0 else if (privateOnly && c.zdr) c.zdrOut else c.completionPerM
        // The role chip already says Free: the price line only gives the limit.
        ModelOption(r, if (free) "50 messages a day" else io.github.erkanw.vault.provider.priceLine(false, inP, outP), free, keepsData = c != null && !c.zdr, catalog = c)
    }

    /** The model id in use now (the main model), to preselect it when the welcome is shown again. */
    fun currentMainModel(): String? = models.callChain().firstOrNull()?.model

    /** Makes [model] the main model (adding it if it isn't there yet); [allowKeepsData] lets it skip private providers. */
    fun chooseModel(model: String, label: String, catalog: CatalogModel?, allowKeepsData: Boolean) {
        if (allowKeepsData) ProviderStore.exemptFromPrivate(getApplication(), model)
        val saved = models.load()
        val existing = saved.presets.firstOrNull { it.model == model }
        if (existing != null) {
            models.setOrder(listOf(existing.name) + saved.callOrder.filter { it != existing.name })
        } else {
            val name = presetNameFor(model, saved.presets.map { it.name }.toSet())
            models.add(ModelPreset(name, label, model, false, contextTokens = catalog?.contextTokens ?: 0L), asMain = true)
        }
        viewModelScope.launch(Dispatchers.Default) { ModelsHub.refresh(AppGraph.backend(getApplication())) }
    }

    // ---- the welcome ----

    fun finishWelcome(name: String) {
        AgentProfile.setName(getApplication(), name)
        AgentProfile.setWelcomeDone(getApplication(), true)
    }
}
