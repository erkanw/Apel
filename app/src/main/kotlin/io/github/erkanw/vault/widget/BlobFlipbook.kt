package io.github.erkanw.vault.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.widget.RemoteViews
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.mascot.BlobShapes
import io.github.erkanw.vault.mascot.BlobEngine
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.mascot.BlobPainter
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Blob on the home screen, drawn offscreen by the app's own engine and painter. A widget can't run an animation, but the
 * launcher runs a ViewFlipper by itself, so the widget hands it [LOOP_FRAMES] frames and it cycles through them with the
 * app closed. The engine runs in loop mode, so the last frame leads straight back into the first.
 *
 * RemoteViews has a memory ceiling for all its bitmaps together (one and a half screens' worth), so the frames are sized
 * to stay well under it on the phone at hand.
 */
object BlobFlipbook {
    /** The flipper layouts hold this many frames and flip every [LOOP_FRAME_MS] (a unit test checks both). */
    const val LOOP_FRAMES = 72
    const val LOOP_FRAME_MS = 90

    private const val LOOP_SECONDS = LOOP_FRAMES * LOOP_FRAME_MS / 1000f

    /** The engine takes at most 50 ms a step, so each frame is two steps. */
    private const val STEP = LOOP_FRAME_MS / 2000f

    /** The largest slot Blob has in the widget layouts (the small one). */
    private const val SLOT_DP = 84

    /**
     * The part of the painter's 320-unit box a frame shows: enough for his thinking dots above and his shadow below,
     * so the body fills the slot instead of floating in the margin the app keeps for hops and drags.
     */
    private const val CROP_UNITS = 250f

    /** The listening halo, shrunk to fit the crop. */
    private const val HALO = 0.8f

    /** The ground shadow under him, as in the app. */
    private const val SHADOW = 0x8C000000.toInt()

    /** The size of a frame on this phone: the slot at full resolution, unless the loop would come near the ceiling. */
    fun framePx(context: Context): Int {
        val dm = context.resources.displayMetrics
        val slot = (SLOT_DP * dm.density).toInt()
        // The ceiling holds for each update on its own; keep 30 % of it free for the layout's other bitmaps.
        val budget = dm.widthPixels.toLong() * dm.heightPixels * 4 * 3 / 2 * 7 / 10
        val fit = sqrt(budget / (LOOP_FRAMES * 4.0)).toInt()
        return min(slot, fit).coerceAtLeast(64)
    }

    /** The [LOOP_FRAMES] frames of [mood]'s loop; [variant] picks where Blob glances and when he blinks. */
    fun frames(mood: BlobMood, px: Int, variant: Int, look: BlobLook): List<Bitmap> {
        val engine = BlobEngine(seed = 7).apply {
            setShape(look.shape, still = true)
            loop = LOOP_SECONDS
            loopVariant = variant
            setMood(mood)
        }
        val painter = BlobPainter(lite = false).apply { haloScale = HALO; this.look = look }
        // Two loops to let every spring settle into its rhythm.
        repeat(LOOP_FRAMES * 4) { engine.step(STEP) }
        return List(LOOP_FRAMES) { i ->
            if (i > 0) {
                engine.step(STEP)
                engine.step(STEP)
            }
            draw(engine, painter, px)
        }
    }

    /** One still frame of [mood], for when the loop is off in Settings. */
    fun still(mood: BlobMood, px: Int, look: BlobLook): Bitmap {
        val engine = BlobEngine(seed = 7).apply { setShape(look.shape, still = true); setMood(mood) }
        engine.step(0f, still = true)
        return draw(engine, BlobPainter(lite = false).apply { haloScale = HALO; this.look = look }, px)
    }

    private fun draw(engine: BlobEngine, painter: BlobPainter, px: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        // Wide shapes (Pebble, Cloud) take a wider crop, so their sides stay inside the frame.
        val unit = px / maxOf(CROP_UNITS, engine.hw * 2.5f)
        val canvas = Canvas(bitmap)
        // The body's middle (between its top and the ground line) at the frame's centre.
        val bodyMid = (engine.top + 90f) / 2f
        canvas.translate(px / 2f - BlobShapes.ORIGIN_X * unit, px / 2f - (BlobShapes.ORIGIN_Y + bodyMid) * unit)
        painter.draw(canvas, engine, unit, SHADOW)
        return bitmap
    }

    /** Puts [frames] into the flipper layout [views], one bitmap per ImageView. */
    fun apply(context: Context, views: RemoteViews, frames: List<Bitmap>) {
        frames.forEachIndexed { i, bitmap ->
            val id = context.resources.getIdentifier("frame_$i", "id", context.packageName)
            views.setImageViewBitmap(id, bitmap)
        }
    }
}
