package io.github.erkanw.vault

import io.github.erkanw.vault.chat.AutomationJob
import io.github.erkanw.vault.chat.EditResult
import io.github.erkanw.vault.chat.EveryUnit
import io.github.erkanw.vault.chat.ScheduleResult
import io.github.erkanw.vault.chat.automationEdit
import io.github.erkanw.vault.chat.chatId
import io.github.erkanw.vault.chat.moveJobRequest
import io.github.erkanw.vault.chat.scheduleDraftOf
import io.github.erkanw.vault.chat.toSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class AutomationEditTest {
    private val zone = ZoneId.of("Europe/Stockholm")
    private val now = 1_794_400_000_000L // 2026-11-11

    private fun job(kind: String, expr: String = "", tz: String = "", everyMs: Long = 0L, atMs: Long = 0L) = AutomationJob(
        "j1", "Morning brief", true, false, "", 0L, 0L, "ok", "", false, "Send me the news.", "websocket",
        scheduleKind = kind, cronExpr = expr, tz = tz, everyMs = everyMs, atMs = atMs, sessionKey = "websocket:abc",
    )

    @Test
    fun `the fields start from the job's own schedule`() {
        val cron = scheduleDraftOf(job("cron", "6 8 * * *", "Europe/Stockholm"), zone, now)
        assertEquals("cron", cron.kind)
        assertEquals("6 8 * * *", cron.cronExpr)
        assertEquals("Europe/Stockholm", cron.tz)
        val every = scheduleDraftOf(job("every", everyMs = 6 * 3_600_000L), zone, now)
        assertEquals("every", every.kind)
        assertEquals("6", every.everyValue)
        assertEquals(EveryUnit.Hours, every.everyUnit)
        assertEquals(EveryUnit.Minutes, scheduleDraftOf(job("every", everyMs = 90 * 60_000L), zone, now).everyUnit)
        assertEquals(EveryUnit.Days, scheduleDraftOf(job("every", everyMs = 2 * 86_400_000L), zone, now).everyUnit)
    }

    @Test
    fun `an unchanged schedule is not sent, a changed one is, as the WebUI does`() {
        val j = job("cron", "6 8 * * *", "Europe/Stockholm")
        val same = automationEdit(j, "Morning brief", "Send me the news.", scheduleDraftOf(j, zone, now), zone, now) as EditResult.Ok
        assertFalse(same.values.has("schedule"))
        assertEquals("Morning brief", same.values.getString("name"))
        val later = automationEdit(j, " Brief ", "Send me the news.", scheduleDraftOf(j, zone, now).copy(cronExpr = "30  7 * * *", touched = true), zone, now) as EditResult.Ok
        assertEquals("Brief", later.values.getString("name"))
        val schedule = later.values.getJSONObject("schedule")
        assertEquals("cron", schedule.getString("kind"))
        assertEquals("30 7 * * *", schedule.getString("expr"))
        assertEquals("Europe/Stockholm", schedule.getString("tz"))
    }

    @Test
    fun `every, cron and once become the server's three schedules`() {
        val j = job("every", everyMs = 3_600_000L)
        val draft = scheduleDraftOf(j, zone, now)
        val every = draft.copy(everyValue = "15", everyUnit = EveryUnit.Minutes).toSchedule(zone, now) as ScheduleResult.Ok
        assertEquals(15 * 60_000L, every.json.getLong("every_ms"))
        val cron = draft.copy(kind = "cron", cronExpr = "0 9 * * 1", tz = "").toSchedule(zone, now) as ScheduleResult.Ok
        assertFalse(cron.json.has("tz"))
        val once = draft.copy(kind = "at", date = "2026-11-14", time = "08:30").toSchedule(zone, now) as ScheduleResult.Ok
        assertEquals(java.time.ZonedDateTime.of(2026, 11, 14, 8, 30, 0, 0, zone).toInstant().toEpochMilli(), once.json.getLong("at_ms"))
    }

    @Test
    fun `fields that can't make a schedule say why`() {
        val draft = scheduleDraftOf(job("every", everyMs = 3_600_000L), zone, now).copy(touched = true)
        assertTrue(draft.copy(everyValue = "0").toSchedule(zone, now) is ScheduleResult.Invalid)
        assertTrue(draft.copy(everyValue = "").toSchedule(zone, now) is ScheduleResult.Invalid)
        assertTrue(draft.copy(kind = "cron", cronExpr = "0 9 * *").toSchedule(zone, now) is ScheduleResult.Invalid)
        assertTrue(draft.copy(kind = "cron", tz = "Mars/Olympus").toSchedule(zone, now) is ScheduleResult.Invalid)
        assertTrue(draft.copy(kind = "at", date = "14/11/2026", time = "08:30").toSchedule(zone, now) is ScheduleResult.Invalid)
        assertTrue(draft.copy(kind = "at", date = "2020-01-01", time = "08:30").toSchedule(zone, now) is ScheduleResult.Invalid)
        val j = job("every", everyMs = 3_600_000L)
        assertTrue(automationEdit(j, " ", "x", draft, zone, now) is EditResult.Invalid)
        assertTrue(automationEdit(j, "x", "  ", draft, zone, now) is EditResult.Invalid)
    }

    @Test
    fun `renaming a job never touches a schedule the fields didn't change`() {
        // A kind the app doesn't know opens as every hour, but saving a new name must not make it hourly.
        val odd = job("local")
        val renamed = automationEdit(odd, "New name", "Send me the news.", scheduleDraftOf(odd, zone, now), zone, now) as EditResult.Ok
        assertFalse(renamed.values.has("schedule"))
        assertNull(renamed.schedule)
        // A one-time job whose time has passed can still be renamed.
        val past = job("at", atMs = now - 86_400_000L)
        assertTrue(automationEdit(past, "Done once", "x", scheduleDraftOf(past, zone, now), zone, now) is EditResult.Ok)
        // Touch the fields and it is checked.
        assertTrue(automationEdit(past, "Done once", "x", scheduleDraftOf(past, zone, now).copy(touched = true), zone, now) is EditResult.Invalid)
    }

    @Test
    fun `a paused job moves paused, and an unknown schedule is kept as it is`() {
        val off = job("local").copy(enabled = false, schedule = "On a trigger")
        val text = moveJobRequest(off, "Plants", "Water them.", null, "Home")
        assertTrue(text.contains("Create it turned off"))
        assertTrue(text.contains("exactly the schedule of the existing job (On a trigger)"))
        assertFalse(moveJobRequest(job("every", everyMs = 60_000L), "a", "b", null, "Home").contains("turned off"))
    }

    @Test
    fun `the move request names the job to remove and carries the instruction word for word`() {
        val j = job("cron", "6 8 * * *", "Europe/Stockholm")
        val ok = automationEdit(j, "Brief", "Send me the news.\nThree links.", scheduleDraftOf(j, zone, now), zone, now) as EditResult.Ok
        val text = moveJobRequest(j, "Brief", "Send me the news.\nThree links.", ok.schedule, "Daily brief")
        assertTrue(text.contains("(Daily brief)"))
        assertTrue(text.contains("id j1"))
        assertTrue(text.contains("cron_expr \"6 8 * * *\" with tz \"Europe/Stockholm\""))
        assertTrue(text.contains("<<<\nSend me the news.\nThree links.\n>>>"))
    }
}
