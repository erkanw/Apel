package io.github.erkanw.vault.ui

import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import io.github.erkanw.vault.tools.WorkspaceOps
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material3.DropdownMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
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
 * The agent's workspace (PLAN §6.6): folders to go into, files to look at, edit, save on the phone (in Downloads/Apel) or
 * share, a whole folder as a zip; + makes a file or folder or imports one from the phone; ⋯ renames, moves or moves to the
 * Trash, which keeps things 30 days. memory/ and skills/ are only shown: they are edited on their own pages.
 */
@Composable
fun FilesScreen(vm: PagesViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val context = LocalContext.current
    val state by vm.files.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.openFolder(state.path) }
    val viewed by vm.viewed.collectAsStateWithLifecycle()
    var asking by remember { mutableStateOf<NameAsk?>(null) }
    var showTrash by rememberSaveable { mutableStateOf(false) }
    // A text file being edited: a page of its own (a dialog wouldn't make room for the keyboard).
    var editingPath by rememberSaveable { mutableStateOf<String?>(null) }
    editingPath?.let { path ->
        val name = path.substringAfterLast('/')
        val initial = remember(path) { runCatching { java.io.File(io.github.erkanw.vault.tools.Workspace.root(context), path).readText() }.getOrDefault("") }
        TextEditorScreen(name, path, initial, "Plain text; Markdown shows as such when you look at it.",
            onSave = { vm.saveText(path, it); editingPath = null; vm.viewFile(path, name) }, onBack = { editingPath = null; vm.viewFile(path, name) })
        return
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri) }
    viewed?.let { open ->
        if (open.picture != null) {
            MediaViewer(listOf(MessageAttachment(open.name, image = true, localPath = open.picture)), 0, null, onClose = vm::closeViewed)
        } else {
            TextViewer(
                open,
                onDownload = {
                    // The viewer closes so the page underneath can say "Saving…" and offer to open the saved file.
                    vm.closeViewed()
                    vm.download(open.path, open.name, folder = false)
                },
                onClose = vm::closeViewed,
                onEdit = if (WorkspaceOps.readOnly(open.path)) null else ({ editingPath = open.path; vm.closeViewed() }),
            )
        }
    }
    if (showTrash) {
        TrashPage(vm, onBack = { showTrash = false; vm.openFolder(state.path) })
        return
    }
    val here = state.path
    val canEdit = !WorkspaceOps.readOnly(here)
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
            action = {
                Row {
                    if (inFolder) BarButton(
                        VaultIcons.Download, "Save this folder as a zip",
                        { if (state.busy == null) vm.download(state.path, state.path.substringAfterLast('/').ifEmpty { "workspace" }, folder = true) },
                        tint = if (state.busy == null) k.text else k.faint,
                    )
                    if (canEdit) Box {
                        var open by remember { mutableStateOf(false) }
                        BarButton(VaultIcons.Plus, "New file, folder or import", { open = true })
                        DropdownMenu(
                            expanded = open, onDismissRequest = { open = false },
                            shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                        ) {
                            MenuItem(VaultIcons.FileText, "New file") { open = false; asking = NameAsk.NewFile }
                            MenuItem(VaultIcons.Folder, "New folder") { open = false; asking = NameAsk.NewFolder }
                            MenuItem(VaultIcons.Download, "Import from the phone") { open = false; importer.launch(arrayOf("*/*")) }
                        }
                    }
                }
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
                                val path = joinPath(state.path, entry.name)
                                EntryRow(
                                    entry = entry,
                                    busy = state.busy == entry.name,
                                    onClick = {
                                        if (entry.dir) vm.openFolder(path)
                                        else vm.viewFile(path, entry.name)
                                    },
                                    onSave = { vm.download(path, entry.name, folder = entry.dir) },
                                    onShare = if (entry.dir) null else ({ share(context, path, entry.name) }),
                                    onMove = if (WorkspaceOps.readOnly(path)) null else ({ asking = NameAsk.Move(path) }),
                                    onTrash = if (WorkspaceOps.readOnly(path)) null else ({ vm.trashEntry(path) }),
                                )
                            }
                        }
                    }
                    if (!inFolder) item(key = "trash") {
                        VaultGroup(Modifier.padding(top = 12.dp)) {
                            GroupActionRow("Trash", "Deleted files, kept ${WorkspaceOps.TRASH_DAYS} days", { showTrash = true }, chevron = true,
                                leading = { Icon(VaultIcons.Trash, null, tint = k.muted, modifier = Modifier.size(20.dp)) })
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
    asking?.let { ask ->
        NameDialog(ask, onConfirm = { value ->
            asking = null
            when (ask) {
                NameAsk.NewFile -> vm.newFile(value)
                NameAsk.NewFolder -> vm.newFolder(value)
                is NameAsk.Move -> if (value.trim() != ask.path) vm.moveEntry(ask.path, value)
            }
        }, onDismiss = { asking = null })
    }
}

/** What a name dialog is for. */
private sealed interface NameAsk {
    data object NewFile : NameAsk
    data object NewFolder : NameAsk
    /** Rename or move [path]: the field holds its whole path, so changing the folder part moves it. */
    data class Move(val path: String) : NameAsk
}

@Composable
private fun NameDialog(ask: NameAsk, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var text by remember { mutableStateOf(if (ask is NameAsk.Move) ask.path else "") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = {
            Text(
                when (ask) { NameAsk.NewFile -> "New file"; NameAsk.NewFolder -> "New folder"; is NameAsk.Move -> "Rename or move" },
                style = VaultType.Title, color = k.text,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogField(text, { text = it }, if (ask == NameAsk.NewFile) "ideas.md" else if (ask == NameAsk.NewFolder) "projects" else "notes/ideas.md")
                if (ask is NameAsk.Move) Text("Change the folder part to move it.", style = VaultType.Caption, color = k.muted)
            }
        },
        confirmButton = { QuietButton(if (ask is NameAsk.Move) "Save" else "Create", { if (text.isNotBlank()) onConfirm(text.trim()) }, color = if (text.isNotBlank()) k.text else k.faint) },
        dismissButton = { QuietButton("Cancel", onDismiss, color = k.muted) },
    )
}

/** Hands a workspace file to another app (mail, messages, a drive) through the app's file provider. */
private fun share(context: android.content.Context, path: String, name: String) {
    val file = java.io.File(io.github.erkanw.vault.tools.Workspace.root(context), path)
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(Intent.createChooser(send, name))
    } catch (e: ActivityNotFoundException) {
        android.widget.Toast.makeText(context, "No app on the phone can take it.", android.widget.Toast.LENGTH_SHORT).show()
    }
}

/** The Trash (PLAN §6.6): what was deleted in the last 30 days, each with Restore, and Delete for good in its menu. */
@Composable
private fun TrashPage(vm: PagesViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val items by vm.trash.collectAsStateWithLifecycle()
    var forever by remember { mutableStateOf<io.github.erkanw.vault.tools.TrashItem?>(null) }
    LaunchedEffect(Unit) { vm.loadTrash() }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader("Trash", "Kept ${WorkspaceOps.TRASH_DAYS} days", onBack)
        if (items.isEmpty()) {
            Text("The Trash is empty.", style = VaultType.Preview, color = k.muted, modifier = Modifier.padding(24.dp))
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
                item(key = "trash") {
                    VaultGroup(Modifier.navigationBarsPadding()) {
                        items.forEachIndexed { i, item ->
                            if (i > 0) GroupDivider()
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (item.dir) VaultIcons.Folder else VaultIcons.FileText, null, tint = k.muted, modifier = Modifier.size(20.dp))
                                Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                    Text(item.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("Deleted ${shortDateTime(item.deleted)}", style = VaultType.Caption, color = k.muted, maxLines = 1)
                                    Text("From ${item.from.ifEmpty { "the workspace" }}", style = VaultType.Caption, color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                QuietButton("Restore", { vm.restore(item.id) })
                                Box {
                                    var open by remember { mutableStateOf(false) }
                                    BarButton(VaultIcons.More, "Options for ${item.name}", { open = true }, tint = k.muted, iconSize = 20.dp)
                                    DropdownMenu(
                                        expanded = open, onDismissRequest = { open = false },
                                        shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
                                    ) {
                                        MenuItem(VaultIcons.Trash, "Delete for good", color = k.danger) { open = false; forever = item }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    forever?.let { item ->
        VaultDialog(
            title = "Delete ${item.name} for good?",
            text = "It can’t be brought back.",
            confirm = "Delete", destructive = true,
            onConfirm = { vm.deleteForever(item.id); forever = null },
            onDismiss = { forever = null },
        )
    }
}


/** A folder or a file: its kind as an icon, its name, size or "Folder" and when it changed; ⋯ on the right. */
@Composable
private fun EntryRow(
    entry: FileEntry, busy: Boolean, onClick: () -> Unit, onSave: () -> Unit,
    onShare: (() -> Unit)? = null, onMove: (() -> Unit)? = null, onTrash: (() -> Unit)? = null,
) {
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
        // A tap on a file looks at it; everything else is in its menu.
        Box {
            var open by remember { mutableStateOf(false) }
            BarButton(VaultIcons.More, "Options for ${entry.name}", { if (!busy) open = true }, tint = if (busy) k.faint else k.muted, iconSize = 20.dp)
            DropdownMenu(
                expanded = open, onDismissRequest = { open = false },
                shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp,
            ) {
                MenuItem(VaultIcons.Download, if (entry.dir) "Save as a zip" else "Save to the phone") { open = false; onSave() }
                onShare?.let { MenuItem(VaultIcons.ArrowUp, "Share") { open = false; it() } }
                onMove?.let { MenuItem(VaultIcons.Pencil, "Rename or move") { open = false; it() } }
                onTrash?.let { MenuItem(VaultIcons.Trash, "Move to the Trash", color = k.danger) { open = false; it() } }
            }
        }
    }
}

private fun iconOf(entry: FileEntry) = when {
    entry.dir -> VaultIcons.Folder
    entry.name.substringAfterLast('.', "").lowercase() in setOf("png", "jpg", "jpeg", "gif", "webp", "heic") -> VaultIcons.Image
    else -> VaultIcons.FileText
}

/** A text file of the agent’s folders, full screen: selectable, markdown drawn as such, with the save button in the corner. */
@Composable
private fun TextViewer(file: ViewedFile, onDownload: () -> Unit, onClose: () -> Unit, onEdit: (() -> Unit)? = null) {
    val k = MaterialTheme.vault
    val text = file.text.orEmpty()
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(k.background).statusBarsPadding().navigationBarsPadding()) {
            VaultTopBar(
                left = { BarButton(VaultIcons.ArrowLeft, "Back to the files", onClose) },
                right = {
                    Row {
                        if (onEdit != null) BarButton(VaultIcons.Pencil, "Edit ${file.name}", onEdit)
                        BarButton(VaultIcons.Download, "Save ${file.name}", onDownload)
                    }
                },
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
