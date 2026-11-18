package io.github.erkanw.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.chat.MODEL_LABEL_MAX
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.ModelPrefs
import io.github.erkanw.vault.chat.ModelPrefsStore
import io.github.erkanw.vault.chat.ModelsHub
import io.github.erkanw.vault.chat.SeenStore
import io.github.erkanw.vault.chat.movedModelOrder
import io.github.erkanw.vault.chat.prettyModelName
import io.github.erkanw.vault.data.BlobPrefs
import io.github.erkanw.vault.ui.Haptics
import io.github.erkanw.vault.ui.ThemeMode
import io.github.erkanw.vault.ui.applyNightMode
import io.github.erkanw.vault.widget.BlobWidgetUpdater
import io.github.erkanw.vault.widget.WidgetStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The settings area (PLAN §5): appearance, haptics, notifications, the widget, models and usage. */
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val backend: AgentBackend = AppGraph.backend(app)
    private val seenStore = SeenStore(app)

    /** False while no provider is connected (and demo mode is off). */
    val connected: Boolean get() = backend.connected

    // ---- appearance, haptics, the mascot at night --------------------------------------------------------

    /** Light, dark, or the phone's own setting. */
    private val _themeMode = MutableStateFlow(ThemeMode.fromId(BlobPrefs.themeId(app)))
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        BlobPrefs.setThemeId(getApplication<Application>(), mode.id)
        _themeMode.value = mode
        applyNightMode(getApplication(), mode)
    }

    private val _hapticsEnabled = MutableStateFlow(BlobPrefs.hapticsEnabled(app).also { Haptics.enabled = it })
    val hapticsEnabled: StateFlow<Boolean> = _hapticsEnabled.asStateFlow()

    fun setHapticsEnabled(on: Boolean) {
        BlobPrefs.setHapticsEnabled(getApplication<Application>(), on)
        Haptics.enabled = on
        _hapticsEnabled.value = on
    }

    private val _sleepEnabled = MutableStateFlow(BlobPrefs.sleepEnabled(app))
    val sleepEnabled: StateFlow<Boolean> = _sleepEnabled.asStateFlow()

    fun setSleepEnabled(on: Boolean) {
        BlobPrefs.setSleepEnabled(getApplication<Application>(), on)
        _sleepEnabled.value = on
        refreshWidget()
    }

    /** A notification when a reply finishes in the background. */
    private val _newMessageAlerts = MutableStateFlow(seenStore.enabled)
    val newMessageAlertsOn: StateFlow<Boolean> = _newMessageAlerts.asStateFlow()

    fun setNewMessageAlerts(on: Boolean) {
        seenStore.enabled = on
        _newMessageAlerts.value = on
    }

    // ---- the widget ----------------------------------------------------------------------------------------

    private val _widgetAnimated = MutableStateFlow(WidgetStatus.isAnimated(app))
    val widgetAnimated: StateFlow<Boolean> = _widgetAnimated.asStateFlow()

    private val _widgetBlended = MutableStateFlow(WidgetStatus.isBlended(app))
    val widgetBlended: StateFlow<Boolean> = _widgetBlended.asStateFlow()

    /** When the widget was last updated and how it went: "14:32:05 · OK · Thinking". */
    private val _widgetNote = MutableStateFlow(WidgetStatus.lastUpdate(app))
    val widgetNote: StateFlow<String> = _widgetNote.asStateFlow()

    fun setWidgetAnimated(on: Boolean) {
        WidgetStatus.setAnimated(getApplication<Application>(), on)
        _widgetAnimated.value = on
        refreshWidget()
    }

    fun setWidgetBlended(on: Boolean) {
        WidgetStatus.setBlended(getApplication<Application>(), on)
        _widgetBlended.value = on
        refreshWidget()
    }

    /** Redraws the widget now, from the current state (Settings has a button for it). */
    fun refreshWidget() {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.Default) {
            // Draws a few dozen small bitmaps: off the main thread. It records its own outcome.
            BlobWidgetUpdater.updateAll(app)
            _widgetNote.value = WidgetStatus.lastUpdate(app)
        }
    }

    // ---- usage -------------------------------------------------------------------------------------------------

    /** This month against the budget, for the Usage row. */
    val budget: StateFlow<io.github.erkanw.vault.usage.Budget.Status?> = io.github.erkanw.vault.usage.Budget.status

    init {
        ModelPrefsStore.ensureLoaded(app)
        viewModelScope.launch(Dispatchers.IO) { io.github.erkanw.vault.usage.Budget.refresh(app) }
    }

    // ---- models -----------------------------------------------------------------------------------------------

    /** The presets as they are: no phone choices applied. The Models page edits these. */
    val modelsRaw: StateFlow<ModelChoices> = ModelsHub.choices

    /** What you chose on this phone: labels, hidden ones, order. */
    val modelPrefs: StateFlow<ModelPrefs> = ModelPrefsStore.prefs

    val catalog: StateFlow<CatalogState> = ModelsHub.catalog

    /** A change in progress, or the reason it failed (shown on the Models page). */
    private val _modelsStatus = MutableStateFlow(ModelsStatus())
    val modelsStatus: StateFlow<ModelsStatus> = _modelsStatus.asStateFlow()

    /** Reads the presets again (the page opens with it, and every change ends with it). */
    fun loadModels() {
        viewModelScope.launch { ModelsHub.refresh(backend) }
    }

    fun loadCatalog(force: Boolean = false) {
        viewModelScope.launch { ModelsHub.loadCatalog(getApplication(), force) }
    }

    fun clearModelsStatus() {
        _modelsStatus.value = ModelsStatus()
    }

    fun setModelLabel(name: String, label: String) = ModelPrefsStore.update(getApplication()) { p ->
        val clean = label.trim().take(MODEL_LABEL_MAX)
        p.copy(labels = if (clean.isEmpty()) p.labels - name else p.labels + (name to clean))
    }

    fun setModelHidden(name: String, hidden: Boolean) = ModelPrefsStore.update(getApplication()) { p ->
        p.copy(hidden = if (hidden) p.hidden + name else p.hidden - name)
    }

    /** One place up or down in the chat's model list. [shown] is the order you see now. */
    fun moveModel(name: String, up: Boolean, shown: List<String>) = ModelPrefsStore.update(getApplication()) { p ->
        p.copy(order = movedModelOrder(shown, name, up))
    }

    /** Runs a change, then reads the models again. Says what is happening, and why it failed if it does. */
    private fun changeModels(doing: String, work: suspend () -> Unit, done: () -> Unit = {}) {
        if (!backend.connected) {
            _modelsStatus.value = ModelsStatus(error = getApplication<Application>().getString(R.string.not_connected))
            return
        }
        if (_modelsStatus.value.working != null) return
        _modelsStatus.value = ModelsStatus(working = doing)
        viewModelScope.launch {
            try {
                work()
                _modelsStatus.value = ModelsStatus()
                ModelsHub.refresh(backend)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _modelsStatus.value = ModelsStatus(error = e.message ?: "The change wasn’t saved.")
            }
        }
    }

    fun addModel(name: String, model: String, maxTokens: Long, contextTokens: Long, done: () -> Unit) =
        changeModels("Adding ${prettyModelName(model)}…", { backend.createModel(name, model, maxTokens, contextTokens) }, done)

    fun saveModelLimits(name: String, maxTokens: Long, contextTokens: Long) =
        changeModels("Saving…", { backend.updateModel(name, maxTokens, contextTokens) })

    fun removeModel(name: String) = changeModels("Removing…", { backend.deleteModel(name) })

    /** Makes [name] the main model; the one that was main becomes the first it falls back on. */
    fun makeMainModel(name: String) {
        val order = ModelsHub.choices.value.callOrder
        changeModels("Changing the main model…", { backend.setModelOrder(listOf(name) + order.filter { n -> n != name }) })
    }

    /** Puts [name] among the models to fall back on (at the end), or takes it out. The main one can't be taken out. */
    fun setModelFallback(name: String, on: Boolean) {
        val order = ModelsHub.choices.value.callOrder
        val next = if (on) order.filter { it != name } + name else order.filter { it != name }
        if (next.isEmpty()) return
        changeModels("Saving…", { backend.setModelOrder(next) })
    }
}
