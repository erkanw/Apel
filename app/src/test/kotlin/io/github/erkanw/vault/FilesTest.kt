package io.github.erkanw.vault

import io.github.erkanw.vault.chat.joinPath
import io.github.erkanw.vault.chat.humanSize
import io.github.erkanw.vault.chat.mimeOf
import io.github.erkanw.vault.chat.parentPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FilesTest {

    @Test
    fun `paths are joined and cut the way the server writes them`() {
        assertEquals("a", joinPath("", "a"))
        assertEquals("a/b", joinPath("a", "b"))
        assertEquals("a", parentPath("a/b"))
        assertEquals("", parentPath("a"))
        assertEquals("", parentPath(""))
    }

    @Test
    fun `sizes and types read well`() {
        assertEquals("820 B", humanSize(820))
        assertEquals("9.2 KB", humanSize(9400))
        assertEquals("1.5 MB", humanSize(1_572_864))
        assertEquals("text/html", mimeOf("Index.HTML"))
        assertEquals("application/octet-stream", mimeOf("Makefile"))
    }
}
