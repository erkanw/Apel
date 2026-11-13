package io.github.erkanw.vault.mascot

import java.util.Calendar
import java.util.TimeZone

/**
 * When Blob sleeps: from 21:00 to 07:00 local time, but only when he has nothing
 * else to be (a reply on its way, an error and so on all win over sleep).
 */
object SleepSchedule {
    const val SLEEP_FROM_HOUR = 21
    const val WAKE_AT_HOUR = 7

    fun isAsleepAt(hour: Int): Boolean = hour >= SLEEP_FROM_HOUR || hour < WAKE_AT_HOUR

    fun isAsleep(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Boolean {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        return isAsleepAt(cal.get(Calendar.HOUR_OF_DAY))
    }

    /** The next moment he falls asleep or wakes up after [nowMs]: what the widget must refresh for. */
    fun nextChange(nowMs: Long, zone: TimeZone = TimeZone.getDefault()): Long {
        val cal = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        var best = Long.MAX_VALUE
        for (hour in intArrayOf(SLEEP_FROM_HOUR, WAKE_AT_HOUR)) {
            val candidate = (cal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= nowMs) add(Calendar.DAY_OF_MONTH, 1)
            }
            best = minOf(best, candidate.timeInMillis)
        }
        return best
    }

    /** What to show for [state]: an idle Blob sleeps at night, any other state is left alone. */
    fun display(state: BlobState, nowMs: Long, enabled: Boolean, zone: TimeZone = TimeZone.getDefault()): BlobState =
        if (enabled && state == BlobState.Idle && isAsleep(nowMs, zone)) BlobState.Sleeping else state
}
