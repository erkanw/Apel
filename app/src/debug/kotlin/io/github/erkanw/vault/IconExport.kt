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
        // The launch screen draws its icon at 240 dp: a picture of its own at 4x, so Blob stays sharp there.
        val splash = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        draw(Canvas(splash), 960)
        File(dir, "splash_blob.png").outputStream().use { splash.compress(Bitmap.CompressFormat.PNG, 100, it) }
        // And the light launch screen's: the painter's light finish (ink rim, soft ink shadow), for drawable-nodpi; the dark
        // one above goes in drawable-night-nodpi.
        val light = Bitmap.createBitmap(960, 960, Bitmap.Config.ARGB_8888)
        draw(Canvas(light), 960, onLight = true)
        File(dir, "splash_blob_light.png").outputStream().use { light.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The bottom of his ground shadow, in the painter's units: the ground line (95) plus the shadow's 12-unit radius. */
    private const val SHADOW_BOTTOM = 107f

    /**
     * With [centreWhole] (the launcher icon) the whole figure, from the top of his dome to the bottom of his shadow, sits on
     * the canvas's centre, so he looks centred in the launcher's circle. Otherwise (the launch screens) the body's middle
     * does, a touch low for the shadow.
     */
    private fun draw(c: Canvas, px: Int, onLight: Boolean = false, centreWhole: Boolean = false) {
        val engine = BlobEngine(seed = 7).apply { setMood(BlobMood.Idle) }
        engine.step(0f, still = true)
        val unit = px * BODY_DP / 108f / (BlobShapes.hw * 2)
        val mid = if (centreWhole) (BlobShapes.top + SHADOW_BOTTOM) / 2f else (BlobShapes.top + 90f) / 2f
        val nudge = if (centreWhole) 0f else px * 0.01f
        c.translate(px / 2f - BlobShapes.ORIGIN_X * unit, px / 2f - (BlobShapes.ORIGIN_Y + mid) * unit + nudge)
        BlobPainter(lite = false).apply { this.onLight = onLight }.draw(c, engine, unit, if (onLight) 0x382A2140 else 0x8C000000.toInt())
    }
}
