package io.github.erkanw.vault.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/** A small dot that breathes: "something is running here". */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = Motion.Standard), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(modifier.size(size).clip(CircleShape).background(color.copy(alpha = alpha)))
}

/** Three dots that rise and fall in turn: the agent is typing. */
@Composable
fun TypingDots(color: Color, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = androidx.compose.animation.core.LinearEasing)),
        label = "typingPhase",
    )
    Canvas(modifier.size(width = 34.dp, height = 18.dp)) {
        val radius = 3.dp.toPx()
        for (i in 0 until 3) {
            // Each dot is a smooth bump of the phase, a third of a cycle after the one before.
            val local = ((phase - i / 3f) % 1f + 1f) % 1f
            val lift = if (local < 0.5f) sin(local / 0.5f * PI.toFloat()) else 0f
            drawCircle(
                color.copy(alpha = 0.45f + 0.55f * lift),
                radius = radius,
                center = Offset(radius + i * 11.dp.toPx(), size.height / 2f - lift * 4.dp.toPx()),
            )
        }
    }
}
