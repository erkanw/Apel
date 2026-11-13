package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.erkanw.vault.SettingsViewModel
import io.github.erkanw.vault.chat.MODEL_LABEL_MAX
import io.github.erkanw.vault.chat.ModelPreset
import io.github.erkanw.vault.chat.applyModelPrefs
import io.github.erkanw.vault.chat.prettyModelName
import io.github.erkanw.vault.openrouter.CatalogModel
import io.github.erkanw.vault.openrouter.CatalogSort
import io.github.erkanw.vault.openrouter.filterCatalog
import io.github.erkanw.vault.openrouter.presetNameFor
import io.github.erkanw.vault.openrouter.priceLine

/** 128000 is "128k", 1048576 is "1M". */
private fun tokensShort(n: Long): String = when {
    n <= 0L -> "?"
    n >= 1_000_000L -> if (n % 1_000_000L < 50_000L) "${n / 1_000_000L}M" else String.format(java.util.Locale.US, "%.1fM", n / 1e6)
    n >= 1_000L -> "${n / 1_000L}k"
    else -> n.toString()
}

/**
 * The models the agent can run, in one place. For each of the server's models: its nickname, whether the chat's model list shows
 * it and where (those stay on this phone), its output limit and context window, whether it is the main model or a fallback,
 * and removing it (those change the server). "Add a model" searches OpenRouter's catalogue.
 */
@Composable
fun ModelsScreen(vm: SettingsViewModel, onBack: () -> Unit, onSettings: () -> Unit = {}) {
    val k = MaterialTheme.vault
    val raw by vm.modelsRaw.collectAsStateWithLifecycle()
    val prefs by vm.modelPrefs.collectAsStateWithLifecycle()
    val status by vm.modelsStatus.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        vm.clearModelsStatus()
        vm.loadModels()
    }
    BackHandler(enabled = adding) { adding = false }

    if (adding) {
        AddModelScreen(vm, taken = raw.presets.map { it.name }.toSet(), onBack = { adding = false })
        return
    }

    // The server's own "default" placeholder points at the same model as another one: not a choice.
    val shown = applyModelPrefs(raw, prefs).presets.filter { !it.isDefault }
    val main = raw.callOrder.firstOrNull() ?: raw.active

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader("Models", if (shown.isEmpty()) "What ${LocalAgentName.current} can run" else "${shown.size} models", onBack)
        StatusLine(status.working, status.error, onDismiss = vm::clearModelsStatus)
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Without a provider there is nothing to list or add: say so, and lead to the fix.
            if (!vm.connected) {
                item(key = "setup") {
                    Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Connect an AI provider to list and add models.", color = k.muted, style = VaultType.Preview, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Spacer(Modifier.height(14.dp))
                        SecondaryButton("Open Settings", onSettings)
                    }
                }
                return@LazyColumn
            }
            item(key = "add") {
                PrimaryButton("Add a model", { adding = true }, Modifier.fillMaxWidth())
            }
            item(key = "note") {
                Text(
                    "Everything ${LocalAgentName.current} asks of OpenRouter goes only to providers that keep no data (zero data retention), the " +
                        "cheapest first. That holds for every model below, and a model with no such " +
                        "provider is refused rather than sent anywhere else.",
                    color = k.muted, style = VaultType.Caption, lineHeight = 17.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            if (shown.isEmpty()) {
                item(key = "none") {
                    Text(
                        if (raw.presets.isEmpty()) "No models yet. Add one from OpenRouter." else "No models.",
                        color = k.muted, style = VaultType.Preview, modifier = Modifier.padding(16.dp),
                    )
                }
            }
            items(shown, key = { it.name }) { preset ->
                ModelRow(
                    preset = preset,
                    isMain = preset.name == main,
                    isFallback = preset.name in raw.callOrder && preset.name != main,
                    onClick = { editing = preset.name },
                )
            }
            item(key = "end") { Spacer(Modifier.height(24.dp)) }
        }
    }

    val current = shown.firstOrNull { it.name == editing }
    if (current != null) {
        ModelEditSheet(
            preset = current,
            serverLabel = raw.presets.firstOrNull { it.name == current.name }?.label ?: current.label,
            isMain = current.name == main,
            isFallback = current.name in raw.callOrder && current.name != main,
            canReorderServer = raw.callOrderEditable,
            canMoveUp = shown.indexOfFirst { it.name == current.name } > 0,
            canMoveDown = shown.indexOfFirst { it.name == current.name } in 0 until shown.lastIndex,
            onMove = { up -> vm.moveModel(current.name, up, shown.map { it.name }) },
            onHidden = { vm.setModelHidden(current.name, it) },
            onSave = { label, maxTokens, ctx ->
                vm.setModelLabel(current.name, label)
                if (maxTokens != current.maxTokens || ctx != current.contextTokens) vm.saveModelLimits(current.name, maxTokens, ctx)
                editing = null
            },
            onMakeMain = {
                vm.makeMainModel(current.name)
                editing = null
            },
            onFallback = { vm.setModelFallback(current.name, it) },
            onRemove = {
                vm.removeModel(current.name)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

/** What a change is doing, or why it failed; tap the failure to close it. */
@Composable
private fun StatusLine(working: String?, error: String?, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    if (working != null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PulsingDot(k.live, size = 8.dp)
            Spacer(Modifier.width(10.dp))
            Text(working, color = k.muted, style = VaultType.Label.copy(fontWeight = null))
        }
    } else if (error != null) {
        Text(
            error, color = k.text, style = VaultType.Label.copy(fontWeight = null), lineHeight = 18.sp,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(k.pressed)
                .pressable(onClick = onDismiss, pressedScale = 1f, pressed = k.surfaceHigh, onClickLabel = "Dismiss")
                .padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text, color = color, style = VaultType.Caption.copy(fontWeight = FontWeight.Medium),
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun ModelRow(preset: ModelPreset, isMain: Boolean, isFallback: Boolean, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    val shape = RoundedCornerShape(18.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(k.surfaceHigh)
            .pressable(onClick = onClick, pressedScale = 0.985f, pressed = k.pressed)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                preset.label, color = if (preset.hidden) k.muted else k.text, style = VaultType.Title,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (isMain) {
                Spacer(Modifier.width(8.dp))
                Tag("Main", k.text)
            } else if (isFallback) {
                Spacer(Modifier.width(8.dp))
                Tag("FALLBACK", k.muted)
            }
        }
        Text(preset.model, color = k.muted, style = VaultType.Label.copy(fontWeight = null), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Text(
            listOfNotNull(
                preset.contextTokens.takeIf { it > 0 }?.let { "${tokensShort(it)} context" },
                preset.maxTokens.takeIf { it > 0 }?.let { "${tokensShort(it)} output" },
                if (preset.hidden) "not in the chat’s list" else null,
            ).joinToString(" · "),
            color = k.muted, style = VaultType.Caption,
        )
    }
}

private fun tokensFrom(text: String): Long? = text.trim().toLongOrNull()?.takeIf { it in 256L..10_000_000L }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelEditSheet(
    preset: ModelPreset,
    serverLabel: String,
    isMain: Boolean,
    isFallback: Boolean,
    canReorderServer: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Boolean) -> Unit,
    onHidden: (Boolean) -> Unit,
    onSave: (label: String, maxTokens: Long, contextTokens: Long) -> Unit,
    onMakeMain: () -> Unit,
    onFallback: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val k = MaterialTheme.vault
    var label by remember(preset.name) { mutableStateOf(if (preset.label == serverLabel) "" else preset.label) }
    var maxText by remember(preset.name) { mutableStateOf(preset.maxTokens.takeIf { it > 0 }?.toString().orEmpty()) }
    var ctxText by remember(preset.name) { mutableStateOf(preset.contextTokens.takeIf { it > 0 }?.toString().orEmpty()) }
    var confirmRemove by remember { mutableStateOf(false) }
    val maxTokens = tokensFrom(maxText)
    val ctx = tokensFrom(ctxText)
    val valid = maxTokens != null && ctx != null && maxTokens <= ctx

    VaultSheet(onDismiss = onDismiss) {
        Column(
            Modifier.navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(serverLabel, color = k.text, style = VaultType.Title.copy(fontSize = 20.sp, lineHeight = 26.sp))
            Text(preset.model, color = k.muted, style = VaultType.Label.copy(fontWeight = null))

            Text("On this phone", color = k.muted, style = VaultType.Label, modifier = Modifier.padding(top = 6.dp))
            VaultField(
                value = label,
                onValue = { label = it.take(MODEL_LABEL_MAX) },
                label = "Nickname",
                supporting = "Shown in the chat instead of “$serverLabel”. Empty keeps the model’s own name.",
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Show in the chat’s model list", color = k.text, style = VaultType.Body)
                    Text("The model in use is always shown.", color = k.muted, style = VaultType.Caption)
                }
                VaultSwitch(checked = !preset.hidden, onCheckedChange = { onHidden(!it) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallPill("Move up", enabled = canMoveUp) { onMove(true) }
                SmallPill("Move down", enabled = canMoveDown) { onMove(false) }
            }

            Text("For every chat", color = k.muted, style = VaultType.Label, modifier = Modifier.padding(top = 6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VaultField(
                    value = maxText, onValue = { maxText = it.filter(Char::isDigit).take(8) },
                    label = "Output limit", isError = maxTokens == null,
                    keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                )
                VaultField(
                    value = ctxText, onValue = { ctxText = it.filter(Char::isDigit).take(8) },
                    label = "Context window", isError = ctx == null,
                    keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                )
            }
            Text(
                "Output limit: the most it writes in one answer, thinking included. Context window: how much of the chat it keeps in " +
                    "mind before ${LocalAgentName.current} summarises. The output limit can’t be above the window.",
                color = k.muted, style = VaultType.Caption, lineHeight = 17.sp,
            )
            if (canReorderServer) {
                if (!isMain) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Use as a fallback", color = k.text, style = VaultType.Body)
                            Text("Tried when the main model fails.", color = k.muted, style = VaultType.Caption)
                        }
                        VaultSwitch(checked = isFallback, onCheckedChange = onFallback)
                    }
                    SmallPill("Make this the main model", enabled = true, onClick = onMakeMain)
                    Text("The model that is main now becomes the first fallback.", color = k.muted, style = VaultType.Caption)
                } else {
                    Text("This is the main model.", color = k.muted, style = VaultType.Label.copy(fontWeight = null))
                }
            }

            PrimaryButton("Save", { if (valid) onSave(label, maxTokens!!, ctx!!) }, Modifier.fillMaxWidth().padding(top = 4.dp), enabled = valid)
            if (isMain) {
                Text("The main model can’t be removed. Make another one main first.", color = k.muted, style = VaultType.Caption)
            } else {
                QuietButton("Remove this model", { confirmRemove = true }, Modifier.fillMaxWidth(), color = k.danger)
            }
        }
    }
    if (confirmRemove) {
        ConfirmDialog(
            title = "Remove $serverLabel?",
            message = "It is taken off the list: the chat can’t switch to it any more, and it stops being a fallback. Sessions that chose it go back to the main model.",
            confirm = "Remove",
            onConfirm = {
                confirmRemove = false
                onRemove()
            },
            onDismiss = { confirmRemove = false },
        )
    }
}

@Composable
private fun SmallPill(label: String, enabled: Boolean, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    Text(
        label, color = if (enabled) k.text else k.muted.copy(alpha = 0.5f), style = VaultType.Preview, fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(k.surfaceHigh)
            .pressable(onClick = { if (enabled) onClick() }, pressedScale = 0.97f, pressed = k.pressed)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    )
}

// ---- Adding a model from OpenRouter's catalogue -----------------------------------------------------------------

@Composable
private fun AddModelScreen(vm: SettingsViewModel, taken: Set<String>, onBack: () -> Unit) {
    val k = MaterialTheme.vault
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val status by vm.modelsStatus.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var zdrOnly by rememberSaveable { mutableStateOf(true) }
    var toolsOnly by rememberSaveable { mutableStateOf(true) }
    var freeOnly by rememberSaveable { mutableStateOf(false) }
    var sortName by rememberSaveable { mutableStateOf(CatalogSort.Cheapest.name) }
    var picked by remember { mutableStateOf<CatalogModel?>(null) }
    LaunchedEffect(Unit) { vm.loadCatalog() }

    val sort = CatalogSort.valueOf(sortName)
    val results = remember(catalog.models, query, zdrOnly, toolsOnly, freeOnly, sort) {
        filterCatalog(catalog.models, query, zdrOnly, toolsOnly, freeOnly, sort).take(200)
    }

    Column(Modifier.fillMaxSize().background(k.background)) {
        PageHeader("Add a model", "From OpenRouter’s catalogue", onBack)
        StatusLine(status.working, status.error, onDismiss = vm::clearModelsStatus)
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .fillMaxWidth()
                .clip(CircleShape)
                .background(k.surfaceHigh)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(VaultIcons.Search, null, tint = k.muted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                textStyle = VaultType.Body.copy(color = k.text), cursorBrush = SolidColor(k.text),
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search by name", color = k.muted, style = VaultType.Body); inner() } },
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Toggle("Zero retention", zdrOnly) { zdrOnly = !zdrOnly }
            Toggle("Tools", toolsOnly) { toolsOnly = !toolsOnly }
            Toggle("Free", freeOnly) { freeOnly = !freeOnly }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Sort", color = k.muted, style = VaultType.Caption, modifier = Modifier.padding(end = 2.dp))
            for (option in CatalogSort.entries) Toggle(option.label, sort == option) { sortName = option.name }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when {
                catalog.models.isEmpty() && catalog.loading -> TypingDots(k.muted)
                catalog.models.isEmpty() && catalog.error != null -> Text(
                    catalog.error!!, color = k.danger, style = VaultType.Preview, lineHeight = 20.sp,
                    modifier = Modifier.padding(24.dp).pressable(onClick = { vm.loadCatalog(force = true) }, pressedScale = 1f, pressed = k.surfaceHigh, onClickLabel = "Try again"),
                )
                results.isEmpty() -> Text("No model matches.", color = k.muted, style = VaultType.Preview, modifier = Modifier.padding(24.dp))
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(results, key = { it.id }) { model -> CatalogRow(model, onClick = { picked = model }) }
                    item(key = "foot") {
                        Text(
                            "Prices are per million tokens, from the cheapest zero-retention provider.",
                            color = k.muted, style = VaultType.Caption, modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        }
    }

    picked?.let { model ->
        AddModelDialog(
            model = model,
            taken = taken,
            onAdd = { name, maxTokens, ctx ->
                picked = null
                vm.addModel(name, model.id, maxTokens, ctx, done = onBack)
            },
            onDismiss = { picked = null },
        )
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    Text(
        label,
        color = if (on) k.background else k.muted,
        style = VaultType.Label.copy(fontWeight = null),
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(if (on) k.text else k.surfaceHigh)
            .pressable(onClick = onClick, pressedScale = 0.985f, pressed = k.pressed)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

@Composable
private fun CatalogRow(model: CatalogModel, onClick: () -> Unit) {
    val k = MaterialTheme.vault
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(k.surfaceHigh)
            .pressable(onClick = onClick, pressedScale = 0.985f, pressed = k.pressed)
            .padding(14.dp),
    ) {
        Text(model.name, color = k.text, style = VaultType.Body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(model.id, color = k.muted, style = VaultType.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                (if (model.free) "Free" else priceLine(model.bestIn, model.bestOut)) + " · ${tokensShort(model.contextTokens)} context",
                color = k.muted, style = VaultType.Caption, modifier = Modifier.weight(1f, fill = false),
            )
            if (model.zdr) Tag("Zero retention", k.live)
            else Tag("NOT ZDR", k.danger)
        }
    }
}

@Composable
private fun AddModelDialog(model: CatalogModel, taken: Set<String>, onAdd: (String, Long, Long) -> Unit, onDismiss: () -> Unit) {
    val k = MaterialTheme.vault
    var name by remember(model.id) { mutableStateOf(presetNameFor(model.id, taken)) }
    var maxText by remember(model.id) { mutableStateOf("8192") }
    var ctxText by remember(model.id) { mutableStateOf(minOf(model.contextTokens.takeIf { it > 0 } ?: 128_000L, 128_000L).toString()) }
    val maxTokens = tokensFrom(maxText)
    val ctx = tokensFrom(ctxText)
    val nameOk = name.matches(Regex("[a-z0-9._-]{1,32}")) && name !in taken && name != "default"
    val canAdd = model.zdr && nameOk && maxTokens != null && ctx != null && maxTokens <= ctx
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = { Text(model.name, color = k.text, style = VaultType.Title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(model.id, color = k.muted, style = VaultType.Label.copy(fontWeight = null))
                if (!model.zdr) {
                    Text(
                        "No provider of this model keeps zero data retention, and ${LocalAgentName.current} only sends requests to ones that do. It would be refused, " +
                            "so it can’t be added.",
                        color = k.danger, style = VaultType.Label.copy(fontWeight = null), lineHeight = 18.sp,
                    )
                }
                VaultField(
                    value = name, onValue = { name = it.lowercase().take(32) },
                    label = "Short name", isError = !nameOk,
                    supporting = if (name in taken) "Already used." else "Letters, digits, dots and dashes. This is what /model takes.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    VaultField(
                        value = maxText, onValue = { maxText = it.filter(Char::isDigit).take(8) },
                        label = "Output limit", isError = maxTokens == null,
                        keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                    )
                    VaultField(
                        value = ctxText, onValue = { ctxText = it.filter(Char::isDigit).take(8) },
                        label = "Context window", isError = ctx == null,
                        keyboardType = KeyboardType.Number, modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "It is added to your models; the main model and the fallbacks stay as they are. Models that think need a " +
                        "generous output limit, since their thinking counts against it.",
                    color = k.muted, style = VaultType.Caption, lineHeight = 17.sp,
                )
            }
        },
        confirmButton = { QuietButton("Add", { if (canAdd) onAdd(name, maxTokens!!, ctx!!) }, color = if (canAdd) k.text else k.faint) },
        dismissButton = { QuietButton("Cancel", onDismiss, color = k.muted) },
    )
}
