package io.github.erkanw.vault

import io.github.erkanw.vault.chat.ChatException
import io.github.erkanw.vault.chat.SseChunk
import io.github.erkanw.vault.chat.ThinkFilter
import io.github.erkanw.vault.chat.errorDetail
import io.github.erkanw.vault.chat.parseCompletion
import io.github.erkanw.vault.chat.parseModels
import io.github.erkanw.vault.chat.parseSseData
import io.github.erkanw.vault.chat.statusError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ChatParsingTest {

    @Test
    fun `a streamed delta gives its text`() {
        val chunk = parseSseData("""{"choices":[{"delta":{"content":"Hel"}}]}""")
        assertEquals(SseChunk.Text("Hel"), chunk)
    }

    @Test
    fun `done, empty and role-only chunks are told apart`() {
        assertEquals(SseChunk.Done, parseSseData("[DONE]"))
        assertEquals(SseChunk.Skip, parseSseData(""))
        assertEquals(SseChunk.Skip, parseSseData("""{"choices":[{"delta":{"role":"assistant"}}]}"""))
        assertEquals(SseChunk.Skip, parseSseData("""{"choices":[{"delta":{"content":null}}]}"""))
        assertEquals(SseChunk.Skip, parseSseData("not json"))
    }

    @Test
    fun `an error inside the stream is reported`() {
        val chunk = parseSseData("""{"error":{"message":"upstream down"}}""")
        assertEquals(SseChunk.Error("upstream down"), chunk)
    }

    @Test
    fun `a non-streamed completion is read`() {
        assertEquals("Hi!", parseCompletion("""{"choices":[{"message":{"role":"assistant","content":"Hi!"}}]}"""))
        assertEquals("", parseCompletion("""{"choices":[]}"""))
        try {
            parseCompletion("""{"error":{"message":"nope"}}""")
            fail("expected an exception")
        } catch (e: ChatException) {
            assertEquals("nope", e.message)
        }
    }

    @Test
    fun `models keep chat models only and drop the models prefix`() {
        val body = """{"data":[{"id":"gpt-x"},{"id":"text-embedding-3"},{"id":"models/gemini-y"},{"id":"bge-m3"}]}"""
        assertEquals(listOf("gpt-x", "gemini-y"), parseModels(body))
        assertTrue(parseModels("""{"nope":true}""").isEmpty())
        assertTrue(parseModels("garbage").isEmpty())
    }

    @Test
    fun `think blocks are hidden while open and dropped once closed`() {
        assertEquals("Hello", ThinkFilter.visible("Hello"))
        assertEquals("Answer", ThinkFilter.visible("<think>reasoning</think>Answer"))
        assertEquals("", ThinkFilter.visible("<think>still thinking"))
        assertEquals("A B", ThinkFilter.visible("A <think>x</think>B"))
        assertEquals("Done", ThinkFilter.visible("<think>a</think><think>b</think>Done"))
    }

    @Test
    fun `http errors say what to do`() {
        assertTrue(statusError(401, "").contains("key was refused"))
        assertTrue(statusError(404, "model x").contains("model x"))
        assertEquals("Server error 500. boom", statusError(500, "boom"))
        assertEquals("upstream", errorDetail("""{"error":{"message":"upstream"}}"""))
        assertEquals("plain", errorDetail("plain"))
    }
}
