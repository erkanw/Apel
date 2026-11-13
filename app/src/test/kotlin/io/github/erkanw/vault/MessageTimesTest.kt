package io.github.erkanw.vault

import io.github.erkanw.vault.chat.ChatMessage
import io.github.erkanw.vault.chat.ChatRow
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.chatRows
import io.github.erkanw.vault.chat.clockTime
import io.github.erkanw.vault.chat.dayLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class MessageTimesTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    private fun msg(id: Long, role: Role, t: Long) = ChatMessage(id, role, "text $id", createdAt = t)

    @Test
    fun `days are named Today, Yesterday, a weekday and a date`() {
        val now = at(10, 12)
        assertEquals("Today", dayLabel(now, at(10, 1), utc))
        assertEquals("Yesterday", dayLabel(now, at(9, 23), utc))
        assertTrue(dayLabel(now, at(7, 9), utc).isNotBlank())
        assertTrue(dayLabel(now, at(1, 9), utc).isNotBlank())
        assertEquals("", clockTime(0, utc))
        assertTrue(clockTime(at(10, 14, 5), utc).isNotBlank())
    }

    @Test
    fun `every row has its own key even when the days come round again or an id repeats`() {
        // The day goes 11, 10, 11 (messages not in time order): the list must not get two "d" keys the same. That
        // made the app crash on opening the session, and again on every start, since it reopens the last one.
        val rows = chatRows(
            listOf(
                msg(1, Role.User, at(11, 9)), msg(2, Role.Assistant, at(10, 9)),
                msg(3, Role.User, at(11, 10)), msg(4, Role.Assistant, at(10, 10)), msg(4, Role.Assistant, at(10, 11)),
            ),
            at(11, 12), utc,
        )
        val keys = rows.map { it.key }
        assertEquals(keys.toSet().size, keys.size)
        // The day only moves forward: the early ones stay under day 11, so there is one divider, not three.
        assertEquals(1, rows.count { it is ChatRow.Day })
        assertTrue(keys.contains("m4") && keys.contains("m4#1"))
        // In order, as ever: unchanged keys.
        val plain = chatRows(listOf(msg(1, Role.User, at(10, 9)), msg(2, Role.Assistant, at(11, 9))), at(11, 12), utc)
        assertEquals(4, plain.map { it.key }.toSet().size)
        assertTrue(plain.none { it.key.contains("#") })
    }

    @Test
    fun `a divider goes where the day changes, and a time under the end of each run`() {
        val now = at(10, 12)
        val rows = chatRows(
            listOf(
                msg(1, Role.User, at(9, 22)),
                msg(2, Role.Assistant, at(9, 22, 1)),
                msg(3, Role.Assistant, at(9, 22, 2)),
                msg(4, Role.User, at(10, 9)),
            ),
            now, utc,
        )
        assertEquals(
            listOf("Day", "Msg", "Msg", "Msg", "Day", "Msg"),
            rows.map { if (it is ChatRow.Day) "Day" else "Msg" },
        )
        assertEquals("Yesterday", (rows[0] as ChatRow.Day).label)
        assertEquals("Today", (rows[4] as ChatRow.Day).label)
        val m = rows.filterIsInstance<ChatRow.Msg>()
        // The two assistant messages a minute apart are one run: the avatar on the first, the time on the last.
        assertTrue(m[0].firstOfRun && m[0].showTime)
        assertTrue(m[1].firstOfRun && !m[1].showTime)
        assertTrue(!m[2].firstOfRun && m[2].showTime)
        // A new day starts a new run.
        assertTrue(m[3].firstOfRun && m[3].showTime)
    }

    @Test
    fun `a pause of more than five minutes ends a run`() {
        val rows = chatRows(
            listOf(msg(1, Role.Assistant, at(10, 9, 0)), msg(2, Role.Assistant, at(10, 9, 30))),
            at(10, 12), utc,
        ).filterIsInstance<ChatRow.Msg>()
        assertTrue(rows[0].showTime)
        assertTrue(rows[1].showTime)
    }

    @Test
    fun `messages without a time get no dividers and no times`() {
        val rows = chatRows(listOf(msg(1, Role.User, 0), msg(2, Role.Assistant, 0)), at(10, 12), utc)
        assertTrue(rows.all { it is ChatRow.Msg })
        assertFalse(rows.filterIsInstance<ChatRow.Msg>().any { it.showTime })
    }

    @Test
    fun `row keys are stable and different`() {
        val rows = chatRows(listOf(msg(1, Role.User, at(10, 9)), msg(2, Role.Assistant, at(10, 9, 1))), at(10, 12), utc)
        assertEquals(rows.size, rows.map { it.key }.toSet().size)
        assertTrue(rows.any { it.key == "m1" } && rows.any { it.key == "m2" })
    }
}
