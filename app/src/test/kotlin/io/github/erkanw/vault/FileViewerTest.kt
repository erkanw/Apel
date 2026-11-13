package io.github.erkanw.vault

import io.github.erkanw.vault.chat.TextKind
import io.github.erkanw.vault.chat.VIEWER_MAX_BYTES
import io.github.erkanw.vault.chat.decodeForViewer
import io.github.erkanw.vault.chat.readUpTo
import io.github.erkanw.vault.chat.textKindOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileViewerTest {
    @Test
    fun `text files open in the app, by their kind`() {
        assertEquals(TextKind.Markdown, textKindOf("notes.md"))
        assertEquals(TextKind.Markdown, textKindOf("research/Summary.MARKDOWN"))
        assertEquals(TextKind.Text, textKindOf("log.txt"))
        assertEquals(TextKind.Data, textKindOf("plan.ics"))
        assertEquals(TextKind.Data, textKindOf("data.csv"))
        assertEquals(TextKind.Code, textKindOf("script.py"))
        assertEquals(TextKind.Text, textKindOf("README"))
        assertNull(textKindOf("photo.jpg"))
        assertNull(textKindOf("report.pdf"))
        assertNull(textKindOf("archive.zip"))
        // RTF is markup, not text to read: it is saved and opened elsewhere.
        assertNull(textKindOf("letter.rtf"))
    }

    @Test
    fun `text is read as UTF-8 without its byte-order mark`() {
        val (text, cut) = decodeForViewer(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Hej då".toByteArray())!!
        assertEquals("Hej då", text)
        assertFalse(cut)
    }

    @Test
    fun `something binary is not shown as text`() {
        assertNull(decodeForViewer(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)))
    }

    @Test
    fun `a long file is cut on a character boundary, and says so`() {
        val big = ("å".repeat(VIEWER_MAX_BYTES)).toByteArray() // two bytes each
        val (text, cut) = decodeForViewer(big.copyOf(VIEWER_MAX_BYTES + 1), total = big.size.toLong())!!
        assertTrue(cut)
        assertTrue(text.all { it == 'å' })
        assertEquals(VIEWER_MAX_BYTES / 2, text.length)
    }

    @Test
    fun `reading stops at the limit`() {
        val input = java.io.ByteArrayInputStream(ByteArray(100_000) { 'a'.code.toByte() })
        assertEquals(40_000, readUpTo(input, 40_000).size)
        assertEquals(10, readUpTo(java.io.ByteArrayInputStream(ByteArray(10)), 40_000).size)
    }
}
