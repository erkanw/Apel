package io.github.erkanw.vault.mascot

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import kotlin.math.tanh

/**
 * Blob, the agent’s mascot, alive: breathes, blinks, looks around, follows a finger, squishes when poked, and plays [mood].
 *
 * [size] is the drawing box; the body itself takes about two thirds of its width and half its height, centred a little
 * below the middle (the box leaves room for the hop, badge, thought dots and sparkles). Below 72 dp the cheaper "lite"
 * finish is used. When the phone's animations are switched off, Blob holds still in the pose of its mood.
 *
 * [lookAt] makes Blob look somewhere (-1..1 each way, e.g. toward a field being typed in); null lets it look by itself.
 */
@Composable
fun Blob(
    mood: BlobMood,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    interactive: Boolean = true,
    lookAt: Offset? = null,
    /** The ground shadow: black on a dark page; on a light one the original's soft ink (#2a2140) at 22 %. */
    shadow: Color? = null,
    animated: Boolean = true,
    description: String? = null,
    /** The error badge's colour: red by default; amber when the error means "this needs you" (can't reach the agent). */
    errorColor: Color = Color(0xFFFF5A4E),
    seed: Int = 7,
) {
    val context = LocalContext.current
    val engine = remember { BlobEngine(seed) }
    val lite = size < 72.dp
    val painter = remember(lite) { BlobPainter(lite) }
    val onLight = MaterialTheme.colorScheme.background.luminance() > 0.5f
    painter.onLight = onLight
    val reducedMotion = remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    val still = reducedMotion || !animated
    var tick by remember { mutableLongStateOf(0L) }

    engine.setMood(mood)
    engine.look = lookAt?.let { it.x to it.y }

    LaunchedEffect(still, mood) {
        if (still) {
            engine.step(0f, still = true)
            tick++
            return@LaunchedEffect
        }
        var last = -1L
        while (true) {
            withFrameNanos { now ->
                val dt = if (last < 0) 0f else (now - last) / 1_000_000_000f
                last = now
                engine.step(dt)
                tick = now
            }
        }
    }

    val shadowArgb = (shadow ?: if (onLight) Color(0x382A2140) else Color.Black.copy(alpha = 0.55f)).toArgb()
    val errorArgb = errorColor.toArgb()
    val boxPx = with(LocalDensity.current) { size.toPx() }
    var m = modifier.size(size)
    if (description != null) m = m.semantics { contentDescription = description }
    if (interactive && !still) {
        m = m.pointerInput(Unit) {
            val unitsPerPx = BlobShapes.UNIT_BOX / boxPx
            fun gaze(p: Offset) {
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                engine.finger(tanh((p.x - cx) / (this.size.width * 0.55f)), tanh((p.y - cy) / (this.size.height * 0.55f)))
            }
            awaitEachGesture {
                val down = awaitFirstDown()
                val start = down.position
                gaze(start)
                var moved = false
                engine.drag = 0f to 0f
                while (true) {
                    val ev = awaitPointerEvent()
                    val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!ch.pressed) break
                    val d = ch.position - start
                    if (hypot(d.x, d.y) * unitsPerPx > 6f) moved = true
                    engine.drag = d.x * unitsPerPx to d.y * unitsPerPx
                    gaze(ch.position)
                    if (ch.positionChange() != Offset.Zero) ch.consume()
                }
                engine.drag = null
                if (!moved) engine.poke()
            }
        }
    }

    Canvas(m) {
        @Suppress("UNUSED_EXPRESSION") tick // redraw on every frame
        drawIntoCanvas { painter.draw(it.nativeCanvas, engine, this.size.width / BlobShapes.UNIT_BOX, shadowArgb, errorArgb) }
    }
}
