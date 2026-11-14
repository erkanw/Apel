package io.github.erkanw.vault

import io.github.erkanw.vault.mascot.BlobColor
import io.github.erkanw.vault.mascot.BlobEyes
import io.github.erkanw.vault.mascot.BlobFinish
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.mascot.BlobShape
import io.github.erkanw.vault.mascot.BlobShapes
import io.github.erkanw.vault.mascot.BlobTaste
import io.github.erkanw.vault.mascot.Rgb
import io.github.erkanw.vault.mascot.hueShift
import io.github.erkanw.vault.mascot.lum
import io.github.erkanw.vault.mascot.mixc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.random.Random

class LookTest {
    @Test
    fun `a look reads and writes as a Studio link`() {
        assertEquals("series=blobs&shape=mochi&color=milk&material=matte&eyes=dot&cheeks=1", BlobLook.Default.toQuery())
        val l = BlobLook(BlobShape.Kitty, BlobColor.Cocoa, BlobFinish.Velvet, BlobEyes.Capsule, blush = false)
        assertEquals(l, BlobLook.parse(l.toQuery()))
        assertEquals(l, BlobLook.parse("https://victoriawu-77.github.io/blob-atelier/#" + l.toQuery()))
        assertEquals(BlobLook.Default, BlobLook.parse(null))
        assertEquals(BlobLook.Default.copy(shape = BlobShape.Pill), BlobLook.parse("shape=pill&color=nope&material=fur"))
        assertEquals("Dumpling", BlobShape.Mochi.label)
        assertEquals("Bubblegum", BlobColor.Bubblegum.label)
    }

    @Test
    fun `randomize keeps the Studio's taste`() {
        val rnd = Random(3)
        val jellyOk = setOf("peach", "butter", "matcha", "mint", "sky", "lilac", "bubblegum", "tangerine")
        val glassOk = setOf("sky", "lilac", "mint", "bubblegum", "peach", "butter")
        var cur = BlobLook.Default
        repeat(500) {
            val next = BlobTaste.randomize(cur, rnd)
            assertTrue(next.shape != cur.shape || next.color != cur.color || next.finish != cur.finish)
            if (next.finish == BlobFinish.Jelly) assertTrue(next.color.id in jellyOk)
            if (next.finish == BlobFinish.Glass) assertTrue(next.color.id in glassOk)
            if (next.color == BlobColor.Ink || next.color == BlobColor.Cocoa) assertFalse(next.blush)
            cur = next
        }
    }

    @Test
    fun `every shape has the same area and stands on the ground line`() {
        val area = PI * 90 * 90
        for (s in BlobShape.entries) {
            val g = BlobShapes.of(s)
            var a = 0.0
            var maxY = -1e9
            val p = g.points
            for (i in 0 until BlobShapes.N) {
                val j = (i + 1) % BlobShapes.N
                a += p[2 * i] * p[2 * j + 1] - p[2 * j] * p[2 * i + 1]
                maxY = maxOf(maxY, p[2 * i + 1].toDouble())
            }
            assertEquals(s.name, area, abs(a / 2), area * 0.002)
            assertEquals(s.name, 90.0, maxY, 0.01)
            assertTrue(s.name, g.top < g.fy && g.fy < 90f && g.sp > 0f && g.sp < g.hw)
        }
    }

    @Test
    fun `colour helpers match the original`() {
        val peach = Rgb.of(BlobColor.Peach.argb)
        assertEquals(0xFFFFFFFF.toInt(), mixc(peach, Rgb.WHITE, 1.0).argb())
        assertTrue(lum(Rgb.of(BlobColor.Ink.argb)) < 0.18)
        assertTrue(lum(Rgb.of(BlobColor.Cocoa.argb)) < 0.18) // 0.178: white eyes, as in the Studio
        assertTrue(lum(Rgb.of(BlobColor.Tangerine.argb)) >= 0.18)
        assertEquals(peach.argb(), hueShift(peach, 0.0).argb())
        assertNotEquals(peach.argb(), hueShift(peach, 40.0).argb())
    }
}
