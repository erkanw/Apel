package io.github.erkanw.vault

import io.github.erkanw.vault.mascot.BlobEngine
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.widget.BlobFlipbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** The widget's flipbook: the engine's loop mode repeats exactly, and the layouts match the loop's length. */
class WidgetLoopTest {
    private val loop = BlobFlipbook.LOOP_FRAMES * BlobFlipbook.LOOP_FRAME_MS / 1000f
    private val step = BlobFlipbook.LOOP_FRAME_MS / 2000f

    private fun snapshot(e: BlobEngine) = floatArrayOf(
        e.s, e.gx, e.gy, e.bx, e.by, e.eye, e.expr, e.badge, e.tilt, e.lidS,
        e.hop, e.shakeX, e.bob, e.pulse, e.breath, e.sparks.size.toFloat(),
    )

    @Test
    fun `every mood's loop ends where it started`() {
        for (mood in BlobMood.entries) {
            for (variant in 0 until 4) {
                val e = BlobEngine(seed = 7).apply { this.loop = this@WidgetLoopTest.loop; loopVariant = variant; setMood(mood) }
                repeat(BlobFlipbook.LOOP_FRAMES * 4) { e.step(step) }
                val first = snapshot(e)
                val frames = mutableListOf(first)
                repeat(BlobFlipbook.LOOP_FRAMES) {
                    e.step(step); e.step(step)
                    frames += snapshot(e)
                }
                val again = frames.last()
                for (i in first.indices) {
                    assertEquals("$mood v$variant value $i", first[i], again[i], 2e-3f)
                }
                // And the loop moves: no mood but a still one draws the same frame all the way through.
                val moves = frames.zipWithNext().maxOf { (a, b) -> a.indices.maxOf { abs(a[it] - b[it]) } }
                assertTrue("$mood v$variant never moves", moves > 1e-4f)
            }
        }
    }

    @Test
    fun `both flipper layouts have one image per frame and the matching interval`() {
        for (name in listOf("widget_blob_flipper", "widget_blob_flipper_cut")) {
            val xml = File("src/main/res/layout/$name.xml").readText()
            assertEquals(name, BlobFlipbook.LOOP_FRAMES, Regex("""android:id="@\+id/frame_\d+"""").findAll(xml).count())
            assertTrue(name, xml.contains("""android:flipInterval="${BlobFlipbook.LOOP_FRAME_MS}""""))
        }
        for (anim in listOf("frame_in", "frame_hold")) {
            val xml = File("src/main/res/anim/$anim.xml").readText()
            assertTrue(anim, xml.contains("""android:duration="${BlobFlipbook.LOOP_FRAME_MS}""""))
        }
    }
}
