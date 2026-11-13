package io.github.erkanw.vault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.min

/** Where the drawer is: [progress] runs from 0 (closed) to 1 (open), a little past 1 while pulled further. */
@Stable
class PushDrawerState {
    internal val anim = Animatable(0f)
    val progress: Float get() = anim.value
    val isOpen: Boolean get() = anim.targetValue > 0.5f

    /** Opened or closed by a tap: no bounce, since no finger threw it. */
    suspend fun open() = anim.animateTo(1f, Motion.settle())
    suspend fun close() = anim.animateTo(0f, Motion.settle())
}

@Composable
fun rememberPushDrawerState(): PushDrawerState = remember { PushDrawerState() }

/**
 * The side drawer as in Grok: the screen slides right to show the drawer, a scrim dims what was left behind. While a
 * finger is down the drawer follows it 1:1 (with resistance past fully open); on release it settles where the flick
 * was heading, at the finger's speed. It can be caught mid-way. Back closes it. The drag starts anywhere on the screen
 * except the system's own back-gesture edges, and scrollable children (lists, chip rows) keep their own drags.
 */
@Composable
fun PushDrawer(
    state: PushDrawerState,
    drawer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val k = MaterialTheme.vault
    BackHandler(enabled = state.isOpen) { scope.launch { state.close() } }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = min(maxWidth.value * 0.84f, 340f).dp
        val widthPx = with(LocalDensity.current) { width.toPx() }
        val drag = rememberDraggableState { delta ->
            scope.launch {
                val raw = state.anim.value + delta / widthPx
                val next = if (raw > 1f) 1f + Motion.rubberBand(raw - 1f, 1f, 0.15f) else raw.coerceAtLeast(0f)
                state.anim.snapTo(next)
            }
        }
        Box(
            Modifier.fillMaxSize().draggable(
                drag,
                Orientation.Horizontal,
                enabled = gesturesEnabled || state.isOpen,
                onDragStopped = { velocity ->
                    val p = state.anim.value
                    val projected = p + Motion.project(velocity) / widthPx
                    val target = if (projected > 0.5f) 1f else 0f
                    state.anim.animateTo(target, Motion.fling(), initialVelocity = velocity / widthPx)
                },
            ),
        ) {
            // The drawer, sliding in from the left edge.
            Box(
                Modifier.width(width).fillMaxHeight().graphicsLayer {
                    translationX = (state.progress - 1f) * widthPx
                }.background(k.surface),
            ) { drawer() }
            // The screen, pushed right, dimmed by a scrim that also takes the tap to close.
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    translationX = state.progress * widthPx
                },
            ) {
                content()
                val p = state.progress
                if (p > 0.001f) {
                    Box(
                        Modifier.fillMaxSize()
                            .graphicsLayer { alpha = p.coerceIn(0f, 1f) }
                            .background(MaterialTheme.vault.drawerDim)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { scope.launch { state.close() } }
                            .semantics { contentDescription = "Close the menu" },
                    )
                }
            }
        }
    }
}
