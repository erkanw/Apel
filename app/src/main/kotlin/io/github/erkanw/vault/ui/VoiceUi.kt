package io.github.erkanw.vault.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erkanw.vault.VoiceState
import io.github.erkanw.vault.chat.elapsedLabel

/** The microphone, drawn (the icon set in use has none): a capsule on a stand. */
@Composable
fun MicIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = w * 0.085f, cap = StrokeCap.Round)
        // The capsule.
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.37f, h * 0.10f),
            size = Size(w * 0.26f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.13f, w * 0.13f),
        )
        // The cup around it.
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.24f, h * 0.30f),
            size = Size(w * 0.52f, h * 0.44f),
            style = stroke,
        )
        // The stand.
        drawLine(tint, Offset(w * 0.5f, h * 0.74f), Offset(w * 0.5f, h * 0.90f), strokeWidth = stroke.width, cap = StrokeCap.Round)
        drawLine(tint, Offset(w * 0.36f, h * 0.90f), Offset(w * 0.64f, h * 0.90f), strokeWidth = stroke.width, cap = StrokeCap.Round)
    }
}

/** In place of the text while recording: cancel, a red dot, the time, and the last seconds of loudness as bars. */
@Composable
fun RecordingBar(state: VoiceState.Recording, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    val history = remember { mutableStateListOf<Float>() }
    LaunchedEffect(state.elapsedMs) {
        history.add(state.level)
        while (history.size > BAR_COUNT) history.removeAt(0)
    }
    Row(modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        CancelButton(onCancel)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(9.dp).clip(CircleShape).background(k.danger))
        Spacer(Modifier.width(8.dp))
        Text(elapsedLabel(state.elapsedMs), color = k.text, fontSize = 15.sp, modifier = Modifier.width(40.dp))
        Canvas(Modifier.weight(1f).height(32.dp).padding(end = 8.dp)) {
            val step = size.width / BAR_COUNT
            val mid = size.height / 2f
            history.forEachIndexed { i, level ->
                val x = size.width - (history.size - i) * step + step / 2f
                val half = (3.dp.toPx() + level * (size.height / 2f - 3.dp.toPx())).coerceAtMost(mid)
                drawLine(k.accent, Offset(x, mid - half), Offset(x, mid + half), strokeWidth = step * 0.5f, cap = StrokeCap.Round)
            }
        }
    }
}

/** In place of the text while the agent turns the recording into words. */
@Composable
fun TranscribingBar(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val k = MaterialTheme.vault
    Row(modifier.fillMaxWidth().height(46.dp), verticalAlignment = Alignment.CenterVertically) {
        CancelButton(onCancel)
        Spacer(Modifier.width(14.dp))
        TypingDots(k.muted)
        Spacer(Modifier.width(10.dp))
        Text("Turning your voice into text…", color = k.muted, fontSize = 14.sp)
    }
}

@Composable
private fun CancelButton(onCancel: () -> Unit) {
    val k = MaterialTheme.vault
    val view = LocalView.current
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(k.surface)
            .clickable {
                Haptics.tap(view)
                onCancel()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Close, "Cancel the recording", tint = k.text, modifier = Modifier.size(18.dp))
    }
}

private const val BAR_COUNT = 36
