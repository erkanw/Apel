package io.github.erkanw.vault.usage

import android.content.Context
import io.github.erkanw.vault.store.AppDatabase
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The monthly budget (PLAN §6.11). Phase 7 adds the page that sets it; until then no limit is set and nothing pauses.
 * Months are UTC months, like OpenRouter's.
 */
object Budget {
    private const val PREFS = "budget"
    const val KEY_LIMIT = "monthly_limit"
    /** "pause", "ask" or "both". */
    const val KEY_ACTION = "action"

    /** Dollars spent since the start of this UTC month. */
    suspend fun spentThisMonth(context: Context): Double {
        val start = ZonedDateTime.now(ZoneOffset.UTC).withDayOfMonth(1).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        return AppDatabase.get(context).usage().since(start).sumOf { it.cost ?: 0.0 }
    }

    /** Why automations are paused now (the limit is reached and its action pauses them), or null. */
    suspend fun automationsPaused(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val limit = prefs.getFloat(KEY_LIMIT, 0f).toDouble()
        if (limit <= 0.0) return null
        if (prefs.getString(KEY_ACTION, "pause") !in setOf("pause", "both")) return null
        return if (spentThisMonth(context) >= limit) "Skipped: this month’s budget of $" + "%.2f".format(limit) + " is used up." else null
    }
}
