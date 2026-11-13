package io.github.erkanw.vault.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.semantics
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.SessionStats
import io.github.erkanw.vault.chat.formatTokens
import io.github.erkanw.vault.chat.relativeTime
import io.github.erkanw.vault.mascot.Blob
import io.github.erkanw.vault.mascot.BlobState
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything about the session on screen: Blob playing how it stands, the model's name, then three tabs: the session
 * (name, start, length, last message), the model (and how full its memory is, with Compress), and the files.
 */
@Composable
fun SessionInfoPage(
    /** What the page is about, large under Blob: the chat's name, as in the title that was tapped. */
    headline: String,
    sessionName: String,
    /** The model's full name ("Qwen3.8 27B"), or null when none is set. */
    modelTitle: String?,
    status: String,
    /** Set when the server isn't set up: the status line becomes the way to Settings. */
    onSetUp: (() -> Unit)?,
    mood: BlobState,
    startedMs: Long,
    modelName: String?,
    preset: ModelPreset?,
    stats: SessionStats,
    busy: Boolean,
    onClose: () -> Unit,
    onOpenMedia: (List<MessageAttachment>, Int) -> Unit,
    /** A file that isn't a picture or a video: the text viewer, or saved and opened in another app. */
    onOpenFile: (MessageAttachment) -> Unit = {},
    /** Null when there is nothing to rename yet (a session that hasn't been started). */
    onRename: (() -> Unit)?,
    onChangeModel: (() -> Unit)?,
    onCompress: (() -> Unit)?,
) {
    val k = MaterialTheme.vault
    val tracker = rememberEntranceTracker(sessionName)
    // Blob grows into place as the page opens.
    val grow = remember { Animatable(0.6f) }
    LaunchedEffect(Unit) { grow.animateTo(1f, Motion.settle()) }

    var tab by rememberSaveable { mutableIntStateOf(0) }
    // The page covers the chat: taps must not reach it underneath.
    Column(Modifier.fillMaxSize().background(k.background).pointerInput(Unit) { detectTapGestures { } }) {
        Box(Modifier.statusBarsPadding()) {
            VaultTopBar(
                left = { BarButton(VaultIcons.ArrowLeft, "Back to the chat", onClose) },
                title = { Text("Session", style = VaultType.Title, color = k.text) },
            )
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.graphicsLayer { scaleX = grow.value; scaleY = grow.value; alpha = ((grow.value - 0.6f) / 0.4f).coerceIn(0f, 1f) }) {
                    Blob(blobMoodOf(mood), size = 170.dp, description = LocalAgentName.current)
                }
                Text(headline, style = VaultType.Display, color = k.text, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                // How it stands; when the server isn't set up it is "this needs you": amber, and a tap leads to the fix.
                Text(
                    status, style = VaultType.Caption, color = if (onSetUp != null) k.attention else k.muted, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp).then(
                        if (onSetUp != null) Modifier.minimumInteractiveComponentSize().pressable(onClick = onSetUp, shape = CircleShape, pressed = k.surfaceHigh, onClickLabel = "Open Settings")
                            .padding(horizontal = 12.dp, vertical = 6.dp) else Modifier,
                    ),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                listOf("Session", "Model", "Files").forEachIndexed { i, label -> VaultChip(label, { tab = i }, selected = tab == i) }
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> InfoGroup(Modifier.entrance("session", 0, tracker, dx = 0.dp, dy = 8.dp)) {
                    InfoRow("Name", sessionName, action = if (onRename != null) "Rename" else null, onClick = onRename)
                    InfoDivider()
                    InfoRow("Started", if (startedMs > 0L) dateTime(startedMs) else "Not yet")
                    InfoDivider()
                    InfoRow("Length", lengthText(stats), detail = "${plural(stats.prompts, "prompt")} · ${plural(stats.responses, "response")}")
                    if (stats.lastMs > 0L) {
                        InfoDivider()
                        InfoRow(
                            "Last message",
                            relativeTime(System.currentTimeMillis(), stats.lastMs).let {
                                when {
                                    it == "now" -> "Just now"
                                    it.endsWith(" min") || it.endsWith(" h") -> "$it ago"
                                    else -> it
                                }
                            },
                        )
                    }
                }
                1 -> if (modelName == null) {
                    EmptyTab(
                        // Without a server the amber line above already leads to Settings: say why, no second button.
                        if (onSetUp != null) "Connect an AI provider before you choose a model." else "No model chosen yet.",
                        action = if (onSetUp == null && onChangeModel != null) "Choose a model" to onChangeModel else null,
                        modifier = Modifier.entrance("model-empty", 0, tracker, dx = 0.dp, dy = 8.dp),
                    )
                } else InfoGroup(Modifier.entrance("model", 0, tracker, dx = 0.dp, dy = 8.dp)) {
                    InfoRow(
                        "Model", modelTitle ?: modelName,
                        detail = modelName.takeIf { it.isNotBlank() && it != modelTitle },
                        action = if (onChangeModel != null) "Change" else null,
                        onClick = onChangeModel,
                    )
                    val window = preset?.contextTokens ?: 0L
                    if (window > 0L) {
                        InfoDivider()
                        ContextRow(
                            used = stats.estimatedTokens.toLong(), window = window, compacted = stats.compacted,
                            // Nothing to compress in a session with no messages yet.
                            onCompress = if (stats.messages > 0) onCompress else null,
                            busy = busy,
                        )
                    }
                }
                else -> if (stats.attachments.isEmpty()) {
                    EmptyTab("Pictures and files you send in this chat show up here.", action = null, modifier = Modifier.entrance("files-empty", 0, tracker, dx = 0.dp, dy = 8.dp))
                } else InfoGroup(Modifier.entrance("files", 0, tracker, dx = 0.dp, dy = 8.dp)) {
                    run {
                        val pictures = stats.attachments.count { it.image }
                        val videos = stats.attachments.count { it.video }
                        val documents = stats.attachments.size - pictures - videos
                        InfoRow(
                            "Files",
                            plural(stats.attachments.size, "file"),
                            detail = listOfNotNull(
                                pictures.takeIf { it > 0 }?.let { plural(it, "picture") },
                                videos.takeIf { it > 0 }?.let { plural(it, "video") },
                                documents.takeIf { it > 0 }?.let { plural(it, "document") },
                            ).joinToString(" · "),
                        )
                        val viewable = stats.attachments.filter { it.viewable }
                        for (file in stats.attachments) {
                            InfoDivider()
                            FileRow(file, onClick = if (file.viewable) ({ onOpenMedia(viewable, viewable.indexOf(file)) }) else ({ onOpenFile(file) }))
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun dateTime(ms: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(ms))

private fun plural(n: Int, word: String): String = if (n == 1) "1 $word" else "$n ${word}s"

/** "42 messages", or "At least 600 messages" when only the newest part of a very long session could be read. */
private fun lengthText(stats: SessionStats): String {
    val text = plural(stats.messages, "message")
    return if (stats.complete || stats.messages == 0) text else "At least $text"
}

/** A group of rows on the lift, 18 dp corners, no outline. */
@Composable
private fun InfoGroup(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val k = MaterialTheme.vault
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(k.surfaceHigh).padding(vertical = 4.dp)) { content() }
}

/** Between two rows of one group (two parts of the same surface meeting: the one place a hairline is allowed). */
@Composable
private fun InfoDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(MaterialTheme.vault.line))
}

/** A tab with nothing in it yet: one plain sentence, and the way out when there is one. */
@Composable
private fun EmptyTab(text: String, action: Pair<String, () -> Unit>?, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text, style = VaultType.Preview.copy(lineBreak = LineBreak.Heading), color = k.muted, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 280.dp))
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            SecondaryButton(action.first, action.second)
        }
    }
}

/**
 * A label and its value; with an [action] ("Rename") and [onClick] the whole row is a button. At large font sizes the
 * label goes above the value instead of beside it, so neither has to break.
 */
@Composable
private fun InfoRow(label: String, value: String, detail: String? = null, action: String? = null, onClick: (() -> Unit)? = null) {
    val k = MaterialTheme.vault
    val stacked = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.3f
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .let {
                if (onClick != null) it.pressable(onClick = onClick, pressedScale = 1f, pressed = k.pressed, onClickLabel = action)
                else it.semantics(mergeDescendants = true) {}
            }
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.Top,
    ) {
        if (!stacked) Text(label, style = VaultType.Body, color = k.muted, modifier = Modifier.width(112.dp))
        Column(Modifier.weight(1f)) {
            if (stacked) Text(label, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
            Text(value, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
            if (!detail.isNullOrBlank()) Text(detail, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
        }
        if (action != null) {
            Spacer(Modifier.width(10.dp))
            Text(action, style = VaultType.Label, color = k.text)
        }
    }
}

/** How much of the model's memory the session takes up, as a bar. It is a guess, and says so. */
@Composable
private fun ContextRow(used: Long, window: Long, compacted: Boolean, onCompress: (() -> Unit)?, busy: Boolean) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    val share = (used.toFloat() / window).coerceIn(0f, 1f)
    val progress = remember { Animatable(0f) }
    LaunchedEffect(share) { progress.animateTo(share, tween(Motion.Long, easing = Motion.Decelerate)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp)) {
        Row(verticalAlignment = Alignment.Top) {
            Text("Context", style = VaultType.Body, color = k.muted, modifier = Modifier.width(112.dp))
            Column(Modifier.weight(1f)) {
                Text("${formatTokens(window)} tokens", style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
                Text(
                    "About ${formatTokens(used)} used (${(share * 100).toInt().coerceAtLeast(if (used > 0) 1 else 0)}%)",
                    style = VaultType.Label.copy(fontWeight = null), color = k.muted,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(k.pressed)) {
            Box(Modifier.fillMaxWidth(progress.value.coerceAtLeast(0.01f)).height(6.dp).clip(CircleShape).background(k.text))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            if (compacted) {
                "An estimate. The context was compressed earlier in this session, so only what came after it is counted."
            } else {
                "An estimate from the messages in this session. Compress asks ${LocalAgentName.current} to summarise the older ones to make room."
            },
            style = VaultType.Caption, color = k.muted,
        )
        if (onCompress != null) {
            Spacer(Modifier.height(12.dp))
            // The agent’s own /compact: it summarises the older messages to free room, and the chat says how it went.
            SecondaryButton(
                "Compress",
                onClick = { Haptics.tap(view); onCompress() },
                onLift = true,
                enabled = !busy,
            )
            if (busy) Text("Wait for ${LocalAgentName.current} to finish answering.", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun FileRow(file: MessageAttachment, onClick: (() -> Unit)?) {
    val k = MaterialTheme.vault
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .let { if (onClick != null) it.pressable(onClick = onClick, pressedScale = 1f, pressed = k.pressed, onClickLabel = "Open ${file.name}") else it }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            when {
                file.image || file.video -> VaultIcons.Image
                else -> VaultIcons.FileText
            },
            null, tint = k.muted, modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(file.name, style = VaultType.Body, color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (onClick != null) Icon(VaultIcons.ChevronRight, null, tint = k.muted, modifier = Modifier.size(18.dp))
    }
}
