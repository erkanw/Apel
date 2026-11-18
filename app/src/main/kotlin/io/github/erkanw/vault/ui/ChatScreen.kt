package io.github.erkanw.vault.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.ChatViewModel
import io.github.erkanw.vault.chat.AgentStep
import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.stepsSummary
import io.github.erkanw.vault.chat.ChatRow
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.graphics.TransformOrigin
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.prettyModelName
import io.github.erkanw.vault.chat.statsOf
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.erkanw.vault.VoiceState
import io.github.erkanw.vault.chat.appendTranscript
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import io.github.erkanw.vault.chat.LocalCommand
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.NEW_SESSION_TITLE
import io.github.erkanw.vault.chat.defaultLook
import io.github.erkanw.vault.chat.introFor
import io.github.erkanw.vault.chat.lookOf
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.chatRows
import io.github.erkanw.vault.chat.commandDraft
import io.github.erkanw.vault.chat.localCommand
import io.github.erkanw.vault.chat.matchingCommands
import io.github.erkanw.vault.chat.promptOf
import io.github.erkanw.vault.chat.shownPreset
import io.github.erkanw.vault.chat.clockTime
import io.github.erkanw.vault.mascot.BlobState




import io.github.erkanw.vault.mascot.Blob
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.animation.core.animateFloatAsState
import io.github.erkanw.vault.mascot.BlobFlat
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.chat.introFor
import io.github.erkanw.vault.chat.defaultLook
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.exp

@Composable
fun ChatScreen(vm: ChatViewModel, focusTick: Int, onBack: () -> Unit, onSettings: () -> Unit, onModels: () -> Unit = {}) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val vmMessages by vm.messages.collectAsStateWithLifecycle()
    val vmBusy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val messages = vmMessages
    // Debug builds can show a reply in progress (see DemoMode); a release build never does.
    val busy = vmBusy || DemoMode.busy(context)
    val chatMood by vm.mood.collectAsStateWithLifecycle()
    val mood = chatMood
    val connected = vm.connected
    val step by vm.step.collectAsStateWithLifecycle()
    val sessions by vm.sessions.collectAsStateWithLifecycle()
    val chatId by vm.currentChatId.collectAsStateWithLifecycle()
    val threadLoading by vm.threadLoading.collectAsStateWithLifecycle()
    val forkBoundary by vm.forkBoundary.collectAsStateWithLifecycle()
    val commands by vm.commands.collectAsStateWithLifecycle()
    val models by vm.models.collectAsStateWithLifecycle()
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val modelPrefs by vm.modelPrefs.collectAsStateWithLifecycle()
    val attachments by vm.pending.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val voice by vm.voice.collectAsStateWithLifecycle()
    // A file from the chat that has just been saved opens in whatever app on the phone handles that kind of file.
    LaunchedEffect(vm) {
        vm.openSaved.collect { saved ->
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                vm.showNotice("Saved ${saved.name} in Downloads/Apel, but no app on the phone opens that kind of file.")
            }
        }
    }
    val micPermission =rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startVoice()
        else vm.showNotice("Allow the microphone to dictate. You can do it in the phone’s settings for this app.")
    }
    val startVoice = {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startVoice()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // Leaving the app ends dictation: the microphone is never kept open behind your back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.cancelVoice() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxItems = 4)) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris)
    }
    // Any kind of file is offered: some phones label a .md or .log as an unknown type, so the list is checked afterwards.
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.attach(uris)
    }
    /** The message of yours being edited, in the dialog. */
    var editing by remember { mutableStateOf<ChatMessage?>(null) }
    var showModels by remember { mutableStateOf(false) }
    /** The session whose name is being changed, from the information page. */
    var renamingSession by remember { mutableStateOf<SessionSummary?>(null) }
    /** Whether the session's information page is open over the chat. */
    var showInfo by rememberSaveable { mutableStateOf(false) }
    val sessionStats by vm.sessionStats.collectAsStateWithLifecycle()
    // Counted when the page opens, and again whenever a reply has just finished.
    LaunchedEffect(showInfo, chatId, busy) { if (showInfo) vm.loadSessionStats() }
    BackHandler(enabled = showInfo) { showInfo = false }
    // Another session picked from the drawer: its own page, not this one's.
    LaunchedEffect(chatId) { showInfo = false }
    /** The pictures and videos open in the viewer, and which one was tapped. */
    var viewing by remember { mutableStateOf<Pair<List<MessageAttachment>, Int>?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    val matches = matchingCommands(commands, draft)
    val preset = shownPreset(models, sessions.firstOrNull { it.chatId == chatId }?.modelPreset)
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    // Dictated words go into the message box, to be read and changed before it is sent.
    LaunchedEffect(Unit) {
        vm.transcripts.collect { text ->
            draft = appendTranscript(draft, text)
            Haptics.confirm(view)
            runCatching { focus.requestFocus() }
        }
    }
    // Text sent here from another page (an automation to copy into this channel) goes in the box, to read before sending.
    val pendingDraft by vm.pendingDraft.collectAsStateWithLifecycle()
    LaunchedEffect(pendingDraft) {
        val text = pendingDraft ?: return@LaunchedEffect
        draft = text
        vm.clearPendingDraft()
        runCatching { focus.requestFocus() }
    }
    /** The message whose tools (copy) are showing, after a tap on it. */
    var openId by remember { mutableStateOf<Long?>(null) }

    val channel by vm.activeChannel.collectAsStateWithLifecycle()
    val channelLooks by vm.channelLooks.collectAsStateWithLifecycle()
    val channelLook = channel?.let { lookOf(channelLooks, it) }
    val title = when {
        // A channel keeps its own name, whatever the session is called.
        channelLook != null -> channelLook.title
        else -> sessions.firstOrNull { it.chatId == chatId }?.title ?: NEW_SESSION_TITLE
    }
    val status = step.ifBlank { statusLine(mood, connected, threadLoading, busy) }
    val rows = remember(messages, forkBoundary) { chatRows(messages, System.currentTimeMillis(), forkBoundary = forkBoundary) }
    // Messages already on screen when the chat changes don't slide in again; the ones that arrive after do.
    val tracker = rememberEntranceTracker(chatId, alreadyThere = rows.map { it.key })

    // Follow the answer as it arrives, but only while you are at the bottom: scroll up to read and it lets go.
    var follow by remember { mutableStateOf(true) }
    // True while the app itself is moving the list (to the end): then it isn't a finger, and `follow` must not change.
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }.collect { (scrolling, more) ->
            if (scrolling && !autoScrolling) follow = !more
        }
    }
    val lastText = messages.lastOrNull()?.text?.length
    var shownBefore by remember { mutableStateOf(0) }
    LaunchedEffect(rows.size, lastText) {
        if (rows.isEmpty()) {
            shownBefore = 0
            return@LaunchedEffect
        }
        if (follow) {
            if (shownBefore == 0) {
                // A chat was just opened: be at its end at once, no flight through the whole history.
                listState.scrollToItem(rows.lastIndex, scrollOffset = BOTTOM)
            } else {
                autoScrolling = true
                try {
                    listState.glideToEnd()
                } finally {
                    autoScrolling = false
                }
            }
        }
        shownBefore = rows.size
    }
    val showScrollButton by remember { derivedStateOf { listState.canScrollForward } }

    LaunchedEffect(focusTick) {
        if (focusTick > 0) runCatching { focus.requestFocus() }
    }
    // A soft confirm when the agent has answered, a sharper one when something went wrong.
    val firstMood = remember { booleanArrayOf(true) }
    LaunchedEffect(chatMood) {
        if (firstMood[0]) firstMood[0] = false
        else when (chatMood) {
            BlobState.Finished -> Haptics.confirm(view)
            BlobState.Error -> Haptics.reject(view)
            else -> Unit
        }
    }

    run {
    Box(Modifier.fillMaxSize()) {
    Scaffold(
        containerColor = k.background,
        topBar = {
            ChatTopBar(title = title, channel = channel, onBack = onBack, onInfo = { showInfo = true })
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .consumeWindowInsets(inner)
                .imePadding(),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when {
                    messages.isEmpty() && threadLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        TypingDots(k.muted)
                    }
                    messages.isEmpty() -> EmptyState(mood, connected, onSettings, channel, channelLook)
                    else -> SelectionContainer {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        ) {
                            items(rows, key = { it.key }) { row ->
                                val rowModifier = Modifier
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = tween(Motion.Medium, easing = Motion.Standard))
                                    .entrance(row.key, rows.lastIndex - rows.indexOf(row), tracker, dx = 0.dp, dy = 14.dp)
                                when (row) {
                                    is ChatRow.Day -> DayDivider(row.label, rowModifier)
                                    is ChatRow.Marker -> ForkMarker(row.label, rowModifier)
                                    is ChatRow.Msg -> {
                                        val last = row.message.id == messages.lastOrNull()?.id
                                        val toolsOpen = openId == row.message.id ||
                                            (openId == null && last && !busy && row.message.role == Role.Assistant && row.message.text.isNotEmpty())
                                        val at = messages.indexOfFirst { it.id == row.message.id }
                                        val mine = row.message.role == Role.User
                                        MessageRow(
                                            row = row,
                                            mood = mood,
                                            toolsOpen = toolsOpen,
                                            onTap = { openId = if (openId == row.message.id) null else row.message.id },
                                            // Edit one of yours, or ask again for an answer: each starts a branch from there.
                                            // (A message with files can't be edited or asked again: the files aren't kept.)
                                            onEdit = if (mine && !busy && row.message.attachments.isEmpty()) ({ editing = row.message }) else null,
                                            onRetry = if (!mine && !busy && at >= 0 && promptOf(messages, at)?.attachments?.isEmpty() == true) {
                                                {
                                                    openId = null
                                                    vm.retry(row.message)
                                                }
                                            } else {
                                                null
                                            },
                                            mediaBase = null,
                                            onOpenMedia = { files, index -> viewing = files to index },
                                            onOpenFile = vm::openAttachment,
                                            live = busy && last,
                                            modifier = rowModifier,
                                        )
                                    }
                                }
                            }
                            // While the agent works and has no words yet: what it is doing, in a pill under the last message (tap it for the
                            // steps so far). Once the answer starts to appear, its own steps line above it takes over.
                            val waiting = messages.lastOrNull()
                            if (busy && (waiting == null || waiting.role != Role.Assistant || (waiting.text.isEmpty() && !waiting.isError))) {
                                item(key = "status") {
                                    WorkingLine(
                                        status, if (waiting?.role == Role.Assistant) waiting.steps else emptyList(), waiting?.id ?: 0L,
                                        blobMoodOf(chatMood).let { if (it == BlobMood.Idle) BlobMood.Thinking else it },
                                        // The steps open at the end of the list: go there, past the fade, and keep following.
                                        onOpened = {
                                            scope.launch {
                                                follow = true
                                                autoScrolling = true
                                                try {
                                                    listState.glideToEnd()
                                                } finally {
                                                    autoScrolling = false
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                // Messages fade into the ground under the bar and above the composer instead of being cut by a line.
                if (messages.isNotEmpty()) {
                    Box(
                        Modifier.align(Alignment.TopCenter).fillMaxWidth().height(18.dp)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(k.background, k.background.copy(alpha = 0f)))),
                    )
                    Box(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(22.dp)
                            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(k.background.copy(alpha = 0f), k.background))),
                    )
                }
                ScrollToBottom(
                    visible = showScrollButton && messages.isNotEmpty(),
                    onClick = {
                        scope.launch {
                            follow = true
                            autoScrolling = true
                            try {
                                listState.glideToEnd()
                            } finally {
                                autoScrolling = false
                            }
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 12.dp),
                )
            }
            NoticeBanner(notice, onDismiss = vm::clearNotice)
            // A tool waiting for your OK in this chat (PLAN §7.5): the card sits above the composer until you answer.
            val approvals by io.github.erkanw.vault.agent.ApprovalGate.pending.collectAsStateWithLifecycle()
            approvals.firstOrNull { it.sessionId == chatId }?.let { r ->
                ApprovalCard(
                    ApprovalRequest(r.id, r.tool, r.summary, r.detail),
                    sending = false,
                    onAllow = { always ->
                        io.github.erkanw.vault.agent.ApprovalGate.answer(r.id, if (always) io.github.erkanw.vault.agent.ApprovalGate.Answer.AllowAlways else io.github.erkanw.vault.agent.ApprovalGate.Answer.Allow)
                    },
                    onDeny = { io.github.erkanw.vault.agent.ApprovalGate.answer(r.id, io.github.erkanw.vault.agent.ApprovalGate.Answer.Deny) },
                    // At most a third of the screen, so the chat and the composer stay in view with the keyboard up.
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        .heightIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp / 3).dp),
                    allowAlways = r.canAlways,
                )
            }
            CommandMenu(
                matches = matches,
                onPick = { command ->
                    draft = commandDraft(command)
                    runCatching { focus.requestFocus() }
                },
            )
            ChatComposer(
                tray = { AttachmentTray(attachments, onRemove = vm::removeAttachment) },
                canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && attachments.all { it.ready },
                plusItems = listOf(
                    PlusItem("Photo", VaultIcons.Image) { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    PlusItem("File", VaultIcons.FileText) { filePicker.launch(arrayOf("*/*")) },
                    PlusItem("Commands", VaultIcons.Terminal, "Type / to see them") {
                        if (draft.isEmpty()) draft = "/"
                        runCatching { focus.requestFocus() }
                    },
                    PlusItem("Model", VaultIcons.Cube, preset?.label ?: "Choose") { showModels = true },
                ),
                draft = draft,
                onDraft = { draft = it },
                busy = busy,
                focus = focus,
                voice = voice,
                micAvailable = vm.dictationAvailable,
                onMic = {
                    Haptics.tap(view)
                    startVoice()
                },
                onVoiceFinish = {
                    Haptics.tap(view)
                    vm.finishVoice()
                },
                onVoiceCancel = vm::cancelVoice,
                onSend = {
                    Haptics.tap(view)
                    follow = true
                    openId = null
                    // A few commands are done by the screen itself; the rest go to the agent as typed.
                    when (localCommand(draft)) {
                        LocalCommand.NewSession -> vm.newSession()
                        LocalCommand.Stop -> vm.stop()
                        LocalCommand.PickModel -> showModels = true
                        null -> vm.send(draft)
                    }
                    draft = ""
                },
                onStop = vm::stop,
            )
        }
    }
    // The session’s information opens over the chat from the title, which is what was tapped.
    AnimatedVisibility(
        visible = showInfo,
        enter = fadeIn(tween(Motion.Medium, easing = Motion.Decelerate)) +
            scaleIn(tween(Motion.Long, easing = Motion.Decelerate), initialScale = 0.88f, transformOrigin = TransformOrigin(0.5f, 0.03f)),
        exit = fadeOut(tween(Motion.Short, easing = Motion.Accelerate)) +
            scaleOut(tween(Motion.Short, easing = Motion.Accelerate), targetScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0.03f)),
    ) {
        val currentSession = sessions.firstOrNull { it.chatId == chatId }
        val stats = sessionStats ?: statsOf(messages)
        // The model's full name under Blob ("Qwen3.8 27B"); the session's own name is a row below.
        val modelId = preset?.model
        SessionInfoPage(
            // The hero names what was tapped: the chat. The model lives in its own tab.
            headline = title,
            sessionName = title,
            modelTitle = modelId?.let(::prettyModelName)?.ifBlank { null },
            status = status,
            onSetUp = if (!connected) ({ showInfo = false; onSettings() }) else null,
            mood = mood,
            startedMs = currentSession?.createdMs?.takeIf { it > 0L } ?: stats.firstMs,
            modelName = modelId,
            preset = preset,
            stats = stats,
            busy = busy,
            onClose = { showInfo = false },
            onOpenMedia = { files, index -> viewing = files to index },
            onOpenFile = vm::openAttachment,
            onRename = if (currentSession != null) ({ renamingSession = currentSession }) else null,
            onChangeModel = if (models.presets.isNotEmpty()) ({ showModels = true }) else null,
            // Only when the agent can compact (Phase 4): until then the command isn't offered.
            onCompress = if (chatId != null && commands.any { it.name == "/compact" }) ({ vm.send("/compact") }) else null,
        )
    }
    }
    }

    renamingSession?.let { session ->
        RenameDialog(
            current = session.title,
            onConfirm = { name ->
                vm.renameSession(session, name)
                renamingSession = null
            },
            onDismiss = { renamingSession = null },
        )
    }

    viewing?.let { (files, index) ->
        MediaViewer(files, index, null, onClose = { viewing = null }, save = vm::saveToDownloads)
    }
    // A text file of a message or of the session page, in the app's own viewer.
    val viewingFile by vm.viewingFile.collectAsStateWithLifecycle()
    viewingFile?.let { file -> AttachmentViewer(file, vm::readFileText, vm::saveToDownloads, onClose = vm::closeFile) }

    // The prices in the model list come from OpenRouter's catalogue: kept on the phone for a few hours, fetched when the list opens.
    LaunchedEffect(showModels) { if (showModels) vm.loadCatalog() }
    if (showModels) {
        ModelSheet(
            catalog = catalog.models,
            catalogLoading = catalog.loading,
            choices = models,
            current = preset,
            enabled = !busy,
            onManage = { showModels = false; onModels() },
            onPick = {
                showModels = false
                vm.chooseModel(it)
            },
            onDismiss = { showModels = false },
        )
    }

    // After the first reply: notifications, asked once (PLAN §6.13).
    val askNotifications by vm.askNotifications.collectAsStateWithLifecycle()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.notificationsAnswered() }
    if (askNotifications) {
        VaultDialog(
            title = "Get notifications?",
            text = "${LocalAgentName.current} can tell you when it finishes in the background or when an automation runs.",
            confirm = "Allow",
            dismiss = "Not now",
            onConfirm = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) },
            onDismiss = vm::notificationsAnswered,
        )
    }

    val budgetAsk by vm.budgetAsk.collectAsStateWithLifecycle()
    if (budgetAsk != null) {
        val status = io.github.erkanw.vault.usage.Budget.status.value
        VaultDialog(
            title = "This month’s budget is used up",
            text = status?.let { "${io.github.erkanw.vault.openrouter.usd(it.spent)} of ${io.github.erkanw.vault.openrouter.usd(it.settings.limit)} spent. Send this message anyway?" }
                ?: "Send this message anyway?",
            confirm = "Send anyway",
            onConfirm = { vm.answerBudget(true) },
            onDismiss = { vm.answerBudget(false)?.let { if (draft.isBlank()) draft = it } },
            dismiss = "Not now",
        )
    }

    editing?.let { message ->
        EditPromptDialog(
            initial = message.text,
            branching = true,
            onSend = { text ->
                vm.editMessage(message, text)
                editing = null
                openId = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** A scroll offset larger than any message: "to the very end of this item". */
private const val BOTTOM = 1_000_000

/**
 * How far the list still has to go to show its last message completely: exact once the last item is on
 * screen, an estimate (from the size of the items showing) before that.
 */
private fun LazyListState.distanceToEnd(): Float {
    val info = layoutInfo
    val shown = info.visibleItemsInfo
    if (info.totalItemsCount == 0 || shown.isEmpty()) return 0f
    val viewportEnd = info.viewportEndOffset - info.afterContentPadding
    val last = shown.last()
    val rest = (last.offset + last.size - viewportEnd).toFloat()
    if (last.index == info.totalItemsCount - 1) return rest.coerceAtLeast(0f)
    val average = shown.sumOf { it.size } / shown.size.toFloat()
    return rest + (info.totalItemsCount - 1 - last.index) * average
}

/**
 * Moves the list to its end the way a fling settles: it starts gently, flies quickly while far away,
 * and slows down as it arrives, each frame covering a share of what is left. (Jumping to an item
 * moves in big chunks, and every new word of an answer used to interrupt it.)
 */
private suspend fun LazyListState.glideToEnd() {
    val start = withFrameNanos { it }
    var previous = start
    while (true) {
        val now = withFrameNanos { it }
        val dt = ((now - previous) / 1_000_000_000f).coerceIn(0.001f, 0.05f)
        previous = now
        val remaining = distanceToEnd()
        if (remaining < 1f) break
        val ramp = ((now - start) / 1_000_000_000f / 0.12f).coerceIn(0f, 1f)
        val easeIn = (ramp * ramp * (3f - 2f * ramp)).coerceAtLeast(0.15f)
        val share = remaining * (1f - exp(-10f * dt))
        val step = (share.coerceAtLeast(700f * dt) * easeIn).coerceAtMost(remaining).coerceAtMost(9_000f * dt)
        if (scrollBy(step) < 0.5f) break
    }
}

/** The chat's bar: back, the chat's name (with its channel's cloud) that opens the session's information, and info. */
@Composable
private fun ChatTopBar(title: String, channel: io.github.erkanw.vault.chat.Channel?, onBack: () -> Unit, onInfo: () -> Unit) {
    val k = MaterialTheme.vault
    Box(Modifier.fillMaxWidth().background(k.background).statusBarsPadding()) {
        VaultTopBar(
            left = { BarButton(VaultIcons.ArrowLeft, "Back to Home", onBack) },
            right = { BarButton(VaultIcons.Info, "Session information", onInfo) },
            title = {
                Row(
                    Modifier.pressable(onClick = onInfo, shape = CircleShape, pressed = k.surfaceHigh, pressedScale = 0.97f, onClickLabel = "Session information")
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (channel != null) {
                        BlobFlat(channel.color(), size = 22.dp)
                        Spacer(Modifier.width(8.dp))
                    }
                    AnimatedContent(
                        targetState = title,
                        transitionSpec = { fadeIn(tween(Motion.Medium, easing = Motion.Decelerate)) togetherWith fadeOut(tween(Motion.Short, easing = Motion.Accelerate)) },
                        label = "title",
                    ) { text ->
                        Text(text, style = VaultType.Title, color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            },
        )
    }
}

/** What the working line says when the agent sent no step of its own: what it is doing first, setup only when idle. */
private fun statusLine(state: BlobState, configured: Boolean, loading: Boolean, busy: Boolean = false): String = when {
    state == BlobState.Thinking -> "Thinking…"
    state == BlobState.Working -> "Writing…"
    state == BlobState.Searching -> "Searching…"
    state == BlobState.Error -> "Couldn’t reply"
    state == BlobState.Finished -> "Replied"
    busy -> "Thinking…"
    loading -> "Loading…"
    !configured -> "Connect an AI provider in Settings"
    else -> "Ready"
}

/** What the new Blob plays for the chat's state (the old mascot's states, mapped onto Blob Atelier's eight). */
internal fun blobMoodOf(state: BlobState): BlobMood = when (state) {
    BlobState.Thinking -> BlobMood.Thinking
    BlobState.Working -> BlobMood.Talking
    BlobState.Searching -> BlobMood.Working
    BlobState.Approval, BlobState.Question -> BlobMood.Listening
    BlobState.Error, BlobState.RateLimit -> BlobMood.Error
    BlobState.Finished -> BlobMood.Done
    BlobState.Sleeping -> BlobMood.Sleeping
    else -> BlobMood.Idle
}

/** An empty chat: Blob, the channel's name and intro (or a plain invitation), and the way to Settings when not set up. */
@Composable
private fun EmptyState(
    mood: BlobState,
    configured: Boolean,
    onSettings: () -> Unit,
    channel: io.github.erkanw.vault.chat.Channel? = null,
    look: io.github.erkanw.vault.chat.ChannelLook? = null,
) {
    val k = MaterialTheme.vault
    Column(
        Modifier.fillMaxSize().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (channel != null) {
            BlobFlat(channel.color(), size = 56.dp)
            Spacer(Modifier.height(18.dp))
        } else {
            Blob(blobMoodOf(mood), size = 170.dp, description = LocalAgentName.current)
        }
        Text(
            when {
                !configured -> "Hi, I’m ${LocalAgentName.current}"
                channel != null -> (look ?: channel.defaultLook).title
                else -> "What should ${LocalAgentName.current} do?"
            },
            color = k.text, style = VaultType.Display, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            when {
                !configured -> "Connect an AI provider in Settings to start chatting."
                channel != null -> channel.introFor(look ?: channel.defaultLook)
                else -> "Your chats stay on this phone. What you send goes to the AI provider you chose."
            },
            color = k.muted, style = VaultType.Preview, textAlign = TextAlign.Center,
        )
        if (!configured) {
            Spacer(Modifier.height(20.dp))
            PrimaryButton("Open Settings", onSettings)
        }
    }
}

/** "Today", "Yesterday", "Mon"… between the messages of different days. */
@Composable
private fun DayDivider(label: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Box(modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
        Text(label, color = k.muted, style = VaultType.Caption)
    }
}

@Composable
private fun MessageRow(
    row: ChatRow.Msg,
    mood: BlobState,
    toolsOpen: Boolean,
    onTap: () -> Unit,
    onEdit: (() -> Unit)?,
    onRetry: (() -> Unit)?,
    mediaBase: String?,
    onOpenMedia: (List<MessageAttachment>, Int) -> Unit,
    onOpenFile: (MessageAttachment) -> Unit,
    /** True for the reply being made right now: its steps are still arriving. */
    live: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val k = MaterialTheme.vault
    val message = row.message
    val mine = message.role == Role.User
    val pending = message.text.isEmpty() && message.attachments.isEmpty() && !message.isError
    // A reply still being waited for has nothing to show of its own: the working line under the list says what the agent is doing.
    if (pending) return
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        // What the agent did before answering: tool calls and notes, kept after the reply too.
        if (!mine && message.steps.isNotEmpty()) {
            StepsPanel(message.id, message.steps, live, Modifier.padding(bottom = 6.dp).widthIn(max = 340.dp))
        }
        val body: @Composable (Color) -> Unit = { content ->
            // Pictures and videos show themselves and open in the viewer; other files are named.
            val viewable = message.attachments.filter { it.viewable }
            for ((i, file) in message.attachments.withIndex()) {
                val gap = Modifier.padding(bottom = if (i == message.attachments.lastIndex && message.text.isEmpty()) 0.dp else 8.dp)
                if (file.viewable) {
                    MediaPreview(file, mediaBase, content, onOpen = { onOpenMedia(viewable, viewable.indexOf(file)) }, modifier = gap)
                } else {
                    AttachmentLine(file, content, gap, onClick = { onOpenFile(file) })
                }
            }
            when {
                message.text.isEmpty() -> Unit
                message.isError || mine -> Text(message.text, color = content, style = VaultType.Reading)
                else -> MarkdownText(message.text, content, fontSize = 16f)
            }
        }
        val shape = RoundedCornerShape(22.dp)
        val (container, content) = when {
            message.isError -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
            mine -> k.bubbleMine to k.onBubbleMine
            else -> k.bubbleTheirs to k.onBubbleTheirs
        }
        Column(
            Modifier
                .then(if (mine) Modifier.widthIn(max = 320.dp) else Modifier)
                .clip(shape)
                .pressable(onClick = onTap, shape = shape, pressedScale = 0.99f, rest = container, pressed = container, role = androidx.compose.ui.semantics.Role.Button)
                .padding(horizontal = 16.dp, vertical = if (mine) 11.dp else 13.dp),
        ) { body(content) }
        // The time lives with the tools: shown for the latest answer and for any message you tap, not under every bubble.
        AnimatedVisibility(
            visible = toolsOpen,
            enter = fadeIn(tween(Motion.Short, easing = Motion.Decelerate)) + expandVertically(Motion.settle()),
            exit = fadeOut(tween(120, easing = Motion.Accelerate)) + shrinkVertically(Motion.settle()),
        ) {
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(clockTime(message.createdAt), style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 6.dp))
                Row {
                    // A failed reply has nothing worth copying, but it can be asked for again.
                    if (!message.isError && message.text.isNotEmpty()) CopyButton(message.text)
                    if (onEdit != null) ToolButton(VaultIcons.Pencil, "Edit and send again", onEdit)
                    if (onRetry != null) ToolButton(VaultIcons.Refresh, "Ask again", onRetry)
                }
            }
        }
    }
}

/** Copy under a message: puts its text on the clipboard; the icon turns to a check for a moment, with a small tap. */
@Composable
private fun CopyButton(text: String) {
    val k = MaterialTheme.vault
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Box(
        Modifier.size(40.dp).pressable(
            onClick = {
                clipboard.setText(AnnotatedString(text))
                Haptics.tap(view)
                copied = true
            },
            shape = CircleShape, pressedScale = 0.9f, pressed = k.surfaceHigh, onClickLabel = "Copy",
        ).semantics { contentDescription = if (copied) "Copied" else "Copy" },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(copied, animationSpec = tween(Motion.Short), label = "copied") { done ->
            Icon(if (done) VaultIcons.Check else VaultIcons.Copy, null, tint = if (done) k.live else k.muted, modifier = Modifier.size(17.dp))
        }
    }
}

/** A quiet icon tool under a message (edit, ask again). */
@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    Box(
        Modifier.size(40.dp).pressable(onClick = { Haptics.tap(view); onClick() }, shape = CircleShape, pressedScale = 0.9f, pressed = k.surfaceHigh, onClickLabel = description)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Icon(icon, null, tint = k.muted, modifier = Modifier.size(17.dp)) }
}

/** A file sent with a message: its kind and its name, inside the bubble. */
@Composable
private fun AttachmentLine(file: MessageAttachment, content: Color, modifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .background(content.copy(alpha = 0.08f))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (file.image) VaultIcons.Image else VaultIcons.FileText, null, tint = content.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(file.name, color = content, style = VaultType.Label.copy(fontWeight = null), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
    }
}

/** Where a forked chat's own messages begin: what is above came from the chat it was forked from. */
@Composable
private fun ForkMarker(label: String, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    StatusLine(label, modifier.padding(vertical = 4.dp), leading = { Icon(VaultIcons.ArrowDown, null, tint = k.muted, modifier = Modifier.size(13.dp)) })
}

/** Edit one of your messages. Sending it starts a branch: what the agent said before stays as it is. */
@Composable
private fun EditPromptDialog(initial: String, branching: Boolean, onSend: (String) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var text by remember { mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = { Text("Edit your message", style = VaultType.Title, color = k.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).clip(RoundedCornerShape(16.dp)).background(k.surfaceHigh).padding(14.dp)) {
                    BasicTextField(
                        value = text, onValueChange = { text = it }, maxLines = 10,
                        textStyle = VaultType.Body.copy(color = k.text), cursorBrush = SolidColor(k.text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(
                    if (branching) {
                        "${LocalAgentName.current} answers in a new session that starts just before this message. This conversation stays as it is."
                    } else {
                        "The conversation continues from here: what came after this message is replaced."
                    },
                    color = k.muted, style = VaultType.Caption,
                )
            }
        },
        confirmButton = { QuietButton("Send", { if (text.isNotBlank()) onSend(text) }, color = if (text.isNotBlank()) k.text else k.faint) },
        dismissButton = { QuietButton("Cancel", onDismiss, color = k.muted) },
    )
}

/** A round button that appears when you have scrolled up, and takes you back to the latest message. */
@Composable
private fun ScrollToBottom(visible: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(Motion.Short)) + scaleIn(Motion.quick(), initialScale = 0.7f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.7f),
    ) {
        Box(
            Modifier.size(44.dp).pressable(onClick = onClick, shape = CircleShape, pressedScale = 0.9f, rest = k.pressed, pressed = k.surfaceHigh, onClickLabel = "Scroll to the latest message"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(VaultIcons.ArrowDown, "Scroll to the latest message", tint = k.text, modifier = Modifier.size(19.dp))
        }
    }
}

/** One entry of the composer's + sheet: attach a photo or file, open the commands, pick the model. */
class PlusItem(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector, val subtitle: String? = null, val onClick: () -> Unit)

/**
 * The message box, Home's Ask field grown up: a pill on the lift with + on the left (a sheet: photo, file, commands,
 * model), the text, and a round button on the right that is send when there is something to send, stop while the agent
 * answers, and the microphone when the box is empty.
 */
@Composable
private fun ChatComposer(
    tray: (@Composable () -> Unit)?,
    canSend: Boolean,
    plusItems: List<PlusItem>,
    draft: String,
    onDraft: (String) -> Unit,
    busy: Boolean,
    focus: FocusRequester,
    voice: VoiceState,
    micAvailable: Boolean,
    onMic: () -> Unit,
    onVoiceFinish: () -> Unit,
    onVoiceCancel: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val k = MaterialTheme.vault
    val recording = voice is VoiceState.Recording
    val transcribing = voice is VoiceState.Transcribing
    val showMic = micAvailable && !busy && !canSend && !recording && !transcribing
    val strong = busy || canSend || recording
    val disc by animateColorAsState(if (strong) k.accent else k.pressed, Motion.quick(), label = "sendDisc")
    val glyph by animateColorAsState(if (strong) k.onAccent else k.text, Motion.quick(), label = "sendGlyph")
    var plusOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(28.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)
            .clip(shape)
            .background(k.surfaceHigh),
    ) {
        if (voice is VoiceState.Idle) tray?.invoke()
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            if (voice is VoiceState.Idle && plusItems.isNotEmpty()) {
                Box(
                    Modifier.size(40.dp).pressable(onClick = { plusOpen = true }, shape = CircleShape, pressedScale = 0.9f, pressed = k.pressed, onClickLabel = "Attach, commands, model")
                        .semantics { contentDescription = "Attach, commands, model" },
                    contentAlignment = Alignment.Center,
                ) { Icon(VaultIcons.Plus, null, tint = k.muted, modifier = Modifier.size(22.dp)) }
            } else {
                Spacer(Modifier.width(8.dp))
            }
            Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                when (voice) {
                    is VoiceState.Recording -> RecordingBar(voice, onVoiceCancel)
                    is VoiceState.Transcribing -> TranscribingBar(onVoiceCancel)
                    is VoiceState.Idle -> BasicTextField(
                        value = draft,
                        onValueChange = onDraft,
                        maxLines = 6,
                        textStyle = VaultType.Reading.copy(color = k.text),
                        cursorBrush = SolidColor(k.text),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus).padding(vertical = 8.dp),
                        decorationBox = { inner ->
                            Box {
                                if (draft.isEmpty()) Text("Message ${LocalAgentName.current}", style = VaultType.Reading, color = k.muted, maxLines = 1)
                                inner()
                            }
                        },
                    )
                }
            }
            val label = when {
                recording -> "Finish the recording"
                busy -> "Stop"
                canSend -> "Send"
                showMic -> "Record a voice message"
                else -> "Send"
            }
            Box(
                Modifier.size(40.dp).let { base ->
                    if (strong || showMic) {
                        base.pressable(
                            onClick = {
                                when {
                                    recording -> onVoiceFinish()
                                    busy -> onStop()
                                    canSend -> onSend()
                                    else -> onMic()
                                }
                            },
                            shape = CircleShape, pressedScale = 0.88f, rest = disc, pressed = disc, onClickLabel = label,
                        )
                    } else {
                        base.clip(CircleShape).background(disc)
                    }
                }.semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                // Which symbol: stop while the agent answers, check while recording, send, or the microphone.
                val symbol = when {
                    recording -> 3
                    busy -> 0
                    transcribing -> 4
                    canSend -> 1
                    else -> 2
                }
                Crossfade(symbol, animationSpec = tween(Motion.Short, easing = Motion.Standard), label = "sendIcon") { s ->
                    when (s) {
                        0 -> Box(Modifier.size(13.dp).background(glyph, RoundedCornerShape(3.dp)))
                        1 -> Icon(VaultIcons.ArrowUp, null, tint = glyph, modifier = Modifier.size(20.dp))
                        3 -> Icon(VaultIcons.Check, null, tint = glyph, modifier = Modifier.size(20.dp))
                        4 -> androidx.compose.material3.CircularProgressIndicator(color = k.muted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        else -> if (micAvailable) MicIcon(glyph) else Icon(VaultIcons.ArrowUp, null, tint = k.faint, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
    if (plusOpen) {
        VaultSheet(onDismiss = { plusOpen = false }) {
            Column(Modifier.padding(horizontal = 12.dp)) {
                for (item in plusItems) {
                    VaultRow(
                        item.label,
                        onClick = { plusOpen = false; item.onClick() },
                        subtitle = item.subtitle,
                        leading = { Icon(item.icon, null, tint = k.text, modifier = Modifier.size(20.dp)) },
                    )
                }
            }
        }
    }
}

/**
 * The working line under the last message while the agent has no words yet: a small Blob playing what the agent does, and what it
 * is doing in words. Tap it to open what the agent has done so far, live. [key] is the reply being waited for, so each reply
 * starts closed.
 */
@Composable
private fun WorkingLine(
    text: String,
    steps: List<AgentStep>,
    key: Long,
    mood: BlobMood,
    modifier: Modifier = Modifier,
    /** Called once the steps have opened (and as more arrive while open): the list goes to its end so they show whole. */
    onOpened: () -> Unit = {},
) {
    val k = MaterialTheme.vault
    var open by rememberSaveable(key) { mutableStateOf(false) }
    val turn by animateFloatAsState(if (open) 180f else 0f, Motion.quick(), label = "lineTurn")
    LaunchedEffect(open, steps.size) {
        if (open) {
            delay(Motion.Medium.toLong())
            onOpened()
        }
    }
    // Only what the words don't already say: the number of tools the agent has used.
    val tools = steps.count { it.kind == io.github.erkanw.vault.chat.StepKind.Tool }
    Column(modifier) {
        Row(
            Modifier.heightIn(min = 48.dp).pressable(onClick = { open = !open }, shape = CircleShape, pressedScale = 0.98f, pressed = k.surfaceHigh, onClickLabel = if (open) "Hide ${LocalAgentName.current}’s steps" else "Show ${LocalAgentName.current}’s steps")
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Blob(mood, size = 48.dp, interactive = false, description = "${LocalAgentName.current} is working")
            Text(text, style = VaultType.Label.copy(fontWeight = null), color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (tools > 0) {
                Text(" · $tools tool${if (tools == 1) "" else "s"}", style = VaultType.Label.copy(fontWeight = null), color = k.muted, maxLines = 1)
            }
            Spacer(Modifier.width(6.dp))
            Icon(VaultIcons.ChevronDown, null, tint = k.muted, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = turn })
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.Short, easing = Motion.Decelerate)) + expandVertically(Motion.settle()),
            exit = fadeOut(tween(120, easing = Motion.Accelerate)) + shrinkVertically(Motion.settle()),
        ) {
            if (steps.isEmpty()) {
                Text(
                    "Nothing yet: the steps show here as they come.", color = k.muted, style = VaultType.Caption,
                    modifier = Modifier.padding(start = 12.dp, top = 4.dp),
                )
            } else {
                StepsList(steps, Modifier.padding(top = 4.dp).widthIn(max = 340.dp))
            }
        }
    }
}
