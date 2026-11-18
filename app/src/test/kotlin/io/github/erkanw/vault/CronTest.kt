package io.github.erkanw.vault

import io.github.erkanw.vault.automation.Cron
import io.github.erkanw.vault.automation.Schedule
import io.github.erkanw.vault.automation.TidyMemory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** The cron parser and schedules of Phase 5 (PLAN §8), time changes included. */
class CronTest {
    private val stockholm = ZoneId.of("Europe/Stockholm")
    private fun at(s: String, zone: ZoneId = stockholm): Instant = LocalDateTime.parse(s).atZone(zone).toInstant()
    private fun local(i: Instant?, zone: ZoneId = stockholm): String = ZonedDateTime.ofInstant(i!!, zone).toLocalDateTime().toString()

    @Test
    fun `daily at nine runs next at nine, today or tomorrow`() {
        val cron = Cron.parse("0 9 * * *")
        assertEquals("2026-11-17T09:00", local(cron.next(at("2026-11-17T08:59"), stockholm)))
        assertEquals("2026-11-18T09:00", local(cron.next(at("2026-11-17T09:00"), stockholm)))
    }

    @Test
    fun `steps, ranges, lists and names`() {
        assertEquals("2026-11-17T10:15", local(Cron.parse("*/15 * * * *").next(at("2026-11-17T10:07"), stockholm)))
        // Weekdays at 08:00: Friday the 20th → Monday the 23rd.
        assertEquals("2026-11-23T08:00", local(Cron.parse("0 8 * * MON-FRI").next(at("2026-11-20T09:00"), stockholm)))
        assertEquals("2026-11-17T18:00", local(Cron.parse("0,30 8-18/2 * * *").next(at("2026-11-17T17:00"), stockholm)))
        assertEquals("2026-11-18T08:00", local(Cron.parse("0,30 8-18/2 * * *").next(at("2026-11-17T18:30"), stockholm)))
        assertEquals("2027-01-01T00:00", local(Cron.parse("@yearly").next(at("2026-11-17T10:00"), stockholm)))
        // Sunday as 7.
        assertEquals("2026-11-22T12:00", local(Cron.parse("0 12 * * 7").next(at("2026-11-17T10:00"), stockholm)))
        assertEquals("2026-12-01T06:00", local(Cron.parse("0 6 1 DEC *").next(at("2026-11-17T10:00"), stockholm)))
    }

    @Test
    fun `day of month and weekday together match either`() {
        // The 1st, or any Monday: from Tue 17 Nov the next is Mon 23 Nov.
        assertEquals("2026-11-23T09:00", local(Cron.parse("0 9 1 * 1").next(at("2026-11-17T10:00"), stockholm)))
    }

    @Test
    fun `the 29th of February waits for a leap year`() {
        assertEquals("2028-02-29T00:00", local(Cron.parse("0 0 29 2 *").next(at("2026-11-17T10:00"), stockholm)))
        assertNull(Cron.parse("0 0 31 2 *").next(at("2026-11-17T10:00"), stockholm))
    }

    @Test
    fun `a time skipped when the clocks go forward runs just after the jump`() {
        // Stockholm, 29 March 2026: 02:00 → 03:00. 02:30 doesn't exist that night.
        val next = Cron.parse("30 2 * * *").next(at("2026-03-29T01:00"), stockholm)
        assertEquals("2026-03-29T03:30", local(next))
        // And the next night it is 02:30 again.
        assertEquals("2026-03-30T02:30", local(Cron.parse("30 2 * * *").next(next!!, stockholm)))
    }

    @Test
    fun `a time that happens twice when the clocks go back runs once`() {
        // Stockholm, 25 October 2026: 03:00 → 02:00, so 02:30 happens twice.
        val cron = Cron.parse("30 2 * * *")
        val first = cron.next(at("2026-10-25T01:00"), stockholm)!!
        assertEquals(ZonedDateTime.of(LocalDateTime.parse("2026-10-25T02:30"), stockholm).withEarlierOffsetAtOverlap().toInstant(), first)
        assertEquals("2026-10-26T02:30", local(cron.next(first, stockholm)))
    }

    @Test
    fun `wrong expressions say what is wrong`() {
        for (bad in listOf("0 9 * *", "60 * * * *", "0 24 * * *", "0 9 0 * *", "0 9 * 13 *", "0 9 * * 8", "a * * * *", "0 9-5 * * *", "*/0 * * * *")) {
            assertNull(bad, Cron.parseOrNull(bad))
        }
        assertTrue(runCatching { Cron.parse("0 9 * *") }.exceptionOrNull()!!.message!!.contains("five parts"))
    }

    @Test
    fun `every keeps its rhythm and never makes up a backlog`() {
        val hour = 3_600_000L
        val s = Schedule(Schedule.EVERY, everyMs = 2 * hour)
        // Made at 0: the first run is two hours later.
        assertEquals(2 * hour, s.next(0L, anchor = 0L).let { it!! - 0L })
        // Planned at 10:00, the phone was off until 15:30: the next one is 16:00, not 12:00 and 14:00 as well.
        assertEquals(16 * hour, s.next(15 * hour + hour / 2, anchor = 10 * hour))
        // A planned time still ahead stays.
        assertEquals(12 * hour, s.next(11 * hour, anchor = 12 * hour))
    }

    @Test
    fun `a once job runs once`() {
        val s = Schedule(Schedule.AT, atMs = 5_000L)
        assertEquals(5_000L, s.next(1_000L))
        assertNull(s.next(5_000L))
    }

    @Test
    fun `schedules too often, in the past or wrong are refused`() {
        val now = at("2026-11-17T10:00").toEpochMilli()
        assertNotNull(Schedule(Schedule.EVERY, everyMs = 10 * 60_000L).problem(now))
        assertNull(Schedule(Schedule.EVERY, everyMs = 15 * 60_000L).problem(now))
        assertNotNull(Schedule(Schedule.CRON, expr = "*/5 * * * *").problem(now))
        assertNotNull(Schedule(Schedule.CRON, expr = "0,5 9 * * *").problem(now))
        assertNull(Schedule(Schedule.CRON, expr = "*/15 * * * *").problem(now))
        assertNotNull(Schedule(Schedule.CRON, expr = "0 9 * * *", tz = "Mars/Olympus").problem(now))
        assertNotNull(Schedule(Schedule.AT, atMs = now - 1).problem(now))
        assertNull(Schedule(Schedule.AT, atMs = now + 120_000L).problem(now))
    }

    @Test
    fun `a schedule goes to JSON and back`() {
        for (s in listOf(Schedule(Schedule.EVERY, everyMs = 900_000L), Schedule(Schedule.CRON, expr = "0 9 * * *", tz = "Europe/Stockholm"), Schedule(Schedule.AT, atMs = 42L))) {
            assertEquals(s, Schedule.fromJson(s.toJson()))
        }
    }

    @Test
    fun `tidy memory refuses a result that drops most notes`() {
        val before = (1..10).joinToString("\n") { "- fact $it" }
        assertNotNull(TidyMemory.judge(before, "- fact 1\n- fact 2"))
        assertNull(TidyMemory.judge(before, before + "\n- fact 11"))
        assertEquals("No changes needed.", TidyMemory.judge(before, "NO_CHANGES"))
        assertNotNull(TidyMemory.judge(before, "  "))
        assertEquals("- a", TidyMemory.cleanResult("```markdown\n- a\n```"))
        assertFalse(TidyMemory.judge("", "- new") != null)
    }
}
