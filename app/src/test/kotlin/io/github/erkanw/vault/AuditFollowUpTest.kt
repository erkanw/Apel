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
import org.junit.Assert.assertEquals
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
