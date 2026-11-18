package io.github.erkanw.vault.usage

import android.content.Context
import android.util.Log
import io.github.erkanw.vault.store.AppDatabase
import io.github.erkanw.vault.store.UsageEntity
import io.github.erkanw.vault.widget.UsageWidget
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where every answer's cost is written (the runtime, compaction, Tidy memory) and read back (the Usage page, the widget
 * and the budget). Writing one says so: the budget is checked and the pages and the widget redraw.
 */
object UsageLog {
    private val _version = MutableStateFlow(0)
    /** Goes up with every row written, for the pages that show the log. */
    val version: StateFlow<Int> = _version.asStateFlow()

    suspend fun record(context: Context, row: UsageEntity) {
        val app = context.applicationContext
        try {
            AppDatabase.get(app).usage().insert(row)
        } catch (e: Exception) {
            Log.w("UsageLog", "couldn't log usage", e)
            return
        }
        _version.value++
        runCatching { Budget.afterSpend(app) }
        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { UsageWidget.updateAll(app) } }
    }

    /** The log from [since] on, as the statistics read it. */
    suspend fun calls(context: Context, since: Long): List<UsageCall> =
        AppDatabase.get(context).usage().since(since).map {
            UsageCall(it.time, it.model, it.source, it.automationId, it.requests.coerceAtLeast(1), it.tokensIn, it.tokensOut, it.cost)
        }

    /** The page's summary now, with each automation's name. */
    suspend fun summary(context: Context, now: Long = System.currentTimeMillis()): UsageSummary {
        val names = AppDatabase.get(context).automations().all().associate { it.id to it.name }
        return UsageStats.summarize(calls(context, UsageStats.since(now)), now, names)
    }
}
