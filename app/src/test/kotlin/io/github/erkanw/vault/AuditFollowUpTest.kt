package io.github.erkanw.vault

import io.github.erkanw.vault.agent.ApprovalGate
import io.github.erkanw.vault.provider.OpenRouterAuth
import io.github.erkanw.vault.tools.FileTools
import io.github.erkanw.vault.tools.ToolLevel
import io.github.erkanw.vault.tools.ToolRegistry
import io.github.erkanw.vault.tools.UrlTrail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import io.github.erkanw.vault.provider.textAtMost
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URL

/** The second round of fixes from the November 2026 audit (items 5 to 11). */
class AuditFollowUpTest {

    // ---- 6: addresses a turn has seen ----

    @Test fun `addresses are found in text, Markdown and with punctuation after them`() {
        val text = "See https://example.com/a. Also [this](https://en.wikipedia.org/wiki/Foo_(bar)) and (https://x.org/y), " +
            "or “https://quotes.example/q?id=1”! Not ftp://files.example/x."
        assertEquals(
            listOf("example.com/a", "en.wikipedia.org/wiki/Foo_(bar)", "x.org/y", "quotes.example/q?id=1"),
            UrlTrail.find(text),
        )
    }

    @Test fun `the same address has one key`() {
        val key = UrlTrail.key("https://Example.com/page/")
        assertEquals("example.com/page", key)
        assertEquals(key, UrlTrail.key("http://example.com/page"))
        assertEquals(key, UrlTrail.key("https://example.com:443/page#top"))
        assertEquals(key, UrlTrail.key("example.com/page"))
        assertEquals("example.com:8080/page", UrlTrail.key("https://example.com:8080/page"))
        // A query is part of the address: data can hide there.
        assertEquals("example.com/page?d=secret", UrlTrail.key("https://example.com/page?d=secret"))
        assertNull(UrlTrail.key("ftp://example.com/x"))
        assertNull(UrlTrail.key("not an address"))
    }

    @Test fun `an address made up after reading a page isn't among those seen`() {
        val seen = UrlTrail.find("Results: https://news.example/story and https://news.example/other").toSet()
        assertTrue(UrlTrail.key("https://news.example/story") in seen)
        assertTrue(UrlTrail.key("https://attacker.example/?d=my+notes") !in seen)
        assertTrue(UrlTrail.key("https://news.example/story?d=my+notes") !in seen)
    }

    // ---- 8: tool levels from a backup ----

    @Test fun `a backup can't turn on always allow for a tool that asks by default`() {
        // Allowed by default: kept.
        assertEquals(ToolLevel.Allowed, ToolRegistry.restoredLevel("web_fetch", ToolLevel.Allowed))
        assertEquals(ToolLevel.Allowed, ToolRegistry.restoredLevel(ToolRegistry.WEB_SEARCH, ToolLevel.Allowed))
        // Asks or is off by default: Allowed comes back as Ask first.
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel("automation_create", ToolLevel.Allowed))
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel("browser_open", ToolLevel.Allowed))
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel("mcp_server_tool", ToolLevel.Allowed))
        // Stricter choices are kept as they are.
        assertEquals(ToolLevel.Off, ToolRegistry.restoredLevel("web_fetch", ToolLevel.Off))
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel("web_fetch", ToolLevel.Ask))
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel("browser_open", ToolLevel.Ask))
    }

    // ---- 5: approval cards show everything ----

    @Test fun `file approvals show the whole text that would be written`() {
        val long = "x".repeat(5_000) + "THE END"
        val write = JSONObject().put("path", "notes/a.md").put("content", long)
        assertTrue(FileTools.Write.detail(write).endsWith("THE END"))
        val edit = JSONObject().put("path", "notes/a.md").put("old_text", "old").put("new_text", long)
        assertTrue(FileTools.Edit.detail(edit).contains("Replace:\nold") && FileTools.Edit.detail(edit).endsWith("THE END"))
    }

    // ---- 11: a shorter wait for an approval ----

    @Test fun `an approval with no time left is a no at once`() = runBlocking {
        assertEquals(ApprovalGate.Answer.Timeout, ApprovalGate.ask("s", "t", "Summary", "", timeoutMs = 0L))
        assertTrue(ApprovalGate.pending.value.isEmpty())
    }

    // ---- Low items ----

    @Test fun `each agent reads only its own memory`() {
        // The first agent: files right in memory/.
        assertTrue(io.github.erkanw.vault.tools.Workspace.mayRead("memory/MEMORY.md", "apel", isFirst = true))
        assertFalse(io.github.erkanw.vault.tools.Workspace.mayRead("memory/coach/MEMORY.md", "apel", isFirst = true))
        // Another agent: its own folder only.
        assertTrue(io.github.erkanw.vault.tools.Workspace.mayRead("memory/coach/MEMORY.md", "coach", isFirst = false))
        assertTrue(io.github.erkanw.vault.tools.Workspace.mayRead("memory/coach", "coach", isFirst = false))
        assertFalse(io.github.erkanw.vault.tools.Workspace.mayRead("memory/MEMORY.md", "coach", isFirst = false))
        assertFalse(io.github.erkanw.vault.tools.Workspace.mayRead("memory/coachX/MEMORY.md", "coach", isFirst = false))
        assertFalse(io.github.erkanw.vault.tools.Workspace.mayRead("memory/other/MEMORY.md", "coach", isFirst = false))
        // Everything else, and the memory folder itself, as before.
        assertTrue(io.github.erkanw.vault.tools.Workspace.mayRead("notes/a.md", "coach", isFirst = false))
        assertTrue(io.github.erkanw.vault.tools.Workspace.mayRead("memory", "coach", isFirst = false))
    }

    @Test fun `a CSV column with zeros in front stays text`() {
        val import = io.github.erkanw.vault.userdb.Csv.prepare("code,zip,n,x\n007,01234,5,0.5\n12,99999,6,0\n")
        assertEquals(listOf("TEXT", "TEXT", "INTEGER", "REAL"), import.types)
    }

    @Test fun `a price that isn't listed is unknown, not free`() {
        assertTrue(io.github.erkanw.vault.openrouter.perM(null).isNaN())
        assertTrue(io.github.erkanw.vault.openrouter.perM("-1").isNaN())
        assertTrue(io.github.erkanw.vault.openrouter.perM("abc").isNaN())
        assertEquals(0.15, io.github.erkanw.vault.openrouter.perM("0.00000015"), 1e-9)
        val models = io.github.erkanw.vault.openrouter.parseCatalog(
            """{"data":[{"id":"openrouter/auto","pricing":{"prompt":"-1","completion":"-1"}},{"id":"x/nopricing"},{"id":"x/free","pricing":{"prompt":"0","completion":"0"}}]}""",
            "{}",
        )
        val auto = models.first { it.id == "openrouter/auto" }
        assertFalse(auto.free)
        assertEquals("Price unknown", io.github.erkanw.vault.openrouter.priceDetail(auto))
        assertEquals("Price unknown", io.github.erkanw.vault.openrouter.priceLine(auto.bestIn, auto.bestOut))
        assertFalse(models.first { it.id == "x/nopricing" }.free)
        assertTrue(models.first { it.id == "x/free" }.free)
        assertEquals("Price unknown", io.github.erkanw.vault.provider.priceLine(false, Double.NaN, Double.NaN))
        // Kept between visits, an unknown price stays unknown.
        val back = io.github.erkanw.vault.openrouter.parseCachedCatalog(io.github.erkanw.vault.openrouter.catalogToJson(models, 1L))!!.first
        assertFalse(back.first { it.id == "openrouter/auto" }.free)
        assertTrue(back.first { it.id == "x/free" }.free)
    }

    @Test fun `more addresses count as this phone or the home network`() {
        val p = io.github.erkanw.vault.provider.ProviderEndpoints
        for (h in listOf("0.0.0.0", "0.1.2.3", "::", "[::]", "::ffff:192.168.1.1", "[::ffff:127.0.0.1]", "localhost.", "printer.local")) assertTrue(h, p.isPrivateHost(h))
        for (h in listOf("example.com", "8.8.8.8", "::ffff:8.8.8.8", "2001:4860:4860::8888")) assertFalse(h, p.isPrivateHost(h))
    }

    @Test fun `a tool server's levels don't follow its display name`() {
        // A level kept under a server's id and tool name is a tool server's, asking first by default.
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel("mcp:abc123:search", ToolLevel.Allowed))
    }

    @Test fun `an answer is read only up to its limit`() {
        val small = "x".repeat(100).toResponseBody()
        assertEquals(100, small.textAtMost(100)!!.length)
        assertNull("y".repeat(101).toResponseBody().textAtMost(100))
    }

    // ---- 7: the sign-in listener ----

    private fun request(port: Int, line: String?): String = Socket("127.0.0.1", port).use { s ->
        s.soTimeout = 10_000
        if (line != null) s.getOutputStream().apply { write("$line\r\nHost: localhost\r\n\r\n".toByteArray()); flush() }
        runCatching { s.getInputStream().bufferedReader().readLine().orEmpty() }.getOrDefault("")
    }

    @Test fun `only the browser coming back to the secret address finishes the sign-in`() = runBlocking {
        OpenRouterAuth.Loopback().use { loopback ->
            val url = URL(loopback.callbackUrl)
            assertTrue(url.path.startsWith("/callback/") && url.path.length > "/callback/".length + 16)
            val got = async(Dispatchers.IO) { loopback.await() }
            // Another app on the phone: the old address, a wrong secret, and one that connects and says nothing.
            assertTrue(request(url.port, "GET /callback?code=evil HTTP/1.1").contains("404"))
            assertTrue(request(url.port, "GET /callback/wrong?code=evil HTTP/1.1").contains("404"))
            Socket("127.0.0.1", url.port).close()
            // The browser.
            assertTrue(request(url.port, "GET ${url.path}?code=good HTTP/1.1").contains("200"))
            assertEquals("http://localhost${url.path}?code=good", got.await())
        }
    }
}
