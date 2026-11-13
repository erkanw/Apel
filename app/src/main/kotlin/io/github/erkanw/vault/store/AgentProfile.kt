package io.github.erkanw.vault.store

import android.content.Context
import androidx.core.content.edit
import io.github.erkanw.vault.R

/**
 * Who the agent is to the user: its name (Apel until renamed). Every agent-facing string reads the name from here,
 * never a literal (handbook §6). Instructions, memory and the look join it in later phases.
 */
object AgentProfile {
    private const val PREFS = "agent"
    private const val KEY_NAME = "name"
    private const val MAX_NAME = 24

    fun name(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NAME, null)?.trim()?.ifBlank { null }
            ?: context.getString(R.string.agent_default_name)

    fun setName(context: Context, name: String) {
        val clean = name.trim().take(MAX_NAME)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            if (clean.isEmpty()) remove(KEY_NAME) else putString(KEY_NAME, clean)
        }
    }
}
