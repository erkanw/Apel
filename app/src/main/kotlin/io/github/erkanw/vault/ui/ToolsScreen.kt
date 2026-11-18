package io.github.erkanw.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.PagesViewModel
import io.github.erkanw.vault.PageState
import io.github.erkanw.vault.chat.BuiltInTool
import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.serverSubtitle
import io.github.erkanw.vault.tools.ToolLevel
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion

/**
 * What the agent can use: the built-in tools (web search, pages, files...) and the tool servers it is connected to. A
 * built-in tool opens a sheet with its level (Allowed, Ask first, Off; PLAN §7.5); a server opens to its tools, one switch
 * each. Changes apply to every chat.
 */
@Composable
fun ToolsScreen(vm: PagesViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val builtIn by vm.builtInTools.collectAsStateWithLifecycle()
    val servers by vm.mcpServers.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    var levelOf by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<McpServer?>(null) }
    var clearingBrowser by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val agent = LocalAgentName.current
    val tracker = rememberEntranceTracker()
    LaunchedEffect(Unit) { vm.loadTools() }

    fun setServer(server: McpServer, on: Boolean) = vm.setServerOn(server, on)
    fun setTool(server: McpServer, tool: String, on: Boolean) = vm.setServerTool(server, tool, on)

    Column(Modifier.fillMaxSize().background(k.background)) {
        val on = builtIn.items.count { it.enabled } + servers.items.count { it.anyOn }
        val total = builtIn.items.size + servers.items.size
        PageHeader("Tools", if (total == 0) "What ${LocalAgentName.current} can use" else "$on of $total on", onBack)
        // One state for the page: both lists load together.
        val page = PageState(
            items = if (builtIn.items.isEmpty() && servers.items.isEmpty()) emptyList() else listOf(Unit),
            loading = builtIn.loading || servers.loading,
            error = builtIn.error ?: servers.error,
        )
        PageBody(page, "No tools yet.", vm::loadTools) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val (browser, core) = builtIn.items.partition { it.path.startsWith("browser_") }
                if (core.isNotEmpty()) item(key = "builtin") {
                    Column(Modifier.entrance("builtin", 0, tracker, dx = 0.dp, dy = 8.dp)) {
                        GroupLabel("Built in")
                        VaultGroup {
                            core.forEachIndexed { i, tool ->
                                if (i > 0) GroupDivider()
                                GroupActionRow(
                                    tool.title,
                                    if (tool.available) tool.level.label else "Not available",
                                    { levelOf = tool.path },
                                    chevron = true,
                                )
                            }
                        }
                    }
                }
                if (browser.isNotEmpty()) item(key = "browser") {
                    Column(Modifier.entrance("browser", 1, tracker, dx = 0.dp, dy = 8.dp)) {
                        GroupLabel("Browser", trailing = { Text("Experimental", style = VaultType.Label.copy(fontWeight = null), color = k.attention) })
                        VaultGroup {
                            browser.forEachIndexed { i, tool ->
                                if (i > 0) GroupDivider()
                                GroupActionRow(tool.title, tool.level.label, { levelOf = tool.path }, chevron = true)
                            }
                            GroupDivider()
                            GroupActionRow("Clear the browser’s data", "Its cookies, sign-ins and stored pages", { clearingBrowser = true })
                        }
                        Text(
                            "A hidden browser on this phone that $agent can drive: it runs pages’ scripts, keeps its own cookies and never opens your home network. It can make mistakes on complex sites.",
                            style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                        )
                    }
                }
                item(key = "servers") {
                    Column(Modifier.entrance("servers", 2, tracker, dx = 0.dp, dy = 8.dp).navigationBarsPadding()) {
                        // The + keeps its 48dp target but not its height, so this label sits as close to its group as the others.
                        GroupLabel("Connected tools", trailing = {
                            Box(Modifier.height(20.dp).offset(x = 6.dp).wrapContentHeight(unbounded = true)) {
                                BarButton(VaultIcons.Plus, "Add a tool server", { adding = true }, tint = k.muted, iconSize = 20.dp)
                            }
                        })
                        if (servers.items.isEmpty()) {
                            VaultGroup {
                                Text(
                                    "No tool servers yet. Add one with + to give $agent the tools it offers.",
                                    style = VaultType.Body, color = k.muted, modifier = Modifier.padding(16.dp),
                                )
                            }
                        } else VaultGroup {
                            servers.items.forEachIndexed { i, server ->
                                if (i > 0) GroupDivider()
                                ToolRow(
                                    server.title, serverSubtitle(server), server.anyOn, { setServer(server, it) },
                                    subtitleColor = if (server.available) null else k.attention,
                                    onOpen = { open = server.name }, openLabel = "Choose ${server.title}’s tools",
                                )
                            }
                        }
                        Text(
                            "A change applies to every chat.",
                            style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, top = 8.dp),
                        )
                    }
                }
            }
        }
    }

    builtIn.items.firstOrNull { it.path == levelOf }?.let { tool ->
        LevelSheet(tool, onLevel = { vm.setToolLevel(tool, it) }, onDismiss = { levelOf = null })
    }

    servers.items.firstOrNull { it.name == open }?.let { server ->
        ServerToolsSheet(
            server = server,
            onTool = { tool, on -> setTool(server, tool, on) },
            onAll = { on -> setServer(server, on) },
            onRefresh = { done -> vm.refreshServer(server, done) },
            onRemove = { removing = server },
            onDismiss = { open = null },
        )
    }

    if (adding) AddServerSheet(vm, onDismiss = { adding = false }, onAdded = { title -> adding = false; note = "Added $title." })

    removing?.let { server ->
        VaultDialog(
            title = "Remove ${server.title}?",
            text = "$agent can no longer use its tools, and its token is deleted from this phone.",
            confirm = "Remove",
            onConfirm = { vm.removeServer(server); removing = null; open = null },
            onDismiss = { removing = null },
            destructive = true,
        )
    }

    if (clearingBrowser) VaultDialog(
        title = "Clear the browser’s data?",
        text = "Its cookies and stored pages are deleted, and the open page closes. Sites it was signed in to will need signing in again.",
        confirm = "Clear",
        onConfirm = { clearingBrowser = false; vm.clearBrowser { note = "Cleared the browser’s data." } },
        onDismiss = { clearingBrowser = false },
        destructive = true,
    )

    note?.let { text ->
        LaunchedEffect(text) { kotlinx.coroutines.delay(4000); note = null }
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 24.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                text, style = VaultType.Label.copy(fontWeight = null), color = k.text,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(k.pressed).padding(horizontal = 16.dp, vertical = 10.dp)
                    .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
            )
        }
    }
}

/**
 * Adding a tool server (handbook §3): its address and an optional token (masked, kept encrypted), Test (lists the tools it
 * offers, or says why it can't be reached), then Add. Changing a field after a test asks for a new one.
 */
@Composable
private fun AddServerSheet(vm: PagesViewModel, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val k = MaterialTheme.vault
    var address by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<io.github.erkanw.vault.chat.McpTest?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    fun changed() { found = null; error = null }
    VaultSheet(onDismiss = onDismiss, title = "Add a tool server") {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            VaultField(
                address, { address = it.trim(); changed() }, "Address",
                placeholder = "https://example.com/mcp", keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                supporting = error ?: "The web address of a tool server, often ending in /mcp.",
                isError = error != null,
            )
            VaultField(
                token, { token = it.trim(); changed() }, "Token (optional)",
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                transformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                supporting = "Kept encrypted on this phone and sent only to this address.",
            )
            found?.let { f ->
                VaultField(name, { name = it.take(40) }, "Name", placeholder = f.name.ifBlank { "Tool server" })
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        if (f.tools.isEmpty()) "It answered, but offers no tools." else "It offers ${f.tools.size} tool${if (f.tools.size == 1) "" else "s"}:",
                        style = VaultType.Label, color = k.text,
                    )
                    if (f.tools.isNotEmpty()) Text(
                        f.tools.take(6).joinToString(", ") { toolTitle(it) } + if (f.tools.size > 6) ", and ${f.tools.size - 6} more" else "",
                        style = VaultType.Caption, color = k.muted,
                    )
                    f.warning?.let { Text(it, style = VaultType.Caption, color = k.attention) }
                }
            }
            if (found == null) {
                PrimaryButton(if (testing) "Testing…" else "Test", {
                    testing = true
                    error = null
                    val tested = address to token
                    vm.testServer(address, token) { r ->
                        testing = false
                        // A field changed while the test ran: its result is for another address, so it is dropped.
                        if (tested != address to token) return@testServer
                        r.onSuccess { found = it; name = it.name.take(40) }.onFailure { error = it.message ?: "Couldn’t reach the server." }
                    }
                }, Modifier.fillMaxWidth(), enabled = address.isNotBlank() && !testing)
            } else {
                PrimaryButton(if (saving) "Adding…" else "Add server", {
                    saving = true
                    vm.addServer(address, token, name) { e ->
                        saving = false
                        if (e == null) onAdded(name.ifBlank { found?.name ?: "the server" }) else error = e
                    }
                }, Modifier.fillMaxWidth(), enabled = !saving)
                // A failure to add (the server stopped answering) shows here: the address field only holds test failures.
                error?.let { Text(it, style = VaultType.Caption, color = k.attention) }
            }
            Spacer(Modifier.heightIn(min = 4.dp))
        }
    }
}

/**
 * A tool, switched like a skill: tapping the row turns it on or off. A server also has a chevron that opens its
 * tools; the chevron's slot is kept on every row, so the switches of both groups stand in one column.
 */
@Composable
private fun ToolRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    subtitleColor: Color? = null,
    onOpen: (() -> Unit)? = null,
    openLabel: String = "",
) {
    val k = MaterialTheme.vault
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .pressable(onClick = { if (enabled) onToggle(!checked) }, pressedScale = 1f, pressed = if (enabled) k.pressed else Color.Transparent, role = Role.Switch)
            .padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = if (enabled) k.text else k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = VaultType.Label.copy(fontWeight = null), color = subtitleColor ?: k.muted)
        }
        VaultSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
        if (onOpen != null) BarButton(VaultIcons.ChevronRight, openLabel, onOpen, tint = k.muted, iconSize = 20.dp)
        else Spacer(Modifier.width(48.dp))
    }
}

/** A built-in tool's level: one line on what it does, and three chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun LevelSheet(tool: BuiltInTool, onLevel: (ToolLevel) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    VaultSheet(onDismiss = onDismiss, title = tool.title) {
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(tool.subtitle, style = VaultType.Preview, color = k.muted)
            if (tool.available) {
                androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    tool.levels.forEach { level ->
                        VaultChip(
                            level.label, { onLevel(level) }, selected = tool.level == level,
                            modifier = Modifier.semantics { contentDescription = "Level: ${level.label}"; selected = tool.level == level },
                        )
                    }
                }
                Text(
                    when (tool.level) {
                        ToolLevel.Allowed -> "${LocalAgentName.current} uses it when it helps."
                        ToolLevel.Ask -> "${LocalAgentName.current} asks you each time, in the chat."
                        ToolLevel.Off -> "${LocalAgentName.current} can’t use it."
                    },
                    style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/** A server's tools, one switch each, with all on / all off. */
@Composable
private fun ServerToolsSheet(
    server: McpServer, onTool: (String, Boolean) -> Unit, onAll: (Boolean) -> Unit,
    onRefresh: (onDone: () -> Unit) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit,
) {
    val k = MaterialTheme.vault
    // Check again: "Checking…" while it runs, then what it found.
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    VaultSheet(onDismiss = onDismiss, title = server.title) {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(serverSubtitle(server), style = VaultType.Label.copy(fontWeight = null), color = k.muted, modifier = Modifier.weight(1f))
            QuietButton("All on", { onAll(true) }, color = if (server.allOn) k.faint else k.text)
            QuietButton("All off", { onAll(false) }, color = if (!server.anyOn) k.faint else k.text)
        }
        if (server.tools.isEmpty()) {
            Text(
                "This server’s tools show once it has been reached.",
                style = VaultType.Preview, color = k.muted, textAlign = TextAlign.Start,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            )
        } else {
            // The list scrolls inside the sheet, so Check again and Remove stay in view however many tools there are.
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                VaultGroup {
                    server.tools.forEachIndexed { i, tool ->
                        if (i > 0) GroupDivider()
                        // The server's own words, with curly apostrophes like the rest of the app.
                        val about = server.descriptions[tool]?.lineSequence()?.firstOrNull()?.take(140)?.replace('\'', '’')?.ifBlank { null }
                        GroupSwitchRow(toolTitle(tool), about, server.isOn(tool), { onTool(tool, it) })
                    }
                }
                Text(
                    "Tools the server marks as only reading run when they help; the others ask you first, until you choose Always allow.",
                    style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            if (!server.available && server.status.isNotBlank()) Text(server.status, style = VaultType.Caption, color = k.attention, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            if (server.url.isNotBlank()) Text(server.url, style = VaultType.Caption, color = k.muted, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            VaultGroup {
                GroupActionRow(
                    "Check again",
                    when {
                        checking -> "Checking…"
                        checked && server.available -> "Up to date: ${server.tools.size} tool${if (server.tools.size == 1) "" else "s"}"
                        else -> "Reads its tools again"
                    },
                    { checking = true; onRefresh { checking = false; checked = true } },
                    enabled = !checking,
                )
                GroupDivider()
                GroupActionRow("Remove server", null, onRemove, color = k.danger)
            }
        }
    }
}

/** A tool's name made readable: "browser_take_screenshot" → "Take screenshot" (the server's prefix dropped). */
internal fun toolTitle(tool: String): String = io.github.erkanw.vault.tools.readableToolName(tool)
