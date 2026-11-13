package io.github.erkanw.vault

import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.groupSessions
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.TimeZone

class SessionGroupsTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val noon = 1_700_000_000_000L - (1_700_000_000_000L % 86_400_000L) + 12 * 3_600_000L   // noon UTC on some day

    private fun s(id: String, agoMs: Long, pinned: Boolean = false) =
        SessionSummary(chatId = id, key = "websocket:$id", title = id, preview = "", updatedMs = noon - agoMs, running = false, pinned = pinned)

    @Test
    fun `chats are grouped by how long ago they were used, pinned ones first`() {
        val day = 86_400_000L
        val groups = groupSessions(
            listOf(s("pin", 5 * day, pinned = true), s("a", 60_000L), s("b", 3 * 3_600_000L), s("c", day), s("d", 3 * day), s("e", 30 * day), s("zero", noon)),
            noon, utc,
        )
        assertEquals(listOf("Pinned", "Today", "Yesterday", "Previous 7 days", "Earlier"), groups.map { it.label })
        assertEquals(listOf("pin"), groups[0].sessions.map { it.chatId })
        assertEquals(listOf("a", "b"), groups[1].sessions.map { it.chatId })
        assertEquals(listOf("c"), groups[2].sessions.map { it.chatId })
        assertEquals(listOf("d"), groups[3].sessions.map { it.chatId })
        assertEquals(listOf("e", "zero"), groups[4].sessions.map { it.chatId })    // a chat with no known time sits with the oldest
    }

    @Test
    fun `groups with nothing in them are left out and a future time counts as today`() {
        val groups = groupSessions(listOf(s("future", -5 * 60_000L)), noon, utc)
        assertEquals(listOf("Today"), groups.map { it.label })
        assertEquals(emptyList<String>(), groupSessions(emptyList(), noon, utc).map { it.label })
    }
}
