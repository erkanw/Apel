package io.github.erkanw.vault

import io.github.erkanw.vault.mascot.BlobState
import io.github.erkanw.vault.mascot.SleepSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class SleepScheduleTest {
    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    @Test
    fun `he sleeps from nine at night to seven in the morning`() {
        for (hour in listOf(21, 22, 23, 0, 3, 6)) assertTrue("hour $hour", SleepSchedule.isAsleepAt(hour))
        for (hour in listOf(7, 8, 12, 18, 20)) assertTrue("hour $hour", !SleepSchedule.isAsleepAt(hour))
    }

    @Test
    fun `the next change is the next nine pm or seven am`() {
        assertEquals(at(10, 21), SleepSchedule.nextChange(at(10, 12), utc))
        assertEquals(at(11, 7), SleepSchedule.nextChange(at(10, 22), utc))
        assertEquals(at(10, 7), SleepSchedule.nextChange(at(10, 3), utc))
        // At the exact moment he falls asleep, the next change is the morning.
        assertEquals(at(11, 7), SleepSchedule.nextChange(at(10, 21), utc))
    }

    @Test
    fun `only an idle Blob goes to sleep, and only when it is on`() {
        val night = at(10, 23)
        val day = at(10, 14)
        assertEquals(BlobState.Sleeping, SleepSchedule.display(BlobState.Idle, night, true, utc))
        assertEquals(BlobState.Idle, SleepSchedule.display(BlobState.Idle, night, false, utc))
        assertEquals(BlobState.Idle, SleepSchedule.display(BlobState.Idle, day, true, utc))
        for (busy in listOf(BlobState.Thinking, BlobState.Working, BlobState.Error, BlobState.Finished)) {
            assertEquals(busy, SleepSchedule.display(busy, night, true, utc))
        }
    }
}
