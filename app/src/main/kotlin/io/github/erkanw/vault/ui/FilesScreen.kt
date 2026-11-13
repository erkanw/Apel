package io.github.erkanw.vault.ui

import androidx.compose.foundation.layout.heightIn
import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.remember
import io.github.erkanw.vault.chat.MessageAttachment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.PagesViewModel
import io.github.erkanw.vault.SavedFile
import io.github.erkanw.vault.ViewedFile
import io.github.erkanw.vault.chat.FileEntry
import io.github.erkanw.vault.chat.humanSize
import io.github.erkanw.vault.chat.joinPath
import io.github.erkanw.vault.chat.shortDateTime

/**
 * The agent's projects folder, as the server shows it: folders to go into, files to look at or save on the phone (in
 * Downloads/the agent), and a whole folder as a zip. Read only: nothing here changes the server.
 */
@Composable
fun FilesScreen(vm: PagesViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val state by vm.files.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.openFolder(state.path) }
    val viewed by vm.viewed.collectAsStateWithLifecycle()
    viewed?.let { open ->
        if (open.picture != null) {
            MediaViewer(listOf(MessageAttachment(open.name, image = true, localPath = open.picture)), 0, null, onClose = vm::closeViewed)
        } else {
            TextViewer(open, onDownload = {
                // The viewer closes so the page underneath can say "Saving…" and offer to open the saved file.
                vm.closeViewed()
                vm.download(open.path, open.name, folder = false)
            }, onClose = vm::closeViewed)
        }
    }
    val inFolder = state.path.isNotEmpty()
    val tracker = rememberEntranceTracker(state.path)

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader(
            "Files",
            // The top shows the agent’s work folders side by side (projects, research, ...); below it the path starts with one of them.
            state.path.ifEmpty { "Workspace" },
            // Back goes up a folder first, like the system Back.
            onBack = { if (!vm.folderUp()) onBack() },
            iconDescription = if (inFolder) "Up one folder" else "Back",
            action = if (inFolder) {
                {
                    BarButton(
                        VaultIcons.Download, "Save this folder as a zip",
                        { if (state.busy == null) vm.download(state.path, state.path.substringAfterLast('/').ifEmpty { "workspace" }, folder = true) },
                        tint = if (state.busy == null) k.text else k.faint,
                    )
                }
            } else {
                null
            },
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val page = io.github.erkanw.vault.PageState(state.entries, state.loading, state.error)
            PageBody(page, if (inFolder) "This folder is empty." else "Nothing here yet.", onRetry = { vm.openFolder(state.path) }) { entries ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
                    item(key = "group:" + state.path) {
                        VaultGroup(Modifier.entrance("files:" + state.path, 0, tracker, dx = 0.dp, dy = 8.dp)) {
                            entries.forEachIndexed { i, entry ->
                                if (i > 0) GroupDivider()
                                EntryRow(
                                    entry = entry,
                                    busy = state.busy == entry.name,
                                    onClick = {
                                        if (entry.dir) vm.openFolder(joinPath(state.path, entry.name))
                                        else vm.viewFile(joinPath(state.path, entry.name), entry.name)
                                    },
                                    onSave = { vm.download(joinPath(state.path, entry.name), entry.name, folder = entry.dir) },
                                )
                            }
                        }
                    }
                }
            }
            // What the download is doing, or what it made.
            val note = state.saved
            when {
                state.viewing != null -> Banner("Opening ${state.viewing}…", null, {}, Modifier.align(Alignment.BottomCenter))
                state.busy != null -> Banner("Saving ${state.busy}…", null, {}, Modifier.align(Alignment.BottomCenter))
                note != null -> SavedBanner(note, onDismiss = vm::dismissSaved, modifier = Modifier.align(Alignment.BottomCenter))
            }
        }
    }
}

/** A folder or a file: its kind as an icon, its name, size or "Folder" and when it changed; a save button on the right. */
@Composable
private fun EntryRow(entry: FileEntry, busy: Boolean, onClick: () -> Unit, onSave: () -> Unit) {
    val k = MaterialTheme.vault
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick = onClick, pressedScale = 1f, pressed = k.pressed, onClickLabel = if (entry.dir) "Open folder" else "Look at the file")
            .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconOf(entry), null, tint = if (entry.dir) k.text else k.muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(entry.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                if (entry.dir) "Folder" else humanSize(entry.size),
                entry.modified.takeIf { it > 0 }?.let { shortDateTime(it) },
            ).joinToString(" · ")
            Text(detail, style = VaultType.Caption, color = k.muted, maxLines = 1)
        }
        // A tap on a file looks at it; saving it to the phone (a folder as a zip) is this button's job alone.
        BarButton(
            VaultIcons.Download, if (entry.dir) "Save ${entry.name} as a zip" else "Save ${entry.name}",
            { if (!busy) onSave() }, tint = if (busy) k.faint else k.muted, iconSize = 20.dp,
        )
    }
}

private fun iconOf(entry: FileEntry) = when {
    entry.dir -> VaultIcons.Folder
    entry.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "heic") -> VaultIcons.Image
    else -> VaultIcons.FileText
}

/** A text file of the agent’s folders, full screen: selectable, markdown drawn as such, with the save button in the corner. */
@Composable
private fun TextViewer(file: ViewedFile, onDownload: () -> Unit, onClose: () -> Unit) {
    val k = MaterialTheme.vault
    val text = file.text.orEmpty()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(k.background).statusBarsPadding().navigationBarsPadding()) {
            VaultTopBar(
                left = { BarButton(VaultIcons.ArrowLeft, "Back to the files", onClose) },
                right = { BarButton(VaultIcons.Download, "Save ${file.name}", onDownload) },
                title = { Text(file.name, style = VaultType.Title, color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
            SelectionContainer(Modifier.weight(1f).fillMaxWidth()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (file.name.endsWith(".md", ignoreCase = true)) {
                        MarkdownText(text, k.text, fontSize = 16f)
                    } else {
                        Text(text.ifEmpty { "(empty file)" }, color = k.text, fontFamily = FontFamily.Monospace, style = VaultType.Label.copy(fontWeight = null, lineHeight = 19.sp))
                    }
                }
            }
        }
    }
}

/** A short note floating above the bottom edge (saving, opening, saved), with its action. */
@Composable
private fun Banner(text: String, action: String?, onAction: () -> Unit, modifier: Modifier = Modifier, onDismiss: (() -> Unit)? = null) {
    val k = MaterialTheme.vault
    Row(
        modifier
            .navigationBarsPadding()
            .padding(16.dp)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(k.pressed)
            .padding(start = 18.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = VaultType.Label.copy(fontWeight = null), color = k.text, modifier = Modifier.weight(1f).padding(vertical = 10.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action != null) QuietButton(action, onAction)
        if (onDismiss != null) BarButton(VaultIcons.Close, "Dismiss", onDismiss, tint = k.muted, iconSize = 18.dp)
        if (action == null && onDismiss == null) Spacer(Modifier.width(12.dp))
    }
}

/** "Saved … in Downloads/the agent", with a button that opens it in whatever app handles that kind of file. */
@Composable
private fun SavedBanner(saved: SavedFile, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Banner(
        text = "Saved ${saved.name} in Downloads/Apel",
        action = "Open",
        onAction = {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(saved.uri, saved.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try {
                context.startActivity(view)
            } catch (e: ActivityNotFoundException) {
                android.widget.Toast.makeText(context, "No app on the phone opens that kind of file.", android.widget.Toast.LENGTH_SHORT).show()
            }
        },
        modifier = modifier,
        onDismiss = onDismiss,
    )
}
