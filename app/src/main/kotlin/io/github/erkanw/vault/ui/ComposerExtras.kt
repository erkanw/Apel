package io.github.erkanw.vault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import io.github.erkanw.vault.PendingAttachment
import kotlinx.coroutines.delay
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.SlashCommand

/** The list that opens above the message box while you type "/": tap a command to put it in the box. */
@Composable
fun CommandMenu(matches: List<SlashCommand>, onPick: (SlashCommand) -> Unit, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    // Keeps showing the last list while the menu closes, so it doesn't collapse empty.
    val shown = remember { arrayOf<List<SlashCommand>>(emptyList()) }
    if (matches.isNotEmpty()) shown[0] = matches
    AnimatedVisibility(
        visible = matches.isNotEmpty(),
        enter = fadeIn(tween(Motion.Short, easing = Motion.Decelerate)) + expandVertically(tween(Motion.Medium, easing = Motion.Decelerate)),
        exit = fadeOut(tween(Motion.Short, easing = Motion.Accelerate)) + shrinkVertically(tween(Motion.Short, easing = Motion.Accelerate)),
        modifier = modifier,
    ) {
        LazyColumn(
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .heightIn(max = 264.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(k.surface),
            contentPadding = PaddingValues(vertical = 6.dp),
        ) {
            items(shown[0], key = { it.name }) { command ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            Haptics.tap(view)
                            onPick(command)
                        }
                        .padding(horizontal = 16.dp, vertical = 9.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(command.name, color = k.text, style = VaultType.Body.copy(fontWeight = FontWeight.SemiBold))
                        if (command.argHint.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(command.argHint, color = k.muted, style = VaultType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (command.description.isNotBlank()) {
                        Text(command.description, color = k.muted, style = VaultType.Caption, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** The small buttons inside the message box, under the text: attach, the command list, and the model in use. */
@Composable
fun ComposerChips(
    model: ModelPreset?,
    onPhoto: () -> Unit,
    onFile: () -> Unit,
    onCommands: () -> Unit,
    onModel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val k = MaterialTheme.vault
    var attachMenu by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box {
            Chip("+", { attachMenu = true })
            DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                DropdownMenuItem(
                    text = { Text("Photo", color = k.text) },
                    onClick = {
                        attachMenu = false
                        onPhoto()
                    },
                )
                DropdownMenuItem(
                    text = { Text("File", color = k.text) },
                    onClick = {
                        attachMenu = false
                        onFile()
                    },
                )
            }
        }
        Chip("/", onCommands)
        Chip(model?.label ?: "Model", onModel, dot = model != null)
    }
}

/** The files chosen for the message, above the text inside the message box: a picture shows itself, a document its type. */
@Composable
fun AttachmentTray(items: List<PendingAttachment>, onRemove: (Long) -> Unit, modifier: Modifier = Modifier) {
    val shown = remember { arrayOf<List<PendingAttachment>>(emptyList()) }
    if (items.isNotEmpty()) shown[0] = items
    AnimatedVisibility(
        visible = items.isNotEmpty(),
        enter = fadeIn(tween(Motion.Short, easing = Motion.Decelerate)) + expandVertically(tween(Motion.Medium, easing = Motion.Decelerate)),
        exit = fadeOut(tween(Motion.Short, easing = Motion.Accelerate)) + shrinkVertically(tween(Motion.Short, easing = Motion.Accelerate)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (item in shown[0]) AttachmentTile(item, onRemove = { onRemove(item.id) })
        }
    }
}

@Composable
private fun AttachmentTile(item: PendingAttachment, onRemove: () -> Unit) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    val shape = RoundedCornerShape(14.dp)
    Box {
        // The × sits over the corner, so the tile keeps a little room around it.
        Box(Modifier.padding(top = 6.dp, end = 6.dp)) {
            Box(Modifier.clip(shape).background(k.pressed)) {
                if (item.image && item.thumbnail != null) {
                    Image(
                        item.thumbnail.asImageBitmap(),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(64.dp),
                    )
                } else {
                    Row(
                        Modifier.height(64.dp).widthIn(max = 180.dp).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(VaultIcons.FileText, extensionLabel(item.name), tint = k.muted, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(item.name, color = k.text, style = VaultType.Label.copy(fontWeight = null), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!item.ready) {
                    Box(Modifier.matchParentSize().background(k.surfaceHigh.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = k.text, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .size(22.dp)
                .clip(CircleShape)
                .background(k.text)
                .clickable(onClickLabel = "Remove ${item.name}") {
                    Haptics.tap(view)
                    onRemove()
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(VaultIcons.Close, "Remove ${item.name}", tint = k.background, modifier = Modifier.size(13.dp))
        }
    }
}

/** "PDF", "DOCX", "TXT": a document's type, as its label. */
fun extensionLabel(name: String): String =
    name.substringAfterLast('.', "").uppercase().take(4).ifEmpty { "FILE" }

/** A short message above the message box (a file that was refused); it goes away by itself. */
@Composable
fun NoticeBanner(text: String?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    val last = remember { arrayOf("") }
    if (text != null) last[0] = text
    LaunchedEffect(text) {
        if (text != null) {
            delay(6_000)
            onDismiss()
        }
    }
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(Motion.Short, easing = Motion.Decelerate)) + expandVertically(tween(Motion.Medium, easing = Motion.Decelerate)),
        exit = fadeOut(tween(Motion.Short, easing = Motion.Accelerate)) + shrinkVertically(tween(Motion.Short, easing = Motion.Accelerate)),
        modifier = modifier,
    ) {
        // On the lift like the rest of the chat; the words, not a red box, carry the message.
        Text(
            last[0],
            color = k.text,
            style = VaultType.Label.copy(fontWeight = null),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(k.surface)
                .clickable(onClickLabel = "Dismiss", onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit, dot: Boolean = false) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    Row(
        Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(k.surface)
            .clickable {
                Haptics.tap(view)
                onClick()
            }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot) {
            Spacer(Modifier.size(7.dp).clip(CircleShape).background(k.accent))
            Spacer(Modifier.width(7.dp))
        }
        Text(label, color = k.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** The models the agent can run with ("presets"): tap one to switch the chat to it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSheet(
    choices: ModelChoices,
    current: ModelPreset?,
    enabled: Boolean,
    onPick: (ModelPreset) -> Unit,
    onDismiss: () -> Unit,
    onManage: (() -> Unit)? = null,
    /** OpenRouter's catalogue, for each model's price per million tokens. Empty while it loads or if it can't be reached. */
    catalog: List<io.github.erkanw.vault.openrouter.CatalogModel> = emptyList(),
    catalogLoading: Boolean = false,
) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    VaultSheet(onDismiss = onDismiss, title = "Model") {
        Column(Modifier.navigationBarsPadding().padding(bottom = 4.dp)) {
            if (choices.presets.isEmpty()) {
                Text("No models yet. Add one on the Models page.", color = k.muted, style = VaultType.Preview, modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
            }
            // The server's "default" preset is a placeholder pointing at the same model as another preset: list it once.
            val listed = choices.presets.filter { p -> !(p.isDefault && choices.presets.any { it !== p && it.model == p.model }) && (!p.hidden || p.name == current?.name) }
            for (preset in listed) {
                val selected = preset.name == current?.name
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .alpha(if (enabled || selected) 1f else 0.5f)
                        .pressable(
                            onClick = {
                                if (enabled && !selected) {
                                    Haptics.tap(view)
                                    onPick(preset)
                                }
                            },
                            shape = RoundedCornerShape(14.dp), pressedScale = 0.985f,
                            rest = if (selected) k.surfaceHigh else androidx.compose.ui.graphics.Color.Transparent, pressed = k.pressed,
                        )
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(preset.label, color = k.text, style = VaultType.Row)
                        if (preset.model.isNotBlank()) {
                            Text(preset.model, color = k.muted, style = VaultType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val priced = io.github.erkanw.vault.openrouter.findCatalogModel(catalog, preset.model)
                            when {
                                priced != null -> Text(io.github.erkanw.vault.openrouter.priceDetail(priced), color = k.text, style = VaultType.Caption, maxLines = 1)
                                catalogLoading -> Text("Price loading…", color = k.muted, style = VaultType.Caption)
                                catalog.isEmpty() -> Text("Price unavailable", color = k.muted, style = VaultType.Caption)
                            }
                        }
                    }
                    if (selected) Icon(VaultIcons.Check, "In use", tint = k.text, modifier = Modifier.size(20.dp))
                }
            }
            if (onManage != null) {
                QuietButton("Manage models", onManage, Modifier.padding(start = 8.dp, top = 4.dp))
            }
            if (catalog.isNotEmpty()) {
                Text(
                    "Prices are in US dollars per million tokens, from OpenRouter (the cheapest provider that keeps no data).",
                    color = k.muted, style = VaultType.Caption, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp),
                )
            }
            if (!enabled) {
                Text("Wait for ${LocalAgentName.current} to finish answering to switch.", color = k.muted, style = VaultType.Caption, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            }
        }
    }
}
