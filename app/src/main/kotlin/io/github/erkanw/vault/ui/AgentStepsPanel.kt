package io.github.erkanw.vault.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erkanw.vault.chat.AgentStep
import io.github.erkanw.vault.chat.StepKind
import io.github.erkanw.vault.chat.stepsSummary

/**
 * What the agent did on the way to an answer: a small line above it ("3 tools") that opens the list of tool calls, notes
 * and thinking, in order. While the answer is still being made it says so and grows as steps arrive; afterwards it
 * stays, and is kept with the chat, so the steps can be read long after the reply.
 */
@Composable
fun StepsPanel(messageId: Long, steps: List<AgentStep>, live: Boolean, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    var open by rememberSaveable(messageId) { mutableStateOf(false) }
    Column(modifier) {
        Row(
            Modifier
                .heightIn(min = 40.dp)
                .pressable(onClick = { open = !open }, shape = CircleShape, pressedScale = 0.97f, pressed = k.surfaceHigh, onClickLabel = if (open) "Hide ${LocalAgentName.current}’s steps" else "Show ${LocalAgentName.current}’s steps")
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (live) {
                PulsingDot(k.live, size = 7.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(
                (if (live) "Working · " else "") + stepsSummary(steps),
                color = k.muted, style = VaultType.Caption.copy(fontWeight = FontWeight.Medium),
            )
            Spacer(Modifier.width(6.dp))
            Icon(VaultIcons.ChevronDown, null, tint = k.muted, modifier = Modifier.size(14.dp).graphicsLayer { rotationZ = if (open) 180f else 0f })
        }
        AnimatedVisibility(
            visible = open,
            enter = fadeIn(tween(Motion.Medium, easing = Motion.Decelerate)) + expandVertically(tween(Motion.Medium, easing = Motion.Decelerate)),
            exit = fadeOut(tween(Motion.Short, easing = Motion.Accelerate)) + shrinkVertically(tween(Motion.Short, easing = Motion.Accelerate)),
        ) {
            StepsList(steps, Modifier.padding(top = 6.dp))
        }
    }
}

/** A step is a dot in its kind's color and its text; a long one is cut short until it is tapped. */
@Composable
private fun StepRow(step: AgentStep) {
    val k = MaterialTheme.vault
    var full by remember { mutableStateOf(false) }
    val long = step.text.length > LONG_STEP
    val color = when (step.kind) {
        StepKind.Tool -> k.text.copy(alpha = 0.55f)
        StepKind.Note -> k.muted
        StepKind.Thinking -> k.muted.copy(alpha = 0.6f)
    }
    Row(
        Modifier.fillMaxWidth().then(if (long) Modifier.clickable { full = !full } else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp).size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(9.dp))
        Text(
            step.text,
            color = if (step.kind == StepKind.Tool) k.text else k.muted,
            style = if (step.kind == StepKind.Tool) VaultType.Caption.copy(fontFamily = FontFamily.Monospace, lineHeight = 17.sp) else VaultType.Label.copy(fontWeight = null, lineHeight = 18.sp),
            fontStyle = if (step.kind == StepKind.Thinking) FontStyle.Italic else FontStyle.Normal,
            maxLines = if (full) Int.MAX_VALUE else COLLAPSED_LINES,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val LONG_STEP = 160
private const val COLLAPSED_LINES = 3

/** The steps in a rounded box: each tool call, note and bit of thinking, in order. */
@Composable
fun StepsList(steps: List<AgentStep>, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(k.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        steps.forEach { StepRow(it) }
    }
}
