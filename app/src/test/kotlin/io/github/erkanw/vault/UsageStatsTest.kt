package io.github.erkanw.vault

import io.github.erkanw.vault.openrouter.weekOf
import io.github.erkanw.vault.usage.UsageCall
import io.github.erkanw.vault.usage.UsageStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime

class UsageStatsTest {
    private fun at(y: Int, m: Int, d: Int, h: Int = 12, min: Int = 0) =
        ZonedDateTime.of(y, m, d, h, min, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

    private fun call(time: Long, cost: Double?, model: String = "a/m", source: String = "user", auto: String? = null, requests: Int = 1) =
        UsageCall(time, model, source, auto, requests, 1000, 100, cost)

    // Wednesday 18 November 2026, 10:00 UTC.
    private val now = at(2026, 11, 18, 10)

    @Test
    fun todayWeekAndMonthAreUtc() {
        val calls = listOf(
            call(at(2026, 11, 18, 0, 30), 0.01), // today, just after midnight UTC
            call(at(2026, 11, 17, 23, 59), 0.02), // yesterday, still this week
            call(at(2026, 11, 16, 0, 0), 0.04), // Monday: this week
            call(at(2026, 11, 15, 23, 0), 0.08), // Sunday: last week, this month
            call(at(2026, 10, 31, 23, 0), 0.16), // last month, inside 30 days
        )
        val s = UsageStats.summarize(calls, now)
        assertEquals(0.01, s.today, 1e-9)
        assertEquals(0.07, s.week, 1e-9)
        assertEquals(0.15, s.month, 1e-9)
        assertEquals(0.31, s.total, 1e-9)
        assertEquals(30, s.days.size)
        assertEquals("2026-11-18", s.days.last().date)
        assertEquals(0.01, s.days.last().usage, 1e-9)
        assertEquals("2026-10-20", s.days.first().date)
    }

    @Test
    fun theMonthStartsOnTheFirstEvenBeyondThirtyDays() {
        // 31 December: the month began 30 days ago, so "since" reaches the 1st.
        val end = at(2026, 12, 31, 20)
        assertEquals(at(2026, 12, 1, 0), UsageStats.since(end))
        val s = UsageStats.summarize(listOf(call(at(2026, 12, 1, 0, 1), 0.5), call(at(2026, 11, 30, 23), 1.0)), end)
        assertEquals(0.5, s.month, 1e-9)
        // The 1st is outside the 30-day bars (2 Dec to 31 Dec) but in the month.
        assertEquals(0.0, s.total, 1e-9)
    }

    @Test
    fun modelsSourcesAndCounters() {
        val calls = listOf(
            call(now - 1000, 0.003, model = "x/cheap", requests = 2),
            call(now - 2000, 0.010, model = "x/dear"),
            call(now - 3000, 0.0, model = "y/free:free", requests = 3),
            call(now - 4000, 0.0, model = "y/free:free", source = "automation", auto = "brief"),
            call(now - 5000, 0.004, model = "x/cheap", source = "automation", auto = "gone"),
            call(now - 6000, null, model = "local/llama"),
        )
        val s = UsageStats.summarize(calls, now, mapOf("brief" to "Morning brief"))
        assertEquals(listOf("x/dear", "x/cheap"), s.models.take(2).map { it.model })
        assertEquals(3, s.models.first { it.model == "x/cheap" }.requests)
        assertEquals("You", s.sources.first().label)
        assertEquals(setOf("You", "Morning brief", "A deleted automation"), s.sources.map { it.label }.toSet())
        assertEquals(4, s.freeToday)
        assertEquals(6, s.messages)
        assertEquals(9, s.requests)
        assertEquals(1, s.unpriced)
        assertEquals(0.017 / 6, s.perMessage!!, 1e-9)
    }

    @Test
    fun emptyLog() {
        val s = UsageStats.summarize(emptyList(), now)
        assertTrue(s.isEmpty)
        assertNull(s.perMessage)
        assertNull(s.busiest)
    }

    @Test
    fun theWidgetsWeekCountsToday() {
        val rows = UsageStats.activityRows(listOf(call(now, 0.02, model = "a/one"), call(at(2026, 11, 16), 0.01, model = "a/two")))
        val today = LocalDate.of(2026, 11, 18)
        val week = weekOf(rows, today, includeToday = true)!!
        assertEquals(0.03, week.spend, 1e-9)
        assertEquals("2026-11-18", week.through)
        // OpenRouter's own activity has no today: without the flag, only Monday counts.
        assertEquals(0.01, weekOf(rows, today)!!.spend, 1e-9)
    }
}
