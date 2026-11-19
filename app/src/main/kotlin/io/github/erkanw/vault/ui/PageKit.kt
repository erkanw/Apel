package io.github.erkanw.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * The pages' vocabulary (Settings, Files, Skills, Automation, Models, OpenRouter): groups of rows on the
 * lift with sentence-case labels above them, the agent’s switch, and the inset field. Built on Kit.kt; no outlines.
 */

private val GroupShape = RoundedCornerShape(18.dp)

/** A label above a group, sentence case and muted; [trailing] sits at its end (a state, a count). */
@Composable
fun GroupLabel(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val k = MaterialTheme.vault
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = VaultType.Label, color = k.muted, modifier = Modifier.weight(1f))
        if (trailing != null) trailing()
    }
}

/** A small state beside a group label: a dot and a word ("Set up", "Not set up"). */
@Composable
fun GroupState(text: String, on: Boolean) {
    val k = MaterialTheme.vault
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(if (on) k.live else k.faint))
        Spacer(Modifier.width(6.dp))
        Text(text, style = VaultType.Caption, color = k.muted)
    }
}

/** Rows grouped on the lift: 18 dp corners, no outline. */
@Composable
fun VaultGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val k = MaterialTheme.vault
    Column(modifier.fillMaxWidth().clip(GroupShape).background(k.surfaceHigh).padding(vertical = 4.dp), content = content)
}

/** Between two rows of one group: inset hairline (two parts of the same surface meeting). */
@Composable
fun GroupDivider() {
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(MaterialTheme.vault.line))
}

/** The agent’s switch: white track when on, the pressed tone when off, no outline. */
@Composable
fun VaultSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val k = MaterialTheme.vault
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        modifier = modifier,
        colors = SwitchDefaults.colors(
            checkedThumbColor = k.onAccent,
            checkedTrackColor = k.accent,
            checkedBorderColor = Color.Transparent,
            uncheckedThumbColor = k.muted,
            uncheckedTrackColor = k.pressed,
            uncheckedBorderColor = Color.Transparent,
            disabledCheckedThumbColor = k.onAccent.copy(alpha = 0.6f),
            disabledCheckedTrackColor = k.accent.copy(alpha = 0.35f),
            disabledUncheckedThumbColor = k.faint,
            disabledUncheckedTrackColor = k.pressed.copy(alpha = 0.6f),
            disabledUncheckedBorderColor = Color.Transparent,
            disabledCheckedBorderColor = Color.Transparent,
        ),
    )
}

/** A row with a title, a muted second line and a switch; the whole row toggles. */
@Composable
fun GroupSwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val k = MaterialTheme.vault
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick = { if (enabled) onChange(!checked) }, pressedScale = 1f, pressed = if (enabled) k.pressed else Color.Transparent, role = Role.Switch)
            .semantics { toggleableState = androidx.compose.ui.state.ToggleableState(checked) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp).alpha(if (enabled) 1f else 0.5f)) {
            Text(title, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = k.text)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = VaultType.Label.copy(fontWeight = null), color = k.muted)
        }
        VaultSwitch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A row that does something (opens a page, runs an action): title, muted second line, optional chevron. */
@Composable
fun GroupActionRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    enabled: Boolean = true,
    chevron: Boolean = false,
    color: Color? = null,
    leading: (@Composable () -> Unit)? = null,
    /** Amber for a line that needs you (a failure to act on). */
    subtitleColor: Color? = null,
    /** Shown at the end, before the chevron (a small mark, a check). */
    trailing: (@Composable () -> Unit)? = null,
    /**
     * The second line is a status (progress, a failure): kept at full strength while the row is disabled, never cut,
     * and read out when it changes.
     */
    status: Boolean = false,
) {
    val k = MaterialTheme.vault
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .pressable(onClick = { if (enabled) onClick() }, pressedScale = 1f, pressed = if (enabled) k.pressed else Color.Transparent)
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (enabled || status) 1f else 0.5f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = VaultType.Body.copy(fontWeight = VaultType.Row.fontWeight), color = color ?: k.text, modifier = Modifier.alpha(if (!enabled && status) 0.5f else 1f))
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = VaultType.Label.copy(fontWeight = null), color = subtitleColor ?: k.muted,
                maxLines = if (status) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = if (status) Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite } else Modifier,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        if (chevron) {
            Spacer(Modifier.width(8.dp))
            androidx.compose.material3.Icon(VaultIcons.ChevronRight, null, tint = k.muted, modifier = Modifier.size(18.dp))
        }
    }
}

/** True where fields sit straight on the ground (onboarding) rather than in a group on the lift: their well is then the lift. */
val LocalOnGround = androidx.compose.runtime.compositionLocalOf { false }

/**
 * A text field: its label above, the text in an inset well (the ground tone, so it reads as a hole in the lift), the
 * help text below. No outline; the caret is the text colour.
 */
@Composable
internal fun VaultField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    placeholder: String? = null,
    supporting: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    transformation: VisualTransformation = VisualTransformation.None,
    singleLine: Boolean = true,
    minLines: Int = 1,
    modifier: Modifier = Modifier.fillMaxWidth(),
    /** The value can't be used: the label and help text turn amber. */
    isError: Boolean = false,
) {
    val k = MaterialTheme.vault
    Column(modifier) {
        Text(label, style = VaultType.Label, color = if (isError) k.attention else k.muted, modifier = Modifier.padding(bottom = 6.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(if (LocalOnGround.current) k.surfaceHigh else k.background).padding(horizontal = 14.dp, vertical = 13.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty() && placeholder != null) Text(placeholder, style = VaultType.Body, color = k.faint, maxLines = if (singleLine) 1 else minLines)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = singleLine,
                minLines = minLines,
                maxLines = if (singleLine) 1 else 6,
                visualTransformation = transformation,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                textStyle = VaultType.Body.copy(color = k.text),
                cursorBrush = SolidColor(k.text),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (supporting != null) Text(supporting, style = VaultType.Caption, color = if (isError) k.attention else k.muted, modifier = Modifier.padding(top = 6.dp))
    }
}
