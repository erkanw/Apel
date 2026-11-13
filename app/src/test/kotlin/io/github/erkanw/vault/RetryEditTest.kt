package io.github.erkanw.vault

import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.ChatRow
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.chatRows
import io.github.erkanw.vault.chat.promptOf
import io.github.erkanw.vault.chat.userIndexBefore
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class RetryEditTest {
    private fun u(id: Long) = ChatMessage(id, Role.User, "q$id", createdAt = 0)
    private fun a(id: Long) = ChatMessage(id, Role.Assistant, "a$id", createdAt = 0)

    @Test
    fun `a fork starts before the message, counting your messages from zero across the whole chat`() {
        val chat = listOf(u(1), a(2), u(3), a(4), u(5), a(6))
        assertEquals(0, userIndexBefore(chat, 0, 0))
        assertEquals(1, userIndexBefore(chat, 2, 0))
        assertEquals(2, userIndexBefore(chat, 4, 0))
        // Only the latest page is on screen: the messages before it count too.
        assertEquals(5, userIndexBefore(chat, 2, 4))
        assertEquals(4, userIndexBefore(chat, 0, 4))
    }

    @Test
    fun `a reply is retried from the message of yours it answers`() {
        val chat = listOf(u(1), a(2), a(3), u(4), a(5))
        assertEquals(1L, promptOf(chat, 1)?.id)
        assertEquals(1L, promptOf(chat, 2)?.id)
        assertEquals(4L, promptOf(chat, 4)?.id)
        assertNull(promptOf(listOf(a(1)), 0))
        assertNull(promptOf(chat, 0))
    }

    @Test
    fun `a line marks where a fork's own messages begin`() {
        val rows = chatRows(listOf(u(1), a(2), a(3), a(4)), 0L, TimeZone.getTimeZone("UTC"), forkBoundary = 3)
        assertEquals(listOf("Msg", "Msg", "Msg", "Marker", "Msg"), rows.map { r -> r::class.simpleName })
        val marker = rows[3] as ChatRow.Marker
        assertEquals("Forked from history", marker.label)
        // The first message after the line starts a new run, so the agent’s avatar shows again.
        assertTrue((rows[4] as ChatRow.Msg).firstOfRun)
        // No boundary, no line.
        assertTrue(chatRows(listOf(u(1), a(2)), 0L, TimeZone.getTimeZone("UTC")).none { it is ChatRow.Marker })
    }
}
