package io.github.erkanw.vault.data

import android.content.Context
import androidx.core.content.edit

/** Blob settings the app and the widget share. The widget runs outside the app, so they live in a preferences file. */
object BlobPrefs {
    private const val PREFS = "look"

    /** Whether Blob falls asleep at night (see SleepSchedule). On by default. */
    fun sleepEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("sleepAtNight", true)

    /** Whether the one-time notification permission prompt (for the "the agent is replying" notice) has been shown. */
    fun notificationsAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("notificationsAsked", false)

    fun setNotificationsAsked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("notificationsAsked", true) }
    }

    /** Whether the app vibrates a little when you send, copy, and when the agent has answered. On by default. */
    fun hapticsEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("haptics", true)

    fun setHapticsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("haptics", enabled) }
    }

    /** The look chosen in Settings: "system" (follow the phone, the default), "light" or "dark". */
    fun themeId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("theme", null)

    fun setThemeId(context: Context, id: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("theme", id) }
    }

    fun setSleepEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("sleepAtNight", enabled) }
    }
}
