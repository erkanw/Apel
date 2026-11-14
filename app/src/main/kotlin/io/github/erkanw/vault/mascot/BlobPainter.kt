package io.github.erkanw.vault.mascot

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
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
 * Draws the mascot in any look of Blob Atelier's Blobs series (handbook §4.5): the shape the engine is morphing, one of
 * six finishes, dot or capsule eyes, blush. Ported from agent-mascot.js: `_build` and `_buildMaterial` (the layers),
 * `_paint` (their colours) and `_render` (where the moving parts go). SVG blurs become Gaussian-profile radial gradients
 * for discs and ellipses, and BlurMaskFilter for the stroked edges; SVG's objectBoundingBox gradients become unit-space
 * gradients mapped onto the body's bounds. The layers that never move against the body (frost, edges, clay's light and
 * shade, velvet's sheen, jelly's glow) are drawn once into a bitmap per look, size and shape.
 */
internal class BlobPainter(private val lite: Boolean) {
    /** The listening halo's size: the widget draws it smaller, so it stays inside its cropped frame. */
    var haloScale = 1f

    /** On a light page the glass finish's white rim would vanish: it becomes a faint ink line. */
    var onLight = false

    /** The look to draw. Colours follow it at once; the shape follows the engine (which morphs). */
    var look: BlobLook = BlobLook.Default
        set(v) {
            if (v != field) { field = v; colors = Colors.of(v); overlayKey = null }
        }

    private var colors = Colors.of(look)

    private val body = Path()
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
    private val grainPaint = Paint().apply { blendMode = BlendMode.SOFT_LIGHT }

    private val frame = BlobEngine.Frame()
    private var shadowShader: RadialGradient? = null
    private var shadowShaderColor = 0

    /** A soft white halo (unit circle), for listening. */
    private val haloShader = RadialGradient(0f, 0f, 1f, intArrayOf(0xFFFFFFFF.toInt(), 0x66FFFFFF, 0x00FFFFFF), floatArrayOf(0.55f, 0.8f, 1f), Shader.TileMode.CLAMP)

    fun draw(c: Canvas, e: BlobEngine, unit: Float, shadowColor: Int, errorColor: Int = ERROR_RED) {
        e.frame(frame)
        val f = frame
        val L = colors
        val finish = look.finish
        val top = e.top
        val hw = e.hw
        val bigH = 90f - top
        val cyB = (top + 90f) / 2
        val t = e.clock
        BlobShapes.fillPath(body, e.points)

        c.save()
        c.translate(BlobShapes.ORIGIN_X * unit, BlobShapes.ORIGIN_Y * unit)
        c.scale(unit, unit)

        // Ground shadow (soft radial ellipse, shrinking as the blob leaves the ground), darker under clay, fainter under glass.
        val lift = f.lift
        val srx = hw * 0.9f * f.sx * (1 - lift / 90f)
        val sry = 12f * (1 - lift / 110f)
        if (srx > 0 && sry > 0) {
            val op = (1 - lift / 70f).coerceIn(0.3f, 1f) * L.shadow
            c.save()
            c.translate(f.bodyX * 0.6f, 95f)
            c.scale(srx, sry)
            if (shadowShader == null || shadowShaderColor != shadowColor) {
                shadowShader = RadialGradient(0f, 0f, 1f, intArrayOf(shadowColor, alphaOf(shadowColor, 0f)), null, Shader.TileMode.CLAMP)
                shadowShaderColor = shadowColor
            }
            fill.shader = shadowShader
            fill.alpha = (op.coerceAtMost(1f) * 255).toInt()
            c.drawCircle(0f, 0f, 1f, fill)
            fill.shader = null
            c.restore()
        }

        // Working: a small satellite orbits, always behind the body, so it never lands on the face in a still frame.
        val an = t * e.hz(3.4f)
        if (e.mood == BlobMood.Working) drawSatellite(c, an, hw, top, cyB, f)

        // Listening: soft light gathering in toward the blob (two halos shrinking in turn), behind the body.
        if (e.mood == BlobMood.Listening) {
            for (i in 0..1) {
                val u = (t / e.per(1.538f) + i * 0.5f) % 1f
                val sc = (1.42f - 0.32f * u) * haloScale
                val a = sin(PI * u).pow(1.5).toFloat() * 0.16f
                c.save()
                c.translate(f.bodyX, f.bodyY + cyB)
                c.scale(hw * sc, bigH * 0.62f * sc)
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

        // The base: flat, a gradient over the body's bounds, or (glass) milk lifted toward white and a little see-through.
        bounds(e.points, top)
        fill.shader = baseShader(finish, L)
        fill.color = if (finish == BlobFinish.Glass) L.glassBase else L.base
        if (fill.shader == null) fill.alpha = if (finish == BlobFinish.Glass) (0.78f * 255).toInt() else 255
        else fill.alpha = 255
        c.drawPath(body, fill)
        fill.shader = null

        // The finish, clipped to the body.
        c.save()
        c.clipPath(body)
        val hx = -e.bx * 7f
        val hy = -e.by * 5f
        if (finish == BlobFinish.Glass) {
            for (i in 0..2) {
                val px = ORB_POS[i][0] * hw + sin(t * e.hz(0.5f) + i * 2) * 7f
                val py = (if (i == 2) 90f - bigH * 0.02f else top + bigH * ORB_POS[i][1]) + cos(t * e.hz(0.4f) + i) * 6f
                val r = ORB_POS[i][2] * hw * 1.15f
                blurredEllipse(c, px * 0.92f - f.bodyX, py - f.bodyY * 0.9f, r, r, 10f, L.orbs[i], 0.85f)
            }
        }
        overlay(unit, e)?.let { c.drawBitmap(it, null, BOX, bitmapPaint) }
        when (finish) {
            BlobFinish.Soft -> blurredEllipse(c, -hw * 0.3f + hx, top + bigH * 0.25f + hy, hw * 0.55f, bigH * 0.2f, 10f, WHITE, 0.28f)
            BlobFinish.Jelly -> {
                val jx = -hw * 0.42f + hx
                val jy = top + bigH * 0.24f + hy
                c.save()
                c.rotate(-32f, jx, jy)
                blurredEllipse(c, jx, jy, hw * 0.26f, bigH * 0.11f, 2f, WHITE, 0.92f)
                c.restore()
                fill.color = WHITE
                fill.alpha = (0.95f * 255).toInt()
                c.drawCircle(-hw * 0.14f + hx * 1.2f, top + bigH * 0.13f + hy, 4.5f, fill)
            }
            BlobFinish.Glass -> blurredEllipse(c, -hw * 0.3f + hx, top + bigH * 0.16f + hy, hw * 0.45f, bigH * 0.08f, 4f, WHITE, 0.55f)
            else -> Unit
        }
        c.restore()

        // A near-black body on a dark page gets a faint light rim, so its silhouette holds (the dark twin of the light-page
        // rule below).
        if (!onLight && L.darkBody && finish != BlobFinish.Glass) {
            stroke.color = WHITE
            stroke.alpha = (0.12f * 255).toInt()
            stroke.strokeWidth = 1.2f
            stroke.maskFilter = null
            c.drawPath(body, stroke)
        }

        // Glass has a rim: white on a dark page, a faint ink line on a light one.
        if (finish == BlobFinish.Glass) {
            stroke.color = if (onLight) INK else WHITE
            stroke.alpha = ((if (onLight) 0.11f else 0.9f) * 255).toInt()
            stroke.strokeWidth = if (onLight) 1.2f else 1.4f
            stroke.maskFilter = null
            c.drawPath(body, stroke)
        }

        drawFace(c, e, f)
        c.restore() // body

        // Thinking: three thought dots float up beside the head.
        if (e.mood == BlobMood.Thinking) {
            fill.color = L.dots
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
            stroke.color = WHITE
            stroke.alpha = (ba * 255).toInt()
            stroke.strokeWidth = 3.6f
            stroke.maskFilter = null
            scratch.reset()
            if (error) {
                scratch.moveTo(0f, -7.5f); scratch.lineTo(0f, 1.5f)
                c.drawPath(scratch, stroke)
                fill.color = WHITE; fill.alpha = (ba * 255).toInt()
                c.drawCircle(0f, 6.6f, 2.1f, fill)
            } else {
                scratch.moveTo(-6f, 0.5f); scratch.lineTo(-1.8f, 4.6f); scratch.lineTo(6.2f, -4.2f)
                c.drawPath(scratch, stroke)
            }
            c.restore()
        }

        // Sparkles.
        fill.color = L.spark
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

    // ---- the body's bounds and the base gradients (SVG objectBoundingBox) ----

    private var bx0 = 0f; private var by0 = 0f; private var bw = 1f; private var bh = 1f

    private fun bounds(p: FloatArray, top: Float) {
        var mn = Float.MAX_VALUE; var mx = -Float.MAX_VALUE
        for (i in 0 until BlobShapes.N) { mn = min(mn, p[2 * i]); mx = max(mx, p[2 * i]) }
        bx0 = mn; by0 = top; bw = max(1f, mx - mn); bh = max(1f, 90f - top)
    }

    private val shaders = HashMap<BlobFinish, Shader>()
    private var shadersFor: Colors? = null

    private fun baseShader(finish: BlobFinish, L: Colors): Shader? {
        if (shadersFor !== L) { shaders.clear(); shadersFor = L }
        val s = when (finish) {
            BlobFinish.Soft -> shaders.getOrPut(finish) { RadialGradient(0.36f, 0.28f, 0.92f, L.softStops, floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP) }
            BlobFinish.Jelly -> shaders.getOrPut(finish) { LinearGradient(0f, 0f, 0.25f, 1f, L.jellyStops, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP) }
            BlobFinish.Velvet -> shaders.getOrPut(finish) { RadialGradient(0.45f, 0.42f, 0.7f, L.velvetStops, floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP) }
            BlobFinish.Clay -> shaders.getOrPut(finish) { RadialGradient(0.4f, 0.3f, 0.95f, L.clayStops, floatArrayOf(0f, 1f), Shader.TileMode.CLAMP) }
            else -> return null
        }
        m.reset(); m.setScale(bw, bh); m.postTranslate(bx0, by0)
        s.setLocalMatrix(m)
        return s
    }

    // ---- the layers that never move against the body, baked per look, size and shape ----

    private var overlay: Bitmap? = null
    private var overlayKey: String? = null

    private fun overlay(unit: Float, e: BlobEngine): Bitmap? {
        val finish = look.finish
        if (finish == BlobFinish.Matte || finish == BlobFinish.Soft) return null
        val px = (BlobShapes.UNIT_BOX * unit).toInt().coerceAtLeast(1)
        val key = "$look/$lite/$px/${e.shape}/${e.points[0]}/${e.points[37]}"
        overlay?.let { if (key == overlayKey) return it }
        val bmp = overlay?.takeIf { it.width == px && !it.isRecycled } ?: Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { overlay?.recycle() }
        bmp.eraseColor(0)
        val oc = Canvas(bmp)
        oc.translate(BlobShapes.ORIGIN_X * unit, BlobShapes.ORIGIN_Y * unit)
        oc.scale(unit, unit)
        oc.clipPath(body)
        val L = colors
        val top = e.top
        val hw = e.hw
        val bigH = 90f - top
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
        // The grain sits in the same clipped group as these layers (an isolated group in the original), so it blends in
        // soft light with them alone: over bare body it simply lays light-grey noise on top.
        fun grain() {
            if (lite) return
            grainPaint.shader = grainShader(unit)
            grainPaint.alpha = (L.grain * 255).toInt()
            oc.drawRect(BOX, grainPaint)
        }
        fun blurred(sigma: Float) = BlurMaskFilter(max(0.5f, sigma * 1.73f), BlurMaskFilter.Blur.NORMAL)
        fun edge(color: Int, width: Float, alpha: Float, sigma: Float, dx: Float = 0f, dy: Float = 0f) {
            s.color = color; s.alpha = (alpha * 255).toInt(); s.strokeWidth = width; s.maskFilter = blurred(sigma)
            oc.save(); oc.translate(dx, dy); oc.drawPath(body, s); oc.restore()
        }
        when (finish) {
            BlobFinish.Jelly -> {
                // A coloured glow low in the body, a deep inner edge, a thin white edge.
                blurredEllipseOn(oc, hw * 0.08f, 90f - bigH * 0.12f, hw * 0.75f, bigH * 0.2f, 10f, L.glow, 0.5f)
                edge(L.deep, 18f, 0.35f, 6.5f)
                edge(WHITE, 4f, 0.4f, 2f)
            }
            BlobFinish.Glass -> {
                p.color = 0x33FFFFFF // frost 0.2
                oc.drawRect(BOX, p)
                grain()
                edge(WHITE, 10f, 0.55f, 4f)
            }
            BlobFinish.Clay -> {
                edge(WHITE, 30f, 0.62f, 6.5f, -9f, -11f)
                edge(L.clayShade, 34f, 0.3f, 6.5f, 10f, 13f)
                grain()
            }
            BlobFinish.Velvet -> {
                edge(L.sheen, 26f, 0.75f, 6.5f)
                edge(WHITE, 6f, 0.28f, 2f)
                grain()
            }
            else -> Unit
        }
        overlay = bmp
        overlayKey = key
        return bmp
    }

    private var grain: BitmapShader? = null
    private var grainUnit = 0f

    private fun grainShader(unit: Float): BitmapShader {
        grain?.let { if (abs(grainUnit - unit) < 0.01f) return it }
        return BitmapShader(grainBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also {
            it.setLocalMatrix(Matrix().apply { setScale(1 / unit, 1 / unit) })
            grain = it; grainUnit = unit
        }
    }

    // ---- the face ----

    private fun drawFace(c: Canvas, e: BlobEngine, f: BlobEngine.Frame) {
        val L = colors
        val cap = look.eyes == BlobEyes.Capsule
        val ew = (if (cap) 15f else 21f) * e.eye
        val eh = (if (cap) 30f else 21f) * e.eye
        val lidS = e.lidS.coerceIn(0.04f, 1.2f)
        val xe = e.expr.coerceIn(0f, 1f)
        for (i in 0..1) {
            val side = if (i == 1) 1f else -1f
            val x = f.eyeX[i]
            val y = f.eyeY[i]
            val fs = f.eyeFs[i]
            // Blush: soft ellipses under and outside the eyes.
            if (look.blush) {
                c.save()
                blurredEllipse(c, x + side * ew * 0.55f, y + (if (cap) eh * 0.5f else eh * 0.62f) + 7f, 12f * fs, 6.5f, 4f, L.cheek, L.cheekAlpha)
                c.restore()
            }
        }
        for (i in 0..1) {
            val side = if (i == 1) 1f else -1f
            val x = f.eyeX[i]
            val y = f.eyeY[i]
            val fs = f.eyeFs[i]
            val w = ew * fs
            val hh = eh * lidS
            if (xe < 1f) {
                val a = 1f - xe
                fill.color = L.eye
                fill.alpha = (a * 255).toInt()
                val rr = min(w, hh) / 2
                rect.set(x - w / 2, y - hh / 2, x + w / 2, y + hh / 2)
                c.drawRoundRect(rect, rr, rr, fill)
                if (lidS >= 0.5f && L.glint) {
                    fill.color = WHITE
                    fill.alpha = (0.9f * a * 255).toInt()
                    c.drawCircle(x - w * 0.2f, y - hh * 0.24f, min(w, hh) * 0.16f, fill)
                }
            }
            if (xe > 0f) {
                val xw = (if (cap) 11f else 12.5f) * fs * e.eye
                val xh = (if (cap) 9f else 8f) * e.eye
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
                stroke.color = L.eye
                stroke.alpha = (xe * 255).toInt()
                stroke.strokeWidth = (if (cap) 7f else 6.2f) * e.eye
                stroke.maskFilter = null
                c.drawPath(scratch, stroke)
                c.restore()
            }
        }
    }

    private fun drawSatellite(c: Canvas, an: Float, hw: Float, top: Float, cyB: Float, f: BlobEngine.Frame) {
        val ox = cos(an) * (hw + 22f)
        val oy = sin(an) * 20f
        val rot = (-14 * PI / 180).toFloat()
        val zx = ox * cos(rot) - oy * sin(rot) + f.bodyX
        val zy = ox * sin(rot) + oy * cos(rot) + cyB + f.bodyY + 8f
        // Seen only beside the blob: as it passes behind the body it fades out.
        val dx = (zx - f.bodyX) / hw
        val dy = (zy - f.bodyY - cyB) / ((90f - top) / 2f)
        val a = (((dx * dx + dy * dy) - 1.0f) / 0.35f).coerceIn(0f, 1f)
        if (a <= 0f) return
        fill.color = colors.dots
        fill.alpha = (a * 255).toInt()
        c.drawCircle(zx, zy, 5f + 1.8f * sin(an), fill)
    }

    // ---- blurred ellipses: a radial gradient with the Gaussian edge profile, stretched ----

    private val ellipseShaders = HashMap<Int, RadialGradient>()

    private fun blurredEllipse(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, sigma: Float, color: Int, alpha: Float) =
        blurredEllipseOn(c, cx, cy, rx, ry, sigma, color, alpha)

    private fun blurredEllipseOn(c: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, sigma: Float, color: Int, alpha: Float) {
        if (rx <= 0f || ry <= 0f) return
        val r = (rx + ry) / 2
        val rel = (sigma / r).coerceIn(0.02f, 2f)
        val key = (Math.round(rel * 50) * 31 + 7) xor color xor Math.round(alpha * 100)
        val sh = ellipseShaders.getOrPut(key) { profile(rel, color, alpha) }
        val outer = 1f + 3f * rel
        c.save()
        c.translate(cx, cy)
        c.scale(rx, ry)
        fill.shader = sh
        fill.alpha = 255
        c.drawCircle(0f, 0f, outer, fill)
        fill.shader = null
        c.restore()
    }

    /** A unit disc blurred by [rel] (sigma over radius), as a radial gradient out to 1 + 3 rel. */
    private fun profile(rel: Float, color: Int, alpha: Float): RadialGradient {
        val outer = 1f + 3f * rel
        val inner = max(0f, 1f - 3f * rel)
        val n = 9
        val pos = FloatArray(n + 1)
        val cols = IntArray(n + 1)
        pos[0] = 0f
        cols[0] = alphaOf(color, alpha * edge(0f, 1f, rel))
        for (k in 1..n) {
            val d = inner + (outer - inner) * (k - 1) / (n - 1)
            pos[k] = (d / outer).coerceIn(0f, 1f)
            cols[k] = alphaOf(color, alpha * edge(d, 1f, rel))
        }
        return RadialGradient(0f, 0f, outer, cols, pos, Shader.TileMode.CLAMP)
    }

    /** The colours of a look (`_paint` in the original). */
    private class Colors(
        val base: Int, val glassBase: Int, val eye: Int, val glint: Boolean, val dots: Int, val spark: Int,
        val cheek: Int, val cheekAlpha: Float, val shadow: Float, val grain: Float,
        val softStops: IntArray, val jellyStops: IntArray, val velvetStops: IntArray, val clayStops: IntArray,
        val glow: Int, val deep: Int, val clayShade: Int, val sheen: Int, val orbs: IntArray,
        /** Close to black (ink): needs the light rim on a dark page. */
        val darkBody: Boolean,
    ) {
        companion object {
            fun of(look: BlobLook): Colors {
                val c = Rgb.of(look.color.argb)
                val w = Rgb.WHITE
                val ink = Rgb.INK
                val l = lum(c)
                val dark = l < 0.18
                val glass = look.finish == BlobFinish.Glass
                val hue = rgb2hsl(c)[0] * 360
                val eye = if (dark && !glass) WHITE else EYE
                val sat = hueShift(c, 0.0, 0.12)
                return Colors(
                    base = c.argb(),
                    glassBase = mixc(c, w, 0.45).argb(0.78),
                    eye = eye,
                    glint = eye != WHITE,
                    dots = (if (dark) mixc(c, w, 0.55) else mixc(c, ink, 0.38)).argb(),
                    spark = if (hue > 25 && hue < 75) 0xFFFF8FB3.toInt() else 0xFFFFC53D.toInt(),
                    cheek = if (dark) 0xFFFF7EA6.toInt() else if (hue > 300 || hue < 20) 0xFFFF5E86.toInt() else 0xFFFF7A93.toInt(),
                    cheekAlpha = if (dark) 0.32f else 0.26f,
                    shadow = when (look.finish) { BlobFinish.Clay -> 1.35f; BlobFinish.Glass -> 0.6f; else -> 1f },
                    grain = when (look.finish) { BlobFinish.Glass -> 0.22f; BlobFinish.Clay -> 0.16f; BlobFinish.Velvet -> 0.32f; else -> 0f },
                    softStops = intArrayOf(mixc(c, w, if (dark) 0.22 else 0.42).argb(), c.argb(), mixc(c, ink, if (dark) 0.1 else 0.2).argb()),
                    jellyStops = intArrayOf(mixc(sat, w, 0.3).argb(), sat.argb(), mixc(hueShift(c, -6.0, 0.18), ink, 0.24).argb()),
                    velvetStops = intArrayOf(mixc(c, ink, if (dark) 0.0 else 0.16).argb(), c.argb(), mixc(c, w, 0.18).argb()),
                    clayStops = intArrayOf(mixc(c, w, 0.12).argb(), c.argb()),
                    glow = mixc(hueShift(c, 8.0, 0.15, 0.05), w, 0.35).argb(),
                    deep = mixc(c, ink, 0.5).argb(),
                    clayShade = mixc(c, Rgb(150.0, 80.0, 60.0), 0.4).argb(),
                    sheen = mixc(c, w, if (dark) 0.25 else 0.45).argb(),
                    darkBody = l < 0.06,
                    orbs = intArrayOf(hueShift(c, 0.0, 0.15, -0.06).argb(), hueShift(c, 40.0, 0.2, -0.02).argb(), hueShift(c, -45.0, 0.15).argb()),
                )
            }
        }
    }

    companion object {
        private val ORB_POS = arrayOf(floatArrayOf(-0.75f, 0.3f, 0.55f), floatArrayOf(0.72f, 0.62f, 0.5f), floatArrayOf(-0.05f, 0f, 0.4f))
        private const val WHITE = 0xFFFFFFFF.toInt()
        private val EYE = 0xFF1E1C24.toInt()
        /** The original's shadow ink (#2a2140), for the glass rim on a light page. */
        private val INK = 0xFF2A2140.toInt()
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

        /**
         * Grey noise for the grain, made once. The original's feTurbulence noise is about 0.5 in linear light, so it shows as
         * a light grey near sRGB 187, varying a little from pixel to pixel.
         */
        private val grainBitmap: Bitmap by lazy {
            val size = 128
            val rnd = Random(9)
            val px = IntArray(size * size) {
                val v = (163 + rnd.nextInt(25) + rnd.nextInt(25)).coerceIn(0, 255)
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
            Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
        }
    }
}
