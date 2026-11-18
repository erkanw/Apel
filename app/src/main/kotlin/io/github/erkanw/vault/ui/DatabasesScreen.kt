@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.DatabasesViewModel
import io.github.erkanw.vault.userdb.Access
import io.github.erkanw.vault.userdb.Column as DbColumn
import io.github.erkanw.vault.userdb.ColumnDef
import io.github.erkanw.vault.userdb.SqlRules
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Databases (handbook §3, PLAN §6.10): the registry, one database's tables, the table browser, the SQL console, backups
 * and activity, as pages inside this screen (Back steps up one). Create and edit forms are sheets; deleting a table needs
 * its name typed; a console change shows how many rows it changes before it is kept.
 */
@Composable
fun DatabasesScreen(vm: DatabasesViewModel, onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf("list") }
    var db by rememberSaveable { mutableStateOf("") }
    var table by rememberSaveable { mutableStateOf("") }
    val up: () -> Unit = {
        when (page) {
            "list" -> onBack()
            "db" -> page = "list"
            "table" -> { vm.clearPage(); page = "db" }
            else -> page = if (page == "console" || page == "backups" || page == "activity") "db" else "list"
        }
    }
    BackHandler(enabled = page != "list") { up() }
    val note by vm.note.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.vault.background)) {
        when (page) {
            "list" -> DbListPage(vm, onBack, onOpen = { db = it; page = "db" })
            "db" -> DbPage(vm, db, up, onTable = { table = it; page = "table" }, onPage = { page = it })
            "table" -> TablePage(vm, db, table, up)
            "console" -> ConsolePage(vm, db, up)
            "backups" -> BackupsPage(vm, db, up)
            "activity" -> ActivityPage(vm, db, up)
        }
        NoticeBanner(note, onDismiss = vm::clearNote, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp))
    }
}

/**
 * What a database lets the agent do, as a chip on its row (not a control: the row opens the database). A dot in a signal
 * colour leads it: green when the agent may read, amber when it may also change; none when it doesn't see the database.
 */
@Composable
private fun AccessPill(access: Access) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    val said = when (access) {
        Access.None -> "$agent can’t see it"
        Access.Read -> "$agent can read it"
        Access.ReadWrite -> "$agent can read and change it"
    }
    Row(
        Modifier.heightIn(min = 32.dp).clip(RoundedCornerShape(50)).background(k.background).padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = said },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (access != Access.None) {
            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (access == Access.ReadWrite) k.attention else k.live))
            Spacer(Modifier.width(7.dp))
        }
        Text(access.label, style = VaultType.Label.copy(fontWeight = null), color = if (access == Access.None) k.muted else k.text, maxLines = 1)
    }
}

private fun sizeLabel(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
}

private fun dateTime(ms: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ms))

// ---- the registry ------------------------------------------------------------------------------------------------

@Composable
private fun DbListPage(vm: DatabasesViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    val dbs by vm.dbs.collectAsStateWithLifecycle()
    val archived by vm.archived.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.refreshList() }
    Column(Modifier.fillMaxSize()) {
        PageHeader(
            "Databases", dbs?.let { if (it.isEmpty()) "Data $agent can keep for you" else "${it.size} database${if (it.size == 1) "" else "s"}" }.orEmpty(), onBack,
            action = { BarButton(VaultIcons.Plus, "New database", { creating = true }) },
        )
        val list = dbs
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (list.isEmpty()) item(key = "empty") {
                    Text(
                        "No databases yet. Make one with +, then choose how far $agent may go in it.", style = VaultType.Body, color = k.muted,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp),
                    )
                }
                items(list, key = { it.db.name }) { row ->
                    VaultGroup(Modifier.pressable(onClick = { onOpen(row.db.name) }, shape = RoundedCornerShape(18.dp), pressedScale = 0.985f, pressed = k.pressed)) {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.db.name, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (row.db.description.isNotBlank()) Text(row.db.description, style = VaultType.Label.copy(fontWeight = null), color = k.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${row.tables} table${if (row.tables == 1) "" else "s"} · ${sizeLabel(row.bytes)}" + if (row.db.access == Access.ReadWrite && row.db.mayCreateTables) " · may create tables" else "",
                                    style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            AccessPill(row.db.access)
                        }
                    }
                }
                if (archived.isNotEmpty()) {
                    item(key = "archived-label") { GroupLabel("Archived") }
                    item(key = "archived") {
                        VaultGroup {
                            archived.forEachIndexed { i, a ->
                                if (i > 0) GroupDivider()
                                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(a.name, style = VaultType.Body, color = k.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    QuietButton("Restore", { vm.restoreArchived(a.name) })
                                    QuietButton("Delete", { deleting = a.name }, color = k.danger)
                                }
                            }
                        }
                    }
                }
                item(key = "foot") {
                    Text(
                        "$agent sees only the databases you open to it, and changes them only with Read and write. Every change is backed up first.",
                        style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
    if (creating) NewDbSheet(vm, onDismiss = { creating = false }, onMade = { creating = false; onOpen(it) })
    deleting?.let { name ->
        ConfirmDialog(
            "Delete $name for good?", "The database and its backups are deleted. This can’t be undone.", "Delete for good",
            onConfirm = { vm.deleteArchived(name); deleting = null }, onDismiss = { deleting = null },
        )
    }
}

/** The access chips and the "may create tables" switch, shared by the new-database and access sheets. */
@Composable
private fun AccessChooser(access: Access, mayCreate: Boolean, onAccess: (Access) -> Unit, onMayCreate: (Boolean) -> Unit) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("What $agent may do", style = VaultType.Label, color = k.muted)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Access.entries.forEach { a -> VaultChip(a.label, { onAccess(a) }, selected = access == a) }
        }
        Text(
            when (access) {
                Access.None -> "$agent doesn’t see this database."
                Access.Read -> "$agent can read it, never change it."
                Access.ReadWrite -> "$agent can add, change and delete rows, and asks you first unless you allowed Change databases in Tools."
            },
            style = VaultType.Caption, color = k.muted,
        )
    }
    if (access == Access.ReadWrite) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).pressable(onClick = { onMayCreate(!mayCreate) }, pressedScale = 1f, pressed = k.pressed, role = androidx.compose.ui.semantics.Role.Switch)
                .semantics { toggleableState = androidx.compose.ui.state.ToggleableState(mayCreate) }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("May create tables", style = VaultType.Body, color = k.text)
                Text("New tables, indexes and columns. Never deleting them.", style = VaultType.Caption, color = k.muted)
            }
            VaultSwitch(mayCreate, null)
        }
    }
}

@Composable
private fun NewDbSheet(vm: DatabasesViewModel, onDismiss: () -> Unit, onMade: (String) -> Unit) {
    val k = MaterialTheme.vault
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var access by remember { mutableStateOf(Access.Read) }
    var mayCreate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    VaultSheet(onDismiss = onDismiss, title = "New database") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultField(
                name, { name = it.lowercase().replace(' ', '_').filter { c -> c.isLetterOrDigit() || c == '_' }.take(31) }, "Name",
                placeholder = "reading_log", supporting = "Lower-case letters, digits and _. It is how you and ${LocalAgentName.current} refer to it.",
            )
            VaultField(description, { description = it.take(200) }, "What it holds (optional)", placeholder = "Books I read, with dates and ratings")
            AccessChooser(access, mayCreate, { access = it }, { mayCreate = it })
            error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            PrimaryButton("Make database", {
                vm.create(name, description, access, mayCreate) { e -> if (e == null) onMade(name.trim()) else error = e }
            }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---- one database ------------------------------------------------------------------------------------------------

@Composable
private fun DbPage(vm: DatabasesViewModel, name: String, onBack: () -> Unit, onTable: (String) -> Unit, onPage: (String) -> Unit) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    val dbs by vm.dbs.collectAsStateWithLifecycle()
    val schema by vm.schema.collectAsStateWithLifecycle()
    val row = dbs?.firstOrNull { it.db.name == name }
    var menu by remember { mutableStateOf(false) }
    var editingAccess by remember { mutableStateOf(false) }
    var newTable by remember { mutableStateOf(false) }
    var archiving by remember { mutableStateOf(false) }
    var csv by remember { mutableStateOf<Pair<String, String>?>(null) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { vm.readCsv(uri)?.let { csv = it } }
    }
    LaunchedEffect(name) { vm.loadSchema(name) }
    Column(Modifier.fillMaxSize()) {
        PageHeader(name, row?.db?.description.orEmpty(), onBack, action = {
            Box {
                BarButton(VaultIcons.More, "Options for $name", { menu = true })
                DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                    MenuItem(VaultIcons.SquarePen, "SQL console") { menu = false; onPage("console") }
                    MenuItem(VaultIcons.FileText, "Import a CSV") { menu = false; picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel", "application/octet-stream")) }
                    MenuItem(VaultIcons.Clock, "Backups") { menu = false; onPage("backups") }
                    MenuItem(VaultIcons.Info, "Activity") { menu = false; onPage("activity") }
                    MenuItem(VaultIcons.Archive, "Archive") { menu = false; archiving = true }
                }
            }
        })
        val s = schema
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
            if (row != null) item(key = "access") {
                VaultGroup {
                    GroupActionRow(
                        "What $agent may do",
                        row.db.access.label + if (row.db.access == Access.ReadWrite && row.db.mayCreateTables) " · may create tables" else "",
                        { editingAccess = true }, chevron = true,
                    )
                }
                Spacer(Modifier.height(18.dp))
            }
            item(key = "tables-label") {
                GroupLabel("Tables", trailing = { BarButton(VaultIcons.Plus, "New table", { newTable = true }, tint = k.muted, iconSize = 20.dp) })
            }
            when {
                s == null -> item(key = "loading") { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { TypingDots(k.muted) } }
                s.tables.isEmpty() -> item(key = "none") {
                    Text("No tables yet. Make one with +, import a CSV from the menu, or ask $agent to.", style = VaultType.Body, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
                }
                else -> item(key = "tables") {
                    VaultGroup {
                        s.tables.forEachIndexed { i, t ->
                            if (i > 0) GroupDivider()
                            GroupActionRow(t.name, "${t.rows} row${if (t.rows == 1) "" else "s"} · ${t.columns.size} column${if (t.columns.size == 1) "" else "s"}", { onTable(t.name) }, chevron = true)
                        }
                    }
                }
            }
            if (s != null) item(key = "foot") {
                Text("${sizeLabel(s.bytes)}. Every change is backed up first; restore one from Backups in the menu.", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp))
            }
        }
    }
    if (editingAccess && row != null) {
        var access by remember { mutableStateOf(row.db.access) }
        var mayCreate by remember { mutableStateOf(row.db.mayCreateTables) }
        VaultSheet(onDismiss = { editingAccess = false }, title = name) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                AccessChooser(access, mayCreate, { access = it }, { mayCreate = it })
                PrimaryButton("Save", { vm.setAccess(name, access, mayCreate); editingAccess = false }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
            }
        }
    }
    if (newTable) NewTableSheet(vm, name, onDismiss = { newTable = false })
    csv?.let { (file, text) -> ImportSheet(vm, name, file, text, schema?.tables?.map { it.name }.orEmpty(), onDismiss = { csv = null }) }
    if (archiving) {
        ConfirmDialog(
            "Archive $name?", "It is moved aside and $agent can no longer use it. You can restore it from the end of the Databases list.", "Archive",
            onConfirm = { vm.archive(name); archiving = false; onBack() }, onDismiss = { archiving = false },
        )
    }
}

@Composable
private fun NewTableSheet(vm: DatabasesViewModel, db: String, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var table by remember { mutableStateOf("") }
    val names = remember { mutableStateListOf("id", "") }
    val types = remember { mutableStateListOf("INTEGER", "TEXT") }
    val keys = remember { mutableStateListOf(true, false) }
    var error by remember { mutableStateOf<String?>(null) }
    VaultSheet(onDismiss = onDismiss, title = "New table") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultField(table, { table = it.replace(' ', '_').take(63) }, "Table name", placeholder = "books")
            names.indices.forEach { i ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    VaultField(names[i], { names[i] = it.replace(' ', '_').take(63) }, "Column ${i + 1}", placeholder = if (i == 0) "id" else "title")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SqlRules.COLUMN_TYPES.forEach { t -> VaultChip(t.lowercase().replaceFirstChar { it.uppercase() }, { types[i] = t }, selected = types[i] == t) }
                        VaultChip("Key", { keys[i] = !keys[i] }, selected = keys[i])
                    }
                }
            }
            QuietButton("Add a column", { names += ""; types += "TEXT"; keys += false })
            error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            PrimaryButton("Make table", {
                val cols = names.indices.filter { names[it].isNotBlank() }.map { ColumnDef(names[it].trim(), types[it], primaryKey = keys[it]) }
                vm.createTable(db, table.trim(), cols) { e -> if (e == null) onDismiss() else error = e }
            }, Modifier.fillMaxWidth(), enabled = table.isNotBlank() && names.any { it.isNotBlank() })
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ImportSheet(vm: DatabasesViewModel, db: String, file: String, text: String, tables: List<String>, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    val suggested = remember(file) { file.substringBeforeLast('.').lowercase().replace(Regex("[^a-z0-9_]"), "_").trim('_').ifEmpty { "imported" }.let { if (it[0].isDigit()) "t_$it" else it }.take(63) }
    var into by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf(suggested) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val lines = remember(text) { text.count { it == '\n' } }
    VaultSheet(onDismiss = onDismiss, title = "Import $file") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("About ${(lines - 1).coerceAtLeast(0)} rows. The first line names the columns.", style = VaultType.Caption, color = k.muted)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Into", style = VaultType.Label, color = k.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultChip("A new table", { into = null }, selected = into == null)
                    tables.forEach { t -> VaultChip(t, { into = t }, selected = into == t) }
                }
            }
            if (into == null) VaultField(newName, { newName = it.replace(' ', '_').take(63) }, "New table name", supporting = "Column types (text, whole numbers, decimals) are taken from the values.")
            else Text("The rows are added after the ones in $into; its columns must include every column in the file.", style = VaultType.Caption, color = k.muted)
            error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            PrimaryButton(if (busy) "Importing…" else "Import", {
                if (busy) return@PrimaryButton
                busy = true
                vm.importCsv(db, into ?: newName.trim(), text) { e -> busy = false; if (e == null) onDismiss() else error = e }
            }, Modifier.fillMaxWidth(), enabled = !busy && (into != null || newName.isNotBlank()))
            Spacer(Modifier.height(4.dp))
        }
    }
}

// ---- the table browser -------------------------------------------------------------------------------------------

/** How many rows a page of the table browser shows. */
private const val PAGE_ROWS = 50

/**
 * The shape of a grid: each column as wide as its longest value (13 sp mono, 64 to 240 dp; the first column, which stays
 * put, at most 140 dp so the others keep room), and the sideways scroll the rows share.
 */
private class GridSpec(val headers: List<String>, val widths: List<Dp>, val scroll: androidx.compose.foundation.ScrollState)

@Composable
private fun rememberGridSpec(headers: List<String>, rows: List<List<Any?>>): GridSpec {
    val scale = LocalDensity.current.fontScale
    val scroll = rememberScrollState()
    return remember(headers, rows, scale) {
        val widths = headers.indices.map { c ->
            val longest = maxOf(headers[c].length, rows.maxOfOrNull { cellText(it.getOrNull(c)).length } ?: 0)
            (longest * 13f * 0.62f * scale + 44f).coerceIn(64f, if (c == 0) 140f else 240f).dp
        }
        GridSpec(headers, widths, scroll)
    }
}

private fun cellText(v: Any?): String = when (v) {
    null -> "null"
    is Double -> if (v == Math.floor(v) && kotlin.math.abs(v) < 1e15) v.toLong().toString() + ".0" else v.toString()
    else -> v.toString().replace('\n', ' ')
}

private val GridTop = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)
private val GridBottom = RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp)

/**
 * One line of a grid (the header band or a row), like the chat's markdown tables: a surface block, a header band, 13 sp
 * mono values with null in faint. The first cell stays put while the others scroll sideways with every other line; a
 * hairline marks the edge once scrolled, and the right edge fades while there is more.
 */
@Composable
private fun GridLine(
    spec: GridSpec,
    values: List<Any?>,
    header: Boolean,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape? = null,
    sortColumn: String? = null,
    descending: Boolean = false,
    onHeader: ((String) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    val mono = VaultType.Label.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = null)
    @Composable
    fun Cell(c: Int) {
        val name = spec.headers[c]
        val raw = values.getOrNull(c)
        Row(
            Modifier.width(spec.widths[c]).heightIn(min = 48.dp).then(
                if (header && onHeader != null) Modifier.pressable(onClick = { onHeader(name) }, pressedScale = 1f, pressed = k.pressed, onClickLabel = "Sort by $name") else Modifier,
            ).padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (header) name else cellText(raw), style = if (header) mono.copy(fontWeight = FontWeight.SemiBold, fontFamily = null) else mono,
                color = if (!header && raw == null) k.faint else k.text, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                modifier = Modifier.weight(1f, fill = false),
            )
            // The sorted column: a small chevron, down for descending.
            if (header && name == sortColumn) Icon(VaultIcons.ChevronDown, if (descending) "sorted descending" else "sorted ascending", tint = k.muted, modifier = Modifier.padding(start = 4.dp).size(14.dp).rotate(if (descending) 0f else 180f))
        }
    }
    Box(
        modifier.fillMaxWidth().then(if (shape != null) Modifier.clip(shape) else Modifier).background(k.surface)
            .then(if (header) Modifier.background(k.surfaceHigh.copy(alpha = 0.6f)) else Modifier)
            .then(if (onClick != null) Modifier.pressable(onClick = onClick, pressedScale = 1f, pressed = k.pressed, onClickLabel = "Edit this row") else Modifier),
    ) {
        Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Cell(0)
            if (spec.scroll.value > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(k.text.copy(alpha = 0.08f)))
            Row(Modifier.horizontalScroll(spec.scroll)) { for (c in 1 until spec.headers.size) Cell(c) }
        }
        if (spec.scroll.canScrollForward) Box(
            Modifier.matchParentSize().background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(0f to k.surface.copy(alpha = 0f), 0.86f to k.surface.copy(alpha = 0f), 1f to k.surface),
            ),
        )
    }
}

/** The hairline between two lines of a grid. */
@Composable
private fun GridRule() {
    val k = MaterialTheme.vault
    Box(Modifier.fillMaxWidth().background(k.surface)) { Box(Modifier.fillMaxWidth().height(1.dp).background(k.text.copy(alpha = 0.08f))) }
}

/** A whole grid in place (the console's results): the header band and every row. */
@Composable
private fun Grid(headers: List<String>, rows: List<List<Any?>>, modifier: Modifier = Modifier) {
    val spec = rememberGridSpec(headers, rows)
    Column(modifier) {
        GridLine(spec, headers, header = true, shape = if (rows.isEmpty()) RoundedCornerShape(12.dp) else GridTop)
        rows.forEachIndexed { r, values ->
            GridRule()
            GridLine(spec, values, header = false, shape = if (r == rows.lastIndex) GridBottom else null)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TablePage(vm: DatabasesViewModel, db: String, table: String, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val page by vm.page.collectAsStateWithLifecycle()
    var q by remember { mutableStateOf(DatabasesViewModel.PageQuery()) }
    var search by rememberSaveable { mutableStateOf("") }
    var editing by remember { mutableStateOf<Int?>(null) }
    var inserting by remember { mutableStateOf(false) }
    var addColumn by remember { mutableStateOf(false) }
    var addIndex by remember { mutableStateOf(false) }
    var dropping by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var version by remember { mutableStateOf(0) }
    LaunchedEffect(search) { if (search != q.search) { kotlinx.coroutines.delay(250); q = q.copy(search = search, page = 0) } }
    LaunchedEffect(q, version) { vm.browse(db, table, q) }
    val p = page
    Column(Modifier.fillMaxSize()) {
        PageHeader(table, p?.let { "${it.total} row${if (it.total == 1) "" else "s"}" }.orEmpty(), onBack, action = {
            Row {
                if (p?.editable != false) BarButton(VaultIcons.Plus, "Add a row", { inserting = true })
                Box {
                    BarButton(VaultIcons.More, "Options for $table", { menu = true })
                    DropdownMenu(menu, { menu = false }, shape = RoundedCornerShape(16.dp), containerColor = k.pressed, tonalElevation = 0.dp, shadowElevation = 0.dp) {
                        MenuItem(VaultIcons.Plus, "Add a column") { menu = false; addColumn = true }
                        MenuItem(VaultIcons.Sliders, "Add an index") { menu = false; addIndex = true }
                        MenuItem(VaultIcons.Download, "Export as CSV") { menu = false; vm.exportCsv(db, table) }
                        MenuItem(VaultIcons.Trash, "Delete table", color = k.danger) { menu = false; dropping = true }
                    }
                }
            }
        })
        SearchPill(search, { search = it }, Modifier.padding(horizontal = 16.dp, vertical = 6.dp), placeholder = "Search this table")
        when {
            p == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
            else -> {
                val headers = p.columns.map { it.name }
                val spec = rememberGridSpec(headers, p.rows.map { it.values })
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
                    if (p.rows.isEmpty()) item(key = "empty") {
                        Text(
                            if (q.search.isNotBlank()) "Nothing matches “${q.search}”." else "No rows yet. Add one with +, or import a CSV from the database’s menu.",
                            style = VaultType.Body, color = k.muted, modifier = Modifier.padding(8.dp),
                        )
                    } else {
                        // The header band stays put while the rows scroll under it.
                        stickyHeader(key = "header") {
                            GridLine(
                                spec, headers, header = true, shape = GridTop, sortColumn = q.sort, descending = q.descending,
                                onHeader = { c -> q = if (q.sort == c) q.copy(descending = !q.descending, page = 0) else q.copy(sort = c, descending = false, page = 0) },
                            )
                        }
                        items(p.rows.size, key = { i -> p.rows[i].rowid ?: i.toLong() }) { i ->
                            Column {
                                GridRule()
                                GridLine(
                                    spec, p.rows[i].values, header = false, shape = if (i == p.rows.lastIndex) GridBottom else null,
                                    onClick = if (p.editable) ({ editing = i }) else null,
                                )
                            }
                        }
                    }
                    if (!p.editable) item(key = "readonly") {
                        Text("This table has no row ids, so its rows can be read here but not edited.", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(8.dp))
                    }
                    if (p.total > PAGE_ROWS) item(key = "paging") {
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val back = q.page > 0
                            QuietButton("Previous", { if (back) q = q.copy(page = q.page - 1) }, color = if (back) k.text else k.faint,
                                modifier = if (back) Modifier else Modifier.semantics { disabled() })
                            Text(
                                "${q.page * PAGE_ROWS + 1}–${minOf(p.total, (q.page + 1) * PAGE_ROWS)} of ${p.total}", style = VaultType.Caption, color = k.muted,
                                textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                            )
                            val more = (q.page + 1) * PAGE_ROWS < p.total
                            QuietButton("Next", { if (more) q = q.copy(page = q.page + 1) }, color = if (more) k.text else k.faint,
                                modifier = if (more) Modifier else Modifier.semantics { disabled() })
                        }
                    }
                }
            }
        }
    }
    val refresh = { version++ }
    if (inserting && p != null) RowSheet(p.columns, null, onDismiss = { inserting = false }, onSave = { values, done ->
        vm.insertRow(db, table, values) { e -> done(e); if (e == null) { inserting = false; refresh() } }
    })
    editing?.let { i ->
        val row = p?.rows?.getOrNull(i) ?: return@let
        RowSheet(p.columns, row.values, onDismiss = { editing = null }, onSave = { values, done ->
            vm.updateRow(db, table, row.rowid!!, values) { e -> done(e); if (e == null) { editing = null; refresh() } }
        }, onDelete = { vm.deleteRow(db, table, row.rowid!!) { editing = null; refresh() } })
    }
    if (addColumn) AddColumnSheet(onDismiss = { addColumn = false }) { column, type, done ->
        vm.addColumn(db, table, column, type) { e -> done(e); if (e == null) { addColumn = false; refresh() } }
    }
    if (addIndex && p != null) AddIndexSheet(p.columns.map { it.name }, onDismiss = { addIndex = false }) { cols, unique, done ->
        vm.createIndex(db, table, cols, unique) { e -> done(e); if (e == null) addIndex = false }
    }
    if (dropping) TypeToConfirmDialog(table, onConfirm = { dropping = false; vm.dropTable(db, table, onBack) }, onDismiss = { dropping = false })
}

/** A row's fields, to edit or to add one. An empty field is saved as null. Only changed fields are sent. */
@Composable
private fun RowSheet(columns: List<DbColumn>, values: List<Any?>?, onDismiss: () -> Unit, onSave: (Map<String, String>, (String?) -> Unit) -> Unit, onDelete: (() -> Unit)? = null) {
    val k = MaterialTheme.vault
    val initial = remember(values) { columns.indices.map { values?.getOrNull(it)?.let { v -> if (v.toString().startsWith("<blob ")) null else v.toString() } ?: "" } }
    val fields = remember(values) { mutableStateListOf(*initial.toTypedArray()) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    VaultSheet(onDismiss = onDismiss, title = if (values == null) "New row" else "Edit row") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            columns.forEachIndexed { i, c ->
                val blob = values?.getOrNull(i)?.toString()?.startsWith("<blob ") == true
                VaultField(
                    fields[i], { if (!blob) fields[i] = it }, c.name + " · " + c.type.lowercase().ifEmpty { "any" }, placeholder = if (blob) values?.getOrNull(i).toString() else "null",
                    keyboardType = if (c.type.uppercase() in setOf("INTEGER", "REAL", "NUMERIC")) androidx.compose.ui.text.input.KeyboardType.Number else androidx.compose.ui.text.input.KeyboardType.Text,
                    singleLine = c.type.uppercase() != "TEXT", minLines = 1,
                )
            }
            Text("An empty field is saved as null. A backup is made first.", style = VaultType.Caption, color = k.muted)
            error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            PrimaryButton(if (values == null) "Add row" else "Save", {
                val changed = columns.indices.filter { values == null || fields[it] != initial[it] }.associate { columns[it].name to fields[it] }
                    .let { m -> if (values == null) m.filterValues { it.isNotEmpty() } else m }
                if (values != null && changed.isEmpty()) { onDismiss(); return@PrimaryButton }
                onSave(changed) { e -> error = e }
            }, Modifier.fillMaxWidth())
            if (onDelete != null) QuietButton("Delete row", { confirmDelete = true }, color = k.danger)
            Spacer(Modifier.height(4.dp))
        }
    }
    if (confirmDelete && onDelete != null) {
        ConfirmDialog("Delete this row?", "A backup is made first, so it can be put back from Backups.", "Delete row", onConfirm = { confirmDelete = false; onDelete() }, onDismiss = { confirmDelete = false })
    }
}

@Composable
private fun AddColumnSheet(onDismiss: () -> Unit, onSave: (String, String, (String?) -> Unit) -> Unit) {
    val k = MaterialTheme.vault
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("TEXT") }
    var error by remember { mutableStateOf<String?>(null) }
    VaultSheet(onDismiss = onDismiss, title = "Add a column") {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultField(name, { name = it.replace(' ', '_').take(63) }, "Column name", placeholder = "rating")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SqlRules.COLUMN_TYPES.forEach { t -> VaultChip(t.lowercase().replaceFirstChar { it.uppercase() }, { type = t }, selected = type == t) }
            }
            Text("Existing rows get null in it.", style = VaultType.Caption, color = k.muted)
            error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            PrimaryButton("Add column", { onSave(name.trim(), type) { e -> error = e } }, Modifier.fillMaxWidth(), enabled = name.isNotBlank())
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun AddIndexSheet(columns: List<String>, onDismiss: () -> Unit, onSave: (List<String>, Boolean, (String?) -> Unit) -> Unit) {
    val k = MaterialTheme.vault
    val chosen = remember { mutableStateListOf<String>() }
    var unique by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    VaultSheet(onDismiss = onDismiss, title = "Add an index") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("An index makes searching and sorting by these columns faster.", style = VaultType.Caption, color = k.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                columns.forEach { c -> VaultChip(c, { if (c in chosen) chosen.remove(c) else chosen.add(c) }, selected = c in chosen) }
            }
            VaultChip("Unique values only", { unique = !unique }, selected = unique)
            error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            PrimaryButton("Add index", { onSave(chosen.toList(), unique) { e -> error = e } }, Modifier.fillMaxWidth(), enabled = chosen.isNotEmpty())
            Spacer(Modifier.height(4.dp))
        }
    }
}

/** Deleting a table: its name must be typed. */
@Composable
private fun TypeToConfirmDialog(table: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var typed by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss, containerColor = k.surface, shape = RoundedCornerShape(24.dp), tonalElevation = 0.dp,
        title = { Text("Delete the table $table?", style = VaultType.Title, color = k.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Its rows go with it. A backup is made first. Type the table’s name to delete it.", style = VaultType.Body, color = k.muted)
                DialogField(typed, { typed = it }, table)
            }
        },
        confirmButton = {
            QuietButton("Delete table", { if (typed == table) onConfirm() }, color = if (typed == table) k.danger else k.faint,
                modifier = if (typed == table) Modifier else Modifier.semantics { disabled() })
        },
        dismissButton = { QuietButton("Cancel", onDismiss, color = k.muted) },
    )
}

// ---- the SQL console ---------------------------------------------------------------------------------------------

@Composable
private fun ConsolePage(vm: DatabasesViewModel, db: String, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    var sql by rememberSaveable { mutableStateOf("") }
    val console by vm.console.collectAsStateWithLifecycle()
    val schema by vm.schema.collectAsStateWithLifecycle()
    val example = schema?.tables?.firstOrNull()?.name ?: "my_table"
    LaunchedEffect(Unit) { vm.clearConsole(); vm.loadSchema(db) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("SQL console", db, onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 120.dp).clip(RoundedCornerShape(14.dp)).background(k.surfaceHigh).padding(14.dp)) {
                val style = VaultType.Label.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, fontWeight = null, color = k.text)
                if (sql.isEmpty()) Text("SELECT * FROM $example LIMIT 20", style = style.copy(color = k.faint))
                BasicTextField(
                    sql, { sql = it; if (console != null) vm.clearConsole() }, textStyle = style, cursorBrush = SolidColor(k.text),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        autoCorrectEnabled = false, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                    ),
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "SQL statement" },
                )
            }
            // While a change waits for Confirm, Confirm is the one primary: Run waits too.
            PrimaryButton("Run", { vm.run(db, sql) }, Modifier.fillMaxWidth(), enabled = sql.isNotBlank() && console !is DatabasesViewModel.Console.Pending)
            Text("One statement at a time. A change shows how many rows it changes before it is kept, and is backed up first.", style = VaultType.Caption, color = k.muted)
            when (val c = console) {
                null -> Unit
                is DatabasesViewModel.Console.Rows -> {
                    Text(
                        "${c.result.rows.size} row${if (c.result.rows.size == 1) "" else "s"}" + if (c.result.truncated) " (the first ${c.result.rows.size})" else "",
                        style = VaultType.Label.copy(fontWeight = null), color = k.muted,
                    )
                    if (c.result.columns.isNotEmpty()) Grid(c.result.columns, c.result.rows, Modifier.fillMaxWidth())
                }
                is DatabasesViewModel.Console.Pending -> VaultGroup {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (c.schema) "This changes the structure of $db." else "This changes ${c.changes} row${if (c.changes == 1) "" else "s"}.",
                            style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrimaryButton("Confirm", { vm.confirm(db) })
                            QuietButton("Cancel", { vm.clearConsole() }, color = k.muted)
                        }
                    }
                }
                is DatabasesViewModel.Console.Done -> Text(
                    if (c.schema) "Done. The structure changed." else "Done: ${c.changes} row${if (c.changes == 1) "" else "s"} changed.",
                    style = VaultType.Body, color = k.text,
                )
                is DatabasesViewModel.Console.Failed -> Text(c.reason, style = VaultType.Body, color = k.attention)
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

// ---- backups and activity ----------------------------------------------------------------------------------------

@Composable
private fun BackupsPage(vm: DatabasesViewModel, db: String, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val loaded by vm.backups.collectAsStateWithLifecycle()
    var restoring by remember { mutableStateOf<io.github.erkanw.vault.userdb.Backup?>(null) }
    LaunchedEffect(db) { vm.loadBackups(db) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Backups", db, onBack)
        val backups = loaded
        if (backups == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
            if (backups.isEmpty()) item(key = "none") { Text("No backups yet: one is made before the first change.", style = VaultType.Body, color = k.muted, modifier = Modifier.padding(8.dp)) }
            else item(key = "list") {
                VaultGroup {
                    backups.forEachIndexed { i, b ->
                        if (i > 0) GroupDivider()
                        GroupActionRow(dateTime(b.time), sizeLabel(b.bytes) + " · tap to restore", { restoring = b }, chevron = true)
                    }
                }
            }
            item(key = "foot") { Text("The last 40 are kept. Restoring backs up the current state first.", style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp)) }
        }
    }
    restoring?.let { b ->
        VaultDialog(
            "Restore this backup?", "$db goes back to how it was on ${dateTime(b.time)}. Its current state is backed up first.", "Restore",
            onConfirm = { vm.restore(db, b.file); restoring = null }, onDismiss = { restoring = null },
        )
    }
}

@Composable
private fun ActivityPage(vm: DatabasesViewModel, db: String, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val agent = LocalAgentName.current
    val loadedActivity by vm.activity.collectAsStateWithLifecycle()
    LaunchedEffect(db) { vm.loadActivity(db) }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Activity", db, onBack)
        val activity = loadedActivity
        if (activity == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { TypingDots(k.muted) }
        else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
            if (activity.isEmpty()) item(key = "none") { Text("No changes yet.", style = VaultType.Body, color = k.muted, modifier = Modifier.padding(8.dp)) }
            else item(key = "list") {
                VaultGroup {
                    activity.forEachIndexed { i, a ->
                        if (i > 0) GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            val who = if (a.actor == "agent") agent else "You"
                            val what = when (a.action) {
                                "execute" -> {
                                    val verb = Regex("^\\s*(?:WITH\\b.*?\\)\\s*)?(\\w+)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(a.statement)?.groupValues?.get(1)?.uppercase()
                                    val n = "${a.rows} row${if (a.rows == 1) "" else "s"}"
                                    when (verb) {
                                        "CREATE", "ALTER", "DROP" -> "changed the structure"
                                        "INSERT", "REPLACE" -> "added $n"
                                        "DELETE" -> "deleted $n"
                                        else -> "changed $n"
                                    }
                                }
                                "csv-import" -> "imported a CSV"
                                "restore-backup" -> "restored a backup"
                                "create" -> "made the database"
                                "archive" -> "archived it"
                                "restore" -> "restored it"
                                else -> a.action
                            }
                            Text("$who $what", style = VaultType.Body, color = k.text)
                            Text(dateTime(a.time), style = VaultType.Caption, color = k.muted)
                            if (a.statement.isNotBlank()) Text(
                                a.statement, style = VaultType.Caption.copy(fontFamily = FontFamily.Monospace), color = k.muted, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
