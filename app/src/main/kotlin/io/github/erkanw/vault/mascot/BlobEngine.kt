package io.github.erkanw.vault.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/** What Blob is doing: the eight states of Blob Atelier's agent mascot, and the agent’s own Sleeping (eyes shut, slow breath). */
enum class BlobMood { Idle, Listening, Thinking, Talking, Working, Happy, Done, Error, Sleeping }

private const val TAU = (PI * 2).toFloat()

/**
 * Blob's body in motion: the spring model of agent-mascot.js (Blob Atelier by Victoria Wu, MIT), ported as faithfully as
 * possible. [step] advances time; the fields it fills are read by [BlobPainter]. All lengths are in the original's units.
 */
internal class BlobEngine(seed: Int = 7) {
    private val seed0 = seed
    private var rand = Random(seed)

    /**
     * Loop mode, for the widget's flipbook: above 0, everything Blob does by himself repeats exactly every [loop] seconds
     * (blinks and glances on a fixed schedule, every rhythm rounded to a whole number of beats per loop, the dice
     * re-rolled the same way each time), so frames drawn over one period join up. [loopVariant] picks the glances.
     */
    var loop = 0f
    var loopVariant = 0
    private var loopT = 0f

    /** The clock the rhythms run on: [t], or in loop mode the time into the loop. */
    val clock: Float get() = if (loop > 0f) loopT else t

    /** [p] seconds, or in loop mode the nearest period that divides the loop. */
    fun per(p: Float): Float = if (loop > 0f) loop / max(1, Math.round(loop / p)) else p

    /** An angular frequency [w] (radians a second), rounded in loop mode to a whole number of turns per loop. */
    fun hz(w: Float): Float = if (loop > 0f) TAU * max(1, Math.round(w * loop / TAU)) / loop else w

    /** Loop mode's glances: (phase 0..1, gaze x, gaze y), the first always straight ahead. */
    private val glances: List<Triple<Float, Float, Float>> by lazy {
        val r = Random(loopVariant * 7919 + 13)
        val side = if (r.nextBoolean()) 1f else -1f
        listOf(
            Triple(0f, 0f, 0f),
            Triple(0.22f + r.nextFloat() * 0.1f, side * (0.25f + r.nextFloat() * 0.2f), -0.2f + r.nextFloat() * 0.25f),
            Triple(0.55f + r.nextFloat() * 0.12f, -side * (0.2f + r.nextFloat() * 0.25f), -0.1f + r.nextFloat() * 0.25f),
        )
    }

    /** Loop mode's blinks, as phases of the loop. */
    private val blinks: FloatArray by lazy { val r = Random(loopVariant * 104729 + 5); floatArrayOf(0.12f + r.nextFloat() * 0.1f, 0.6f + r.nextFloat() * 0.15f) }

    var mood = BlobMood.Idle
        private set

    /** Time since creation and since the mood last changed, in seconds. */
    var t = 0f
        private set
    var moodT = 0f
        private set

    // Springs: value and velocity.
    var s = 0f; private var sV = 0f            // squash
    var ox = 0f; private var oxV = 0f          // drag offset
    var oy = 0f; private var oyV = 0f
    var gx = 0f; private var gxV = 0f          // gaze
    var gy = 0f; private var gyV = 0f
    var bx = 0f; private var bxV = 0f          // body lean following the gaze
    var by = 0f; private var byV = 0f
    var eye = 1f; private var eyeV = 0f        // eye size
    var expr = 0f; private var exprV = 0f      // expression eyes (happy arcs or chevrons) instead of capsules
    var badge = 0f; private var badgeV = 0f    // done / error badge
    var tilt = 0f; private var tiltV = 0f

    /** Lid opening (1 open, near 0 shut), blink included. */
    var lidS = 1f
        private set

    /** True while the expression eyes are chevrons (error) rather than happy arcs. */
    var chevrons = false
        private set

    // Per-frame results for the painter.
    var hop = 0f; private set
    var shakeX = 0f; private set
    var bob = 0f; private set
    var pulse = 0f; private set
    var breath = 0f; private set

    // Blink and idle gaze drift.
    private var blinkNext = 1.4f
    private var blinkT = -1f
    private var driftNext = 0.6f
    private var driftX = 0f
    private var driftY = 0f

    // Mood bookkeeping.
    private var shakeT = -9f
    private var hopT = 0f
    private var landed = true
    private var exprUntil = 0f
    private var exprChev = false

    /** Sparkles: angle, distance, age (negative while waiting), size. */
    class Spark(val a: Float, val d: Float, var age: Float, val size: Float)
    val sparks = ArrayList<Spark>(8)

    /** Where Blob looks, -1..1 each way, set by a finger nearby or by the screen; null lets it look around by itself. */
    var look: Pair<Float, Float>? = null

    /** A drag on the body, in units, or null when not held. */
    var drag: Pair<Float, Float>? = null

    private var fingerLook: Pair<Float, Float>? = null
    private var fingerUntil = 0f

    /** A finger at gaze (-1..1 each way): Blob follows it, and keeps looking there for 2.5 s after it goes. */
    fun finger(gx: Float, gy: Float) {
        fingerLook = gx to gy
        fingerUntil = t + 2.5f
    }

    fun setMood(m: BlobMood) {
        if (m == mood) return
        mood = m
        moodT = 0f
        when (m) {
            BlobMood.Done -> { sV -= 3.4f; exprUntil = 1.5f; burst(6) }
            BlobMood.Error -> { shakeT = 0f; sV -= 2f }
            BlobMood.Happy -> hopT = 0f
            BlobMood.Listening -> sV += 1.6f
            BlobMood.Thinking, BlobMood.Working -> sV -= 1f
            else -> Unit
        }
    }

    /** A tap on the body: a squish and a little turn. */
    fun poke() {
        sV -= 3.2f
        tiltV += (rand.nextFloat() - 0.5f) * 40f
    }

    private fun burst(n: Int) {
        for (i in 0 until n) {
            val a = (-PI / 2).toFloat() + (i.toFloat() / max(1, n - 1) - 0.5f) * 2.4f + (rand.nextFloat() - 0.5f) * 0.3f
            sparks.add(Spark(a, BlobShapes.hw * 1.05f + rand.nextFloat() * 16f, -i * 0.04f, 5f + rand.nextFloat() * 5f))
        }
        while (sparks.size > 8) sparks.removeAt(0)
    }

    /** Advances by [dtIn] seconds. With [still], every spring jumps to its target and nothing moves by itself. */
    fun step(dtIn: Float, still: Boolean = false) {
        val dt = if (still) 0f else min(dtIn, 0.05f)
        t += dt
        moodT += dt
        if (loop > 0f && dt > 0f) {
            loopT += dt
            if (loopT >= loop - 1e-3f) { loopT -= loop; rand = Random(seed0 * 31 + loopVariant) }
        }
        var sT = 0f
        var eyeT = 1f
        var tiltT = 0f
        var lid = 1f
        var exprT = 0f
        var chev = false
        var badgeT = 0f
        var hopV = 0f
        var shake = 0f
        var bobV = 0f
        var pulseV = 0f
        var tgx: Float
        var tgy: Float
        val lk = look ?: fingerLook?.takeIf { !still && t < fingerUntil }
        if (lk != null) {
            tgx = lk.first
            tgy = lk.second
        } else if (loop > 0f) {
            val ph = loopT / loop
            val g = glances.last { it.first <= ph + 1e-4f }
            tgx = g.second
            tgy = g.third
        } else {
            if (t > driftNext) {
                val rr = rand.nextFloat()
                driftX = if (rr < 0.3f) 0f else (rand.nextFloat() - 0.5f) * 0.9f
                driftY = if (rr < 0.3f) 0f else (rand.nextFloat() - 0.5f) * 0.5f
                driftNext = t + 1.1f + rand.nextFloat() * 2.4f
            }
            tgx = driftX
            tgy = driftY
        }
        var blinkRate = 1f
        // In loop mode the rhythms run on the loop's own clock, which wraps, so they repeat exactly.
        val tt = clock
        when (mood) {
            BlobMood.Listening -> {
                eyeT = 1.14f; tiltT = 5f + tgx * 4f; sT = 0.02f
                // A little nod every 1.8 s (in loop mode the beat is offset so it never falls on a frame's edge).
                val p = per(1.8f)
                val due = if (loop > 0f) floor((loopT - 0.3f) / p) != floor((loopT - 0.3f - dt) / p) else (moodT % p) / p < dt / p
                if (dt > 0 && due && moodT > 0.5f) sV -= 0.9f
                blinkRate = 0.6f
            }
            BlobMood.Thinking -> {
                tgx = 0.55f + 0.12f * cos(tt * hz(1.3f)); tgy = -0.62f + 0.1f * sin(tt * hz(1.3f))
                lid = 0.82f; tiltT = -4f + 3f * sin(tt * hz(0.9f)); blinkRate = 0.5f
            }
            BlobMood.Talking -> {
                val ph = tt % per(3.1f)
                val on = if (ph < 2.3f) 1f else 0f
                val syl = max(0f, sin(tt * hz(TAU * 4.6f) + sin(tt * hz(3.1f)) * 2f))
                val amp = 0.55f + 0.45f * sin(tt * hz(2.7f) + 1f) * sin(tt * hz(1.3f))
                pulseV = on * syl * syl * (0.6f + 0.4f * amp)
                lid = 1f - 0.14f * pulseV
            }
            BlobMood.Working -> {
                val sc = (tt / per(1.333f)) % 1f
                tgx = -0.55f + sc * 1.1f; tgy = 0.32f; lid = 0.86f
                bobV = -2.6f * abs(sin(tt * hz(TAU * 2.2f))); sT = 0.012f * sin(tt * hz(TAU * 2.2f)); blinkRate = 0.5f
            }
            BlobMood.Happy -> {
                exprT = 1f
                val per = per(0.92f)
                val air = 0.44f
                hopT += dt
                val hp = (if (loop > 0f) loopT else hopT) % per
                if (hp < air) {
                    val u = hp / air
                    hopV = 26f * 4f * u * (1 - u); sT = 0.05f
                    if (landed) { landed = false; sV += 2.4f }
                } else if (!landed) {
                    landed = true; sV -= 3.6f
                    if (rand.nextFloat() < 0.7f) burst(2)
                }
                tgy = -0.15f
            }
            BlobMood.Done -> {
                badgeT = 1f
                if (moodT < exprUntil) exprT = 1f
                tgy = -0.1f
            }
            BlobMood.Error -> {
                badgeT = 1f; exprT = 1f; chev = true; sT = -0.045f; tiltT = -3f; tgy = 0.15f; tgx *= 0.3f
                val su = if (loop > 0f) (loopT + per(2.8f) - 0.2f) % per(2.8f) else {
                    if (moodT - shakeT > 2.8f) shakeT = moodT
                    moodT - shakeT
                }
                shake = 11f * exp(-5f * su) * sin(su * TAU * 6.5f)
            }
            BlobMood.Sleeping -> {
                lid = 0.05f; tgx = 0f; tgy = 0.3f; tiltT = 3f; sT = -0.02f
            }
            BlobMood.Idle -> Unit
        }

        // Springs, sub-stepped at 240 Hz as in the original.
        var dragTX = 0f
        var dragTY = 0f
        var dk = 180f
        var dd = 7f
        val dr = drag
        if (dr != null) {
            val dl = hypot(dr.first, dr.second)
            val lim = 34f
            val f = if (dl > 0) lim * tanh(dl * 0.45f / lim) / dl else 0f
            dragTX = dr.first * f; dragTY = dr.second * f; dk = 500f; dd = 34f
        }
        val n = max(1, ceil(dt / (1f / 240f)).toInt())
        val h = dt / n
        if (dt > 0) repeat(n) {
            sV += (260f * (sT - s) - 10f * sV) * h; s += sV * h
            oxV += (dk * (dragTX - ox) - dd * oxV) * h; ox += oxV * h
            oyV += (dk * (dragTY - oy) - dd * oyV) * h; oy += oyV * h
            gxV += (260f * (tgx - gx) - 30f * gxV) * h; gx += gxV * h
            gyV += (260f * (tgy - gy) - 30f * gyV) * h; gy += gyV * h
            bxV += (40f * (gx - bx) - 12f * bxV) * h; bx += bxV * h
            byV += (40f * (gy - by) - 12f * byV) * h; by += byV * h
            eyeV += (300f * (eyeT - eye) - 20f * eyeV) * h; eye += eyeV * h
            exprV += (320f * (exprT - expr) - 30f * exprV) * h; expr += exprV * h
            badgeV += (320f * (badgeT - badge) - 16f * badgeV) * h; badge += badgeV * h
            tiltV += (120f * (tiltT - tilt) - 12f * tiltV) * h; tilt += tiltV * h
        }
        if (still) {
            s = sT; gx = tgx; gy = tgy; bx = tgx; by = tgy; eye = eyeT; expr = exprT; badge = badgeT; tilt = tiltT; ox = 0f; oy = 0f
            sV = 0f; gxV = 0f; gyV = 0f; bxV = 0f; byV = 0f; eyeV = 0f; exprV = 0f; badgeV = 0f; tiltV = 0f; oxV = 0f; oyV = 0f
        }
        if (chev != exprChev && expr < 0.05f) exprChev = chev
        if (exprT > 0) exprChev = chev
        chevrons = exprChev

        // Blink.
        var lidB = 1f
        if (still || mood == BlobMood.Sleeping) {
            // No blinking: the eyes stay as they are (shut, when asleep).
        } else if (loop > 0f) {
            for (b in blinks) {
                var bt = loopT - b * loop
                if (bt < 0f) bt += loop
                if (bt < 0.24f && expr < 0.3f) lidB = min(lidB, blinkLid(bt))
            }
        } else {
            if (blinkT < 0 && t > blinkNext && expr < 0.3f) {
                blinkT = 0f
                val dbl = rand.nextFloat() < 0.16f
                blinkNext = t + if (dbl) 0.32f else (2.0f + rand.nextFloat() * 3.6f) / blinkRate
            }
            if (blinkT >= 0) {
                blinkT += dt
                lidB = blinkLid(blinkT)
                if (blinkT > 0.24f) blinkT = -1f
            }
        }
        lidS = lid * lidB

        // Sparkles age with Blob's own clock.
        if (dt > 0) {
            for (q in sparks) q.age += dt
            sparks.removeAll { it.age >= 0.8f }
        }

        hop = hopV
        shakeX = shake
        bob = bobV
        pulse = pulseV
        breath = when {
            still -> 0f
            mood == BlobMood.Sleeping -> sin(clock * TAU / per(5.2f)) * 0.022f
            else -> sin(clock * TAU / per(if (mood == BlobMood.Listening) 2.6f else 3.8f)) * 0.012f
        }
    }

    /** The lid during a blink, [bt] seconds in: shut fast, open a little slower. */
    private fun blinkLid(bt: Float): Float = when {
        bt < 0.075f -> 1 - bt / 0.075f * 0.94f
        bt < 0.1f -> 0.06f
        bt < 0.24f -> 0.06f + 0.94f * (1 - (1 - (bt - 0.1f) / 0.14f).pow(3))
        else -> 1f
    }

    // ---- Derived geometry the painter needs (computed from the springs, as _render does). ----

    class Frame {
        var sx = 1f; var sy = 1f
        var bodyX = 0f; var bodyY = 0f; var tilt = 0f
        var dragLen = 0f; var dragAngle = 0f; var dragEx = 0f
        var lift = 0f
        val eyeX = FloatArray(2); val eyeY = FloatArray(2); val eyeFs = FloatArray(2)
    }

    fun frame(out: Frame) {
        val sq = s + breath + pulse * 0.05f
        out.sy = 1 + sq
        out.sx = 1 - sq * 0.62f
        out.dragLen = hypot(ox, oy)
        out.dragAngle = (atan2(oy, ox) * 180 / PI).toFloat()
        out.dragEx = out.dragLen * 0.0055f
        out.bodyX = ox * 0.55f + shakeX + bx * 3f
        out.bodyY = oy * 0.55f - hop + bob
        out.tilt = tilt + bx * 2.5f
        out.lift = max(0f, hop - bob + max(0f, -oy * 0.5f))
        // The eyes ride a sphere that turns toward the gaze.
        val top = BlobShapes.top
        val rs = BlobShapes.hw * 1.05f
        val yaw = gx * 0.5f
        val pit = gy * 0.38f
        val cy = BlobShapes.fy - (top + 90f) / 2
        for (i in 0..1) {
            val side = if (i == 1) 1f else -1f
            val a = asin((BlobShapes.sp / rs).coerceIn(-0.95f, 0.95f)) * side
            val b = asin((cy / rs).coerceIn(-0.9f, 0.9f))
            val vx = sin(a) * cos(b)
            val vy = sin(b)
            val vz = cos(a) * cos(b)
            val x1 = vx * cos(yaw) + vz * sin(yaw)
            val z1 = -vx * sin(yaw) + vz * cos(yaw)
            val y2 = vy * cos(pit) + z1 * sin(pit)
            val z2 = -vy * sin(pit) + z1 * cos(pit)
            out.eyeX[i] = x1 * rs
            out.eyeY[i] = (top + 90f) / 2 + y2 * rs
            out.eyeFs[i] = 0.5f + 0.5f * z2.coerceIn(0f, 1f)
        }
    }
}
