package io.github.erkanw.vault.ui

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
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
 * The skill editor (handbook §3): a full page. The name (new skills only) and the one line on when to use it as a group of
 * wells, the instructions in a Reading-size block below, Save in the header. Back with unsaved changes asks first.
 */
@Composable
fun SkillEditorPage(
    taken: Set<String>,
    onSave: (slug: String, description: String, instructions: String) -> Unit,
    onBack: () -> Unit,
    editing: String? = null,
    initialDescription: String = "",
    initialInstructions: String = "",
    error: String? = null,
) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    var name by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(editing.orEmpty()) }
    var description by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialDescription) }
    var instructions by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(initialInstructions) }
    var tried by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val slug = if (editing != null) editing else skillSlug(name)
    val problem = skillProblem(slug, description, instructions, if (editing != null) emptySet() else taken)
    val changed = name != editing.orEmpty() || description != initialDescription || instructions != initialInstructions
    val back = { if (changed) leaving = true else onBack() }
    androidx.activity.compose.BackHandler(onBack = back)
    if (leaving) {
        VaultDialog(
            title = "Discard your changes?", text = "What you changed here isn\u2019t saved.", confirm = "Discard", destructive = true,
            onConfirm = { leaving = false; onBack() }, onDismiss = { leaving = false }, dismiss = "Keep editing",
        )
    }
    Column(Modifier.fillMaxSize().background(k.background).imePadding()) {
        PageHeader(
            editing ?: "New skill", agent, back,
            action = {
                QuietButton("Save", {
                    tried = true
                    if (problem == null) onSave(slug, description.trim(), instructions.trim())
                }, color = if (changed) k.text else k.muted)
            },
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            VaultGroup {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (editing == null) VaultField(
                        name, { name = it.take(48) }, "Name", placeholder = "study-planner",
                        supporting = if (slug.isNotEmpty() && slug != name.trim()) "Saved as $slug" else null,
                        isError = tried && (slug.isEmpty() || slug in taken),
                    )
                    VaultField(
                        description, { description = it.take(LOOK_BLURB_MAX * 4) }, "When to use it",
                        placeholder = "Turns a list of chapters into a revision plan.",
                        supporting = "One line. $agent reads it to decide when to use the skill.",
                        isError = tried && description.isBlank(),
                    )
                }
            }
            if (tried && problem != null) Text(problem, style = VaultType.Caption, color = k.attention, modifier = Modifier.padding(horizontal = 8.dp))
            if (error != null) Text(error, style = VaultType.Caption, color = k.attention, modifier = Modifier.padding(horizontal = 8.dp))
            Text("Instructions: step by step, what $agent should do when it uses this skill.", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp))
        }
        androidx.compose.foundation.text.BasicTextField(
            instructions, { instructions = it },
            textStyle = VaultType.Reading.copy(color = k.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(k.text),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp)
                .background(k.surfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(16.dp).navigationBarsPadding(),
        )
    }
}
