package io.github.erkanw.vault.mascot

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Blob's body: the "cloud" shape of Blob Atelier by Victoria Wu (MIT, https://github.com/victoriawu-77/blob-atelier),
 * ported from agent-mascot.js. Everything is in the original's units: the drawing box is 320 x 320, x from -160 to 160,
 * y from -165 to 155, the body stands on y = 90.
 */
internal object BlobShapes {
    const val N = 72
    const val UNIT_BOX = 320f
    const val ORIGIN_X = 160f
    const val ORIGIN_Y = 165f

    /** The outline, N points as x0, y0, x1, y1, ... */
    val points: FloatArray

    /** Top of the body (negative, above the origin). */
    val top: Float

    /** Half the body's width. */
    val hw: Float

    /** The eye line and half the spacing between the eyes. */
    val fy: Float
    val sp: Float

    /** The outline as cubic segments: for each point i, the two control points and the next point (6 floats). */
    val cubics: FloatArray

    private fun spow(v: Double, p: Double) = sign(v) * abs(v).pow(p)

    private fun cloud(t: Double): Pair<Double, Double> {
        val s = sin(t)
        val c = cos(t)
        val up = s < 0
        val n = if (up) 2.1 else 4.0
        val x = 1.08 * spow(c, 2 / n)
        val y = (if (up) 0.98 else 0.5) * spow(s, 2 / n)
        val phi = atan2(c, -s)
        val w = ((-s + 0.3) / 0.55).coerceIn(0.0, 1.0)
        val b = 1 + 0.11 * (abs(cos(2.5 * phi)) - 0.62) * w
        return x * b to y * b
    }

    init {
        val p = DoubleArray(N * 2)
        for (i in 0 until N) {
            val (x, y) = cloud(i.toDouble() / N * 2 * PI)
            p[2 * i] = x
            p[2 * i + 1] = y
        }
        var a = 0.0
        var cx = 0.0
        for (i in 0 until N) {
            val j = (i + 1) % N
            val cr = p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
            a += cr
            cx += (p[2 * i] + p[2 * j]) * cr
        }
        a /= 2
        cx /= 6 * a
        val sc = sqrt(PI * 0.9 * 0.9 / abs(a))
        var maxY = -9.0
        var minY = 9.0
        var halfW = 0.0
        for (i in 0 until N) {
            p[2 * i] = (p[2 * i] - cx) * sc
            p[2 * i + 1] *= sc
            maxY = max(maxY, p[2 * i + 1])
        }
        for (i in 0 until N) {
            p[2 * i + 1] += 0.9 - maxY
            minY = min(minY, p[2 * i + 1])
            halfW = max(halfW, abs(p[2 * i]))
        }
        val s = 100.0
        points = FloatArray(N * 2) { (p[it] * s).toFloat() }
        top = (minY * s).toFloat()
        hw = (halfW * s).toFloat()
        fy = ((minY + (0.9 - minY) * 0.54) * s).toFloat()
        sp = (halfW * s * 0.32).toFloat()

        // A smooth closed curve through every point (as pathD in the original).
        cubics = FloatArray(N * 6)
        val q = points
        for (i in 0 until N) {
            val ia = (i - 1 + N) % N
            val ic = (i + 1) % N
            val ie = (i + 2) % N
            val x1 = q[2 * i]
            val y1 = q[2 * i + 1]
            val x2 = q[2 * ic]
            val y2 = q[2 * ic + 1]
            cubics[6 * i] = x1 + (x2 - q[2 * ia]) / 6
            cubics[6 * i + 1] = y1 + (y2 - q[2 * ia + 1]) / 6
            cubics[6 * i + 2] = x2 - (q[2 * ie] - x1) / 6
            cubics[6 * i + 3] = y2 - (q[2 * ie + 1] - y1) / 6
            cubics[6 * i + 4] = x2
            cubics[6 * i + 5] = y2
        }
    }

    /** A new android Path of the outline, in units. */
    fun newPath(): android.graphics.Path = android.graphics.Path().apply {
        moveTo(points[0], points[1])
        for (i in 0 until N) {
            cubicTo(cubics[6 * i], cubics[6 * i + 1], cubics[6 * i + 2], cubics[6 * i + 3], cubics[6 * i + 4], cubics[6 * i + 5])
        }
        close()
    }

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
