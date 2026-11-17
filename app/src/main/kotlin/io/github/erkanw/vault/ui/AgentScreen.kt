package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.mascot.LocalBlobLook
import io.github.erkanw.vault.store.AgentProfile
import io.github.erkanw.vault.tools.MemoryTools
import java.text.DateFormat
import java.util.Date

private enum class Rules(val title: String, val hint: String) {
    Instructions("Instructions", "How it should work: tone, language, what to always or never do."),
    AboutYou("About you", "What it should know about you: your name, where you live, what you work on."),
    Memory("Memory", "What it keeps from earlier chats. It adds to this itself; you can change or clear it."),
}

/**
 * Settings → Agent (handbook §3): who the agent is to you. Its name, its look, the instructions it follows, what it knows
 * about you, and its memory. A text item opens a full-page editor; Save writes it, and the next turn reads it.
 */
@Composable
fun AgentScreen(onBack: () -> Unit, onLook: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val agent = LocalAgentName.current
    val look = LocalBlobLook.current
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    // Bumped after a save, so the rows read the files again.
    var version by remember { mutableIntStateOf(0) }

    editing?.let { name ->
        val item = Rules.valueOf(name)
        val initial = remember(name, version) { read(context, item) }
        TextEditorScreen(
            title = item.title, subtitle = agent, initial = initial, hint = item.hint,
            onSave = { text -> write(context, item, text); version++; editing = null },
            onBack = { editing = null },
        )
        return
    }

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader("Agent", agent, onBack)
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            var name by remember { mutableStateOf(AgentProfile.name(context)) }
            Column {
                VaultGroup {
                    Column(Modifier.padding(12.dp)) {
                        VaultField(name, { name = it.take(24); if (it.isNotBlank()) AgentProfile.setName(context, it) }, "Name", placeholder = agent)
                    }
                }
            }
            Column {
                GroupLabel("Look and rules")
                VaultGroup {
                    GroupActionRow("Look", listOf(look.shape.label, look.color.label, look.finish.label).joinToString(" · "), onLook, chevron = true)
                    GroupDivider()
                    Rules.entries.forEachIndexed { i, item ->
                        if (i > 0) GroupDivider()
                        val text = remember(version) { read(context, item) }
                        val sub = when {
                            text.isBlank() -> if (item == Rules.Memory) "Empty" else "Not set"
                            item == Rules.Memory -> text.lines().count { it.trimStart().startsWith("- ") }.let { n -> "$n note${if (n == 1) "" else "s"} · updated ${updated(context)}" }
                            else -> text.lines().first { it.isNotBlank() }.take(80)
                        }
                        GroupActionRow(item.title, sub, { editing = item.name }, chevron = true)
                    }
                }
            }
            if (read(context, Rules.Memory).isNotBlank()) {
                QuietButton("Clear memory", { clearing = true }, color = k.danger)
            }
            Text(
                "$agent reads these at the start of every reply. Memory changes as it learns; the others change only when you edit them.",
                style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
    }
    if (clearing) {
        VaultDialog(
            title = "Clear memory?",
            text = "$agent forgets everything it kept from earlier chats. This can’t be undone.",
            confirm = "Clear",
            onConfirm = { MemoryTools.file(context).delete(); version++; clearing = false },
            onDismiss = { clearing = false },
        )
    }
}

private fun read(context: android.content.Context, item: Rules): String = when (item) {
    Rules.Instructions -> AgentProfile.instructions(context)
    Rules.AboutYou -> AgentProfile.aboutYou(context)
    Rules.Memory -> MemoryTools.file(context).takeIf { it.isFile }?.readText().orEmpty()
}

private fun write(context: android.content.Context, item: Rules, text: String) = when (item) {
    Rules.Instructions -> AgentProfile.setInstructions(context, text)
    Rules.AboutYou -> AgentProfile.setAboutYou(context, text)
    Rules.Memory -> MemoryTools.file(context).let { f -> f.parentFile?.mkdirs(); f.writeText(text.trim().let { if (it.isEmpty()) it else it + "\n" }) }
}

private fun updated(context: android.content.Context): String =
    MemoryTools.file(context).lastModified().let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) }

/** A full-page text editor (handbook §3): the header holds Save; the text is Reading size in a ground well. */
@Composable
fun TextEditorScreen(title: String, subtitle: String, initial: String, hint: String, onSave: (String) -> Unit, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    var text by rememberSaveable { mutableStateOf(initial) }
    var leaving by remember { mutableStateOf(false) }
    // Back with unsaved changes asks first; nothing typed is lost by accident.
    val back = { if (text != initial) leaving = true else onBack() }
    BackHandler(onBack = back)
    if (leaving) {
        VaultDialog(
            title = "Discard your changes?", text = "What you changed here isn’t saved.", confirm = "Discard", destructive = true,
            onConfirm = { leaving = false; onBack() }, onDismiss = { leaving = false }, dismiss = "Keep editing",
        )
    }
    Column(Modifier.fillMaxSize().background(k.background).imePadding()) {
        PageHeader(title, subtitle, back, action = { QuietButton("Save", { onSave(text) }, color = if (text != initial) k.text else k.muted) })
        Text(hint, style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 8.dp))
        BasicTextField(
            text, { text = it },
            textStyle = VaultType.Reading.copy(color = k.text),
            cursorBrush = SolidColor(k.text),
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .background(k.surfaceHigh, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .padding(16.dp).navigationBarsPadding(),
        )
    }
}
