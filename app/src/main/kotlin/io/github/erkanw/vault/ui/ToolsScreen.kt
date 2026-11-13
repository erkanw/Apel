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
import androidx.compose.foundation.layout.navigationBarsPadding
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

/**
 * What the agent can use: the built-in tools (web search, pages, files...) and the tool servers it is connected to, each
 * switched on and off like skills and automations. A server opens to its tools, one switch each. Changes apply to every
 * chat. (Phase 3 gives each built-in tool a level: Allowed, Ask first, Off.)
 */
@Composable
fun ToolsScreen(vm: PagesViewModel, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val builtIn by vm.builtInTools.collectAsStateWithLifecycle()
    val servers by vm.mcpServers.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    val tracker = rememberEntranceTracker()
    LaunchedEffect(Unit) { vm.loadTools() }

    fun toggleBuiltIn(tool: BuiltInTool, on: Boolean) = vm.setBuiltInTool(tool, on)
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
                if (builtIn.items.isNotEmpty()) item(key = "builtin") {
                    Column(Modifier.entrance("builtin", 0, tracker, dx = 0.dp, dy = 8.dp)) {
                        GroupLabel("Built in")
                        VaultGroup {
                            val byPath = builtIn.items.associateBy { it.path }
                            builtIn.items.forEachIndexed { i, tool ->
                                if (i > 0) GroupDivider()
                                val usable = tool.needs == null || byPath[tool.needs]?.enabled != false
                                ToolRow(tool.title, tool.subtitle, tool.enabled && usable, { toggleBuiltIn(tool, it) }, enabled = usable)
                            }
                        }
                    }
                }
                if (servers.items.isNotEmpty()) item(key = "servers") {
                    Column(Modifier.entrance("servers", 1, tracker, dx = 0.dp, dy = 8.dp).navigationBarsPadding()) {
                        GroupLabel("Connected tools")
                        VaultGroup {
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

    servers.items.firstOrNull { it.name == open }?.let { server ->
        ServerToolsSheet(
            server = server,
            onTool = { tool, on -> setTool(server, tool, on) },
            onAll = { on -> setServer(server, on) },
            onDismiss = { open = null },
        )
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

/** A server's tools, one switch each, with all on / all off. */
@Composable
private fun ServerToolsSheet(server: McpServer, onTool: (String, Boolean) -> Unit, onAll: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
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
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
                VaultGroup {
                    server.tools.forEachIndexed { i, tool ->
                        if (i > 0) GroupDivider()
                        GroupSwitchRow(toolTitle(tool), null, server.isOn(tool), { onTool(tool, it) })
                    }
                }
                Spacer(Modifier.width(1.dp).heightIn(min = 12.dp))
            }
        }
    }
}

/** A tool's name made readable: "browser_take_screenshot" → "Take screenshot" (the server's prefix dropped). */
internal fun toolTitle(tool: String): String {
    val words = tool.split('_', '-').filter { it.isNotBlank() }
    val trimmed = if (words.size > 1 && words.first() in setOf("browser", "db")) words.drop(1) else words
    return trimmed.joinToString(" ").replaceFirstChar { it.uppercase() }.ifBlank { tool }
}
