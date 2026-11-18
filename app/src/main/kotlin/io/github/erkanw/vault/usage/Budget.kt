package io.github.erkanw.vault.usage

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import io.github.erkanw.vault.MainActivity
import io.github.erkanw.vault.R
import io.github.erkanw.vault.openrouter.usd
import io.github.erkanw.vault.store.AppDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The monthly budget (PLAN §6.11): off by default; a limit in dollars per UTC month (as OpenRouter counts), a
 * notification at 80 %, and at the limit what was chosen: pause automations, ask before each message, or both.
 * The limit is what the app's own log says was spent (the Usage page), so it holds with any provider that prices.
 */
object Budget {
    private const val PREFS = "budget"
    private const val KEY_LIMIT = "monthly_limit"
    private const val KEY_ACTION = "action"
    private const val KEY_WARNED = "warned"
    const val SUGGESTED = 5.0
    const val WARN_AT = 0.8

    enum class Action(val id: String, val label: String) {
        Pause("pause", "Pause automations"),
        Ask("ask", "Ask first"),
        Both("both", "Both");

        val pauses: Boolean get() = this != Ask
        val asks: Boolean get() = this != Pause
    }

    /** The limit (0: no budget) and what happens at it. */
    data class Settings(val limit: Double, val action: Action) {
        val on: Boolean get() = limit > 0.0
    }

    /** Where this month stands: [month] is "2026-11", so last month's state is never taken for this one's. */
    data class Status(val settings: Settings, val spent: Double, val month: String) {
        val fraction: Double get() = if (settings.on) spent / settings.limit else 0.0
        val over: Boolean get() = settings.on && spent >= settings.limit
        val near: Boolean get() = settings.on && fraction >= WARN_AT
    }

    private val _status = MutableStateFlow<Status?>(null)
    /** The last known state, for the chat (which asks before sending) and the pages. Null until first read. */
    val status: StateFlow<Status?> = _status.asStateFlow()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): Settings {
        val p = prefs(context)
        val action = Action.entries.firstOrNull { it.id == p.getString(KEY_ACTION, null) } ?: Action.Pause
        return Settings(p.getFloat(KEY_LIMIT, 0f).toDouble().coerceAtLeast(0.0), action)
    }

    /** Sets the budget ([limit] 0 turns it off) and reads the month again. */
    suspend fun set(context: Context, limit: Double, action: Action) {
        prefs(context).edit {
            putFloat(KEY_LIMIT, limit.coerceAtLeast(0.0).toFloat())
            putString(KEY_ACTION, action.id)
            // A new limit may warn again this month.
            remove(KEY_WARNED)
        }
        refresh(context)
    }

    private fun monthKey(now: Long) = UsageStats.utcDay(now).toString().take(7)

    /** Dollars spent since the start of this UTC month, from the log. */
    suspend fun spentThisMonth(context: Context, now: Long = System.currentTimeMillis()): Double =
        AppDatabase.get(context).usage().since(UsageStats.monthStart(now)).sumOf { it.cost?.takeIf { c -> !c.isNaN() } ?: 0.0 }

    /** Reads the month now and keeps it in [status]. */
    suspend fun refresh(context: Context, now: Long = System.currentTimeMillis()): Status {
        val s = Status(settings(context), spentThisMonth(context, now), monthKey(now))
        _status.value = s
        return s
    }

    /** Whether a message should wait for "Send anyway": the budget is used up and asking was chosen. Cheap (no database). */
    fun asksBeforeSending(now: Long = System.currentTimeMillis()): Boolean {
        val s = _status.value ?: return false
        return s.month == monthKey(now) && s.over && s.settings.action.asks
    }

    /**
     * Why automations are paused now (the limit is reached and its action pauses them), or null. [extra] is spent in a
     * turn that is still running and not logged yet.
     */
    suspend fun automationsPaused(context: Context, extra: Double = 0.0): String? {
        val settings = settings(context)
        if (!settings.on || !settings.action.pauses) return null
        return if (spentThisMonth(context) + extra >= settings.limit) "Skipped: this month’s budget of ${usd(settings.limit)} is used up." else null
    }

    /** After an answer was logged: reads the month, and says so once at 80 % and once at the limit. */
    suspend fun afterSpend(context: Context) {
        val s = refresh(context)
        if (!s.settings.on) return
        val level = when {
            s.over -> "over"
            s.near -> "near"
            else -> return
        }
        val p = prefs(context)
        val mark = "${s.month}:$level"
        val before = p.getString(KEY_WARNED, "").orEmpty()
        // Once per month per level; past the limit, "near" is not sent after "over".
        if (before == mark || (level == "near" && before == "${s.month}:over")) return
        p.edit { putString(KEY_WARNED, mark) }
        notify(context, s)
    }

    private const val CHANNEL = "budget"
    private const val NOTIFICATION_ID = 4300

    private fun notify(context: Context, s: Status) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_budget), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.channel_budget_description)
            },
        )
        val limit = usd(s.settings.limit)
        val title = if (s.over) "This month’s budget is used up" else "80% of this month’s budget is spent"
        val text = if (s.over) {
            "${usd(s.spent)} of $limit. " + when (s.settings.action) {
                Action.Pause -> "Automations are paused until the month ends."
                Action.Ask -> "Each message asks you first until the month ends."
                Action.Both -> "Automations are paused and each message asks you first until the month ends."
            }
        } else "${usd(s.spent)} of $limit. Months end at midnight UTC."
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_USAGE)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }
}
