package io.github.erkanw.vault

import io.github.erkanw.vault.chat.McpServer
import io.github.erkanw.vault.chat.serverSubtitle
import io.github.erkanw.vault.ui.toolTitle
import io.github.erkanw.vault.provider.ChatRequest
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.StreamPart
import io.github.erkanw.vault.provider.parseStreamData
import io.github.erkanw.vault.provider.requestBody
import io.github.erkanw.vault.tools.HtmlText
import io.github.erkanw.vault.tools.MemoryTools
import io.github.erkanw.vault.tools.PublicOnlySockets
import io.github.erkanw.vault.tools.WebFetchTool
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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

    @Test
    fun `tool calls stream in pieces and tools go in the request`() {
        val first = parseStreamData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","type":"function","function":{"name":"web_fetch","arguments":"{\"url\""}}]}}]}""", "m", true)
        assertEquals(listOf(StreamPart.ToolCall(0, "c1", "web_fetch", "{\"url\"")), first)
        val next = parseStreamData("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"https://a.b\"}"}}]}}]}""", "m", true)
        assertEquals(listOf(StreamPart.ToolCall(0, null, null, ":\"https://a.b\"}")), next)
        val tools = JSONArray().put(JSONObject().put("type", "function"))
        assertTrue(requestBody(ProviderKind.OpenRouter, ChatRequest("m", JSONArray(), tools = tools)).has("tools"))
        assertFalse(requestBody(ProviderKind.OpenRouter, ChatRequest("m", JSONArray(), tools = JSONArray())).has("tools"))
    }

    @Test
    fun `a page becomes readable text`() {
        val page = HtmlText.extract(
            """<html><head><title>Trains &amp; times</title><style>p{}</style></head><body><nav>Menu</nav>
               <main><h1>Airport</h1><p>The 07:42 is the quickest.</p><ul><li>One</li><li>Two</li></ul>
               <script>alert(1)</script><p>Caf&eacute; &#8212; open</p></main></body></html>""",
        )
        assertEquals("Trains & times", page.title)
        assertTrue(page.text.contains("# Airport"))
        assertTrue(page.text.contains("The 07:42 is the quickest."))
        assertTrue(page.text.contains("- One"))
        assertFalse(page.text.contains("alert"))
        assertFalse(page.text.contains("Menu"))
        assertTrue(page.text.contains("— open"))
    }

    @Test
    fun `the web tool can't reach this phone or the home network`() {
        listOf("127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "100.64.0.1", "169.254.1.1", "::1", "fd00::1", "0.0.0.0", "0.1.2.3", "224.0.0.1", "::ffff:127.0.0.1", "64:ff9b::a00:1")
            .forEach { assertFalse(it, WebFetchTool.isPublic(InetAddress.getByName(it))) }
        listOf("8.8.8.8", "1.1.1.1", "2606:4700:4700::1111").forEach { assertTrue(it, WebFetchTool.isPublic(InetAddress.getByName(it))) }
    }

    @Test
    fun `a page's socket refuses the phone and the home network before connecting`() {
        // A server really listening on this machine: the guard must refuse it before any connection is made.
        java.net.ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val refused = try {
                PublicOnlySockets.createSocket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", server.localPort), 2000) }
                false
            } catch (e: java.io.IOException) {
                e.message == "local"
            }
            assertTrue(refused)
        }
    }

    @Test
    fun `memory keeps sections`() {
        var doc = "# Memory\n"
        doc = MemoryTools.withSection(doc, "About the user", "Likes trains", replace = false)
        doc = MemoryTools.withSection(doc, "Projects", "Physics exam on the 12th", replace = false)
        doc = MemoryTools.withSection(doc, "About the user", "Lives in Utrecht", replace = false)
        assertEquals("# Memory\n\n## About the user\n- Likes trains\n- Lives in Utrecht\n\n## Projects\n- Physics exam on the 12th\n", doc)
        doc = MemoryTools.withSection(doc, "about the user", "- Lives in Leiden", replace = true)
        assertEquals("# Memory\n\n## About the user\n- Lives in Leiden\n\n## Projects\n- Physics exam on the 12th\n", doc)
    }
}
