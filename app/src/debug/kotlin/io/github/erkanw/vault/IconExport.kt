package io.github.erkanw.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.erkanw.vault.mascot.BlobShapes
import io.github.erkanw.vault.mascot.BlobEngine
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.mascot.BlobPainter
import java.io.File

/**
 * Debug only: draws the launcher icon's foreground with the app's own painter, at every density, into files/icon/
 * (`am start -n io.github.erkanw.vault/.MascotGalleryActivity --ez export true`, then pull them with run-as). The PNGs
 * are committed under res/mipmap-*; this is how to make them again after Blob changes.
 */
object IconExport {
    /** Blob's body spans this much of the 108 dp adaptive canvas: about two thirds of the 72 dp a launcher shows, clear of any mask. */
    private const val BODY_DP = 50f

    private val densities = listOf("mdpi" to 1f, "hdpi" to 1.5f, "xhdpi" to 2f, "xxhdpi" to 3f, "xxxhdpi" to 4f)

    fun run(context: Context) {
        val dir = File(context.filesDir, "icon").apply { mkdirs() }
        for ((name, scale) in densities) {
            val px = (108 * scale).toInt()
            val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
            draw(Canvas(bitmap), px, centreWhole = true)
            File(dir, "ic_launcher_foreground-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // The store icon (512 px, fastlane/…/images/icon.png): what a launcher shows of the adaptive icon (the middle
        // 72 of 108 dp), the ground and Blob, square (stores round it themselves).
        val store = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888)
        val full = (512 * 108f / 72f).toInt()
        Canvas(store).apply {
            translate((512 - full) / 2f, (512 - full) / 2f)
            context.getDrawable(R.drawable.ic_launcher_background)!!.apply { setBounds(0, 0, full, full) }.draw(this)
            draw(this, full, centreWhole = true)
        }
        File(dir, "store_icon.png").outputStream().use { store.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The launch screen draws its icon at 240 dp: a picture of its own at 4x, so Blob stays sharp there.
        val splash = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        draw(Canvas(splash), 960)
        File(dir, "splash_blob.png").outputStream().use { splash.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // And the light launch screen's: the painter's light finish (ink rim, soft ink shadow), for drawable-nodpi; the dark
        // one above goes in drawable-night-nodpi.
        val light = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        draw(Canvas(light), 960, onLight = true)
        File(dir, "splash_blob_light.png").outputStream().use { light.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // The silhouettes, eyes cut out: the themed icon's monochrome layer (where the foreground puts the body) and the
        // status bar icon (the body across 22 of 24 units, eyes enlarged 1.4x so they read at that size).
        val e = engine()
        val iconUnit = 108f * BODY_DP / 108f / (e.hw * 2)
        val iconMid = (e.top + SHADOW_BOTTOM) / 2f
        File(dir, "ic_launcher_monochrome.xml").writeText(vector(108, silhouette(e, iconUnit, 54f, 54f - iconMid * iconUnit, 1f)))
        val nUnit = 22f / (e.hw * 2)
        File(dir, "ic_notification.xml").writeText(vector(24, silhouette(e, nUnit, 12f, 12f - (e.top + 90f) / 2f * nUnit, 1.4f)))
    }

    private fun engine() = BlobEngine(seed = 7).apply { setMood(BlobMood.Idle); step(0f, still = true) }

    /** The body as cubic path data (pathD), then the two dot eyes as circles, in a box where a body unit is [u]. */
    private fun silhouette(e: BlobEngine, u: Float, ox: Float, oy: Float, eyeScale: Float): String {
        fun n(v: Float) = String.format(java.util.Locale.ROOT, "%.2f", v)
        val q = e.points
        val N = io.github.erkanw.vault.mascot.BlobShapes.N
        fun x(i: Int) = ox + q[2 * ((i + N) % N)] * u
        fun y(i: Int) = oy + q[2 * ((i + N) % N) + 1] * u
        val sb = StringBuilder("M${n(x(0))},${n(y(0))}")
        for (i in 0 until N) {
            sb.append("C${n(x(i) + (x(i + 1) - x(i - 1)) / 6)},${n(y(i) + (y(i + 1) - y(i - 1)) / 6)} ${n(x(i + 1) - (x(i + 2) - x(i)) / 6)},${n(y(i + 1) - (y(i + 2) - y(i)) / 6)} ${n(x(i + 1))},${n(y(i + 1))}")
        }
        sb.append("Z")
        val f = BlobEngine.Frame().also { e.frame(it) }
        for (k in 0..1) {
            val r = 10.5f * eyeScale * u
            val cx = ox + f.eyeX[k] * u
            val cy = oy + f.eyeY[k] * u
            sb.append("M${n(cx - r)},${n(cy)}A${n(r)},${n(r)} 0 1,0 ${n(cx + r)},${n(cy)}A${n(r)},${n(r)} 0 1,0 ${n(cx - r)},${n(cy)}Z")
        }
        return sb.toString()
    }

    private fun vector(size: Int, d: String) = """<?xml version="1.0" encoding="utf-8"?>
<!-- Made by IconExport (debug) from the default look's outline: the Dumpling with its dot eyes cut out. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="${size}dp"
    android:height="${size}dp"
    android:viewportWidth="$size"
    android:viewportHeight="$size">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="$d" />
</vector>
"""

    /** The bottom of his ground shadow, in the painter's units: the ground line (95) plus the shadow's 12-unit radius. */
    private const val SHADOW_BOTTOM = 107f

    /**
     * With [centreWhole] (the launcher icon) the whole figure, from the top of his dome to the bottom of his shadow, sits on
     * the canvas's centre, so he looks centred in the launcher's circle. Otherwise (the launch screens) the body's middle
     * does, a touch low for the shadow.
     */
    private fun draw(c: Canvas, px: Int, onLight: Boolean = false, centreWhole: Boolean = false) {
        val engine = engine()
        val unit = px * BODY_DP / 108f / (engine.hw * 2)
        val mid = if (centreWhole) (engine.top + SHADOW_BOTTOM) / 2f else (engine.top + 90f) / 2f
        val nudge = if (centreWhole) 0f else px * 0.01f
        c.translate(px / 2f - BlobShapes.ORIGIN_X * unit, px / 2f - (BlobShapes.ORIGIN_Y + mid) * unit + nudge)
        BlobPainter(lite = false).apply { this.onLight = onLight }.draw(c, engine, unit, if (onLight) 0x382A2140 else 0x8C000000.toInt())
    }
}
