package io.github.erkanw.vault.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * How things move in the agent: eased, never linear, and never slow. Things arrive quickly and settle
 * gently ([Decelerate]), leave quickly and out of the way ([Accelerate]), and move between two
 * places along [Standard]. A normal change takes [Medium] milliseconds.
 */
object Motion {
    /** Fast start, long soft landing: for things arriving. */
    val Decelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** Slow start, quick end: for things leaving. */
    val Accelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    /** Ease in and out: for things moving from one place to another. */
    val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    const val Short = 200
    const val Medium = 360
    const val Long = 460

    /** The pause between one row of a list and the next when they arrive together. */
    const val StaggerStepMs = 40

    /** Rows past this many arrive together, so a long list doesn't take seconds to appear. */
    const val StaggerMaxItems = 8

    /*
     * Springs, in Apple's two numbers (damping ratio, response in seconds; stiffness = (2 pi / response)^2).
     * Anything that moves because of a tap settles without bounce; only what a finger threw may overshoot a little.
     */

    /** The default: critically damped, response 0.35 s. Panels, sheets opened by a tap, morphs. */
    fun <T> settle(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 322f)

    /** After a fling or drag release: a slight overshoot (damping 0.85, response 0.3 s). */
    fun <T> fling(): SpringSpec<T> = spring(dampingRatio = 0.85f, stiffness = 438f)

    /** Small things: chips, dots, the send button appearing (damping 1, response 0.25 s). */
    fun <T> quick(): SpringSpec<T> = spring(dampingRatio = 1f, stiffness = 632f)

    /** Where a flick would come to rest (Apple's projection, deceleration 0.998), from [velocity] in px/s. */
    fun project(velocity: Float, deceleration: Float = 0.998f): Float = velocity / 1000f * deceleration / (1f - deceleration)

    /** Resistance past an edge: the further past, the less it follows. */
    fun rubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float =
        (overshoot * dimension * constant) / (dimension + constant * kotlin.math.abs(overshoot))
}

/**
 * Makes something pressable the way the platform's best apps do: the response starts on the finger-down, not on release.
 * It sinks a little ([pressedScale], on a spring so it can be let go of mid-press) and takes the [pressed] color; long
 * press is optional. No ripple: the sinking is the feedback.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun Modifier.pressable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    shape: Shape = RectangleShape,
    pressedScale: Float = 0.97f,
    rest: Color = Color.Transparent,
    pressed: Color? = null,
    role: Role = Role.Button,
    onClickLabel: String? = null,
): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val down by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (down) pressedScale else 1f, spring(dampingRatio = 0.65f, stiffness = 900f), label = "pressScale")
    val fill by animateColorAsState(if (down && pressed != null) pressed else rest, tween(80), label = "pressFill")
    graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(shape)
        .background(fill)
        .combinedClickable(
            interactionSource = source, indication = null, role = role, onClickLabel = onClickLabel,
            onClick = onClick, onLongClick = onLongClick,
        )
}

/** How long row number [index] of a list waits before it arrives. */
fun staggerDelayMs(index: Int): Int = index.coerceIn(0, Motion.StaggerMaxItems) * Motion.StaggerStepMs

/**
 * Remembers which list rows have already arrived, so scrolling one out of sight and back doesn't make it
 * arrive again.
 */
class EntranceTracker {
    private val seen = HashSet<Any>()
    fun played(key: Any): Boolean = key in seen
    fun mark(key: Any) {
        seen.add(key)
    }
}

/** An [EntranceTracker] that starts with [alreadyThere] counted as arrived (what is on screen when it is made). */
@Composable
fun rememberEntranceTracker(vararg inputs: Any?, alreadyThere: List<Any> = emptyList()): EntranceTracker =
    remember(*inputs) { EntranceTracker().also { tracker -> alreadyThere.forEach(tracker::mark) } }

/**
 * A list row arrives by sliding in from ([dx], [dy]) and fading in, after its place in the
 * stagger ([index]), once per [key] as far as [tracker] knows.
 */
fun Modifier.entrance(
    key: Any,
    index: Int,
    tracker: EntranceTracker,
    dx: Dp = 24.dp,
    dy: Dp = 10.dp,
): Modifier = composed {
    val already = remember(key) { tracker.played(key) }
    val progress = remember(key) { Animatable(if (already) 1f else 0f) }
    LaunchedEffect(key) {
        if (!already) {
            tracker.mark(key)
            delay(staggerDelayMs(index).toLong())
            progress.animateTo(1f, tween(Motion.Long, easing = Motion.Decelerate))
        }
    }
    val density = LocalDensity.current
    graphicsLayer {
        val p = progress.value
        alpha = p
        translationX = (1f - p) * with(density) { dx.toPx() }
        translationY = (1f - p) * with(density) { dy.toPx() }
    }
}
