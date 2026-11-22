package io.github.erkanw.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** What a tool waits on before it runs (PLAN §7.5): which tool, what it wants to do in a line, and the details. */
data class ApprovalRequest(
    val id: String,
    val tool: String,
    /** One line: "Add 3 rows to the expenses table". */
    val summary: String,
    /** The SQL, the file path, the schedule: shown in monospace. */
    val detail: String,
)

/**
 * The approval card (handbook §3, the agent’s request card made generic): an amber dot and "Wants your OK", what the agent
 * wants to do, the details in monospace, Allow, "Allow and don’t ask again" (when calls like it can be remembered) and
 * Deny stacked on the pressed tone, and "Always allow this tool" as a quiet checkbox row. [sending] greys the choices
 * while the answer is being applied.
 */
@Composable
fun ApprovalCard(
    request: ApprovalRequest,
    sending: Boolean,
    onAllow: (always: Boolean) -> Unit,
    onDeny: () -> Unit,
    modifier: Modifier = Modifier,
    /** Offer "Always allow this tool" (only when the tool’s level is what asks). */
    allowAlways: Boolean = true,
    /** What "Allow and don’t ask again" covers ("pages on example.com"); null: not offered. */
    similar: String? = null,
    onAllowSimilar: (always: Boolean) -> Unit = onAllow,
) {
    val k = MaterialTheme.vault
    var always by remember(request.id) { mutableStateOf(false) }
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(k.surfaceHigh)
            // Capped by the chat; what doesn’t fit scrolls inside the card.
            .then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Wants your OK", color = k.text, style = VaultType.Body.copy(fontSize = 16.sp), fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(8.dp))
            PulsingDot(k.attention, size = 8.dp)
        }
        Spacer(Modifier.height(6.dp))
        Text(request.summary, color = k.text, style = VaultType.Body, lineHeight = 21.sp)
        if (request.detail.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            // Everything that will run is shown, never a start of it: a long request says so, so it is read to the end.
            if (request.detail.length > LONG_DETAIL) {
                Text(
                    "This is long (${java.text.NumberFormat.getIntegerInstance().format(request.detail.length)} characters). Scroll to read all of it before you allow it.",
                    color = k.muted, style = VaultType.Label.copy(fontWeight = null), lineHeight = 19.sp,
                )
                Spacer(Modifier.height(8.dp))
            }
            Text(
                request.detail,
                color = k.text, style = VaultType.Preview.copy(fontSize = 13.sp), lineHeight = 19.sp, fontFamily = FontFamily.Monospace,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.background).padding(12.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        // The choices, stacked one to a line on the pressed tone (one step up from the card), a hairline between them.
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(k.pressed)) {
            ChoiceRow("Allow", k.text, enabled = !sending) { onAllow(always) }
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(k.line))
            if (similar != null) {
                ChoiceRow("Allow and don’t ask again", k.text, enabled = !sending, subtitle = "For $similar") { onAllowSimilar(always) }
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(1.dp).background(k.line))
            }
            ChoiceRow("Deny", k.danger, enabled = !sending, onClick = onDeny)
        }
        if (allowAlways) Spacer(Modifier.height(4.dp))
        if (allowAlways) Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .pressable(
                    onClick = { if (!sending) always = !always }, shape = RoundedCornerShape(12.dp), pressedScale = 0.99f,
                    pressed = k.pressed, role = Role.Checkbox, onClickLabel = "Always allow ${request.tool}",
                )
                .padding(horizontal = 4.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(if (always) k.accent else k.pressed),
                contentAlignment = Alignment.Center,
            ) { if (always) Icon(VaultIcons.Check, null, tint = k.onAccent, modifier = Modifier.size(14.dp)) }
            Spacer(Modifier.width(12.dp))
            Text("Always allow this tool", color = k.muted, style = VaultType.Label.copy(fontWeight = null))
        }
    }
}

/** Details longer than this get a line saying how long they are. */
private const val LONG_DETAIL = 600

/** One full-width choice in the stack under a request, with a muted second line when it needs one. */
@Composable
private fun ChoiceRow(label: String, color: androidx.compose.ui.graphics.Color, enabled: Boolean, subtitle: String? = null, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .pressable(onClick = { if (enabled) onClick() }, pressedScale = 1f, pressed = MaterialTheme.vault.faint.copy(alpha = 0.35f), onClickLabel = label)
            .padding(horizontal = 16.dp, vertical = if (subtitle == null) 15.dp else 12.dp),
    ) {
        Text(
            label,
            color = if (enabled) color else MaterialTheme.vault.muted.copy(alpha = 0.6f),
            style = VaultType.Body,
            fontWeight = FontWeight.Medium,
        )
        if (subtitle != null) Text(subtitle, color = MaterialTheme.vault.muted, style = VaultType.Label.copy(fontWeight = null), lineHeight = 19.sp)
    }
}
