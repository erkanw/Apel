@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.AgentDb
import io.github.erkanw.vault.AgentsViewModel
import io.github.erkanw.vault.R
import io.github.erkanw.vault.agent.AgentPresets
import io.github.erkanw.vault.tools.AgentTools
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.mascot.BlobFlat
import io.github.erkanw.vault.store.AgentInfo
import io.github.erkanw.vault.store.AgentProfile
import io.github.erkanw.vault.userdb.Access
import kotlinx.coroutines.launch

/** A small flat blob in an agent's own look, for rows and chips. */
@Composable
internal fun AgentMark(look: BlobLook, size: androidx.compose.ui.unit.Dp = 28.dp) {
    val k = MaterialTheme.vault
    val body = Color(look.color.argb)
    // Milk on a light lift and ink on a dark one would vanish: they sit on a small disc one tone away.
    val near = kotlin.math.abs(body.luminance() - k.surfaceHigh.luminance()) < 0.08f
    if (near) {
        Box(Modifier.size(size + 6.dp).background(if (k.light) k.faint.copy(alpha = 0.35f) else k.pressed, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) {
            BlobFlat(body, size = size - 2.dp, look = look)
        }
    } else BlobFlat(body, size = size, look = look)
}

/**
 * Shows [content] as the agent [id] (PLAN §6.16): its name and look are what the mascot, the question and the composer
 * show. Apel (or an unknown id) keeps the app's own name and look.
 */
@Composable
fun AsAgent(vm: AgentsViewModel, id: String?, content: @Composable () -> Unit) {
    val agents by vm.agents.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == id && !it.isApel }
    // One shape for every agent: switching agent (on the session page) keeps the screen's own state, such as an open page.
    androidx.compose.runtime.CompositionLocalProvider(
        LocalAgentName provides (agent?.name ?: LocalAgentName.current),
        io.github.erkanw.vault.mascot.LocalBlobLook provides (agent?.look ?: io.github.erkanw.vault.mascot.LocalBlobLook.current),
        content = content,
    )
}

/** What an agent's model reads as: its preset's label, or "Main model". */
@Composable
private fun modelLabel(vm: AgentsViewModel, agent: AgentInfo): String =
    agent.row.model?.let { name -> vm.presets().firstOrNull { it.name == name }?.let { it.label.ifBlank { it.name } } }
        ?: stringResource(R.string.agents_main_model)

/**
 * The Agents page (PLAN §6.16): every agent with its mark, name and model; the one new chats start with is marked. + makes
 * one from a template or blank; a row opens its page.
 */
@Composable
fun AgentsScreen(vm: AgentsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val k = MaterialTheme.vault
    val agents by vm.agents.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(
            stringResource(R.string.agents_title), pluralStringResource(R.plurals.agents_count, agents.size, agents.size), onBack,
            action = { BarButton(VaultIcons.Plus, stringResource(R.string.agents_new), { adding = true }) },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            VaultGroup {
                agents.forEachIndexed { i, a ->
                    if (i > 0) GroupDivider()
                    val forNew = (selected ?: AgentPresets.APEL) == a.id
                    GroupActionRow(
                        a.name,
                        modelLabel(vm, a) + if (forNew) " · " + stringResource(R.string.agents_for_new_chats) else "",
                        { onOpen(a.id) }, chevron = true,
                        leading = { AgentMark(a.look) },
                    )
                }
            }
            Text(stringResource(R.string.agents_caption), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
        }
    }
    if (adding) NewAgentSheet(onDismiss = { adding = false }) { name, template ->
        adding = false
        vm.create(name, template, onOpen)
    }
}

/** New agent: the templates, each with its look, and Blank. */
@Composable
private fun NewAgentSheet(onDismiss: () -> Unit, onPick: (String, String?) -> Unit) {
    val k = MaterialTheme.vault
    VaultSheet(onDismiss, title = stringResource(R.string.agents_new_title)) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.agents_new_caption), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            VaultGroup {
                AgentPresets.templates.forEachIndexed { i, t ->
                    if (i > 0) GroupDivider()
                    GroupActionRow(t.name, t.blurb, { onPick(t.name, t.id) }, leading = { AgentMark(BlobLook.parse(t.look)) })
                }
                GroupDivider()
                GroupActionRow(
                    stringResource(R.string.agents_blank), stringResource(R.string.agents_blank_sub), { onPick("", null) },
                    leading = { AgentMark(BlobLook.Default) },
                )
            }
        }
    }
}

/**
 * One agent's page (PLAN §6.16): name; look, model and system prompt; About you (shared) and its own memory; then what it
 * may use: tools, skills, databases; and the automations that run as it. Settings → Agent opens Apel's.
 */
@Composable
fun AgentScreen(vm: AgentsViewModel, agentId: String, onBack: () -> Unit, onLook: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val agents by vm.agents.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val detail by vm.detail.collectAsStateWithLifecycle()
    val agent = agents.firstOrNull { it.id == agentId }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(agentId) { vm.load(agentId) }
    if (agent == null) {
        Box(Modifier.fillMaxSize().background(k.background))
        return
    }
    val apelName = agents.firstOrNull { it.isApel }?.name ?: agent.name

    when (editing) {
        "prompt" -> {
            val current = agent.row.prompt ?: AgentPresets.defaultPrompt(agent.name)
            TextEditorScreen(
                title = stringResource(R.string.agent_prompt), subtitle = agent.name, initial = current,
                hint = stringResource(R.string.agent_prompt_explain, agent.name),
                placeholder = stringResource(R.string.agent_prompt_placeholder),
                reset = stringResource(R.string.agent_prompt_reset) to AgentPresets.defaultPrompt(agent.name),
                onSave = { vm.setPrompt(agent.id, agent.name, it); editing = null },
                onBack = { editing = null },
            )
            return
        }
        "about" -> {
            AboutYouScreen(onBack = { editing = null })
            return
        }
        "memory" -> {
            TextEditorScreen(
                title = stringResource(R.string.agent_memory), subtitle = agent.name, initial = detail.memoryText,
                hint = stringResource(R.string.agent_memory_hint, agent.name),
                onSave = { vm.saveMemory(agent.id, it); editing = null },
                onBack = { editing = null },
            )
            return
        }
    }

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(
            stringResource(R.string.agent_page_title), agent.name, onBack,
            action = {
                Box {
                    BarButton(VaultIcons.More, "More options", { menu = true }, tint = k.muted)
                    DropdownMenu(
                        expanded = menu, onDismissRequest = { menu = false },
                        shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                    ) {
                        MenuItem(VaultIcons.Copy, stringResource(R.string.agent_duplicate)) { menu = false; vm.duplicate(agent.id) {} }
                        if (!agent.isApel) MenuItem(VaultIcons.Trash, stringResource(R.string.agent_delete), color = k.danger) { menu = false; deleting = true }
                    }
                }
            },
        )
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Name: saved as you type.
            var name by remember(agent.id) { mutableStateOf(agent.name) }
            VaultGroup {
                Column(Modifier.padding(12.dp)) {
                    VaultField(name, { name = it.take(AgentPresets.MAX_NAME); if (it.isNotBlank()) vm.rename(agent.id, it) }, stringResource(R.string.agent_name), placeholder = agent.name)
                }
            }
            if ((selected ?: AgentPresets.APEL) != agent.id) {
                SecondaryButton(stringResource(R.string.agent_use_for_new), { vm.select(agent.id) }, Modifier.fillMaxWidth())
            }

            Column {
                GroupLabel(stringResource(R.string.agent_group_setup))
                VaultGroup {
                    GroupActionRow(
                        stringResource(R.string.agent_look), listOf(agent.look.shape.label, agent.look.color.label, agent.look.finish.label).joinToString(" · "),
                        onLook, chevron = true, trailing = { AgentMark(agent.look, size = 24.dp) },
                    )
                    GroupDivider()
                    GroupActionRow(stringResource(R.string.agent_model), agent.row.model?.let { modelLabel(vm, agent) } ?: stringResource(R.string.agent_model_sub_main), { picking = true }, chevron = true)
                    GroupDivider()
                    GroupActionRow(
                        stringResource(R.string.agent_prompt),
                        if (agent.row.prompt == null) stringResource(R.string.agent_prompt_default)
                        else agent.row.prompt.lines().firstOrNull { it.isNotBlank() }?.take(80) ?: stringResource(R.string.agent_prompt_custom),
                        { editing = "prompt" }, chevron = true,
                    )
                }
            }

            Column {
                GroupLabel(stringResource(R.string.agent_memory))
                VaultGroup {
                    GroupActionRow(stringResource(R.string.agent_about_you), stringResource(R.string.agent_about_you_sub), { editing = "about" }, chevron = true)
                    GroupDivider()
                    GroupActionRow(
                        stringResource(R.string.agent_memory),
                        if (detail.memoryText.isBlank()) stringResource(R.string.agent_memory_empty) else pluralStringResource(R.plurals.agent_memory_notes, detail.memoryNotes, detail.memoryNotes),
                        { editing = "memory" }, chevron = true,
                    )
                    if (agent.isApel) TidySwitch()
                }
                if (detail.memoryText.isNotBlank()) QuietButton(stringResource(R.string.agent_memory_clear), { clearing = true }, color = k.danger)
            }

            if (detail.tools.isNotEmpty()) Column {
                GroupLabel(stringResource(R.string.agent_tools))
                VaultGroup {
                    detail.tools.forEachIndexed { i, t ->
                        if (i > 0) GroupDivider()
                        GroupSwitchRow(t.title, t.subtitle, t.on, { vm.setTool(agent.id, t.id, it) })
                    }
                }
                Text(stringResource(R.string.agent_tools_caption), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
            }

            // Working with other agents (PLAN §6.16): each a switch, kept with the agent's switched-off tools.
            Column {
                val off = AgentPresets.setOf(agent.row.toolsOff)
                fun on(key: String) = key !in off
                GroupLabel(stringResource(R.string.agent_others))
                VaultGroup {
                    GroupSwitchRow(
                        stringResource(R.string.agent_others_message), stringResource(R.string.agent_others_message_sub, agent.name),
                        on(AgentTools.MESSAGE), { vm.setTool(agent.id, AgentTools.MESSAGE, it) },
                    )
                    GroupDivider()
                    GroupSwitchRow(
                        stringResource(R.string.agent_others_subagents), stringResource(R.string.agent_others_subagents_sub, agent.name),
                        on(AgentTools.SUBAGENT), { vm.setTool(agent.id, AgentTools.SUBAGENT, it) },
                    )
                    GroupDivider()
                    GroupSwitchRow(
                        stringResource(R.string.agent_others_model),
                        if (on(AgentTools.SUBAGENT_MODEL)) stringResource(R.string.agent_others_model_on, agent.name) else stringResource(R.string.agent_others_model_off, agent.name),
                        on(AgentTools.SUBAGENT_MODEL), { vm.setTool(agent.id, AgentTools.SUBAGENT_MODEL, it) },
                        enabled = on(AgentTools.SUBAGENT),
                    )
                }
                Text(stringResource(R.string.agent_others_caption), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
            }

            Column {
                GroupLabel(stringResource(R.string.agent_skills))
                VaultGroup {
                    if (detail.skills.isEmpty()) Text(stringResource(R.string.agent_skills_none), style = VaultType.Body, color = k.muted, modifier = Modifier.padding(16.dp))
                    detail.skills.forEachIndexed { i, s ->
                        if (i > 0) GroupDivider()
                        GroupSwitchRow(s.title, s.subtitle, s.on, { vm.setSkill(agent.id, s.id, it) })
                    }
                }
            }

            if (detail.databases.isNotEmpty()) Column {
                GroupLabel(stringResource(R.string.agent_databases))
                VaultGroup {
                    detail.databases.forEachIndexed { i, db ->
                        if (i > 0) GroupDivider()
                        DbAccessRow(db) { vm.setDb(agent.id, db, it) }
                    }
                }
                Text(stringResource(R.string.agent_databases_caption), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp))
            }

            Column {
                GroupLabel(stringResource(R.string.agent_jobs))
                VaultGroup {
                    if (detail.jobs.isEmpty()) Text(stringResource(R.string.agent_jobs_none, agent.name), style = VaultType.Body, color = k.muted, modifier = Modifier.padding(16.dp))
                    detail.jobs.forEachIndexed { i, j ->
                        if (i > 0) GroupDivider()
                        Text(j, style = VaultType.Body, color = k.text, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (picking) VaultSheet({ picking = false }, title = stringResource(R.string.agent_model)) {
        val catalog by io.github.erkanw.vault.chat.ModelsHub.catalog.collectAsStateWithLifecycle()
        // Prices come from the catalogue, read (or taken from its cache) when the sheet opens.
        LaunchedEffect(Unit) { runCatching { io.github.erkanw.vault.chat.ModelsHub.loadCatalog(context) } }
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.agent_model_caption), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
            VaultGroup {
                ModelChoiceRow(stringResource(R.string.agents_main_model), stringResource(R.string.agent_model_main_sub), agent.row.model == null) { vm.setModel(agent.id, null); picking = false }
                vm.presets().forEach { p ->
                    GroupDivider()
                    val m = catalog.models.firstOrNull { it.id == p.model }
                    val price = if (!(io.github.erkanw.vault.provider.ProviderStore.state.value.kind == io.github.erkanw.vault.provider.ProviderKind.OpenRouter)) null else m?.let { io.github.erkanw.vault.provider.priceLine(it.free, it.bestIn, it.bestOut) }
                        ?: if (p.model.endsWith(":free") && (io.github.erkanw.vault.provider.ProviderStore.state.value.kind == io.github.erkanw.vault.provider.ProviderKind.OpenRouter)) io.github.erkanw.vault.provider.priceLine(true, 0.0, 0.0) else null
                    ModelChoiceRow(p.label.ifBlank { p.name }, price, agent.row.model == p.name) { vm.setModel(agent.id, p.name); picking = false }
                }
            }
        }
    }
    if (clearing) VaultDialog(
        title = stringResource(R.string.agent_memory_clear_title, agent.name), text = stringResource(R.string.agent_memory_clear_text, agent.name),
        confirm = stringResource(R.string.agent_memory_clear), destructive = true,
        onConfirm = { vm.clearMemory(agent.id); clearing = false }, onDismiss = { clearing = false },
    )
    if (deleting) VaultDialog(
        title = stringResource(R.string.agent_delete_title, agent.name), text = stringResource(R.string.agent_delete_text, apelName),
        confirm = stringResource(R.string.agent_delete), destructive = true,
        onConfirm = { deleting = false; vm.delete(agent.id); onBack() }, onDismiss = { deleting = false },
    )
}

/** A model to pick for an agent: its name, what a message costs, a check on the chosen one. */
@Composable
private fun ModelChoiceRow(title: String, subtitle: String?, chosen: Boolean, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    Box(Modifier.semantics { selected = chosen }) {
        GroupActionRow(
            title, subtitle, onClick,
            trailing = if (chosen) ({ androidx.compose.material3.Icon(VaultIcons.Check, null, tint = k.text, modifier = Modifier.size(20.dp)) }) else null,
        )
    }
}

/** One database on an agent's page: Off, Read, Read and write, up to what the database itself allows. */
@Composable
private fun DbAccessRow(db: AgentDb, onAccess: (Access) -> Unit) {
    val k = MaterialTheme.vault
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(db.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Access.None to R.string.agent_db_off, Access.Read to R.string.agent_db_read, Access.ReadWrite to R.string.agent_db_write)
                .filter { (a, _) -> a.ordinal <= db.own.ordinal }
                .forEach { (a, label) ->
                    val text = stringResource(label)
                    VaultChip(text, { onAccess(a) }, Modifier.semantics { contentDescription = "${db.name}: $text" }, selected = db.effective == a, onLift = true)
                }
        }
    }
}

/**
 * The About you form: name (needed), country, city, work or studies, and notes. Shared by every agent; also a step of
 * the welcome. [onChange] gets every edit.
 */
@Composable
fun AboutYouForm(about: io.github.erkanw.vault.store.AboutYou, onChange: (io.github.erkanw.vault.store.AboutYou) -> Unit, nameError: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        VaultField(about.name, { onChange(about.copy(name = it.take(60))) }, stringResource(R.string.about_you_name),
            supporting = if (nameError) stringResource(R.string.about_you_name_needed) else null, isError = nameError)
        VaultField(about.country, { onChange(about.copy(country = it.take(60))) }, stringResource(R.string.about_you_country), placeholder = stringResource(R.string.about_you_optional))
        VaultField(about.city, { onChange(about.copy(city = it.take(60))) }, stringResource(R.string.about_you_city), placeholder = stringResource(R.string.about_you_optional))
        VaultField(about.work, { onChange(about.copy(work = it.take(120))) }, stringResource(R.string.about_you_work), placeholder = stringResource(R.string.about_you_work_hint))
        VaultField(about.notes, { onChange(about.copy(notes = it.take(AgentProfile.MAX_RULES))) }, stringResource(R.string.about_you_notes),
            placeholder = stringResource(R.string.about_you_notes_hint), singleLine = false, minLines = 3)
    }
}

/** Settings → an agent → About you: the form on a page of its own, Save in the header. */
@Composable
private fun AboutYouScreen(onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val initial = remember { AgentProfile.about(context) }
    var about by remember { mutableStateOf(initial) }
    var tried by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val back = { if (about != initial) leaving = true else onBack() }
    BackHandler(onBack = back)
    if (leaving) VaultDialog(
        title = "Discard your changes?", text = "What you changed here isn’t saved.", confirm = "Discard", destructive = true,
        onConfirm = { leaving = false; onBack() }, onDismiss = { leaving = false }, dismiss = "Keep editing",
    )
    Column(Modifier.fillMaxSize().background(k.background).imePadding()) {
        PageHeader(stringResource(R.string.agent_about_you), stringResource(R.string.agent_about_you_sub), back, action = {
            QuietButton("Save", {
                tried = true
                if (about.ready) { AgentProfile.setAbout(context, about); onBack() }
            }, color = if (about != initial) k.text else k.muted)
        })
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.agent_about_you_hint), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp))
            VaultGroup { Column(Modifier.padding(12.dp)) { AboutYouForm(about, { about = it }, nameError = tried && !about.ready) } }
        }
    }
}

/** The built-in nightly job (PLAN §6.8): off here is off on the Automations page too. It tidies every agent's memory. */
@Composable
private fun TidySwitch() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tidy by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        io.github.erkanw.vault.automation.Automations.ensureBuiltIns(context)
        tidy = io.github.erkanw.vault.automation.Automations.get(context, io.github.erkanw.vault.automation.Automations.TIDY_MEMORY)?.enabled
    }
    tidy?.let { on ->
        GroupDivider()
        GroupSwitchRow(stringResource(R.string.agent_tidy), stringResource(R.string.agent_tidy_sub), on, { next ->
            tidy = next
            scope.launch {
                runCatching { io.github.erkanw.vault.automation.Automations.setEnabled(context, io.github.erkanw.vault.automation.Automations.TIDY_MEMORY, next) }
                    .onFailure { tidy = !next }
            }
        })
    }
}

/**
 * A full-page text editor (handbook §3): the header holds Save; the text is Reading size in a ground well. [placeholder]
 * shows in grey while the text is empty; [reset] is a quiet button that puts a starting text back.
 */
@Composable
fun TextEditorScreen(
    title: String, subtitle: String, initial: String, hint: String, onSave: (String) -> Unit, onBack: () -> Unit,
    placeholder: String? = null,
    reset: Pair<String, String>? = null,
) {
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
        Text(hint, style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 4.dp))
        if (reset != null && text != reset.second) {
            Row(Modifier.padding(start = 8.dp)) { QuietButton(reset.first, { text = reset.second }, color = k.muted) }
        }
        // The well starts at a few lines and grows a line at a time with the text; the page scrolls, not the well.
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(top = 12.dp)) {
        BasicTextField(
            text, { text = it },
            textStyle = VaultType.Reading.copy(color = k.text),
            cursorBrush = SolidColor(k.text),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)
                .heightIn(min = 168.dp)
                .background(k.surfaceHigh, RoundedCornerShape(18.dp))
                .padding(16.dp),
            decorationBox = { field ->
                Box {
                    if (text.isEmpty() && placeholder != null) Text(placeholder, style = VaultType.Reading, color = k.faint)
                    field()
                }
            },
        )
        }
    }
}
