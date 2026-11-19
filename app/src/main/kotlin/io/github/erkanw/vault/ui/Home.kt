@file:OptIn(ExperimentalLayoutApi::class)

package io.github.erkanw.vault.ui

import io.github.erkanw.vault.chat.nextChannelColor
import io.github.erkanw.vault.chat.lookOf
import io.github.erkanw.vault.chat.ChannelLooks
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelEntry
import io.github.erkanw.vault.chat.ChannelLook
import io.github.erkanw.vault.chat.LOOK_BLURB_MAX
import io.github.erkanw.vault.chat.LOOK_TITLE_MAX
import io.github.erkanw.vault.chat.NEW_SESSION_TITLE
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.cleanLook
import io.github.erkanw.vault.chat.groupSessions
import io.github.erkanw.vault.chat.relativeTime
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobFlat
import io.github.erkanw.vault.mascot.BlobMood
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The places Home leads to. */
enum class Screen { Home, Chat, Skills, Automation, AutomationRuns, Databases, Settings, Usage, Files, Models, Tools, Provider, Onboarding, Look, Agent, About }

/** The next automation to run, for its tile on Home. */
data class NextRun(val name: String, val atMs: Long)

/** A channel's colour: fixed by its place in the list, so it never changes. */
/** The channel's colour as you chose it (its own until you pick another); follows a change at once. */
@Composable
fun Channel.color(): Color {
    val looks by ChannelLooks.looks.collectAsState()
    return ChannelColors.at(lookOf(looks, this).color)
}

/**
 * Home, the Grok app played straight: Blob and one question, a few suggestions, and the Ask field. Chats, channels and
 * The agent's tools live in the drawer (the menu button, or a swipe to the right).
 */
@Composable
fun HomeScreen(
    presence: Presence,
    /** One quiet line under the question when there is something to say ("Can’t reach the agent", "Working in…"). */
    status: String?,
    sessions: List<SessionSummary>,
    loading: Boolean,
    error: String?,
    currentChatId: String?,
    openCount: Int,
    onNewSession: () -> Unit,
    /** Starts a new chat with this first message. */
    onAsk: (String) -> Unit,
    onOpenSession: (String) -> Unit,
    onSetArchived: (SessionSummary, Boolean) -> Unit,
    onRename: (SessionSummary, String) -> Unit,
    onTogglePinned: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onNavigate: (Screen) -> Unit,
    onRetry: () -> Unit,
    /** Opening the drawer reads the list again; unlike [onRetry] it never leaves Home (no provider is needed to read chats). */
    onRefresh: () -> Unit = onRetry,
    channels: List<ChannelEntry> = emptyList(),
    onOpenChannel: (Channel) -> Unit = {},
    onSaveChannelLook: (Channel, ChannelLook) -> Unit = { _, _ -> },
    onResetChannelLook: (Channel) -> Unit = {},
    /** Makes a channel from a look and opens it. */
    onCreateChannel: (ChannelLook) -> Unit = {},
    onDeleteChannel: (Channel) -> Unit = {},
    query: String = "",
    onQuery: (String) -> Unit = {},
    unreadChats: Set<String> = emptySet(),
    nextRun: NextRun? = null,
    /** Files picked from Home's +: a new chat opens with them (and what was typed) ready to send. */
    onAttach: ((List<android.net.Uri>, String) -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    var draft by rememberSaveable { mutableStateOf("") }
    val drawer = rememberPushDrawerState()
    var showAttach by remember { mutableStateOf(false) }
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4),
    ) { uris -> if (uris.isNotEmpty()) onAttach?.invoke(uris, draft).also { draft = "" } }
    val filePicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) onAttach?.invoke(uris, draft).also { draft = "" } }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    var editingChannel by remember { mutableStateOf<ChannelEntry?>(null) }
    var creatingChannel by remember { mutableStateOf(false) }
    var deletingChannel by remember { mutableStateOf<ChannelEntry?>(null) }
    var showMore by remember { mutableStateOf(false) }
    val fieldSource = remember { MutableInteractionSource() }
    val typing by fieldSource.collectIsFocusedAsState()
    // The keyboard counts as up only when it really takes room: some keyboards report "visible" with no height left
    // (a collapsed toolbar), which must not hide the suggestions.
    val density = androidx.compose.ui.platform.LocalDensity.current
    val keyboardUp = WindowInsets.ime.getBottom(density) > with(density) { 120.dp.roundToPx() }
    val now = remember(openCount, sessions) { System.currentTimeMillis() }

    // Opening the drawer asks the server for the latest list and puts the keyboard away.
    LaunchedEffect(drawer) {
        snapshotFlow { drawer.isOpen }.filter { it }.collect {
            focus.clearFocus()
            onRefresh()
        }
    }

    val working = sessions.any { it.running } || channels.any { it.running }
    val mood = when {
        presence == Presence.Problem -> BlobMood.Error
        typing && draft.isNotBlank() -> BlobMood.Listening
        working -> BlobMood.Working
        else -> BlobMood.Idle
    }
    val unreadElsewhere = sessions.any { it.chatId in unreadChats && !it.archived }

    PushDrawer(
        drawer,
        drawer = {
            HomeDrawer(
                sessions = sessions,
                loading = loading,
                error = error,
                currentChatId = currentChatId,
                now = now,
                channels = channels,
                unreadChats = unreadChats,
                query = query,
                onQuery = onQuery,
                onNewSession = { scope.launch { drawer.close() }; onNewSession() },
                onOpenSession = onOpenSession,
                onOpenChannel = onOpenChannel,
                onEditChannel = { editingChannel = it },
                onNewChannel = { creatingChannel = true },
                onNavigate = onNavigate,
                onRetry = onRetry,
                onMore = { showMore = true },
                onRename = { renaming = it },
                onDelete = { deleting = it },
                onSetArchived = onSetArchived,
                onTogglePinned = onTogglePinned,
            )
        },
    ) {
        Column(Modifier.fillMaxSize().background(k.background).statusBarsPadding().navigationBarsPadding().imePadding()) {
            VaultTopBar(
                left = {
                    Box {
                        BarButton(VaultIcons.Menu, "Chats and channels", { scope.launch { drawer.open() } })
                        // Something unread in the drawer: a small dot clear of the icon's lines, ringed in the ground colour.
                        if (unreadElsewhere) {
                            Box(
                                Modifier.align(Alignment.TopEnd).offset((-6).dp, 8.dp).size(12.dp).background(k.background, CircleShape).padding(2.dp)
                                    .background(k.accent, CircleShape),
                            )
                        }
                    }
                },
                right = { BarButton(VaultIcons.SquarePen, "New chat", onNewSession, iconSize = 21.dp) },
                title = { TitleWithDot(LocalAgentName.current, presence, onClick = if (presence == Presence.Problem) onRetry else null) },
            )

            // The hero: Blob and the question, in the middle of what is left. The blob takes the room left after the
            // question (landscape, a big font, the keyboard), and steps aside when there is too little for it.
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            val room = maxHeight - 120.dp
            val wanted = if (keyboardUp) 150.dp else 210.dp
            val fits = if (room < wanted) room else wanted
            Column(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val blobSize by androidx.compose.animation.core.animateDpAsState(if (fits < 72.dp) 0.dp else fits, Motion.settle(), label = "blobSize")
                if (blobSize > 0.dp) Blob(
                    mood,
                    size = blobSize,
                    lookAt = if (typing) Offset(0f, 0.85f) else null,
                    description = LocalAgentName.current,
                    // Can't reach the agent is "this needs you" (amber), the same signal as the title dot and the line below.
                    errorColor = k.attention,
                )
                Text("What should ${LocalAgentName.current} do?", style = VaultType.Display, color = k.text, textAlign = TextAlign.Center)
                if (status != null) {
                    Text(
                        status, style = VaultType.Label.copy(fontWeight = null), color = if (presence == Presence.Problem) k.attention else k.muted,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp).then(
                            if (presence == Presence.Problem) Modifier.minimumInteractiveComponentSize().clip(CircleShape).pressable(onClick = onRetry, shape = CircleShape, pressed = k.surfaceHigh, onClickLabel = "Try again")
                                .padding(horizontal = 12.dp, vertical = 6.dp) else Modifier,
                        ),
                    )
                }
            }
            }

            // A few suggestions: the channels, the latest chat, the next automation. They step aside while typing.
            val tiles = suggestions(channels, sessions, unreadChats, nextRun, now, onOpenChannel, onOpenSession, onNavigate)
            AnimatedVisibility(
                visible = !keyboardUp && tiles.isNotEmpty(),
                enter = fadeIn(tween(Motion.Short)) + expandVertically(Motion.settle()),
                exit = fadeOut(tween(120)) + shrinkVertically(Motion.settle()),
            ) {
                val tracker = rememberEntranceTracker(openCount)
                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEachIndexed { row, pair ->
                        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEachIndexed { col, t ->
                                SuggestionTile(
                                    t.title, t.subtitle, t.onClick,
                                    Modifier.weight(1f).fillMaxHeight().entrance("${t.key}:$openCount", row * 2 + col, tracker, dx = 0.dp, dy = 8.dp),
                                    leading = t.leading,
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            Composer(
                value = draft,
                onValueChange = { draft = it },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        draft = ""
                        focus.clearFocus()
                        onAsk(text)
                    }
                },
                placeholder = "Ask ${LocalAgentName.current}",
                onAttach = if (onAttach != null) ({ showAttach = true }) else null,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                fieldModifier = Modifier,
                interactionSource = fieldSource,
            )
        }
    }

    if (showAttach) {
        VaultSheet(onDismiss = { showAttach = false }, title = "Add to a new chat") {
            Column(Modifier.padding(horizontal = 12.dp)) {
                VaultRow("Photo", {
                    showAttach = false
                    photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, leading = { Icon(VaultIcons.Image, null, tint = k.text, modifier = Modifier.size(20.dp)) })
                VaultRow("File", {
                    showAttach = false
                    filePicker.launch(arrayOf("*/*"))
                }, leading = { Icon(VaultIcons.FileText, null, tint = k.text, modifier = Modifier.size(20.dp)) })
            }
        }
    }
    if (showMore) MoreSheet(onDismiss = { showMore = false }, onOpen = { showMore = false; onNavigate(it) })
    editingChannel?.let { entry ->
        ChannelDialog(
            channel = entry.channel,
            look = entry.look,
            onSave = { onSaveChannelLook(entry.channel, it); editingChannel = null },
            onReset = { onResetChannelLook(entry.channel); editingChannel = null },
            onDelete = if (entry.channel.custom) ({ deletingChannel = entry; editingChannel = null }) else null,
            onDismiss = { editingChannel = null },
        )
    }
    if (creatingChannel) {
        NewChannelDialog(
            taken = channels.map { it.look },
            onCreate = { onCreateChannel(it); creatingChannel = false },
            onDismiss = { creatingChannel = false },
        )
    }
    deletingChannel?.let { entry ->
        ConfirmDialog(
            title = "Delete this channel?",
            message = "“${entry.look.title}” goes back to your chats as an ordinary chat, with everything in it. Nothing is deleted.",
            confirm = "Delete",
            onConfirm = { onDeleteChannel(entry.channel); deletingChannel = null },
            onDismiss = { deletingChannel = null },
        )
    }
    renaming?.let { session ->
        RenameDialog(session.title, onConfirm = { onRename(session, it); renaming = null }, onDismiss = { renaming = null })
    }
    deleting?.let { session ->
        ConfirmDialog(
            title = "Delete this chat?",
            message = "\"${session.title}\" will be deleted for good. This can’t be undone.",
            confirm = "Delete",
            onConfirm = { onDelete(session); deleting = null },
            onDismiss = { deleting = null },
        )
    }
}

private class Suggestion(
    val key: String,
    val title: String,
    val subtitle: String,
    val onClick: () -> Unit,
    val leading: (@Composable () -> Unit)? = null,
)

/** Up to four tiles: each channel, the latest chat that isn't a channel's, and the next automation. */
private fun suggestions(
    channels: List<ChannelEntry>,
    sessions: List<SessionSummary>,
    unread: Set<String>,
    nextRun: NextRun?,
    now: Long,
    onOpenChannel: (Channel) -> Unit,
    onOpenSession: (String) -> Unit,
    onNavigate: (Screen) -> Unit,
): List<Suggestion> {
    val out = ArrayList<Suggestion>(4)
    // At most two channels, the ones with news first, so the latest chat and the next automation keep their place.
    channels.sortedByDescending { it.running || it.unread }.take(2).forEach { e ->
        val sub = when {
            e.running -> "Working…"
            e.unread -> "New message"
            else -> e.look.blurb
        }
        out += Suggestion("ch:" + e.channel.id, e.look.title, sub, { onOpenChannel(e.channel) }) { BlobFlat(e.channel.color(), size = 22.dp) }
    }
    sessions.filter { !it.archived }.maxByOrNull { it.updatedMs }?.let { s ->
        val sub = when {
            s.running -> "Working…"
            s.chatId in unread -> "New message"
            else -> relativeTime(now, s.updatedMs)
        }
        out += Suggestion("chat:" + s.chatId, s.title, sub, { onOpenSession(s.chatId) }) {
            Icon(VaultIcons.Chat, null, tint = MaterialTheme.vault.muted, modifier = Modifier.size(20.dp))
        }
    }
    if (nextRun != null) {
        out += Suggestion("next", nextRun.name, whenLabel(now, nextRun.atMs), { onNavigate(Screen.Automation) }) {
            Icon(VaultIcons.Clock, null, tint = MaterialTheme.vault.muted, modifier = Modifier.size(20.dp))
        }
    }
    return out.take(4)
}

/** "08:06", "Tomorrow 08:06", "Fri 18:30", "12 Dec 09:00". */
internal fun whenLabel(now: Long, at: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val day = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = day.format(DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault()))
    val d: LocalDate = day.toLocalDate()
    return when {
        d == today -> time
        d == today.plusDays(1) -> "Tomorrow $time"
        d.isBefore(today.plusDays(7)) -> day.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault())) + " " + time
        else -> day.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())) + " " + time
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// The drawer
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun HomeDrawer(
    sessions: List<SessionSummary>,
    loading: Boolean,
    error: String?,
    currentChatId: String?,
    now: Long,
    channels: List<ChannelEntry>,
    unreadChats: Set<String>,
    query: String,
    onQuery: (String) -> Unit,
    onNewSession: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenChannel: (Channel) -> Unit,
    onEditChannel: (ChannelEntry) -> Unit,
    onNewChannel: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onRetry: () -> Unit,
    onMore: () -> Unit,
    onRename: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onSetArchived: (SessionSummary, Boolean) -> Unit,
    onTogglePinned: (SessionSummary) -> Unit,
) {
    val k = MaterialTheme.vault
    var showArchived by rememberSaveable { mutableStateOf(false) }
    // The chat whose long-press menu is open: while it is, only that row is lit (not also the current chat).
    var menuFor by remember { mutableStateOf<String?>(null) }
    val searching = query.isNotBlank()
    BackHandler(enabled = searching) { onQuery("") }
    val matching = remember(sessions, query) {
        sessions.filter { !searching || it.title.contains(query, ignoreCase = true) || it.preview.contains(query, ignoreCase = true) }
    }
    val active = remember(matching) { matching.filter { !it.archived } }
    val archived = remember(matching) { matching.filter { it.archived } }
    val groups = remember(active, now) { groupSessions(active, now) }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        SearchPill(query, onQuery, Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp))
        Box(Modifier.weight(1f)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 20.dp)) {
            if (!searching) {
                item(key = "new") { ToolRow(VaultIcons.SquarePen, "New chat", onNewSession) }
                item(key = "files") { ToolRow(VaultIcons.Folder, "Files") { onNavigate(Screen.Files) } }
                item(key = "skills") { ToolRow(VaultIcons.Sparkles, "Skills") { onNavigate(Screen.Skills) } }
                item(key = "auto") { ToolRow(VaultIcons.Clock, "Automations") { onNavigate(Screen.Automation) } }
                item(key = "dbs") { ToolRow(VaultIcons.Database, "Databases") { onNavigate(Screen.Databases) } }
                item(key = "tools") { ToolRow(VaultIcons.Wrench, "Tools") { onNavigate(Screen.Tools) } }
                run {
                    // Making a channel is rare: a quiet + on the heading, not a row of its own in the list.
                    item(key = "ch-label") {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Channels", style = VaultType.Label, color = k.muted, modifier = Modifier.weight(1f).padding(start = 12.dp))
                            BarButton(VaultIcons.Plus, "New channel", onNewChannel, tint = k.muted, iconSize = 18.dp)
                        }
                    }
                    items(channels, key = { "ch:" + it.channel.id }) { e ->
                        VaultRow(
                            e.look.title,
                            onClick = { onOpenChannel(e.channel) },
                            onLongClick = { onEditChannel(e) },
                            selected = e.selected,
                            emphasized = e.unread,
                            leading = { BlobFlat(e.channel.color(), size = 26.dp) },
                            trailing = if (e.running || e.unread) ({ StateDot(working = e.running) }) else null,
                        )
                    }
                }
            }
            run {
                if (error != null) {
                    item(key = "error") {
                        Text(
                            if (sessions.isEmpty()) "Couldn’t load your chats. Tap to try again." else "Couldn’t refresh. Tap to try again.",
                            style = VaultType.Label.copy(fontWeight = null), color = k.attention,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).pressable(onClick = onRetry, shape = RoundedCornerShape(14.dp), pressed = k.surfaceHigh)
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
                if (matching.isEmpty() && error == null) {
                    item(key = "empty") {
                        Text(
                            when {
                                searching -> "No chat matches \"$query\"."
                                loading -> "Loading your chats…"
                                else -> "No chats yet. Ask ${LocalAgentName.current} something to start one."
                            },
                            style = VaultType.Preview, color = k.muted, modifier = Modifier.padding(horizontal = 12.dp, vertical = 16.dp),
                        )
                    }
                }
                groups.forEach { group ->
                    item(key = "g:" + group.label) { SectionLabel(group.label) }
                    items(group.sessions, key = { it.chatId }) { s ->
                        ChatRow(s, if (menuFor != null) s.chatId == menuFor else s.chatId == currentChatId, s.chatId in unreadChats, now, onOpenSession, onRename, onDelete, onSetArchived, onTogglePinned, menu = menuFor == s.chatId, onMenu = { open -> menuFor = if (open) s.chatId else null })
                    }
                }
                if (archived.isNotEmpty()) {
                    item(key = "arch") {
                        VaultRow(
                            "Archived",
                            onClick = { showArchived = !showArchived },
                            leading = { Icon(VaultIcons.Archive, null, tint = k.muted, modifier = Modifier.size(20.dp)) },
                            trailing = { Text("${archived.size}", style = VaultType.Caption, color = k.muted) },
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                    if (showArchived || searching) {
                        items(archived, key = { it.chatId }) { s ->
                            ChatRow(s, if (menuFor != null) s.chatId == menuFor else s.chatId == currentChatId, s.chatId in unreadChats, now, onOpenSession, onRename, onDelete, onSetArchived, onTogglePinned, menu = menuFor == s.chatId, onMenu = { open -> menuFor = if (open) s.chatId else null })
                        }
                    }
                }
            }
        }
            // The list fades out above the footer, so a group heading scrolled there never reads as Settings' label.
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                    .background(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            0f to k.surface.copy(alpha = 0f), 0.6f to k.surface.copy(alpha = 0.9f), 1f to k.surface,
                        ),
                    ),
            )
        }
        // The pages that are not for every visit, on their own footer: a hairline where the list's surface meets it.
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(k.line))
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            VaultRow(
                "Settings", { onNavigate(Screen.Settings) }, Modifier.weight(1f),
                leading = { Icon(VaultIcons.Sliders, null, tint = k.text, modifier = Modifier.size(20.dp)) },
            )
            BarButton(VaultIcons.More, "Models and usage", onMore, tint = k.muted)
        }
    }
}

@Composable
private fun ToolRow(icon: ImageVector, title: String, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    VaultRow(title, onClick, leading = { Icon(icon, null, tint = k.text, modifier = Modifier.size(20.dp)) })
}

@Composable
internal fun SearchPill(value: String, onValue: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Search chats") {
    val k = MaterialTheme.vault
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).clip(CircleShape).background(k.surfaceHigh).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(VaultIcons.Search, null, tint = k.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = VaultType.Body, color = k.muted)
            BasicTextField(value, onValue, singleLine = true, textStyle = VaultType.Body.copy(color = k.text), cursorBrush = SolidColor(k.text), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) BarButton(VaultIcons.Close, "Clear search", { onValue("") }, tint = k.muted, iconSize = 18.dp)
        else Spacer(Modifier.height(44.dp))
    }
}

@Composable
private fun ChatRow(
    s: SessionSummary,
    selected: Boolean,
    unread: Boolean,
    now: Long,
    onOpen: (String) -> Unit,
    onRename: (SessionSummary) -> Unit,
    onDelete: (SessionSummary) -> Unit,
    onSetArchived: (SessionSummary, Boolean) -> Unit,
    onTogglePinned: (SessionSummary) -> Unit,
    menu: Boolean,
    onMenu: (Boolean) -> Unit,
) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    Box {
        VaultRow(
            s.title,
            onClick = { onOpen(s.chatId) },
            onLongClick = { Haptics.tap(view); onMenu(true) },
            selected = selected,
            emphasized = unread,
            trailing = when {
                s.running || unread -> ({ StateDot(working = s.running) })
                s.pinned -> ({ Icon(VaultIcons.Pin, "Pinned", tint = k.muted, modifier = Modifier.size(15.dp)) })
                else -> null
            },
        )
        // Anchored at the row's bottom end, so the menu opens under the row and never covers its title.
        Box(Modifier.align(Alignment.BottomEnd)) {
            DropdownMenu(
                expanded = menu,
                onDismissRequest = { onMenu(false) },
                shape = RoundedCornerShape(16.dp),
                // One step above everything in the drawer: separated by tone, not by a line or a shadow.
                containerColor = k.pressed,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
            ) {
            MenuItem(VaultIcons.Pencil, "Rename") { onMenu(false); onRename(s) }
            if (!s.archived) MenuItem(VaultIcons.Pin, if (s.pinned) "Unpin" else "Pin") { onMenu(false); onTogglePinned(s) }
            MenuItem(VaultIcons.Archive, if (s.archived) "Move back to chats" else "Archive") { onMenu(false); onSetArchived(s, !s.archived) }
            MenuItem(VaultIcons.Trash, "Delete", color = k.danger) { onMenu(false); onDelete(s) }
            }
        }
    }
}

@Composable
internal fun MenuItem(icon: ImageVector, text: String, color: Color? = null, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    DropdownMenuItem(
        text = { Text(text, style = VaultType.Body, color = color ?: k.text) },
        leadingIcon = { Icon(icon, null, tint = color ?: k.muted, modifier = Modifier.size(18.dp)) },
        onClick = onClick,
    )
}

/** Models and usage: one tap deeper than everything else. */
@Composable
private fun MoreSheet(onDismiss: () -> Unit, onOpen: (Screen) -> Unit) {
    VaultSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 12.dp)) {
            val k = MaterialTheme.vault
            VaultRow("Models", { onOpen(Screen.Models) }, subtitle = "What ${LocalAgentName.current} can run", leading = { Icon(VaultIcons.Cube, null, tint = k.text, modifier = Modifier.size(20.dp)) })
            VaultRow("Usage", { onOpen(Screen.Usage) }, subtitle = "What each answer cost", leading = { Icon(VaultIcons.Coin, null, tint = k.text, modifier = Modifier.size(20.dp)) })
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Shared with other screens
// ---------------------------------------------------------------------------------------------------------------------

internal enum class DotState { Idle, Ready, Working, Problem }

/** The small dot that says how the agent is: green when connected, breathing while it works, amber when it can't be reached. */
@Composable
internal fun StatusDot(state: DotState) {
    val k = MaterialTheme.vault
    when (state) {
        DotState.Working -> PulsingDot(k.live, size = 7.dp)
        DotState.Problem -> Box(Modifier.size(7.dp).clip(CircleShape).background(k.attention))
        DotState.Ready -> Box(Modifier.size(7.dp).clip(CircleShape).background(k.live))
        DotState.Idle -> Box(Modifier.size(7.dp).clip(CircleShape).background(k.faint))
    }
}

/** A single-line field on the lift, for dialogs. */
@Composable
internal fun DialogField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Box(modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(k.surfaceHigh).padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) {
        if (value.isEmpty()) Text(placeholder, style = VaultType.Body, color = k.muted)
        BasicTextField(value, onValue, singleLine = true, textStyle = VaultType.Body.copy(color = k.text), cursorBrush = SolidColor(k.text), modifier = Modifier.fillMaxWidth())
    }
}

/** Asks for a chat's new name. Left empty, the chat goes back to its automatic name. */
@Composable
fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var text by remember { mutableStateOf(if (current == NEW_SESSION_TITLE) "" else current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = { Text("Rename chat", style = VaultType.Title, color = k.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField(text, { text = it }, "Name")
                Text("Leave it empty to use the automatic name.", style = VaultType.Caption, color = k.muted)
            }
        },
        confirmButton = { QuietButton("Save", { onConfirm(text) }) },
        dismissButton = { QuietButton("Cancel", onDismiss, color = k.muted) },
    )
}

/**
 * Changes a channel's name, description and colour. Fields left empty go back to the channel's own; Reset puts all three
 * back. A channel you made also has Delete.
 */
@Composable
fun ChannelDialog(
    channel: Channel,
    look: ChannelLook,
    onSave: (ChannelLook) -> Unit,
    onReset: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val k = MaterialTheme.vault
    var title by remember { mutableStateOf(look.title) }
    var blurb by remember { mutableStateOf(look.blurb) }
    var color by remember { mutableIntStateOf(look.color) }
    val shown = cleanLook(channel, title, blurb, color)
    ChannelForm(
        heading = shown.title, color = color, title = title, blurb = blurb,
        onTitle = { title = it }, onBlurb = { blurb = it }, onColor = { color = it },
        onDelete = onDelete,
        confirm = { QuietButton("Save", { onSave(shown) }) },
        dismiss = {
            Row {
                // A channel you made has nothing to go back to; Delete is its other action.
                if (!channel.custom) QuietButton("Reset", onReset, color = k.muted)
                QuietButton("Cancel", onDismiss, color = k.muted)
            }
        },
        onDismiss = onDismiss,
    )
}

/** Makes a channel: a name (needed), a description and a colour, the first one no channel wears yet to start with. */
@Composable
fun NewChannelDialog(taken: List<ChannelLook>, onCreate: (ChannelLook) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var title by remember { mutableStateOf("") }
    var blurb by remember { mutableStateOf("") }
    var color by remember { mutableIntStateOf(nextChannelColor(taken)) }
    val ready = title.isNotBlank()
    ChannelForm(
        heading = title.trim().ifBlank { "New channel" }, color = color, title = title, blurb = blurb,
        onTitle = { title = it }, onBlurb = { blurb = it }, onColor = { color = it },
        onDelete = null,
        confirm = {
            QuietButton("Create", {
                if (ready) onCreate(ChannelLook(title.trim().replace(Regex("\\s+"), " "), blurb.trim().replace(Regex("\\s+"), " "), color))
            }, color = if (ready) k.text else k.faint)
        },
        dismiss = { QuietButton("Cancel", onDismiss, color = k.muted) },
        onDismiss = onDismiss,
    )
}

@Composable
private fun ChannelForm(
    heading: String,
    color: Int,
    title: String,
    blurb: String,
    onTitle: (String) -> Unit,
    onBlurb: (String) -> Unit,
    onColor: (Int) -> Unit,
    onDelete: (() -> Unit)?,
    confirm: @Composable () -> Unit,
    dismiss: @Composable () -> Unit,
    onDismiss: () -> Unit,
) {
    val k = MaterialTheme.vault
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BlobFlat(ChannelColors.at(color), size = 28.dp)
                Spacer(Modifier.width(12.dp))
                Text(heading, style = VaultType.Title, color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField(title, { onTitle(it.take(LOOK_TITLE_MAX)) }, "Name")
                DialogField(blurb, { onBlurb(it.take(LOOK_BLURB_MAX)) }, "Description")
                Text("The description is also what the empty chat says.", style = VaultType.Caption, color = k.muted)
                Text("Colour", style = VaultType.Label, color = k.muted, modifier = Modifier.padding(top = 10.dp))
                ColorClouds(color, onColor)
                if (onDelete != null) {
                    QuietButton("Delete channel", onDelete, color = k.danger, modifier = Modifier.padding(top = 4.dp).offset(x = (-12).dp))
                }
            }
        },
        confirmButton = confirm,
        dismissButton = dismiss,
    )
}

/**
 * The eight channel colours as small flat clouds, the chosen one on the pressed tone (chosen by tone, never by outline).
 * Each is a 48 dp target; they wrap onto a second row on a narrow dialog.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorClouds(current: Int, onSelect: (Int) -> Unit) {
    val k = MaterialTheme.vault
    androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth().offset(x = (-6).dp), maxItemsInEachRow = 4) {
        ChannelColors.all.forEachIndexed { i, c ->
            val chosen = i == current
            val tone by androidx.compose.animation.animateColorAsState(if (chosen) k.pressed else Color.Transparent, Motion.quick(), label = "swatch")
            Box(
                Modifier.size(48.dp).padding(4.dp).clip(CircleShape).background(tone)
                    .pressable(onClick = { onSelect(i) }, shape = CircleShape, pressedScale = 0.9f, onClickLabel = "Choose this colour")
                    .semantics { selected = chosen; contentDescription = colorName(i) },
                contentAlignment = Alignment.Center,
            ) { BlobFlat(c, size = 24.dp) }
        }
    }
}

private fun colorName(i: Int): String = listOf("Amber", "Coral", "Blue", "Green", "Violet", "Pink", "Teal", "Cocoa").getOrElse(i) { "Colour ${i + 1}" }

/** A question that must be answered before something that can't be undone. */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    VaultDialog(title, message, confirm, onConfirm, onDismiss, destructive = true)
}
