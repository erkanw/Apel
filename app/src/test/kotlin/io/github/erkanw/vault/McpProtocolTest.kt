package io.github.erkanw.vault

import io.github.erkanw.vault.tools.McpException
import io.github.erkanw.vault.tools.McpProtocol
import io.github.erkanw.vault.tools.McpToolInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpProtocolTest {
    @Test
    fun plainJsonReply() {
        val r = McpProtocol.response("""{"jsonrpc":"2.0","id":3,"result":{"ok":true}}""", "application/json", 3)
        assertTrue(r.getBoolean("ok"))
    }

    @Test
    fun eventStreamSkipsNotificationsAndJoinsDataLines() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{}}

            event: message
            data: {"jsonrpc":"2.0","id":2,
            data: "result":{"tools":[]}}

        """.trimIndent()
        val r = McpProtocol.response(body, "text/event-stream", 2)
        assertEquals(0, r.getJSONArray("tools").length())
    }

    @Test
    fun errorReplyBecomesItsMessage() {
        try {
            McpProtocol.response("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"Unknown tool: x"}}""", "application/json", 1)
            fail()
        } catch (e: McpException) {
            assertEquals("Unknown tool: x", e.message)
        }
    }

    @Test
    fun aReplyToAnotherRequestIsNotTaken() {
        try {
            McpProtocol.response("""{"jsonrpc":"2.0","id":9,"result":{}}""", "application/json", 1)
            fail()
        } catch (e: McpException) {
            // expected
        }
    }

    @Test
    fun toolsAndPaging() {
        val (tools, next) = McpProtocol.tools(
            JSONObject("""{"tools":[{"name":"ask","description":"Ask","inputSchema":{"type":"object","properties":{"q":{"type":"string"}}},"annotations":{"readOnlyHint":true}},{"name":"write"},{"description":"no name"}],"nextCursor":"p2"}"""),
        )
        assertEquals(listOf("ask", "write"), tools.map { it.name })
        assertTrue(tools[0].readOnly)
        assertEquals("object", tools[1].schema.getString("type"))
        assertEquals("p2", next)
        assertNull(McpProtocol.tools(JSONObject("""{"tools":[]}""")).second)
        // Kept and read back.
        assertEquals(tools.map { it.name }, McpToolInfo.listFrom(org.json.JSONArray(tools.map { it.toJson() }).toString()).map { it.name })
        assertTrue(McpToolInfo.listFrom(org.json.JSONArray(tools.map { it.toJson() }).toString())[0].readOnly)
    }

    @Test
    fun callResultAsText() {
        val ok = McpProtocol.callText(JSONObject("""{"content":[{"type":"text","text":"one"},{"type":"image","mimeType":"image/png","data":"x"},{"type":"text","text":"two"}]}"""))
        assertTrue(ok.startsWith("one"))
        assertTrue(ok.contains("image/png"))
        assertTrue(ok.endsWith("two"))
        assertTrue(McpProtocol.callText(JSONObject("""{"content":[{"type":"text","text":"bad"}],"isError":true}""")).startsWith("Error: bad"))
        assertEquals("(The tool returned nothing.)", McpProtocol.callText(JSONObject("{}")))
        assertTrue(McpProtocol.callText(JSONObject("""{"content":[{"type":"text","text":"${"x".repeat(50)}"}]}"""), max = 10).endsWith("…(cut)"))
    }

    @Test
    fun exposedNames() {
        assertEquals("deepwiki", McpProtocol.slug("DeepWiki"))
        assertEquals("my_tools", McpProtocol.slug("My tools!"))
        assertEquals("mcp_deepwiki__ask_question", McpProtocol.exposedName("deepwiki", "ask_question"))
        assertEquals("mcp_x__a_b_c", McpProtocol.exposedName("x", "a.b c"))
        assertTrue(McpProtocol.exposedName("averyveryverylongservername", "t".repeat(100)).length <= 64)
    }
}
