package io.github.erkanw.vault

import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.chatsWithNews
import io.github.erkanw.vault.chat.hasNews
import io.github.erkanw.vault.chat.notificationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelWatchTest {
    private fun session(chat: String, updated: Long, running: Boolean = false, archived: Boolean = false, title: String = "t") =
        SessionSummary(chat, "websocket:$chat", title, "p", updated, running, pinned = false, archived = archived)

    private val bound = mapOf(Channel.DailyBrief.id to "brief", Channel.Notes.id to "notes")

    @Test
    fun `a chat updated after you last had it open has news, any chat`() {
        val sessions = listOf(session("brief", 10_000), session("notes", 5_000), session("random", 9_000, title = "Trip plan"))
        val news = chatsWithNews(sessions, bound, seen = mapOf("brief" to 4_000L), told = emptyMap(), baselineMs = 1_000, channels = Channel.starters)
        assertEquals(listOf("brief", "notes", "random"), news.map { it.chatId })
        // A channel is called by its name, a plain session by its title.
        assertEquals(listOf("Daily brief", "Notes", "Trip plan"), news.map { it.title })
        assertEquals(Channel.DailyBrief, news[0].channel)
        assertNull(news[2].channel)
    }

    @Test
    fun `nothing is news that you saw, were told about, or that was there before watching began`() {
        val sessions = listOf(session("a", 10_000), session("b", 10_000))
        // Seen after it, told of it, or older than the watching itself: not news.
        assertTrue(chatsWithNews(sessions, bound, mapOf("a" to 10_500L), mapOf("b" to 10_000L), 0).isEmpty())
        assertTrue(chatsWithNews(sessions, bound, emptyMap(), emptyMap(), baselineMs = 20_000).isEmpty())
        // The same moment, give or take the two clocks (2 s): still not news. A few seconds later is.
        assertTrue(chatsWithNews(sessions, bound, mapOf("a" to 9_000L, "b" to 9_000L), emptyMap(), 0).isEmpty())
        assertEquals(2, chatsWithNews(sessions, bound, mapOf("a" to 7_000L, "b" to 7_000L), emptyMap(), 0).size)
        // Seen and told are separate marks: the later one counts, per chat.
        assertEquals(listOf("a"), chatsWithNews(sessions, bound, mapOf("a" to 7_000L), mapOf("b" to 9_500L), 0).map { it.chatId })
    }

    @Test
    fun `a chat the agent is still working in waits, and an archived one is left alone`() {
        assertFalse(hasNews(session("a", 10_000, running = true), emptyMap(), emptyMap(), 0))
        assertFalse(hasNews(session("a", 10_000, archived = true), emptyMap(), emptyMap(), 0))
        assertTrue(hasNews(session("a", 10_000), emptyMap(), emptyMap(), 0))
        assertTrue(chatsWithNews(emptyList(), bound, emptyMap(), emptyMap(), 0).isEmpty())
    }

    @Test
    fun `a notification shows plain words, not Markdown, and is cut on a word`() {
        val text = "## Good morning\n\n**Weather:** sunny, 14°\n- Training at *18:30*\n- `Math` test\n\n\n\nHave a good day"
        assertEquals("Good morning\n\nWeather: sunny, 14°\n• Training at 18:30\n• Math test\n\nHave a good day", notificationText(text))
        val long = notificationText("word ".repeat(400), limit = 100)
        assertTrue(long.length <= 101 && long.endsWith("…") && !long.dropLast(1).endsWith(" "))
        assertEquals("", notificationText("  \n "))
    }
}
