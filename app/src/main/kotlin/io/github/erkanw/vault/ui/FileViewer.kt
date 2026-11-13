package io.github.erkanw.vault.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.erkanw.vault.SavedFile
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.TextKind
import io.github.erkanw.vault.chat.VIEWER_MAX_BYTES
import io.github.erkanw.vault.chat.humanSize
import io.github.erkanw.vault.chat.textKindOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * The in-app file viewer, for the text files of a chat and of the session page (Markdown drawn as such, plain text as
 * prose, everything else in monospace), on the app's own ground: the page header's place, so back is on the left, the
 * file's name and kind in the middle, and the download on the right, where the Files page keeps its own. Saving goes
 * to Downloads/the agent; a short note then opens under the header, moving the text down, and offers to open it in another app.
 */

/** Where a file's text is. */
sealed interface FileLoad {
    data object Loading : FileLoad
    data class Shown(val text: String, val cut: Boolean, val bytes: Long) : FileLoad
    data object NotText : FileLoad
    data class Failed(val reason: String) : FileLoad
}

/** The download button's state: it turns into a check for a moment once the file is saved. */
enum class SaveState { Idle, Saving, Saved }

/** A file of a message: reads its text, and saves it when asked. */
@Composable
fun AttachmentViewer(
    file: MessageAttachment,
    read: suspend (MessageAttachment) -> Pair<String, Boolean>?,
    save: suspend (MessageAttachment) -> SavedFile,
    onClose: () -> Unit,
) {
    var load by remember(file) { mutableStateOf<FileLoad>(FileLoad.Loading) }
    var attempt by remember(file) { mutableIntStateOf(0) }
    LaunchedEffect(file, attempt) {
        load = FileLoad.Loading
        load = try {
            read(file)?.let { (text, cut) -> FileLoad.Shown(text, cut, text.toByteArray().size.toLong()) } ?: FileLoad.NotText
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            FileLoad.Failed(e.message ?: "the download failed")
        }
    }
    TextFileViewer(file.name, load, onRetry = { attempt++ }, save = { save(file) }, onClose = onClose)
}

/** The viewer itself, full screen over whatever opened it. [save] puts the file in Downloads/the agent and returns it. */
@Composable
fun TextFileViewer(
    name: String,
    load: FileLoad,
    onRetry: () -> Unit,
    save: suspend () -> SavedFile,
    onClose: () -> Unit,
) {
    val k = MaterialTheme.vault
    val scope = rememberCoroutineScope()
    var saveState by remember { mutableStateOf(SaveState.Idle) }
    var saved by remember { mutableStateOf<SavedFile?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val kind = textKindOf(name) ?: TextKind.Text
    val title = name.substringAfterLast('/')
    val detail = when (load) {
        is FileLoad.Shown -> kind.label + " · " + if (load.cut) "over ${VIEWER_MAX_BYTES / 1024} KB" else humanSize(load.bytes)
        else -> kind.label
    }
    fun download() {
        if (saveState == SaveState.Saving) return
        saveState = SaveState.Saving
        problem = null
        scope.launch {
            try {
                saved = save()
                saveState = SaveState.Saved
                delay(2_500)
                if (saveState == SaveState.Saved) saveState = SaveState.Idle
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                saveState = SaveState.Idle
                problem = "Couldn’t save $title: ${e.message ?: "the download failed"}."
            }
        }
    }
    // The note at the bottom goes away by itself after a while.
    LaunchedEffect(saved, problem) {
        if (saved != null || problem != null) {
            delay(6_000)
            saved = null
            problem = null
        }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(k.background)) {
            Column(Modifier.fillMaxSize()) {
                PageHeader(
                    title, detail, onClose, icon = VaultIcons.Close, iconDescription = "Close",
                    action = { DownloadButton(saveState, "Save $title in Downloads", ::download) },
                )
                // Under the header, by the button that was tapped; it moves the text down rather than covering it.
                SavedNote(saved, problem, onProblem = { problem = it })
                Box(Modifier.weight(1f)) {
                    Crossfade(load, animationSpec = androidx.compose.animation.core.tween(Motion.Short), label = "file") { state ->
                        when (state) {
                            FileLoad.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
                            is FileLoad.Failed -> Centered("Couldn’t open $title: ${state.reason}.", "Try again", onRetry)
                            FileLoad.NotText -> Centered("$title isn’t text the app can show. Save it to open it in another app.", "Save it", ::download)
                            is FileLoad.Shown -> FileText(state, kind)
                        }
                    }
                }
            }
        }
    }
}

/** "Saved in Downloads/the agent" (with Open) or why it couldn't be, opening under a viewer's header. */
@Composable
internal fun SavedNote(saved: SavedFile?, problem: String?, onProblem: (String) -> Unit, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    AnimatedVisibility(
        saved != null || problem != null,
        enter = fadeIn(androidx.compose.animation.core.tween(Motion.Short)) + androidx.compose.animation.expandVertically(Motion.settle(), expandFrom = Alignment.Top),
        exit = fadeOut(androidx.compose.animation.core.tween(Motion.Short)) + androidx.compose.animation.shrinkVertically(Motion.settle(), shrinkTowards = Alignment.Top),
        modifier = modifier,
    ) {
        val s = saved
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().heightIn(min = 56.dp)
                .clip(RoundedCornerShape(18.dp)).background(k.pressed).padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                problem ?: "Saved in Downloads/Apel",
                style = VaultType.Label.copy(fontWeight = null), color = if (problem != null) k.attention else k.text,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (s != null && problem == null) {
                QuietButton("Open", {
                    val view = Intent(Intent.ACTION_VIEW).setDataAndType(s.uri, s.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    try {
                        context.startActivity(view)
                    } catch (e: ActivityNotFoundException) {
                        onProblem("No app on the phone opens that kind of file.")
                    }
                })
            } else {
                Spacer(Modifier.width(12.dp))
            }
        }
    }
}

/** The header's download: the arrow; quiet while saving; a green check for a moment once saved. */
@Composable
internal fun DownloadButton(state: SaveState, description: String, onClick: () -> Unit, tint: androidx.compose.ui.graphics.Color? = null) {
    val k = MaterialTheme.vault
    Crossfade(state, animationSpec = androidx.compose.animation.core.tween(Motion.Short), label = "download") { s ->
        when (s) {
            SaveState.Saved -> BarButton(VaultIcons.Check, "Saved", onClick, tint = k.live)
            SaveState.Saving -> BarButton(VaultIcons.Download, "Saving…", {}, tint = k.faint)
            SaveState.Idle -> BarButton(VaultIcons.Download, description, onClick, tint = tint ?: k.text)
        }
    }
}

@Composable
private fun FileText(state: FileLoad.Shown, kind: TextKind) {
    val k = MaterialTheme.vault
    SelectionContainer(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 96.dp),
        ) {
            when {
                state.text.isBlank() -> Text("This file is empty.", style = VaultType.Preview, color = k.muted)
                kind == TextKind.Markdown -> MarkdownText(state.text, k.text, fontSize = 16f)
                kind == TextKind.Text -> Text(state.text, style = VaultType.Reading, color = k.text)
                // Code keeps its lines: it scrolls sideways rather than wrapping.
                else -> Box(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        state.text, color = k.text, fontFamily = FontFamily.Monospace, softWrap = false,
                        style = VaultType.Label.copy(fontWeight = null, fontSize = 13.sp, lineHeight = 20.sp),
                    )
                }
            }
            if (state.cut) {
                Text(
                    "Showing the first ${VIEWER_MAX_BYTES / 1024} KB. Save the file for the rest.",
                    style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun Centered(text: String, action: String, onAction: () -> Unit) {
    val k = MaterialTheme.vault
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = VaultType.Preview, color = k.muted, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        SecondaryButton(action, onAction)
    }
}

