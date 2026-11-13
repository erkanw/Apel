package io.github.erkanw.vault

import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.serverSubtitle
import io.github.erkanw.vault.ui.toolTitle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    @Test
    fun `turning tools on and off keeps the server's own shorthand`() {
        val all = McpServer("db", "db", listOf("a", "b", "c"), listOf("*"), true, "")
        assertEquals(listOf("a", "c"), all.withTool("b", false))
        val some = all.copy(enabledTools = listOf("a", "c"))
        assertEquals(listOf("*"), some.withTool("b", true))
        assertEquals(listOf("a"), some.withTool("c", false))
        assertEquals(1, some.copy(enabledTools = listOf("a")).onCount)
        assertEquals("Off", serverSubtitle(some.copy(enabledTools = emptyList())))
    }

    @Test
    fun `tool names read as words, without the server's prefix`() {
        assertEquals("Take screenshot", toolTitle("browser_take_screenshot"))
        assertEquals("Query", toolTitle("db_query"))
        assertEquals("Call owner result", toolTitle("call_owner_result"))
        assertEquals("Browser", toolTitle("browser"))
    }
}
