package io.github.erkanw.vault

import io.github.erkanw.vault.chat.AttachmentKind
import io.github.erkanw.vault.chat.IngressLimits
import io.github.erkanw.vault.chat.MIB
import io.github.erkanw.vault.chat.MediaPart
import io.github.erkanw.vault.chat.Rejection
import io.github.erkanw.vault.chat.checkAdding
import io.github.erkanw.vault.chat.classifyAttachment
import io.github.erkanw.vault.chat.dataUrl
import io.github.erkanw.vault.chat.encodedSize
import io.github.erkanw.vault.chat.fileMimeFor
import io.github.erkanw.vault.chat.rejectionMessage
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private val limits = IngressLimits()

    @Test
    fun `a data url is the type and the base64`() {
        assertEquals("data:image/jpeg;base64,QUJD", dataUrl("image/jpeg", "QUJD"))
    }

    @Test
    fun `pictures go as images and documents by their extension`() {
        assertEquals(AttachmentKind.Image, classifyAttachment("x.heic", "image/heic"))
        assertEquals(AttachmentKind.Image, classifyAttachment("photo", "image/jpeg"))
        assertNull(classifyAttachment("logo.svg", "image/svg+xml"))
        assertEquals(AttachmentKind.File, classifyAttachment("Report.PDF", "application/pdf"))
        assertEquals(AttachmentKind.File, classifyAttachment("notes.md", "application/octet-stream"))
        assertEquals(AttachmentKind.File, classifyAttachment("data.csv", null))
        assertNull(classifyAttachment("movie.mp4", "video/mp4"))
        assertNull(classifyAttachment("archive.zip", "application/zip"))
        assertEquals("text/markdown", fileMimeFor("a.md"))
        assertNull(fileMimeFor("noextension"))
    }

    @Test
    fun `base64 grows a file by a third in groups of three bytes`() {
        assertEquals(0L, encodedSize(0))
        assertEquals(4L, encodedSize(1))
        assertEquals(4L, encodedSize(3))
        assertEquals(8L, encodedSize(4))
    }

    @Test
    fun `a file is checked against the count, the sizes and the frame`() {
        assertNull(checkAdding(AttachmentKind.File, 1000, 0, 0, 0, limits))
        assertEquals(Rejection.Unsupported, checkAdding(null, 1000, 0, 0, 0, limits))
        assertEquals(Rejection.Empty, checkAdding(AttachmentKind.File, 0, 0, 0, 0, limits))
        assertEquals(Rejection.TooLarge, checkAdding(AttachmentKind.File, 6 * MIB + 1, 0, 0, 0, limits))
        // A picture is shrunk before sending, so a big one is not refused for its original size.
        assertNull(checkAdding(AttachmentKind.Image, 20 * MIB, 0, 0, 0, limits))
        assertEquals(Rejection.TooMany, checkAdding(AttachmentKind.File, 10, 4, 40, 40, limits))
        assertEquals(Rejection.TotalTooLarge, checkAdding(AttachmentKind.File, 5 * MIB, 3, 20 * MIB, 0, limits))
        val tight = IngressLimits(maxFrameBytes = 10_000, maxTextBytes = 1_000)
        // 7,000 bytes become about 9,336 once encoded: more than the 9,000 left in the frame after the text.
        assertEquals(Rejection.FrameTooLarge, checkAdding(AttachmentKind.File, 7_000, 0, 0, 0, tight))
        assertNull(checkAdding(AttachmentKind.File, 5_000, 0, 0, 0, tight))
    }

    @Test
    fun `refusals are explained`() {
        assertTrue(rejectionMessage(Rejection.TooLarge, "big.pdf", limits).contains("6 MB"))
        assertTrue(rejectionMessage(Rejection.TooMany, "x", limits).contains("4"))
        assertTrue(rejectionMessage(Rejection.Unsupported, "x.zip", limits).contains("x.zip"))
    }

    @Test
    fun `an attachment link is placed on the agent's own address only`() {
        val base = "https://agent.example.org"
        assertEquals("https://agent.example.org/api/media/a/b", io.github.erkanw.vault.chat.resolveMediaUrl(base, "/api/media/a/b"))
        assertEquals("https://agent.example.org/api/media/a/b", io.github.erkanw.vault.chat.resolveMediaUrl("$base/", "/api/media/a/b"))
        assertEquals("https://agent.example.org/x.png", io.github.erkanw.vault.chat.resolveMediaUrl(base, "https://agent.example.org/x.png"))
        // Somewhere else, a protocol-relative link, or another scheme: not fetched.
        assertNull(io.github.erkanw.vault.chat.resolveMediaUrl(base, "https://evil.example.com/x.png"))
        assertNull(io.github.erkanw.vault.chat.resolveMediaUrl(base, "//evil.example.com/x.png"))
        assertNull(io.github.erkanw.vault.chat.resolveMediaUrl(base, "file:///sdcard/x.png"))
        assertNull(io.github.erkanw.vault.chat.resolveMediaUrl(base, ""))
        assertNull(io.github.erkanw.vault.chat.resolveMediaUrl("", "/api/media/a/b"))
        assertNull(io.github.erkanw.vault.chat.resolveMediaUrl(base, null))
    }

}
