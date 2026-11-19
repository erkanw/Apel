package io.github.erkanw.vault.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The agent’s components. One rule runs through all of them: a block you can touch or read sits on the lift
 * (surfaceHigh) with no border; it sinks a little on finger-down; corners are generous; nothing casts a shadow.
 */

private val TileShape = RoundedCornerShape(18.dp)
private val RowShape = RoundedCornerShape(14.dp)
private val BubbleShape = RoundedCornerShape(22.dp)

// ---------- Bars ----------

/** The 56 dp top bar: a button each side and a centred title. It is transparent; see [TopScrim] for scrolled content. */
@Composable
fun VaultTopBar(
    modifier: Modifier = Modifier,
    left: @Composable RowScope.() -> Unit = {},
    right: @Composable RowScope.() -> Unit = {},
    title: @Composable () -> Unit = {},
) {
    Box(modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 4.dp)) {
        Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically, content = left)
        Box(Modifier.align(Alignment.Center).padding(horizontal = 56.dp)) { title() }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = right)
    }
}

/** Where content scrolls under a bar: a short fade into the ground instead of a hard line. */
@Composable
fun TopScrim(modifier: Modifier = Modifier, height: Dp = 24.dp) {
    val k = MaterialTheme.vault
    Box(modifier.fillMaxWidth().height(height).background(Brush.verticalGradient(listOf(k.background, k.background.copy(alpha = 0f)))))
}

/** Connection state shown by the title dot. */
enum class Presence { Ready, Working, Problem, Unknown }

/** "the agent" with its connection dot: the title of Home and Chat. */
@Composable
fun TitleWithDot(text: String, presence: Presence, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val k = MaterialTheme.vault
    val dot = when (presence) {
        Presence.Ready -> k.live
        Presence.Working -> k.live
        Presence.Problem -> k.attention
        Presence.Unknown -> k.faint
    }
    var m = modifier.clip(CircleShape)
    if (onClick != null) m = m.pressable(onClick = onClick, shape = CircleShape, pressed = k.surfaceHigh, pressedScale = 0.96f)
    Row(m.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = VaultType.Title, color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(7.dp))
        if (presence == Presence.Working) PulsingDot(dot, size = 7.dp) else Box(Modifier.size(7.dp).background(dot, CircleShape))
    }
}

/** A round 48 dp icon button for bars. */
@Composable
fun BarButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color? = null, iconSize: Dp = 22.dp) {
    val k = MaterialTheme.vault
    Box(
        modifier.size(48.dp).pressable(onClick = onClick, shape = CircleShape, pressed = k.surfaceHigh, pressedScale = 0.9f, onClickLabel = description)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint ?: k.text, modifier = Modifier.size(iconSize))
    }
}

// ---------- Text helpers ----------

/** A group heading: sentence case, muted, never uppercase. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = VaultType.Label, color = MaterialTheme.vault.muted, modifier = modifier.padding(start = 12.dp, top = 20.dp, bottom = 6.dp))
}

/** A quiet one-line note between messages: "Created routine · Morning brief", "the agent is working". */
@Composable
fun StatusLine(text: String, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = VaultType.Caption, color = MaterialTheme.vault.muted, textAlign = TextAlign.Center)
    }
}

// ---------- Buttons ----------

/** The one strong button of a screen: a white pill (inverse on light). */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val k = MaterialTheme.vault
    // Disabled on a light page: a quiet grey pill with faint text (the faded ink pill reads as a heavy grey slab there).
    val quiet = !enabled && k.light
    Box(
        modifier.heightIn(min = 48.dp).alpha(if (enabled || quiet) 1f else 0.4f)
            .pressable(onClick = { if (enabled) onClick() }, shape = CircleShape, rest = if (quiet) k.pressed else k.accent, pressed = if (quiet) k.pressed else k.accent.copy(alpha = 0.85f), pressedScale = if (enabled) 0.97f else 1f)
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = VaultType.Label.copy(fontSize = VaultType.Body.fontSize), color = if (quiet) k.faint else k.onAccent) }
}

/** A secondary button: on the lift. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
    /** True when the button sits on the lift itself (inside a group): it then rests one tone up, on `pressed`. */
    onLift: Boolean = false,
    enabled: Boolean = true,
    /** An icon 8dp before the text (Randomize’s dices). */
    leading: (@Composable () -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    val rest = if (onLift) k.pressed else k.surfaceHigh
    Box(
        modifier.heightIn(min = 48.dp).alpha(if (enabled) 1f else 0.4f)
            .pressable(onClick = { if (enabled) onClick() }, shape = CircleShape, rest = rest, pressed = if (onLift) k.faint else k.pressed, pressedScale = if (enabled) 0.97f else 1f)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
            Text(text, style = VaultType.Label.copy(fontSize = VaultType.Body.fontSize), color = color ?: k.text)
        }
    }
}

/** A text-only button, for dialogs and quiet actions. */
@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color? = null) {
    val k = MaterialTheme.vault
    Box(
        modifier.heightIn(min = 48.dp).pressable(onClick = onClick, shape = CircleShape, pressed = k.surfaceHigh, pressedScale = 0.97f).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = VaultType.Label.copy(fontSize = VaultType.Body.fontSize), color = color ?: k.text) }
}

/** A pill chip, 36 dp high in a 48 dp target. */
@Composable
fun VaultChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    /** True inside a group on the lift: unselected, it rests one tone up (pressed) so it still reads as a control. */
    onLift: Boolean = false,
    leading: (@Composable () -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    val bg by animateColorAsState(if (selected) k.accent else if (onLift) k.pressed else k.surfaceHigh, Motion.quick(), label = "chipBg")
    val fg by animateColorAsState(if (selected) k.onAccent else k.text, Motion.quick(), label = "chipFg")
    Box(modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(36.dp).pressable(onClick = onClick, shape = CircleShape, rest = bg, pressed = if (selected) bg else if (onLift) k.faint else k.pressed, pressedScale = 0.95f, role = Role.Tab)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(7.dp))
            }
            Text(text, style = VaultType.Label, color = fg, maxLines = 1)
        }
    }
}

// ---------- Home ----------

/** A suggestion on Home: a channel, the next automation, a recent chat. Title and one muted line on the lift. */
@Composable
fun SuggestionTile(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    val k = MaterialTheme.vault
    Row(
        modifier.heightIn(min = 64.dp).pressable(onClick = onClick, shape = TileShape, rest = k.surfaceHigh, pressed = k.pressed, pressedScale = 0.97f)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            // At large font sizes a title may take two lines rather than lose its end.
            val bigFont = androidx.compose.ui.platform.LocalDensity.current.fontScale > 1.2f
            Text(title, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text, maxLines = if (bigFont) 2 else 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = VaultType.Label.copy(fontWeight = null), color = k.muted, maxLines = if (bigFont) 2 else 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ---------- Composer ----------

/**
 * The Ask field: a 56 dp pill on the lift with + on the left and the round send button on the right. It grows with
 * the text up to [maxLines]. The send disc is white when there is something to send, quiet when not, and becomes a stop
 * button while [busy].
 */
@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Ask ${LocalAgentName.current}",
    onAttach: (() -> Unit)? = null,
    busy: Boolean = false,
    onStop: (() -> Unit)? = null,
    canSend: Boolean = value.isNotBlank(),
    maxLines: Int = 6,
    fieldModifier: Modifier = Modifier,
    interactionSource: androidx.compose.foundation.interaction.MutableInteractionSource? = null,
) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    val ready = busy || canSend
    val disc by animateColorAsState(if (ready) k.accent else k.pressed, Motion.quick(), label = "sendDisc")
    val arrow by animateColorAsState(if (ready) k.onAccent else k.faint, Motion.quick(), label = "sendArrow")
    val discScale by animateFloatAsState(if (ready) 1f else 0.92f, Motion.quick(), label = "sendScale")
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(28.dp)).background(k.surfaceHigh).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (onAttach != null) {
            Box(
                Modifier.size(40.dp).pressable(onClick = onAttach, shape = CircleShape, pressed = k.pressed, pressedScale = 0.9f, onClickLabel = "Attach"),
                contentAlignment = Alignment.Center,
            ) { Icon(VaultIcons.Plus, "Attach", tint = k.muted, modifier = Modifier.size(22.dp)) }
        } else {
            Spacer(Modifier.width(8.dp))
        }
        Box(Modifier.weight(1f).heightIn(min = 40.dp).padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = VaultType.Reading, color = k.muted, maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = VaultType.Reading.copy(color = k.text),
                cursorBrush = SolidColor(k.text),
                maxLines = maxLines,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                interactionSource = interactionSource,
                modifier = fieldModifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
        val label = if (busy) "Stop" else "Send"
        Box(
            Modifier.size(40.dp).alpha(1f).pressable(
                onClick = {
                    if (busy) onStop?.invoke() else if (canSend) {
                        Haptics.tap(view)
                        onSend()
                    }
                },
                shape = CircleShape, rest = disc, pressed = disc, pressedScale = 0.88f * discScale, onClickLabel = label,
            ).semantics { contentDescription = label },
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (busy) VaultIcons.Stop else VaultIcons.ArrowUp, null, tint = arrow, modifier = Modifier.size(if (busy) 18.dp else 20.dp))
        }
    }
}

// ---------- Lists ----------

/**
 * A row in the drawer or a list page: optional leading (a flat cloud, an icon), a title, an optional second line, and
 * a trailing slot (a time, a dot). [selected] puts it on the lift.
 */
@Composable
fun VaultRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    selected: Boolean = false,
    emphasized: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
) {
    val k = MaterialTheme.vault
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp)
            .pressable(onClick = onClick, onLongClick = onLongClick, shape = RowShape, rest = if (selected) k.surfaceHigh else Color.Transparent, pressed = k.pressed, pressedScale = 0.985f)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { leading() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = VaultType.Body.copy(fontWeight = if (emphasized) VaultType.Title.fontWeight else VaultType.Row.fontWeight),
                color = k.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) Text(subtitle, style = VaultType.Label.copy(fontWeight = null), color = k.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** A small dot: unread (solid) or working (breathing). */
@Composable
fun StateDot(working: Boolean, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    if (working) PulsingDot(k.live, modifier, size = 8.dp) else Box(modifier.size(8.dp).background(k.accent, CircleShape))
}

// ---------- Chat ----------

/** Your message: the inverse pill at the end of the line. */
@Composable
fun UserBubble(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val k = MaterialTheme.vault
    Column(modifier.widthIn(max = 320.dp).clip(BubbleShape).background(k.bubbleMine).padding(horizontal = 16.dp, vertical = 11.dp), content = content)
}

/** The agent’s answer: on the lift. */
@Composable
fun VaultBubble(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val k = MaterialTheme.vault
    Column(modifier.clip(BubbleShape).background(k.bubbleTheirs).padding(horizontal = 16.dp, vertical = 12.dp), content = content)
}

// ---------- Sheets and dialogs ----------

/** A bottom sheet in the agent’s dress: one step up from the screen, 28 dp corners, a short grab handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultSheet(onDismiss: () -> Unit, title: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val k = MaterialTheme.vault
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // A tall sheet (large font sizes) stops under the status bar instead of running behind the clock.
        modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = k.surface,
        contentColor = k.text,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        tonalElevation = 0.dp,
        scrimColor = k.sheetDim,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(width = 36.dp, height = 4.dp).background(k.faint, CircleShape)) },
    ) {
        if (title != null) Text(title, style = VaultType.Title, color = k.text, modifier = Modifier.padding(horizontal = 24.dp, vertical = 10.dp))
        content()
        Spacer(Modifier.height(12.dp))
    }
}

/** A dialog for decisions that must interrupt (delete, discard). */
@Composable
fun VaultDialog(
    title: String,
    text: String?,
    confirm: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismiss: String = "Cancel",
    destructive: Boolean = false,
) {
    val k = MaterialTheme.vault
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = k.surface,
        shape = RoundedCornerShape(24.dp),
        tonalElevation = 0.dp,
        title = { Text(title, style = VaultType.Title, color = k.text) },
        text = text?.let { { Text(it, style = VaultType.Body, color = k.muted) } },
        confirmButton = { QuietButton(confirm, onConfirm, color = if (destructive) k.danger else k.text) },
        dismissButton = { QuietButton(dismiss, onDismiss, color = k.muted) },
    )
}
