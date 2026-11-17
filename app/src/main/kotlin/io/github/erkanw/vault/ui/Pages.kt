@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.erkanw.vault.ui

import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.ChatViewModel
import io.github.erkanw.vault.PagesViewModel
import io.github.erkanw.vault.PageState
import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.chatId
import io.github.erkanw.vault.chat.SkillInfo
import io.github.erkanw.vault.chat.shortDateTime
import io.github.erkanw.vault.mascot.BlobFlat

/**
 * A page's bar: back on the left, the title centred with a muted line under it, an optional action on the right, and
 * something under it ([extra], such as tabs).
 */
@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    icon: ImageVector = VaultIcons.ArrowLeft,
    iconDescription: String = "Back",
    extra: (@Composable () -> Unit)? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    Column(Modifier.fillMaxWidth().background(k.background).statusBarsPadding()) {
        VaultTopBar(
            left = { BarButton(icon, iconDescription, onBack) },
            right = { if (action != null) action() },
            title = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(title, style = VaultType.Title, color = k.text, maxLines = 1)
                    if (subtitle.isNotBlank()) Text(subtitle, style = VaultType.Caption, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
        )
        if (extra != null) Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) { extra() }
    }
}

/**
 * What a list page shows: the list; a quiet sign while loading; why it couldn't load, with Try again; or a plain
 * sentence when there is nothing.
 */
@Composable
internal fun <T> PageBody(state: PageState<T>, empty: String, onRetry: () -> Unit, content: @Composable (List<T>) -> Unit) {
    val k = MaterialTheme.vault
    Column(Modifier.fillMaxSize()) {
        // A refresh that failed over a list that is still shown: one tappable line above it.
        if (state.error != null && state.items.isNotEmpty()) {
            Text(
                state.error, style = VaultType.Label.copy(fontWeight = null), color = k.attention,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).pressable(onClick = onRetry, pressedScale = 1f, pressed = k.surfaceHigh, onClickLabel = "Try again")
                    .padding(horizontal = 24.dp, vertical = 14.dp),
            )
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                state.items.isNotEmpty() -> content(state.items)
                state.loading -> TypingDots(k.muted)
                state.error != null -> Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error, style = VaultType.Preview, color = k.muted, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton("Try again", onRetry)
                }
                else -> Text(empty, style = VaultType.Preview, color = k.muted, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
            }
        }
    }
}

@Composable
fun SkillsScreen(
    vm: PagesViewModel,
    onBack: () -> Unit,
    /** Opens a new chat with [String] (the request to save a new skill) in its message box. */
    onRequestInNewChat: (String) -> Unit = {},
) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val state by vm.skills.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<SkillInfo?>(null) }
    var creating by remember { mutableStateOf(false) }
    // A skill of yours being edited: its name, description and instructions.
    var editing by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val skillNote by vm.skillNote.collectAsStateWithLifecycle()
    val skillImporter = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importSkill(uri)
    }
    val tracker = rememberEntranceTracker()
    LaunchedEffect(Unit) { vm.loadSkills(); vm.clearSkillNote() }

    // Making or editing a skill is a page of its own.
    if (creating) {
        SkillEditorPage(
            taken = state.items.map { it.name }.toSet(),
            onSave = { slug, description, instructions -> vm.saveSkill(slug, description, instructions) { e -> saveError = e; if (e == null) creating = false } },
            onBack = { creating = false; saveError = null }, error = saveError,
        )
        return
    }
    editing?.let { (name, description, body) ->
        SkillEditorPage(
            taken = state.items.map { it.name }.toSet() - name,
            onSave = { _, d, instructions -> vm.saveSkill(name, d, instructions) { e -> saveError = e; if (e == null) editing = null } },
            onBack = { editing = null; saveError = null },
            editing = name, initialDescription = description, initialInstructions = body, error = saveError,
        )
        return
    }

    Column(Modifier.fillMaxSize().background(k.background)) {
        val on = state.items.count { it.enabled }
        PageHeader(
            "Skills", if (state.items.isEmpty()) "What ${LocalAgentName.current} knows how to do" else "$on of ${state.items.size} on", onBack,
            action = {
                Box {
                    var open by remember { mutableStateOf(false) }
                    BarButton(VaultIcons.Plus, "New or import a skill", { open = true })
                    DropdownMenu(
                        expanded = open, onDismissRequest = { open = false },
                        shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                    ) {
                        MenuItem(VaultIcons.Pencil, "New skill") { open = false; creating = true }
                        MenuItem(VaultIcons.Download, "Import from the phone") { open = false; skillImporter.launch(arrayOf("*/*")) }
                    }
                }
            },
        )
        skillNote?.let { note ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(note, style = VaultType.Caption, color = k.muted, modifier = Modifier.weight(1f))
                BarButton(VaultIcons.Close, "Dismiss", vm::clearSkillNote, tint = k.muted, iconSize = 16.dp)
            }
        }
        PageBody(state, "No skills yet.", vm::loadSkills) { skills ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                item(key = "group") {
                    VaultGroup(Modifier.entrance("skills", 0, tracker, dx = 0.dp, dy = 8.dp).navigationBarsPadding()) {
                        skills.forEachIndexed { i, skill ->
                            if (i > 0) GroupDivider()
                            SkillRow(
                                skill, onToggle = { vm.setSkillEnabled(skill, it) }, onDelete = { deleting = skill },
                                onEdit = { scope.launch { vm.skillText(skill.name)?.let { (d, b) -> saveError = null; editing = Triple(skill.name, d, b) } } },
                                onDuplicate = { vm.duplicateSkill(skill.name) },
                                onExport = { vm.exportSkill(skill.name) },
                            )
                        }
                    }
                }
            }
        }
    }
    deleting?.let { skill ->
        ConfirmDialog(
            title = "Delete ${skill.name}?",
            message = "It moves to the Trash. To keep it but stop ${LocalAgentName.current} using it, turn it off instead.",
            confirm = "Delete skill",
            onConfirm = {
                vm.deleteSkill(skill)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** A skill: its name and where it comes from, what it does, and its switch; a deletable one has a menu. */
@Composable
private fun SkillRow(
    skill: SkillInfo, onToggle: (Boolean) -> Unit, onDelete: () -> Unit, onEdit: () -> Unit = {},
    onDuplicate: () -> Unit = {}, onExport: () -> Unit = {},
) {
    val k = MaterialTheme.vault
    var menuOpen by remember { mutableStateOf(false) }
    val canToggle = skill.available || skill.enabled
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(
                onClick = { if (canToggle) onToggle(!skill.enabled) },
                onLongClick = { menuOpen = true },
                pressedScale = 1f, pressed = k.pressed, role = androidx.compose.ui.semantics.Role.Switch,
            )
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(skill.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (skill.source.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(skill.source, style = VaultType.Caption, color = k.muted, maxLines = 1)
                }
            }
            if (skill.description.isNotBlank()) {
                Text(skill.description, style = VaultType.Label.copy(fontWeight = null), color = k.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (!skill.available) {
                Text(skill.unavailableReason.ifBlank { "Not available right now" }, style = VaultType.Caption, color = k.attention)
            }
        }
        Spacer(Modifier.width(8.dp))
        VaultSwitch(checked = skill.enabled, onCheckedChange = null, enabled = canToggle)
        // Every row has its menu: a built-in skill can be copied to make your own version.
        run {
            Box {
                BarButton(VaultIcons.More, "Options for ${skill.name}", { menuOpen = true }, tint = k.muted, iconSize = 20.dp)
                DropdownMenu(
                    expanded = menuOpen, onDismissRequest = { menuOpen = false },
                    shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                ) {
                    if (skill.deletable) MenuItem(VaultIcons.Pencil, "Edit") { menuOpen = false; onEdit() }
                    MenuItem(VaultIcons.Copy, if (skill.deletable) "Duplicate" else "Make my own copy") { menuOpen = false; onDuplicate() }
                    if (skill.deletable) MenuItem(VaultIcons.Download, "Export") { menuOpen = false; onExport() }
                    if (skill.deletable) MenuItem(VaultIcons.Trash, "Delete", color = k.danger) { menuOpen = false; onDelete() }
                }
            }
        }
    }
}

@Composable
fun AutomationScreen(
    vm: PagesViewModel,
    chat: ChatViewModel,
    onBack: () -> Unit,
    /** Move a job: opens [Channel] with [String], the request to make it there and remove the original. */
    onMoveToChannel: (AutomationJob, Channel, String) -> Unit = { _, _, _ -> },
    /** Opens a new chat with [String] (the request to add a job) in its message box. */
    onRequestInNewChat: (String) -> Unit = {},
) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val state by vm.automations.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<AutomationJob?>(null) }
    var editing by remember { mutableStateOf<AutomationJob?>(null) }
    var creating by remember { mutableStateOf(false) }
    val channelChats by chat.channelChats.collectAsStateWithLifecycle()
    val custom by chat.customChannels.collectAsStateWithLifecycle()
    val looks by chat.channelLooks.collectAsStateWithLifecycle()
    val channels = remember(custom, looks) { (Channel.builtIn + custom).map { it to io.github.erkanw.vault.chat.lookOf(looks, it) } }
    // Where each job runs: the channel whose session it reports into, if any (the demo's first one runs in Daily brief).
    fun channelFor(job: AutomationJob): Channel? = when {
        job.channel.isNotEmpty() -> (Channel.builtIn + custom).firstOrNull { it.id == job.channel }
        else -> io.github.erkanw.vault.chat.channelOf(channelChats, job.chatId(), Channel.builtIn + custom)
    }
    val tracker = rememberEntranceTracker()
    LaunchedEffect(Unit) { vm.loadAutomations() }

    Column(Modifier.fillMaxSize().background(k.background)) {
        val on = state.items.count { it.enabled }
        PageHeader(
            "Automations", if (state.items.isEmpty()) "What ${LocalAgentName.current} does on a schedule" else "$on of ${state.items.size} on", onBack,
            action = { BarButton(VaultIcons.Plus, "New automation", { creating = true }) },
        )
        PageBody(state, "No scheduled jobs yet.", vm::loadAutomations) { jobs ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                itemsIndexed(jobs, key = { _, j -> j.id }) { i, job ->
                    AutomationCard(
                        job = job,
                        onToggle = { vm.automationAction(job, if (it) "enable" else "disable") },
                        onRun = { vm.automationAction(job, "run") },
                        onDelete = { deleting = job },
                        onEdit = { editing = job },
                        runsIn = channelFor(job)?.let { c -> channels.firstOrNull { it.first.id == c.id } },
                        modifier = Modifier.entrance(job.id, i, tracker, dx = 0.dp, dy = 8.dp),
                    )
                }
            }
        }
    }
    if (creating) {
        AutomationEditSheet(
            job = io.github.erkanw.vault.chat.NEW_JOB,
            current = null,
            channels = channels,
            onSave = { _, _ -> },
            onMove = { _, _, _, _ -> },
            onNewChannel = { look -> chat.createChannel(look.title, look.blurb, look.color) },
            onDismiss = { creating = false },
            onCreate = { channel, name, message, schedule ->
                creating = false
                val title = channel?.let { io.github.erkanw.vault.chat.ChannelLooks.look(it).title }
                val request = io.github.erkanw.vault.chat.createJobRequest(name, message, schedule, title)
                if (channel != null) onMoveToChannel(io.github.erkanw.vault.chat.NEW_JOB, channel, request) else onRequestInNewChat(request)
            },
        )
    }
    editing?.let { job ->
        AutomationEditSheet(
            job = job,
            current = channelFor(job),
            channels = channels,
            onSave = { values, schedule ->
                vm.updateAutomation(job, values, schedule)
                editing = null
            },
            onMove = { channel, name, message, schedule ->
                editing = null
                val title = io.github.erkanw.vault.chat.ChannelLooks.look(channel).title
                onMoveToChannel(job, channel, io.github.erkanw.vault.chat.moveJobRequest(job, name, message, schedule, title))
            },
            onNewChannel = { look -> chat.createChannel(look.title, look.blurb, look.color) },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { job ->
        ConfirmDialog(
            title = "Delete ${job.name}?",
            message = "The job is removed for good. To keep it but stop it running, turn it off instead.",
            confirm = "Delete job",
            onConfirm = {
                vm.automationAction(job, "delete")
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

/** A scheduled job on the lift: name, schedule and switch; when it runs next and how it last went; its actions. */
@Composable
private fun AutomationCard(
    job: AutomationJob,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    /** The channel the job runs in, with its look; null for an ordinary chat. */
    runsIn: Pair<Channel, io.github.erkanw.vault.chat.ChannelLook>?,
    modifier: Modifier = Modifier,
) {
    val k = MaterialTheme.vault
    val failed = job.lastStatus.isNotBlank() && job.lastStatus != "ok"
    VaultGroup(modifier) {
        Row(
            Modifier.fillMaxWidth().pressable(onClick = { onToggle(!job.enabled) }, pressedScale = 1f, pressed = k.pressed, role = androidx.compose.ui.semantics.Role.Switch)
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(job.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (job.schedule.isNotBlank()) Text(job.schedule, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                if (runsIn != null) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    BlobFlat(ChannelColors.at(runsIn.second.color), size = 14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("In ${runsIn.second.title}", style = VaultType.Caption, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            VaultSwitch(checked = job.enabled, onCheckedChange = null)
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
            when {
                job.pending -> Row(verticalAlignment = Alignment.CenterVertically) {
                    PulsingDot(k.live, size = 7.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Running now", style = VaultType.Label.copy(fontWeight = null), color = k.text)
                }
                !job.enabled -> Text("Off", style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                job.nextRunMs > 0L -> Text("Next run ${whenLabel(System.currentTimeMillis(), job.nextRunMs)}", style = VaultType.Label.copy(fontWeight = null), color = k.text)
            }
            if (job.lastRunMs > 0L) {
                Text(
                    "Last run ${shortDateTime(job.lastRunMs)} · ${job.lastStatus.ifBlank { "done" }}" +
                        if (failed && job.lastError.isNotBlank()) " · ${job.lastError.take(80)}" else "",
                    style = VaultType.Caption, color = if (failed) k.attention else k.muted,
                )
            }
            if (job.message.isNotBlank()) {
                Text(
                    job.message, style = VaultType.Caption, color = k.muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        // Run now stays at hand; editing and deleting wait in the menu, as on Skills (nothing destructive at rest).
        var menuOpen by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp, top = 2.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            QuietButton(if (job.pending) "Running…" else "Run now", { if (!job.pending) onRun() }, color = if (job.pending) k.faint else k.text)
            if (!job.protected) {
                Box {
                    BarButton(VaultIcons.More, "Options for ${job.name}", { menuOpen = true }, tint = k.muted, iconSize = 20.dp)
                    DropdownMenu(
                        expanded = menuOpen, onDismissRequest = { menuOpen = false },
                        shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                    ) {
                        MenuItem(VaultIcons.Pencil, "Edit") { menuOpen = false; onEdit() }
                        MenuItem(VaultIcons.Trash, "Delete", color = k.danger) { menuOpen = false; onDelete() }
                    }
                }
            }
        }
    }
}
