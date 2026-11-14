package io.github.erkanw.vault.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * One body shape in the original's units: [points] (N points as x0, y0, …), the body's [top] (negative), half its width
 * [hw], the eye line [fy] and half the eye spacing [sp]. Every shape is scaled to the same area with its bottom on y = 90.
 */
internal class BlobGeometry(val points: FloatArray, val top: Float, val hw: Float, val fy: Float, val sp: Float)

/*
 * The eight Blobs-series shapes of Blob Atelier by Victoria Wu (MIT, https://github.com/victoriawu-77/blob-atelier),
 * ported from agent-mascot.js (SHAPE_FN, FACE, GEO, pathD). The drawing box is 320 x 320, x from -160 to 160, y from -165
 * to 155. All shapes share the parametrisation (N points at the same angles), so one morphs into another point by point.
 */
internal object BlobShapes {
    const val N = 72
    const val UNIT_BOX = 320f
    const val ORIGIN_X = 160f
    const val ORIGIN_Y = 165f

    private fun sp(v: Double, p: Double) = sign(v) * abs(v).pow(p)
    private fun se(t: Double, a: Double, b: Double, n: Double) = doubleArrayOf(a * sp(cos(t), 2 / n), b * sp(sin(t), 2 / n))

    private fun fn(shape: BlobShape, t: Double): DoubleArray = when (shape) {
        BlobShape.Mochi -> { val top = sin(t) < 0; val p = se(t, 1.0, if (top) 1.0 else 0.52, if (top) 2.3 else 3.6); p[0] *= 1 + 0.1 * p[1]; p }
        BlobShape.Bean -> { val p = se(t, 0.8, 1.0, 2.2); p[0] += 0.15 * (p[1] * p[1] - 0.42); p }
        BlobShape.Pebble -> {
            val p = se(t, 1.1, 0.8, 2.15); val r = 1 + 0.035 * cos(2 * t + 0.7) + 0.022 * cos(3 * t + 2.1)
            p[0] *= r; p[1] *= r; if (p[1] > 0) p[1] *= 0.9; p
        }
        BlobShape.Squircle -> se(t, 0.95, 0.9, 4.2)
        BlobShape.Pill -> { val c = cos(t); val s = sin(t); doubleArrayOf(0.66 * c, 0.66 * s + 0.36 * tanh(s * 4.5)) }
        BlobShape.Drop -> {
            val c = cos(t); val s = sin(t); val k = max(0.0, -s)
            doubleArrayOf(0.95 * c * (1 - 0.42 * k.pow(2.4)), (if (s > 0) 0.84 else 0.95) * s - 0.5 * k.pow(4))
        }
        BlobShape.Kitty -> {
            val s = sin(t); val top = s < 0
            val p = se(t, 1.04, if (top) 0.8 else 0.5, if (top) 2.1 else 3.8)
            val d1 = t - (1.5 * PI - 0.62); val d2 = t - (1.5 * PI + 0.62)
            val e = exp(-d1 * d1 / 0.03) + exp(-d2 * d2 / 0.03)
            p[1] -= 0.2 * e; p[0] *= 1 - 0.06 * e; p
        }
        BlobShape.Cloud -> {
            val s = sin(t); val c = cos(t); val top = s < 0
            val p = se(t, 1.08, if (top) 0.98 else 0.5, if (top) 2.1 else 4.0)
            val phi = atan2(c, -s); val w = ((-s + 0.3) / 0.55).coerceIn(0.0, 1.0)
            val b = 1 + 0.11 * (abs(cos(2.5 * phi)) - 0.62) * w
            doubleArrayOf(p[0] * b, p[1] * b)
        }
    }

    /** FACE: where the eye line sits (fraction of the height from the top) and the eye spacing (fraction of half the width). */
    private val face = mapOf(
        BlobShape.Mochi to (0.53 to 0.34), BlobShape.Bean to (0.44 to 0.37), BlobShape.Pebble to (0.5 to 0.33), BlobShape.Squircle to (0.5 to 0.34),
        BlobShape.Pill to (0.4 to 0.42), BlobShape.Drop to (0.6 to 0.36), BlobShape.Kitty to (0.58 to 0.32), BlobShape.Cloud to (0.54 to 0.32),
    )

    private val cache = HashMap<BlobShape, BlobGeometry>()

    fun of(shape: BlobShape): BlobGeometry = synchronized(cache) { cache.getOrPut(shape) { build(shape) } }

    private fun build(shape: BlobShape): BlobGeometry {
        val p = DoubleArray(N * 2)
        for (i in 0 until N) {
            val q = fn(shape, i.toDouble() / N * 2 * PI)
            p[2 * i] = q[0]; p[2 * i + 1] = q[1]
        }
        var a = 0.0; var cx = 0.0
        for (i in 0 until N) {
            val j = (i + 1) % N
            val cr = p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
            a += cr; cx += (p[2 * i] + p[2 * j]) * cr
        }
        a /= 2; cx /= 6 * a
        val sc = sqrt(PI * 0.9 * 0.9 / abs(a))
        var maxY = -9.0; var minY = 9.0; var halfW = 0.0
        for (i in 0 until N) { p[2 * i] = (p[2 * i] - cx) * sc; p[2 * i + 1] *= sc; maxY = max(maxY, p[2 * i + 1]) }
        for (i in 0 until N) { p[2 * i + 1] += 0.9 - maxY; minY = min(minY, p[2 * i + 1]); halfW = max(halfW, abs(p[2 * i])) }
        val s = 100.0
        val (fFy, fSp) = face.getValue(shape)
        return BlobGeometry(
            points = FloatArray(N * 2) { (p[it] * s).toFloat() },
            top = (minY * s).toFloat(),
            hw = (halfW * s).toFloat(),
            fy = ((minY + (0.9 - minY) * fFy) * s).toFloat(),
            sp = (halfW * s * fSp).toFloat(),
        )
    }

    /** Sets [path] to the smooth closed curve through [points] (pathD in the original). */
    fun fillPath(path: android.graphics.Path, points: FloatArray) {
        path.reset()
        val q = points
        path.moveTo(q[0], q[1])
        for (i in 0 until N) {
            val ia = (i - 1 + N) % N; val ic = (i + 1) % N; val ie = (i + 2) % N
            val x1 = q[2 * i]; val y1 = q[2 * i + 1]; val x2 = q[2 * ic]; val y2 = q[2 * ic + 1]
            path.cubicTo(x1 + (x2 - q[2 * ia]) / 6, y1 + (y2 - q[2 * ia + 1]) / 6, x2 - (q[2 * ie] - x1) / 6, y2 - (q[2 * ie + 1] - y1) / 6, x2, y2)
        }
        path.close()
    }

    fun newPath(shape: BlobShape): android.graphics.Path = android.graphics.Path().also { fillPath(it, of(shape).points) }

    /** The four-pointed sparkle, unit size. */
    fun newStar(): android.graphics.Path = android.graphics.Path().apply {
        moveTo(0f, -1f)
        cubicTo(0.12f, -0.12f, 0.12f, -0.12f, 1f, 0f)
        cubicTo(0.12f, 0.12f, 0.12f, 0.12f, 0f, 1f)
        cubicTo(-0.12f, 0.12f, -0.12f, 0.12f, -1f, 0f)
        cubicTo(-0.12f, -0.12f, -0.12f, -0.12f, 0f, -1f)
        close()
    }
}
