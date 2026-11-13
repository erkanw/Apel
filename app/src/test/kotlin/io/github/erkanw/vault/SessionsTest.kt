package io.github.erkanw.vault

import io.github.erkanw.vault.chat.NEW_SESSION_TITLE
import io.github.erkanw.vault.chat.Role
import io.github.erkanw.vault.chat.parseTimeMillis
import io.github.erkanw.vault.chat.relativeTime
import io.github.erkanw.vault.ui.EntranceTracker
import io.github.erkanw.vault.ui.staggerDelayMs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SessionsTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    @Test
    fun `times are read as seconds, milliseconds or ISO text`() {
        assertEquals(1_760_000_000_000L, parseTimeMillis(1_760_000_000))
        assertEquals(1_760_000_000_000L, parseTimeMillis(1_760_000_000_000L))
        assertEquals(1_760_000_000_500L, parseTimeMillis(1_760_000_000.5))
        assertEquals(1_760_000_000_000L, parseTimeMillis("1760000000"))
        assertEquals(at(10, 12), parseTimeMillis("2026-10-10T12:00:00Z"))
        assertEquals(0L, parseTimeMillis("garbage"))
        assertEquals(0L, parseTimeMillis(null))
    }

    @Test
    fun `the time on a row is short and human`() {
        val now = at(10, 15)
        assertEquals("", relativeTime(now, 0, utc))
        assertEquals("now", relativeTime(now, now - 20_000, utc))
        assertEquals("5 min", relativeTime(now, now - 5 * 60_000, utc))
        assertEquals("3 h", relativeTime(now, now - 3 * 3_600_000, utc))
        assertEquals("Yesterday", relativeTime(now, at(9, 22), utc))
        assertTrue(relativeTime(now, at(7, 9), utc).isNotBlank())
        assertTrue(relativeTime(now, at(1, 9), utc).isNotBlank())
    }

    @Test
    fun `list rows arrive one after another and a long list doesn't take forever`() {
        assertEquals(0, staggerDelayMs(0))
        assertEquals(40, staggerDelayMs(1))
        assertEquals(8 * 40, staggerDelayMs(8))
        assertEquals(8 * 40, staggerDelayMs(500))
        assertEquals(0, staggerDelayMs(-3))
    }

    @Test
    fun `a row that has arrived is remembered`() {
        val tracker = EntranceTracker()
        assertFalse(tracker.played("a"))
        tracker.mark("a")
        assertTrue(tracker.played("a"))
        assertFalse(tracker.played("b"))
    }
}

class ArchiveTest {

}

class SessionsFromTheRealServerTest {
    private val paris: java.time.ZoneId = java.time.ZoneId.of("Europe/Paris")

    @Test
    fun `the server's timestamps have no zone and are UTC, whatever the phone's zone is`() {
        // Measured on the phone: a session made at 00:49 in Stockholm (UTC+2) came back stamped 22:49:11.
        val utc = java.time.Instant.parse("2026-10-10T22:49:11Z").toEpochMilli()
        assertEquals(utc, parseTimeMillis("2026-10-10T22:49:11"))
        val expected = java.time.LocalDateTime.of(2026, 10, 10, 15, 23, 44, 304_122_000)
            .atZone(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        assertEquals(expected, parseTimeMillis("2026-10-10T15:23:44.304122"))
        // A zone can still be asked for (and then it is used for the zone-less ones).
        assertEquals(
            java.time.LocalDateTime.of(2026, 10, 10, 15, 23, 44, 304_122_000).atZone(paris).toInstant().toEpochMilli(),
            parseTimeMillis("2026-10-10T15:23:44.304122", paris),
        )
        // With a zone, the zone wins.
        assertEquals(java.time.Instant.parse("2026-10-10T15:23:44Z").toEpochMilli(), parseTimeMillis("2026-10-10T15:23:44Z", paris))
    }

}
