package io.github.erkanw.vault

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.erkanw.vault.openrouter.OpenRouterHub
import io.github.erkanw.vault.openrouter.OrState
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.ProviderState
import io.github.erkanw.vault.provider.ProviderStore
import io.github.erkanw.vault.ui.DemoMode
import io.github.erkanw.vault.usage.Budget
import io.github.erkanw.vault.usage.UsageLog
import io.github.erkanw.vault.usage.UsageStats
import io.github.erkanw.vault.usage.UsageSummary
import io.github.erkanw.vault.widget.UsageWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Usage page (PLAN §6.11): the app's own log made into numbers, the credits and the key's limit from OpenRouter,
 * and the budget. The log is read again whenever an answer is logged.
 */
class UsageViewModel(app: Application) : AndroidViewModel(app) {
    private val demo = DemoMode.active(app)

    private val _summary = MutableStateFlow<UsageSummary?>(null)
    /** Null until read. */
    val summary: StateFlow<UsageSummary?> = _summary.asStateFlow()

    val credits: StateFlow<OrState> = OpenRouterHub.state
    val provider: StateFlow<ProviderState> = ProviderStore.state
    val budget: StateFlow<Budget.Status?> = Budget.status

    init {
        OpenRouterHub.ensureLoaded(app)
        ProviderStore.ensureLoaded(app)
        viewModelScope.launch { UsageLog.version.collect { load() } }
    }

    private suspend fun load() {
        val app = getApplication<Application>()
        val now = System.currentTimeMillis()
        _summary.value = withContext(Dispatchers.IO) {
            (if (demo) DemoMode.usage(now) else null)?.let { UsageStats.summarize(it, now, DemoMode.automationNames) } ?: UsageLog.summary(app, now)
        }
        withContext(Dispatchers.IO) { Budget.refresh(app, now) }
    }

    /** Opening the page: the log at once; the credits if they are more than two minutes old. */
    fun open() {
        viewModelScope.launch { load() }
        if (!demo) refreshCredits(maxAgeMs = 2 * 60_000L)
    }

    fun refreshCredits(maxAgeMs: Long = 0L) {
        if (ProviderStore.state.value.kind != ProviderKind.OpenRouter) return
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            if (maxAgeMs == 0L || OpenRouterHub.isStale(maxAgeMs)) OpenRouterHub.refresh(app)
        }
    }

    fun setBudget(limit: Double, action: Budget.Action) {
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            Budget.set(app, limit, action)
            UsageWidget.updateAll(app)
        }
    }
}
