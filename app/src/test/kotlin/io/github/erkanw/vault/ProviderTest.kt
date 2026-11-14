package io.github.erkanw.vault

import io.github.erkanw.vault.agent.PartKind
import io.github.erkanw.vault.agent.decodeDataUrl
import io.github.erkanw.vault.agent.partKindOf
import io.github.erkanw.vault.agent.titleFrom
import io.github.erkanw.vault.agent.userContent
import io.github.erkanw.vault.chat.MediaPart
import io.github.erkanw.vault.provider.ChatRequest
import io.github.erkanw.vault.provider.OpenRouterAuth
import io.github.erkanw.vault.provider.ProviderEndpoints
import io.github.erkanw.vault.provider.ProviderException
import io.github.erkanw.vault.provider.ProviderKind
import io.github.erkanw.vault.provider.StreamPart
import io.github.erkanw.vault.provider.parseStreamData
import io.github.erkanw.vault.provider.priceLine
import io.github.erkanw.vault.provider.providerError
import io.github.erkanw.vault.provider.requestBody
import io.github.erkanw.vault.store.ModelStore
import io.github.erkanw.vault.store.modelsToJson
import io.github.erkanw.vault.store.parseModels
import io.github.erkanw.vault.chat.ModelPreset
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {
    @Test
    fun `addresses need https unless they are this phone or the home network`() {
        assertEquals("https://api.groq.com/openai/v1", ProviderEndpoints.check(" api.groq.com/openai/v1/ ").url)
        assertNull(ProviderEndpoints.check("https://api.mistral.ai/v1").warning)
        val home = ProviderEndpoints.check("192.168.1.20:1234/v1")
        assertEquals("http://192.168.1.20:1234/v1", home.url)
        assertTrue(home.warning!!.contains("plain http"))
        assertEquals("http://localhost:11434/v1", ProviderEndpoints.check("http://localhost:11434/v1").url)
        assertThrows { ProviderEndpoints.check("http://example.com/v1") }
        assertThrows { ProviderEndpoints.check("https://user:secret@example.com/v1") }
        assertThrows { ProviderEndpoints.check("") }
        listOf("10.0.0.2", "172.20.1.1", "100.100.1.1", "127.0.0.1", "169.254.1.1", "printer.local", "fd12::1").forEach { assertTrue(it, ProviderEndpoints.isPrivateHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "100.128.0.1").forEach { assertFalse(it, ProviderEndpoints.isPrivateHost(it)) }
    }

    @Test
    fun `a key only goes to the address it was saved for`() {
        val base = "https://openrouter.ai/api/v1"
        assertTrue(ProviderEndpoints.mayCarryKey("$base/chat/completions", base))
        assertFalse(ProviderEndpoints.mayCarryKey("https://openrouter.ai.evil.com/api/v1/chat/completions", base))
        assertFalse(ProviderEndpoints.mayCarryKey("http://openrouter.ai/api/v1/chat/completions", base))
    }

    @Test
    fun `402 and 429 say what to do and never fall back`() {
        val credits = providerError(402, "", openRouter = true, model = "anthropic/claude-haiku-5.5")
        assertEquals("Your OpenRouter credits have run out. Add credits at openrouter.ai, then try again.", credits.message)
        assertFalse(credits.fallback)
        val free = providerError(429, "rate limited", openRouter = true, model = "inclusionai/ling-3.2-flash:free")
        assertEquals("Free models allow 50 messages a day. Try again tomorrow or add \$10 of credits.", free.message)
        assertFalse(free.fallback)
        assertTrue(providerError(429, "", true, "openai/gpt-6.1-sol").message!!.startsWith("Too many requests"))
        assertTrue(providerError(503, "", true, "x").fallback)
        assertTrue(providerError(404, "", true, "x").fallback)
        assertTrue(providerError(401, "", true, "x").message!!.contains("Sign in again"))
    }

    @Test
    fun `a price per message is one significant figure`() {
        // Claude Haiku 5.5 at its zero-retention price: $0.10 in, $0.50 out per million.
        assertEquals("About \$0.0005 a message", priceLine(false, 0.10, 0.50))
        assertEquals("About \$0.009 a message", priceLine(false, 2.0, 10.0))
        assertEquals("Free · 50 messages a day", priceLine(true, 0.0, 0.0))
        assertEquals("Price unknown", priceLine(false, 0.0, 0.0))
    }

    @Test
    fun `the request asks OpenRouter for usage and private providers`() {
        val messages = JSONArray().put(JSONObject().put("role", "user").put("content", "hi"))
        val or = requestBody(ProviderKind.OpenRouter, ChatRequest("m", messages, maxTokens = 512, privateOnly = true))
        assertTrue(or.getBoolean("stream"))
        assertEquals(512, or.getInt("max_tokens"))
        assertTrue(or.getJSONObject("usage").getBoolean("include"))
        assertTrue(or.getJSONObject("provider").getBoolean("zdr"))
        assertEquals("deny", or.getJSONObject("provider").getString("data_collection"))
        assertFalse(requestBody(ProviderKind.OpenRouter, ChatRequest("m", messages)).has("provider"))
        val custom = requestBody(ProviderKind.Custom, ChatRequest("m", messages, privateOnly = true))
        assertFalse(custom.has("provider"))
        assertTrue(custom.getJSONObject("stream_options").getBoolean("include_usage"))
    }

    @Test
    fun `stream events give text, reasoning, usage and errors`() {
        assertEquals(listOf(StreamPart.Text("Hel")), parseStreamData("""{"choices":[{"delta":{"content":"Hel"}}]}""", "m", true))
        assertEquals(listOf(StreamPart.Reasoning("hmm")), parseStreamData("""{"choices":[{"delta":{"reasoning":"hmm","content":null}}]}""", "m", true))
        assertEquals(listOf(StreamPart.Usage(1200, 80, 0.00031)), parseStreamData("""{"choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":80,"cost":0.00031}}""", "m", true))
        assertTrue(parseStreamData(" [DONE]", "m", true).isEmpty())
        assertTrue(parseStreamData("not json", "m", true).isEmpty())
        val e = try { parseStreamData("""{"error":{"code":402,"message":"no credits"}}""", "m", true); null } catch (x: ProviderException) { x }
        assertEquals(402, e?.status)
    }

    @Test
    fun `the PKCE challenge is the S256 of the verifier, and the code is read back`() {
        // Computed independently (node crypto, sha256 then base64url).
        assertEquals("OQ3TzB-OYI1JTZ4f2G4CUT7TTkihUIxft4X2EaEH-sA", OpenRouterAuth.challengeOf("dBjftJeZ4CVP-mJ92K9oRiv-4_MvUWw8UX9q7vNz_Mw"))
        val v = OpenRouterAuth.newVerifier()
        assertTrue(v.length in 43..128 && v.none { it == '=' || it == '+' || it == '/' })
        val a = OpenRouterAuth.Attempt("v", "s1", OpenRouterAuth.Method.Loopback, "http://localhost:4242/callback")
        assertEquals("abc", OpenRouterAuth.codeFrom("http://localhost/callback?code=abc&state=s1", a))
        assertEquals("abc", OpenRouterAuth.codeFrom("http://localhost/callback?code=abc", a))
        assertNull(OpenRouterAuth.codeFrom("http://localhost/callback?code=abc&state=other", a))
        val url = OpenRouterAuth.authUrl(a, "Apel on Pixel")
        assertTrue(url.startsWith("https://openrouter.ai/auth?callback_url=http%3A%2F%2Flocalhost%3A4242%2Fcallback&"))
        assertTrue(url.contains("code_challenge_method=S256") && url.contains("key_label=Apel+on+Pixel"))
        assertFalse(OpenRouterAuth.authUrl(a.copy(callback = null), "x").contains("callback_url"))
    }

    @Test
    fun `files become the parts the model can read`() {
        assertEquals(PartKind.Image, partKindOf("a.jpg", "image/jpeg"))
        assertEquals(PartKind.Pdf, partKindOf("a.pdf", "application/octet-stream"))
        assertEquals(PartKind.Text, partKindOf("notes.md", "application/octet-stream"))
        assertEquals(PartKind.Other, partKindOf("deck.pptx", "application/vnd.ms-powerpoint"))
        assertEquals("text/plain" to "hi".toByteArray().toList(), decodeDataUrl("data:text/plain;base64,aGk=")!!.let { it.first to it.second.toList() })
        assertEquals("plain", userContent("plain", emptyList(), true) { _, _ -> null })
        val parts = userContent(
            "Look",
            listOf(MediaPart("p.png", "data:image/png;base64,AAAA"), MediaPart("n.txt", "data:text/plain;base64,aGk="), MediaPart("d.pdf", "data:application/pdf;base64,AAAA")),
            pdfAsFile = true,
        ) { _, _ -> null } as JSONArray
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertTrue(parts.getJSONObject(0).getString("text").contains("Attached file “n.txt”"))
        assertEquals("image_url", parts.getJSONObject(1).getString("type"))
        assertEquals("file", parts.getJSONObject(2).getString("type"))
        val saved = userContent("", listOf(MediaPart("deck.pptx", "data:application/vnd.ms-powerpoint;base64,AAAA")), true) { n, _ -> "downloads/$n" }
        assertEquals("(The user attached “deck.pptx”. It is saved at downloads/deck.pptx in your workspace.)", saved)
    }

    @Test
    fun `a session is titled from its first line, cut at a word`() {
        assertEquals("What's the weather tomorrow?", titleFrom("\n What's the weather tomorrow?\nThanks"))
        assertEquals("Compare three study plans for the…", titleFrom("Compare three study plans for the physics exam next week"))
    }

    @Test
    fun `model presets survive being kept, and the order only names known presets`() {
        val saved = ModelStore.Saved(
            listOf(ModelPreset("haiku", "Claude Haiku 5.5", "anthropic/claude-haiku-5.5", false, contextTokens = 1_000_000), ModelPreset("free", "Ling", "inclusionai/ling-3.2-flash:free", false)),
            listOf("haiku", "free"),
        )
        assertEquals(saved, parseModels(modelsToJson(saved)))
        assertEquals(listOf("haiku"), parseModels("""{"presets":[{"name":"haiku","model":"a/b"}],"order":["gone","haiku"]}""").callOrder)
        assertTrue(parseModels("nope").presets.isEmpty())
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (e: IllegalArgumentException) {
            return
        }
        throw AssertionError("expected IllegalArgumentException")
    }
}
