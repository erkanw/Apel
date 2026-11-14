package io.github.erkanw.vault

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.erkanw.vault.mascot.BlobColor
import io.github.erkanw.vault.mascot.BlobEngine
import io.github.erkanw.vault.mascot.BlobEyes
import io.github.erkanw.vault.mascot.BlobFinish
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.mascot.BlobMood
import io.github.erkanw.vault.mascot.BlobPainter
import io.github.erkanw.vault.mascot.BlobShape
import java.io.File

/**
 * Debug only: contact sheets of every shape (rows) by every finish (columns), still, for comparing against Blob Atelier's
 * Studio (`am start -n io.github.erkanw.vault/.MascotGalleryActivity --es sheet peach`, then pull files/sheet/ with run-as).
 */
object SheetExport {
    private const val CELL = 220

    fun run(context: Context, color: BlobColor, eyes: BlobEyes, bg: Int, light: Boolean) {
        val dir = File(context.filesDir, "sheet").apply { mkdirs() }
        val cols = BlobFinish.entries.size
        val rows = BlobShape.entries.size
        val sheet = Bitmap.createBitmap(cols * CELL, rows * CELL, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(bg)
        BlobShape.entries.forEachIndexed { r, shape ->
            BlobFinish.entries.forEachIndexed { k, finish ->
                val look = BlobLook(shape, color, finish, eyes, blush = true)
                val engine = BlobEngine(seed = 7).apply { setShape(shape, still = true); setMood(BlobMood.Idle) }
                engine.step(0f, still = true)
                val unit = CELL / 260f
                c.save()
                c.translate(k * CELL + CELL / 2f - 160f * unit, r * CELL + CELL / 2f - (165f + (engine.top + 95f) / 2f) * unit)
                BlobPainter(lite = false).apply { this.look = look; onLight = light }
                    .draw(c, engine, unit, if (light) 0x382A2140 else 0x8C000000.toInt())
                c.restore()
            }
        }
        File(dir, "sheet-${color.id}-${eyes.id}${if (light) "-light" else ""}.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
