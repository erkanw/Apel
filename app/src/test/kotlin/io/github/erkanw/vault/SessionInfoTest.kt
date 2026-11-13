package io.github.erkanw.vault

import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.MessageAttachment
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.estimateTokens
import io.github.erkanw.vault.chat.formatTokens
import io.github.erkanw.vault.chat.statsOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionInfoTest {
    private fun user(id: Long, text: String, t: Long = 0L, files: List<MessageAttachment> = emptyList()) =
        ChatMessage(id, Role.User, text, createdAt = t, attachments = files)
    private fun reply(id: Long, text: String, t: Long = 0L, error: Boolean = false) =
        ChatMessage(id, Role.Assistant, text, isError = error, createdAt = t)

    @Test
    fun `every prompt and every response is counted`() {
        val stats = statsOf(
            listOf(
                user(1, "hi", 1_000), reply(2, "hello", 2_000),
                user(3, "and?", 3_000), reply(4, "more", 4_000),
                // A reply still on its way and a failed one are neither.
                user(5, "again", 5_000), reply(6, ""), reply(7, "boom", 6_000, error = true),
            ),
        )
        assertEquals(3, stats.prompts)
        assertEquals(2, stats.responses)
        assertEquals(5, stats.messages)
        assertEquals(1_000L, stats.firstMs)
        assertEquals(5_000L, stats.lastMs)
        assertTrue(stats.complete)
    }

    @Test
    fun `files are listed once and a message of only files counts`() {
        val photo = MessageAttachment("a.jpg", image = true, url = "/api/media/x/y")
        val doc = MessageAttachment("b.pdf", image = false, url = "/api/media/x/z")
        val stats = statsOf(
            listOf(
                user(1, "", 1_000, listOf(photo, doc)),
                // The same file seen again (a page read twice) is not a second file.
                user(2, "look", 2_000, listOf(photo)),
                reply(3, "ok", 3_000),
            ),
        )
        assertEquals(2, stats.prompts)
        assertEquals(listOf("a.jpg", "b.pdf"), stats.attachments.map { it.name })
    }

    @Test
    fun `an empty session has nothing`() {
        val stats = statsOf(emptyList())
        assertEquals(0, stats.messages)
        assertEquals(0L, stats.firstMs)
        assertEquals(0, stats.estimatedTokens)
    }

    @Test
    fun `tokens are guessed at a quarter of the characters`() {
        assertEquals(0, estimateTokens(0))
        assertEquals(1, estimateTokens(1))
        assertEquals(1, estimateTokens(4))
        assertEquals(2, estimateTokens(5))
        assertEquals(25, statsOf(listOf(user(1, "x".repeat(100)))).estimatedTokens)
    }

    @Test
    fun `token counts are written short`() {
        assertEquals("950", formatTokens(950))
        assertEquals("1k", formatTokens(1_000))
        assertEquals("12.4k", formatTokens(12_400))
        assertEquals("128k", formatTokens(128_000))
        assertEquals("262k", formatTokens(262_144))
        assertEquals("1.5M", formatTokens(1_500_000))
    }

    @Test
    fun `model ids are written as names`() {
        assertEquals("Apodex 1.1 Mini", io.github.erkanw.vault.chat.prettyModelName("apodex/apodex-1.1-mini:free"))
        assertEquals("Qwen3.7 Flash", io.github.erkanw.vault.chat.prettyModelName("qwen/qwen3.7-flash"))
        assertEquals("Qwen3.8 27B", io.github.erkanw.vault.chat.prettyModelName("qwen/qwen3.8-27b"))
        assertEquals("Claude Sonnet 5.5", io.github.erkanw.vault.chat.prettyModelName("anthropic/claude-sonnet-5.5"))
        assertEquals("Claude Haiku 4.5", io.github.erkanw.vault.chat.prettyModelName("anthropic/claude-haiku-4.5"))
        assertEquals("GPT 4o", io.github.erkanw.vault.chat.prettyModelName("openai/gpt-4o"))
        assertEquals("Llama 3 70B", io.github.erkanw.vault.chat.prettyModelName("meta/llama-3-70b"))
        assertEquals("", io.github.erkanw.vault.chat.prettyModelName(""))
    }

    @Test
    fun `after the context is compressed only what came later is counted`() {
        val messages = listOf(
            user(1, "x".repeat(400), 1_000), reply(2, "y".repeat(400), 2_000),
            user(3, "z".repeat(40), 3_000), reply(4, "w".repeat(40), 4_000),
        )
        val before = statsOf(messages)
        assertEquals(220, before.estimatedTokens)
        assertFalse(before.compacted)
        // Compressed after the first two messages, with a 100-character summary standing in for them.
        val after = statsOf(messages, compactedAt = 2, summaryChars = 100)
        assertEquals(4, after.messages)  // the session is still that long
        assertEquals((80 + 100 + 3) / 4, after.estimatedTokens)
        assertTrue(after.compacted)
    }

}
