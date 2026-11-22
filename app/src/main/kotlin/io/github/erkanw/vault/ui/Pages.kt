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
import androidx.compose.ui.semantics.heading
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
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
                    Text(title, style = VaultType.Title, color = k.text, maxLines = 1, modifier = Modifier.semantics { heading() })
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
    /** Opens the chat a run posted in. */
    onOpenChat: (String) -> Unit = {},
    /** Opens every run of a job. */
    onShowRuns: (AutomationJob) -> Unit = {},
) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val state by vm.automations.collectAsStateWithLifecycle()
    var deleting by remember { mutableStateOf<AutomationJob?>(null) }
    var editing by remember { mutableStateOf<AutomationJob?>(null) }
    var creating by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    val custom by chat.customChannels.collectAsStateWithLifecycle()
    val looks by chat.channelLooks.collectAsStateWithLifecycle()
    val choices by io.github.erkanw.vault.chat.ModelsHub.choices.collectAsStateWithLifecycle()
    val channels = remember(custom, looks) { (Channel.builtIn + custom).map { it to io.github.erkanw.vault.chat.lookOf(looks, it) } }
    fun channelFor(job: AutomationJob): Channel? = job.channel.takeIf { it.isNotEmpty() }?.let { id -> (Channel.builtIn + custom).firstOrNull { it.id == id } }
    val tracker = rememberEntranceTracker()
    val setup = rememberAutomationSetup()
    // Again on coming back from Android’s settings: "about" goes once exact alarms are allowed.
    LaunchedEffect(setup.resumed) { vm.loadAutomations() }

    Column(Modifier.fillMaxSize().background(k.background)) {
        val on = state.items.count { it.enabled }
        PageHeader(
            "Automations", if (state.items.isEmpty()) "What ${LocalAgentName.current} does on a schedule" else "$on of ${state.items.size} on", onBack,
            action = { BarButton(VaultIcons.Plus, "New automation", { saveError = null; creating = true }) },
        )
        PageBody(state, "No scheduled jobs yet.", vm::loadAutomations) { jobs ->
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Keep running in the background (PLAN §8): off until turned on.
                item(key = "background") {
                    val background by setup.background.collectAsStateWithLifecycle()
                    VaultGroup {
                        GroupSwitchRow(
                            androidx.compose.ui.res.stringResource(io.github.erkanw.vault.R.string.background_switch),
                            androidx.compose.ui.res.stringResource(io.github.erkanw.vault.R.string.background_switch_sub, setup.appName),
                            background == true, setup::setBackground,
                        )
                    }
                }
                // What the phone holds back, in one line with the way to change it (PLAN §6.13: a refusal is respected).
                setup.missing(jobs.any { it.enabled && !it.builtIn })?.let { (text, action, fix) ->
                    item(key = "setup") {
                        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            Text(text, style = VaultType.Caption, color = k.attention, modifier = Modifier.padding(horizontal = 8.dp))
                            QuietButton(action, fix, color = k.text)
                        }
                    }
                }
                itemsIndexed(jobs, key = { _, j -> j.id }) { i, job ->
                    AutomationCard(
                        job = job,
                        onToggle = { vm.automationAction(job, if (it) "enable" else "disable") },
                        onRun = { vm.automationAction(job, "run") },
                        onDelete = { deleting = job },
                        onEdit = { saveError = null; editing = job },
                        onOpenRun = { run -> run.chatId?.let(onOpenChat) },
                        onShowRuns = { onShowRuns(job) },
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
            models = choices.presets,
            onSave = { values, _ ->
                vm.createAutomation(values) { error ->
                    saveError = error
                    if (error == null) {
                        creating = false
                        // The first job saved: ask for what keeps jobs on time (PLAN §6.13).
                        setup.afterFirstSave()
                    }
                }
            },
            onNewChannel = { look -> chat.createChannel(look.title, look.blurb, look.color) },
            onDismiss = { creating = false },
            error = saveError,
        )
    }
    editing?.let { job ->
        AutomationEditSheet(
            job = job,
            current = channelFor(job),
            channels = channels,
            models = choices.presets,
            onSave = { values, schedule ->
                vm.updateAutomation(job, values, schedule)
                editing = null
            },
            onNewChannel = { look -> chat.createChannel(look.title, look.blurb, look.color) },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { job ->
        ConfirmDialog(
            title = "Delete ${job.name}?",
            message = "The job and its run log are removed for good; what it posted stays in its chats. To keep it but stop it running, turn it off instead.",
            confirm = "Delete job",
            onConfirm = {
                vm.automationAction(job, "delete")
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
    setup.Dialog()
}

/**
 * What keeps automations on time (PLAN §6.13, §8): notifications for their results, running in the background, exact
 * alarms and no battery limits. Asked once, when the first job is saved, and when running in the background is turned on
 * without the other two; afterwards a refusal shows as one line on the page that leads to the setting.
 */
private class AutomationSetup(
    private val context: android.content.Context,
    private val askNotifications: () -> Unit,
    private val openSettings: (android.content.Intent) -> Unit,
) {
    var showing by mutableStateOf(false)
    /** The dialog was opened by turning on running in the background: it asks only for the rest. */
    private var fromSwitch by mutableStateOf(false)
    /** Exact alarms were asked for: the battery question follows when the app is back in front. */
    var batteryNext by mutableStateOf(false)
    /** Ticks when the app comes back to the front, so the line re-reads what is allowed now. */
    var resumed by mutableStateOf(0)

    private val prefs = context.getSharedPreferences("automations_ui", android.content.Context.MODE_PRIVATE)

    val appName: String = context.applicationInfo.loadLabel(context.packageManager).toString()
    val background = io.github.erkanw.vault.automation.BackgroundMode.onFlow(context)

    /** The switch: on at once; then the dialog asks for what else keeps jobs on time, if anything is missing. */
    fun setBackground(on: Boolean) {
        io.github.erkanw.vault.automation.BackgroundMode.set(context, on)
        if (!on) return
        // The quiet notification is how you see it running: ask for notifications first, as the first saved job does.
        if (!notifies()) askNotifications()
        markAsked()
        if (!exact() || !unlimited()) {
            fromSwitch = true
            showing = true
        }
    }

    private fun backgroundOn() = io.github.erkanw.vault.automation.BackgroundMode.isOn(context)

    fun exact() = io.github.erkanw.vault.automation.Scheduler.canExact(context)
    fun unlimited() = context.getSystemService(android.os.PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    fun notifies() = android.os.Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    fun afterFirstSave() {
        if (asked()) return
        if (!notifies()) askNotifications()
        // The page's line waits until the dialog is answered.
        if (!exact() || !unlimited() || !backgroundOn()) {
            fromSwitch = false
            showing = true
        } else {
            markAsked()
        }
    }

    private fun asked() = prefs.getBoolean("asked", false)
    private fun markAsked() = prefs.edit().putBoolean("asked", true).apply()

    fun exactAlarmsIntent() = android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:${context.packageName}"))

    @android.annotation.SuppressLint("BatteryLife")
    fun batteryIntent() = android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:${context.packageName}"))

    fun notificationsIntent() = android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)

    /** What the page says when something holds jobs back: the line, its button, and what the button opens; null when nothing does. */
    fun missing(anyOn: Boolean): Triple<String, String, () -> Unit>? {
        resumed // read, so the line follows the settings
        if (!anyOn || showing || !asked()) return null
        return when {
            !exact() && android.os.Build.VERSION.SDK_INT >= 31 -> Triple("Jobs run at about the time set.", "Allow exact alarms", { openSettings(exactAlarmsIntent()) })
            !notifies() -> Triple("Results won’t show as notifications.", "Allow notifications", { openSettings(notificationsIntent()) })
            // Running in the background, but still under battery limits: some phones end it anyway.
            backgroundOn() && !unlimited() -> Triple(
                context.getString(io.github.erkanw.vault.R.string.background_battery, appName),
                context.getString(io.github.erkanw.vault.R.string.background_battery_fix),
                { openSettings(batteryIntent()) },
            )
            else -> null
        }
    }

    @Composable
    fun Dialog() {
        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
            resumed++
            if (batteryNext) {
                batteryNext = false
                if (!unlimited()) openSettings(batteryIntent())
            }
            onPauseOrDispose { }
        }
        if (!showing) return
        val app = appName
        VaultDialog(
            title = "Keep jobs on time",
            text = if (fromSwitch) {
                "To start jobs on the minute with the screen off, also allow $app to set exact alarms and to run without " +
                    "battery limits. You can change both later in Android’s settings."
            } else {
                "Android holds back apps in the background. To start jobs on the minute, even after you close $app, let it " +
                    "keep running in the background (a quiet notification shows it), set exact alarms and run without " +
                    "battery limits. You can change these later."
            },
            confirm = "Allow",
            dismiss = "Not now",
            onConfirm = {
                showing = false
                markAsked()
                if (!fromSwitch) io.github.erkanw.vault.automation.BackgroundMode.set(context, true)
                if (!exact() && android.os.Build.VERSION.SDK_INT >= 31) {
                    batteryNext = true
                    openSettings(exactAlarmsIntent())
                } else if (!unlimited()) {
                    openSettings(batteryIntent())
                }
            },
            onDismiss = { showing = false; markAsked() },
        )
    }
}

@Composable
private fun rememberAutomationSetup(): AutomationSetup {
    val context = LocalContext.current
    val notifications = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { }
    return remember {
        AutomationSetup(
            context,
            askNotifications = {
                io.github.erkanw.vault.data.BlobPrefs.setNotificationsAsked(context)
                if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
            openSettings = { intent -> runCatching { context.startActivity(intent) } },
        )
    }
}

/** A scheduled job on the lift: name, schedule and switch; when it runs next; its last runs; its actions. */
@Composable
private fun AutomationCard(
    job: AutomationJob,
    onToggle: (Boolean) -> Unit,
    onRun: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onOpenRun: (io.github.erkanw.vault.chat.AutomationRun) -> Unit,
    onShowRuns: () -> Unit,
    /** The channel the job posts in, with its look; null for a new chat each time. */
    runsIn: Pair<Channel, io.github.erkanw.vault.chat.ChannelLook>?,
    modifier: Modifier = Modifier,
) {
    val k = MaterialTheme.vault
    val failed = job.lastStatus == "error"
    VaultGroup(modifier) {
        Row(
            Modifier.fillMaxWidth().pressable(onClick = { onToggle(!job.enabled) }, pressedScale = 1f, pressed = k.pressed, role = androidx.compose.ui.semantics.Role.Switch)
                .semantics { toggleableState = androidx.compose.ui.state.ToggleableState(job.enabled) }
                .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(job.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (job.builtIn) {
                        Spacer(Modifier.width(8.dp))
                        Text("Built in", style = VaultType.Caption, color = k.muted, maxLines = 1)
                    }
                }
                if (job.schedule.isNotBlank()) Text(job.schedule, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                when {
                    runsIn != null -> Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        BlobFlat(ChannelColors.at(runsIn.second.color), size = 14.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("In ${runsIn.second.title}", style = VaultType.Caption, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    !job.builtIn -> Text("A new chat each time", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 3.dp))
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
                // A once job that has run turned itself off: its run log says what happened.
                !job.enabled && job.scheduleKind == "at" && job.lastRunMs > 0L -> Unit
                !job.enabled -> Text("Off", style = VaultType.Label.copy(fontWeight = null), color = k.muted)
                job.nextRunMs > 0L -> Text(
                    "Next run " + whenLabel(System.currentTimeMillis(), job.nextRunMs).replace("Tomorrow", "tomorrow").let { if (job.exact) it else "about $it" },
                    style = VaultType.Label.copy(fontWeight = null), color = k.text,
                )
            }
            if (job.runs.isEmpty() && job.lastRunMs > 0L) {
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
        // The run log (handbook §3): the latest runs, each opening the chat it posted in.
        val shown = job.runs.filter { it.status != "running" }.take(RUNS_ON_CARD)
        if (shown.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            GroupDivider()
            shown.forEach { run -> RunRow(run, onClick = if (run.chatId != null) ({ onOpenRun(run) }) else null) }
        }
        // Run now stays at hand; editing and deleting wait in the menu, as on Skills (nothing destructive at rest).
        var menuOpen by remember { mutableStateOf(false) }
        // Starts at 0 so the button's text lines up with the card's 16dp text edge.
        Row(Modifier.fillMaxWidth().padding(end = 4.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (job.runs.count { it.status != "running" } > RUNS_ON_CARD) QuietButton("Show all runs", onShowRuns, color = k.muted)
            Spacer(Modifier.weight(1f))
            QuietButton(if (job.pending) "Running…" else "Run now", { if (!job.pending) onRun() }, color = if (job.pending) k.faint else k.text)
            // A built-in job has no menu; its slot stays so Run now lines up with the other cards.
            if (job.protected) Spacer(Modifier.width(48.dp))
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

/** Runs a card shows; the rest are on the runs page. */
private const val RUNS_ON_CARD = 5

/** One run in a log: its outcome as a dot, when, and its first line (or why it failed or was skipped). */
@Composable
private fun RunRow(run: io.github.erkanw.vault.chat.AutomationRun, onClick: (() -> Unit)?) {
    val k = MaterialTheme.vault
    val color = when (run.status) {
        "ok" -> k.live
        "error" -> k.danger
        "running" -> k.live
        else -> k.faint
    }
    val outcome = when (run.status) {
        "ok" -> "Done"
        "error" -> "Failed"
        "running" -> "Running"
        "skipped" -> "Skipped"
        else -> "Stopped"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick, pressedScale = 1f, pressed = k.pressed, onClickLabel = "Open the chat") else Modifier)
            .semantics(mergeDescendants = true) { contentDescription = "$outcome, ${runTimeLabel(run.startedMs)}: ${run.summary}" }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (run.status == "running") PulsingDot(color, size = 7.dp)
        else Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(runTimeLabel(run.startedMs), style = VaultType.Caption, color = k.muted, maxLines = 1)
            Text(
                run.summary.ifBlank { outcome }, style = VaultType.Label.copy(fontWeight = null),
                color = if (run.status == "error") k.danger else k.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(VaultIcons.ChevronRight, null, tint = k.faint, modifier = Modifier.size(16.dp))
        }
    }
}

/** "Today 09:00", "Yesterday 21:30", "Mon 08:00", "12 Nov 09:00". */
internal fun runTimeLabel(at: Long, now: Long = System.currentTimeMillis(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String {
    val day = java.time.Instant.ofEpochMilli(at).atZone(zone)
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = day.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.getDefault()))
    val d = day.toLocalDate()
    return when {
        d == today -> "Today $time"
        d == today.minusDays(1) -> "Yesterday $time"
        d.isAfter(today.minusDays(7)) -> day.format(java.time.format.DateTimeFormatter.ofPattern("EEE", java.util.Locale.getDefault())) + " " + time
        else -> day.format(java.time.format.DateTimeFormatter.ofPattern("d MMM", java.util.Locale.getDefault())) + " " + time
    }
}

/** Every run of one job kept on the phone (the last 100), newest first; each opens the chat it posted in. */
@Composable
fun AutomationRunsScreen(vm: PagesViewModel, jobId: String?, onBack: () -> Unit, onOpenChat: (String) -> Unit) {
    val k = MaterialTheme.vault
    val state by vm.automations.collectAsStateWithLifecycle()
    val job = state.items.firstOrNull { it.id == jobId }
    var runs by remember { mutableStateOf<List<io.github.erkanw.vault.chat.AutomationRun>?>(null) }
    LaunchedEffect(job?.id, job?.lastRunMs, job?.pending) { if (job != null) runs = vm.automationRuns(job) }
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(job?.name ?: "Runs", runs?.let { "${it.size} run${if (it.size == 1) "" else "s"}" }.orEmpty(), onBack)
        val list = runs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
            list.isEmpty() -> Text("No runs yet.", style = VaultType.Body, color = k.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(32.dp))
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            ) {
                item(key = "runs") {
                    VaultGroup {
                        list.forEachIndexed { i, run ->
                            if (i > 0) GroupDivider()
                            RunRow(run, onClick = run.chatId?.let { id -> { onOpenChat(id) } })
                        }
                    }
                }
            }
        }
    }
}
