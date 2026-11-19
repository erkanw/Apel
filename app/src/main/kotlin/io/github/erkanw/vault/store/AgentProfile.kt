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

    // ---- the agent’s rules (PLAN §7.2): Instructions (AGENT.md) and About you (USER.md), edited in Settings → Agent ----

    private fun rules(context: Context, file: String) = java.io.File(java.io.File(context.filesDir, "agent").apply { mkdirs() }, file)

    fun instructions(context: Context): String = rules(context, "AGENT.md").takeIf { it.isFile }?.readText().orEmpty()
    fun setInstructions(context: Context, text: String) = rules(context, "AGENT.md").writeText(text.trim().take(MAX_RULES))
    /** What the agents read about you: the form's fields as short lines, then your notes. */
    fun aboutYou(context: Context): String = AboutYou.text(about(context))

    /** The About you form (PLAN §6.16): name (needed), country, city, work, and notes (the old free text). */
    fun about(context: Context): AboutYou {
        val p = prefs(context)
        return AboutYou(
            name = p.getString("you_name", "").orEmpty(), country = p.getString("you_country", "").orEmpty(),
            city = p.getString("you_city", "").orEmpty(), work = p.getString("you_work", "").orEmpty(),
            notes = rules(context, "USER.md").takeIf { it.isFile }?.readText().orEmpty(),
        )
    }

    fun setAbout(context: Context, a: AboutYou) {
        prefs(context).edit {
            putString("you_name", a.name.trim().take(60)); putString("you_country", a.country.trim().take(60))
            putString("you_city", a.city.trim().take(60)); putString("you_work", a.work.trim().take(120))
        }
        rules(context, "USER.md").writeText(a.notes.trim().take(MAX_RULES))
    }

    /** Each rules file is capped, so it can’t crowd out the chat. */
    const val MAX_RULES = 8_000

    fun welcomeDone(context: Context): Boolean = prefs(context).getBoolean(KEY_WELCOME, false)

    fun setWelcomeDone(context: Context, done: Boolean) = prefs(context).edit { putBoolean(KEY_WELCOME, done) }

    /** Whether the walkthrough after the welcome was seen (or skipped). */
    fun tourDone(context: Context): Boolean = prefs(context).getBoolean("tourDone", false)
    fun setTourDone(context: Context) = prefs(context).edit { putBoolean("tourDone", true) }
}

/** What you tell your agents about yourself. Only the name is needed. */
data class AboutYou(val name: String = "", val country: String = "", val city: String = "", val work: String = "", val notes: String = "") {
    val ready: Boolean get() = name.isNotBlank()

    companion object {
        /** "Name: Sam\nLives in: Lyon, France\nWork: …\n\nNotes", skipping what is empty. */
        fun text(a: AboutYou): String {
            val lines = listOfNotNull(
                a.name.trim().takeIf { it.isNotEmpty() }?.let { "Name: $it" },
                listOf(a.city.trim(), a.country.trim()).filter { it.isNotEmpty() }.joinToString(", ").takeIf { it.isNotEmpty() }?.let { "Lives in: $it" },
                a.work.trim().takeIf { it.isNotEmpty() }?.let { "Work or studies: $it" },
            )
            val notes = a.notes.trim()
            return (lines.joinToString("\n") + if (notes.isNotEmpty()) (if (lines.isEmpty()) "" else "\n\n") + notes else "").trim()
        }
    }
}
