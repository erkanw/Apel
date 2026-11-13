package io.github.erkanw.vault.mascot

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Draws the blob as it was designed in Blob Atelier: the cloud shape, milk colour, frosted glass finish, capsule eyes, no cheeks.
 * The layers follow agent-mascot.js (base at 0.78, three soft coloured orbs, a white frost veil, grain in soft light, a blurred
 * white edge, a highlight, a white rim). Blurred discs are radial gradients with a Gaussian profile, which is exact for a
 * blurred circle and cheap. The parts that never move against the body (frost, grain, blurred edge) are drawn once into
 * a bitmap per size, so a frame costs a few gradients and paths.
 */
internal class BlobPainter(private val lite: Boolean) {
    /** The listening halo's size: the widget draws it smaller, so it stays inside its cropped frame. */
    var haloScale = 1f

    /**
     * On a light page: a white rim would vanish, so the edge is a faint ink line instead (the frosted body itself is the
     * same). The ground shadow's colour comes from the caller.
     */
    var onLight = false

    private val body: Path = BlobShapes.newPath()
    private val star: Path = BlobShapes.newStar()
    private val scratch = Path()
    private val rect = RectF()
    private val m = Matrix()

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** White at 0.55 with a soft edge, as the original's blurred highlight ellipse (unit circle, stretched when drawn). */
    private val highlightShader = RadialGradient(
        0f, 0f, 1f,
        intArrayOf(0x8CFFFFFF.toInt(), 0x8CFFFFFF.toInt(), 0x40FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.55f, 0.8f, 1f),
        Shader.TileMode.CLAMP,
    )

    private var overlay: Bitmap? = null
    private var overlayUnit = 0f

    /**
     * The frost veil (white 0.2), the grain and the blurred white inner edge, clipped to the body, at the current pixel
     * scale. Made once per size on a software canvas, where the blur and soft-light blend are exact.
     */
    private fun glassOverlay(unit: Float): Bitmap {
        overlay?.let { if (abs(overlayUnit - unit) < 0.01f) return it }
        val px = (BlobShapes.UNIT_BOX * unit).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val oc = Canvas(bmp)
        oc.translate(BlobShapes.ORIGIN_X * unit, BlobShapes.ORIGIN_Y * unit)
        oc.scale(unit, unit)
        oc.clipPath(body)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = 0x33FFFFFF // frost 0.2
        oc.drawRect(-160f, -165f, 160f, 155f, p)
        if (!lite) {
            // Grain: fine grey noise; over near-white glass soft light reduces to a faint mottling.
            val g = Paint().apply {
                shader = BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
                    it.setLocalMatrix(Matrix().apply { setScale(1 / unit, 1 / unit) })
                }
                alpha = (0.06f * 255).toInt()
            }
            oc.drawRect(-160f, -165f, 160f, 155f, g)
        }
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFFFFFFFF.toInt()
            alpha = (0.55f * 255).toInt()
            strokeWidth = 10f
            maskFilter = BlurMaskFilter(4f * 1.7f, BlurMaskFilter.Blur.NORMAL)
        }
        oc.drawPath(body, s)
        overlay?.recycle()
        overlay = bmp
        overlayUnit = unit
        return bmp
    }

    /** Orbs: base colour, then the Gaussian falloff of a disc of radius 1.15 r blurred by sigma 10 units. */
    private val orbShaders = arrayOfNulls<RadialGradient>(3)
    private val orbRadius = FloatArray(3)

    private val frame = BlobEngine.Frame()
    private var shadowShader: RadialGradient? = null
    private var shadowShaderColor = 0

    /** A soft white halo (unit circle), for listening. */
    private val haloShader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)

    fun draw(c: Canvas, e: BlobEngine, unit: Float, shadowColor: Int, errorColor: Int = ERROR_RED) {
        e.frame(frame)
        val f = frame
        val top = BlobShapes.top
        val hw = BlobShapes.hw
        val bigH = 90f - top
        val cyB = (top + 90f) / 2
        val t = e.clock

        c.save()
        c.translate(BlobShapes.ORIGIN_X * unit, BlobShapes.ORIGIN_Y * unit)
        c.scale(unit, unit)

        // Ground shadow (soft radial ellipse, shrinking as Blob leaves the ground).
        val lift = f.lift
        val srx = hw * 0.9f * f.sx * (1 - lift / 90f)
        val sry = 12f * (1 - lift / 110f)
        if (srx > 0 && sry > 0) {
            val op = (1 - lift / 70f).coerceIn(0.3f, 1f)
            c.save()
            c.translate(f.bodyX * 0.6f, 95f)
            c.scale(srx, sry)
            if (shadowShader == null || shadowShaderColor != shadowColor) {
                shadowShader = RadialGradient(0f, 0f, 1f, intArrayOf(shadowColor, alphaOf(shadowColor, 0f)), null, Shader.TileMode.CLAMP)
                shadowShaderColor = shadowColor
            }
            fill.shader = shadowShader
            fill.alpha = (op * 255).toInt()
            c.drawCircle(0f, 0f, 1f, fill)
            fill.shader = null
            c.restore()
        }

        // Working: a small satellite orbits. Unlike the original it always passes behind the body, so it never lands on
        // the face in a still frame; it shows beside Blob and disappears behind it.
        val working = e.mood == BlobMood.Working
        val an = t * e.hz(3.4f)
        if (working) drawSatellite(c, an, hw, cyB, f)

        // Listening: soft light gathering in toward Blob (two halos shrinking in turn), behind the body and following it.
        // The original draws outlines here; on a dark ground they read as a cut line, so this keeps the motion, not the line.
        if (e.mood == BlobMood.Listening) {
            for (i in 0..1) {
                val u = (t / e.per(1.538f) + i * 0.5f) % 1f
                val sc = (1.42f - 0.32f * u) * haloScale
                val a = sin(PI * u).pow(1.5).toFloat() * 0.16f
                c.save()
                c.translate(f.bodyX, f.bodyY + cyB)
                c.scale(hw * sc, (90f - top) * 0.62f * sc)
                fill.shader = haloShader
                fill.alpha = (a * 255).toInt()
                c.drawCircle(0f, 0f, 1f, fill)
                fill.shader = null
                c.restore()
            }
        }

        // ---- Body ----
        c.save()
        c.translate(f.bodyX, f.bodyY)
        c.translate(0f, 90f)
        c.rotate(f.tilt)
        c.scale(f.sx, f.sy)
        c.translate(0f, -90f)
        if (f.dragLen > 0.3f) {
            c.rotate(f.dragAngle)
            c.scale(1 + f.dragEx, 1 / (1 + f.dragEx))
            c.rotate(-f.dragAngle)
        }

        // Base: milk lifted toward white, a little see-through.
        fill.color = BASE
        fill.alpha = (0.78f * 255).toInt()
        c.drawPath(body, fill)

        c.save()
        c.clipPath(body)
        // Three coloured orbs drifting slowly, counter-moving with the body for depth.
        val orbs = ORB_POS
        for (i in 0..2) {
            val px = orbs[i][0] * hw + sin(t * e.hz(0.5f) + i * 2) * 7f
            val py = (if (i == 2) 90f - bigH * 0.02f else top + bigH * orbs[i][1]) + cos(t * e.hz(0.4f) + i) * 6f
            val r = orbs[i][2] * hw * 1.15f
            drawBlurredDisc(c, i, px * 0.92f - f.bodyX, py - f.bodyY * 0.9f, r, 10f, ORB_COLORS[i], 0.85f)
        }
        // Frost veil, grain and the soft white inner edge never move with respect to the body: drawn once into a bitmap.
        c.drawBitmap(glassOverlay(unit), null, BOX, bitmapPaint)
        // Highlight, sliding against the gaze: an ellipse with a blurred edge (a stretched radial gradient).
        val hx = -e.bx * 7f
        val hy = -e.by * 5f
        c.save()
        c.translate(-hw * 0.3f + hx, top + bigH * 0.16f + hy)
        c.scale(hw * 0.45f + 6f, bigH * 0.08f + 6f)
        fill.shader = highlightShader
        fill.alpha = 255
        c.drawCircle(0f, 0f, 1f, fill)
        fill.shader = null
        c.restore()
        c.restore()

        // The rim: white on a dark page, a faint ink line on a light one.
        stroke.color = if (onLight) INK else 0xFFFFFFFF.toInt()
        stroke.alpha = ((if (onLight) 0.11f else 0.9f) * 255).toInt()
        stroke.strokeWidth = if (onLight) 1.2f else 1.4f
        c.drawPath(body, stroke)

        drawFace(c, e, f)
        c.restore() // body

        // Thinking: three thought dots float up beside the head.
        if (e.mood == BlobMood.Thinking) {
            fill.color = DOTS
            for (i in 0..2) {
                val dx = hw * 0.62f + i * 15f + f.bodyX
                val dy = top - 6f - i * 15f + f.bodyY + sin(t * e.hz(3.2f) - i * 0.8f) * 2.5f
                val a = (e.moodT * 3f - i * 0.6f).coerceIn(0f, 1f) * (0.45f + 0.55f * (0.5f + 0.5f * sin(t * e.hz(3.2f) - i * 0.9f)))
                fill.alpha = (a * 255).toInt()
                c.drawCircle(dx, dy, 4.5f + i * 2f, fill)
            }
        }

        // Done / error badge.
        val bs = max(0f, e.badge)
        if (bs > 0.001f) {
            val error = e.mood == BlobMood.Error
            c.save()
            c.translate(hw * 0.68f + f.bodyX, top + 8f + f.bodyY)
            c.scale(bs, bs)
            val ba = (bs * 1.4f).coerceIn(0f, 1f)
            fill.color = if (error) errorColor else DONE_GREEN
            fill.alpha = (ba * 255).toInt()
            c.drawCircle(0f, 0f, 15f, fill)
            stroke.color = 0xFFFFFFFF.toInt()
            stroke.alpha = (ba * 255).toInt()
            stroke.strokeWidth = 3.6f
            scratch.reset()
            if (error) {
                scratch.moveTo(0f, -7.5f); scratch.lineTo(0f, 1.5f)
                c.drawPath(scratch, stroke)
                fill.color = 0xFFFFFFFF.toInt(); fill.alpha = (ba * 255).toInt()
                c.drawCircle(0f, 6.6f, 2.1f, fill)
            } else {
                scratch.moveTo(-6f, 0.5f); scratch.lineTo(-1.8f, 4.6f); scratch.lineTo(6.2f, -4.2f)
                c.drawPath(scratch, stroke)
            }
            c.restore()
        }

        // Sparkles.
        fill.color = SPARK
        for (q in e.sparks) {
            if (q.age < 0) continue
            val life = (q.age / 0.8f).coerceIn(0f, 1f)
            val sc = max(0.01f, sin(PI.toFloat() * life) * q.size)
            val rad = q.d + life * 14f
            c.save()
            c.translate(cos(q.a) * rad + f.bodyX, cyB + sin(q.a) * rad * 0.95f + f.bodyY)
            c.rotate(life * 90f)
            c.scale(sc, sc)
            c.drawPath(star, fill)
            c.restore()
        }
        c.restore()
    }

    private fun drawSatellite(c: Canvas, an: Float, hw: Float, cyB: Float, f: BlobEngine.Frame) {
        val ox = cos(an) * (hw + 22f)
        val oy = sin(an) * 20f
        val rot = (-14 * PI / 180).toFloat()
        val zx = ox * cos(rot) - oy * sin(rot) + f.bodyX
        val zy = ox * sin(rot) + oy * cos(rot) + cyB + f.bodyY + 8f
        // Seen only beside Blob: as it passes behind the body it fades out (the glass is see-through, so drawing it
        // behind is not enough to keep it off the face).
        val dx = (zx - f.bodyX) / BlobShapes.hw
        val dy = (zy - f.bodyY - cyB) / ((90f - BlobShapes.top) / 2f)
        val outside = ((dx * dx + dy * dy) - 1.0f) / 0.35f
        val a = outside.coerceIn(0f, 1f)
        if (a <= 0f) return
        fill.color = DOTS
        fill.alpha = (a * 255).toInt()
        c.drawCircle(zx, zy, 5f + 1.8f * sin(an), fill)
    }

    private fun drawFace(c: Canvas, e: BlobEngine, f: BlobEngine.Frame) {
        val ew = 15f * e.eye
        val eh = 30f * e.eye
        val lidS = e.lidS.coerceIn(0.04f, 1.2f)
        val xe = e.expr.coerceIn(0f, 1f)
        for (i in 0..1) {
            val side = if (i == 1) 1f else -1f
            val x = f.eyeX[i]
            val y = f.eyeY[i]
            val fs = f.eyeFs[i]
            val w = ew * fs
            val hh = eh * lidS
            if (xe < 1f) {
                val a = 1f - xe
                fill.color = EYE
                fill.alpha = (a * 255).toInt()
                val rr = min(w, hh) / 2
                rect.set(x - w / 2, y - hh / 2, x + w / 2, y + hh / 2)
                c.drawRoundRect(rect, rr, rr, fill)
                if (lidS >= 0.5f) {
                    fill.color = 0xFFFFFFFF.toInt()
                    fill.alpha = (0.9f * a * 255).toInt()
                    c.drawCircle(x - w * 0.2f, y - hh * 0.24f, min(w, hh) * 0.16f, fill)
                }
            }
            if (xe > 0f) {
                val xw = 11f * fs * e.eye
                val xh = 9f * e.eye
                scratch.reset()
                if (e.chevrons) {
                    scratch.moveTo(x - side * xw * 0.55f, y - xh * 0.75f)
                    scratch.lineTo(x + side * xw * 0.45f, y)
                    scratch.lineTo(x - side * xw * 0.55f, y + xh * 0.75f)
                } else {
                    scratch.moveTo(x - xw * 0.7f, y + xh * 0.35f)
                    scratch.quadTo(x, y - xh * 1.25f, x + xw * 0.7f, y + xh * 0.35f)
                }
                val ps = 0.85f + 0.15f * xe
                c.save()
                c.translate(x, y); c.scale(ps, ps); c.translate(-x, -y)
                stroke.color = EYE
                stroke.alpha = (xe * 255).toInt()
                stroke.strokeWidth = 7f * e.eye
                c.drawPath(scratch, stroke)
                c.restore()
            }
        }
    }

    /** A disc of radius [r] blurred by [sigma]: a radial gradient with the Gaussian edge profile (0.5 erfc). */
    private fun drawBlurredDisc(c: Canvas, slot: Int, cx: Float, cy: Float, r: Float, sigma: Float, color: Int, alpha: Float) {
        val outer = r + 3f * sigma
        if (orbShaders[slot] == null || abs(orbRadius[slot] - r) > 0.5f) {
            val n = 9
            val pos = FloatArray(n + 1)
            val cols = IntArray(n + 1)
            val inner = max(0f, r - 3f * sigma)
            pos[0] = 0f
            cols[0] = alphaOf(color, alpha * edge(0f, r, sigma))
            for (k in 1..n) {
                val d = inner + (outer - inner) * (k - 1) / (n - 1)
                pos[k] = (d / outer).coerceIn(0f, 1f)
                cols[k] = alphaOf(color, alpha * edge(d, r, sigma))
            }
            orbShaders[slot] = RadialGradient(0f, 0f, outer, cols, pos, Shader.TileMode.CLAMP)
            orbRadius[slot] = r
        }
        m.reset(); m.setTranslate(cx, cy)
        val sh = orbShaders[slot]!!
        sh.setLocalMatrix(m)
        fill.shader = sh
        fill.alpha = 255
        c.drawCircle(cx, cy, outer, fill)
        fill.shader = null
    }

    companion object {
        private val BASE = 0xFFF8F5F0.toInt()             // milk #F2ECE4 mixed 45% toward white
        private val ORB_COLORS = intArrayOf(0xFFEDDECA.toInt(), 0xFFEDF4D8.toInt(), 0xFFF5E1E5.toInt()) // milk hue-shifted 0, +40, -45 deg
        private val ORB_POS = arrayOf(floatArrayOf(-0.75f, 0.3f, 0.55f), floatArrayOf(0.72f, 0.62f, 0.5f), floatArrayOf(-0.05f, 0f, 0.4f))
        private val EYE = 0xFF1E1C24.toInt()
        /** The original's shadow ink (#2a2140), for the rim on a light page. */
        private val INK = 0xFF2A2140.toInt()
        private val DOTS = 0xFFA59CA6.toInt()
        private val SPARK = 0xFFFF8FB3.toInt()
        private val DONE_GREEN = 0xFF30C77B.toInt()
        internal val ERROR_RED = 0xFFFF5A4E.toInt()

        private val BOX = RectF(-160f, -165f, 160f, 155f)

        private fun alphaOf(color: Int, a: Float): Int {
            val base = (color ushr 24) and 0xFF
            val na = (base * a.coerceIn(0f, 1f)).toInt()
            return (color and 0x00FFFFFF) or (na shl 24)
        }

        /** Coverage of a blurred disc edge at distance [d] from the centre. */
        private fun edge(d: Float, r: Float, sigma: Float): Float = (0.5 * erfc((d - r) / (sigma * 1.41421356))).toFloat()

        private fun erfc(x: Double): Double {
            // Abramowitz and Stegun 7.1.26, good to 1e-7.
            val z = abs(x)
            val tt = 1 / (1 + 0.5 * z)
            val r = tt * exp(-z * z - 1.26551223 + tt * (1.00002368 + tt * (0.37409196 + tt * (0.09678418 + tt * (-0.18628806 +
                tt * (0.27886807 + tt * (-1.13520398 + tt * (1.48851587 + tt * (-0.82215223 + tt * 0.17087277)))))))))
            return if (x >= 0) r else 2 - r
        }

        /** Fine grey noise for the frosted grain, made once. */
        private val grainBitmap: Bitmap by lazy {
            val size = 128
            val rnd = Random(9)
            val px = IntArray(size * size) {
                val v = (100 + rnd.nextInt(56) + rnd.nextInt(56)).coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
