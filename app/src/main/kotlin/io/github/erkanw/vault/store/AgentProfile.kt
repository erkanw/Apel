package io.github.erkanw.vault.store

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who the agent is to the user: its name (Apel until renamed), and whether the welcome was done. Every agent-facing
 * string reads the name from here, never a literal (handbook §6). Instructions, memory and the look join it later.
 */
object AgentProfile {
    private const val PREFS = "agent"
    private const val KEY_NAME = "name"
    private const val KEY_WELCOME = "welcomeDone"
    private const val MAX_NAME = 24

    private val _name = MutableStateFlow<String?>(null)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(context: Context): String =
        prefs(context).getString(KEY_NAME, null)?.trim()?.ifBlank { null } ?: context.getString(R.string.agent_default_name)

    /** The name as it changes, for the screens. */
    fun nameFlow(context: Context): StateFlow<String?> {
        if (_name.value == null) _name.value = name(context)
        return _name.asStateFlow()
    }

    fun setName(context: Context, name: String) {
        val clean = name.trim().take(MAX_NAME)
        prefs(context).edit { if (clean.isEmpty()) remove(KEY_NAME) else putString(KEY_NAME, clean) }
        _name.value = name(context)
    }

    fun welcomeDone(context: Context): Boolean = prefs(context).getBoolean(KEY_WELCOME, false)

    fun setWelcomeDone(context: Context, done: Boolean) = prefs(context).edit { putBoolean(KEY_WELCOME, done) }
}
