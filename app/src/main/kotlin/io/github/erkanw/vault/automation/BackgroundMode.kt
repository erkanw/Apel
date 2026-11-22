package io.github.erkanw.vault.automation

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.AgentService
import io.github.erkanw.vault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Keep running in the background" (PLAN §8): [AgentService] stays in the foreground all the time, like a VPN app, so
 * Android doesn't end the app when you clear the recent apps and automations start on time. Off until the user turns it
 * on. It shows as a quiet notification with the next automation, and in Android's list of active apps, where it can be
 * stopped; it comes back when the app opens again, after a restart, or when an automation's alarm goes off.
 */
object BackgroundMode {
    private const val PREFS = "background"
    private const val KEY_ON = "on"

    private val _on = MutableStateFlow<Boolean?>(null)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(context: Context): Boolean = _on.value ?: prefs(context).getBoolean(KEY_ON, false).also { _on.value = it }

    fun onFlow(context: Context): StateFlow<Boolean?> {
        isOn(context)
        return _on.asStateFlow()
    }

    fun set(context: Context, on: Boolean) {
        prefs(context).edit { putBoolean(KEY_ON, on) }
        _on.value = on
        if (on) AgentService.keepRunning(context) else AgentService.stopKeeping(context)
    }

    /** Starts the service when the mode is on and it isn't running; quietly does nothing where Android doesn't allow a start. */
    fun ensure(context: Context) {
        if (isOn(context)) AgentService.keepRunning(context)
    }

    /** The automation that runs next, as [Scheduler.arm] last found it: its name and when. */
    @Volatile private var next: Pair<String, Long>? = null

    fun setNext(name: String?, at: Long) {
        next = name?.let { it to at }
        AgentService.nextChanged()
    }

    /** The background notice's line: "Next: Morning brief at 07:00", or that nothing is planned. */
    fun nextLine(context: Context): String {
        val n = next ?: return context.getString(R.string.background_next_none)
        return context.getString(R.string.background_next, n.first, whenText(n.second, System.currentTimeMillis(), ZoneId.systemDefault()))
    }

    /** "at 07:00" today, "tomorrow at 07:00", "Tue at 07:00" within a week, else "on 3 Dec at 07:00". */
    fun whenText(at: Long, now: Long, zone: ZoneId): String {
        val time = Instant.ofEpochMilli(at).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
        val day: LocalDate = time.toLocalDate()
        return when {
            !day.isAfter(today) -> "at $clock"
            day == today.plusDays(1) -> "tomorrow at $clock"
            day.isBefore(today.plusDays(7)) -> time.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH)) + " at $clock"
            else -> "on " + time.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)) + " at $clock"
        }
    }
}
