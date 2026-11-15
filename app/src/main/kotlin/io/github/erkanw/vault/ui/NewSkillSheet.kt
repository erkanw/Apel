package io.github.erkanw.vault.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.chat.LOOK_BLURB_MAX
import io.github.erkanw.vault.chat.skillProblem
import io.github.erkanw.vault.chat.skillSlug

/**
 * Writes a new skill: a name, one line saying what it does (the agent reads it to know when to use the skill) and the
 * instructions. The agent’s server has no call that creates a skill, so this opens a new chat with a request for the agent to save
 * the skill's file, to read and send.
 */
@Composable
fun NewSkillSheet(
    /** The names the agent’s skills already have, so a new one doesn't overwrite one. */
    taken: Set<String>,
    onCreate: (slug: String, description: String, instructions: String) -> Unit,
    onDismiss: () -> Unit,
    /** Editing one of your skills: its name stays, its text is filled in. */
    editing: String? = null,
    initialDescription: String = "",
    initialInstructions: String = "",
    /** Why the last save failed, in a line. */
    error: String? = null,
) {
    val k = MaterialTheme.vault
    var name by remember { mutableStateOf(editing.orEmpty()) }
    var description by remember { mutableStateOf(initialDescription) }
    var instructions by remember { mutableStateOf(initialInstructions) }
    var tried by remember { mutableStateOf(false) }
    val slug = skillSlug(name)
    val problem = skillProblem(name, description, instructions, taken)

    VaultSheet(onDismiss = onDismiss, title = if (editing != null) editing else "New skill") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (editing == null) VaultField(
                name, { name = it.take(48) }, "Name", placeholder = "study-planner",
                supporting = if (slug.isNotEmpty() && slug != name.trim()) "Saved as $slug" else null,
                isError = tried && (slug.isEmpty() || slug in taken),
            )
            VaultField(
                description, { description = it.take(LOOK_BLURB_MAX * 4) }, "What it does",
                placeholder = "Turns a list of chapters into a revision plan.",
                supporting = "One line. ${LocalAgentName.current} reads it to decide when to use the skill.",
                isError = tried && description.isBlank(),
            )
            VaultField(
                instructions, { instructions = it }, "Instructions", singleLine = false, minLines = 5,
                placeholder = "Step by step, what ${LocalAgentName.current} should do when it uses this skill.",
                isError = tried && instructions.isBlank(),
            )
            if (tried && problem != null) Text(problem, style = VaultType.Caption, color = k.attention)
            if (error != null) Text(error, style = VaultType.Caption, color = k.attention)
            PrimaryButton(
                if (editing != null) "Save" else "Save skill",
                onClick = {
                    tried = true
                    if (problem == null) onCreate(slug, description.trim(), instructions.trim())
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}
