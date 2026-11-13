package io.github.erkanw.vault

import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.ChatRow
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.SessionSummary
import io.github.erkanw.vault.chat.chatRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class BugFixesTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")
    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    private fun msg(id: Long, role: Role, t: Long, text: String = "text $id", error: Boolean = false) =
        ChatMessage(id, role, text, isError = error, createdAt = t)

    // ---- the connection notification ----------------------------------------------------------------------------

    // ---- new messages ----------------------------------------------------------------------------------------------

    private fun session(updated: Long) = SessionSummary("c1", "websocket:c1", "t", "p", updated, running = false, pinned = false)

    // ---- the day markers -------------------------------------------------------------------------------------------

    // ---- a turn that is still running ------------------------------------------------------------------------------

    @Test
    fun `the day never moves back, so a message with an earlier time stays under the day it is in`() {
        val rows = chatRows(
            listOf(msg(1, Role.User, at(12, 9)), msg(2, Role.Assistant, at(11, 9)), msg(3, Role.User, at(12, 10))),
            at(12, 12), utc,
        )
        assertEquals(1, rows.count { it is ChatRow.Day })
    }
}
