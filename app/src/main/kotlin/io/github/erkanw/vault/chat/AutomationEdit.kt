package io.github.erkanw.vault.chat

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Editing a scheduled job (PLAN §8): the sheet's fields, and what they make. A schedule is one of
 * `{kind: "every", every_ms}`, `{kind: "cron", expr, tz?}` or `{kind: "at", at_ms}` ([io.github.erkanw.vault.automation.Schedule]);
 * it is only checked and sent when one of its fields changed, so renaming a job never rewrites its schedule.
 */
enum class EveryUnit(val label: String, val ms: Long) {
    Minutes("Minutes", 60_000L),
    Hours("Hours", 3_600_000L),
    Days("Days", 86_400_000L),
}

/** What the schedule fields say while you edit them. [kind] is "every", "cron" or "at". */
data class ScheduleDraft(
    val kind: String,
    val everyValue: String = "1",
    val everyUnit: EveryUnit = EveryUnit.Hours,
    val cronExpr: String = "0 9 * * *",
    val tz: String = "",
    /** "2026-11-14" and "08:30", in the phone's time zone. */
    val date: String = "",
    val time: String = "",
    /** Set by any change to the fields: until then the job keeps the schedule it has, whatever the fields would make. */
    val touched: Boolean = false,
)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** The fields for [job]'s schedule as it is now; a job without one starts as every hour. */
fun scheduleDraftOf(job: AutomationJob, zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): ScheduleDraft {
    val unit = EveryUnit.entries.reversed().firstOrNull { job.everyMs >= it.ms && job.everyMs % it.ms == 0L } ?: EveryUnit.Minutes
    val at = java.time.Instant.ofEpochMilli(if (job.atMs > 0L) job.atMs else now + 3_600_000L).atZone(zone)
    return ScheduleDraft(
        kind = when (job.scheduleKind) { "cron", "at" -> job.scheduleKind; else -> "every" },
        everyValue = if (job.everyMs > 0L) (job.everyMs / unit.ms).coerceAtLeast(1L).toString() else "1",
        everyUnit = if (job.everyMs > 0L) unit else EveryUnit.Hours,
        cronExpr = job.cronExpr.ifBlank { "0 9 * * *" },
        tz = job.tz,
        date = at.format(DATE),
        time = at.format(TIME),
    )
}

/** The schedule the server would store, or why the fields can't be one. */
sealed interface ScheduleResult {
    data class Ok(val json: JSONObject) : ScheduleResult
    data class Invalid(val reason: String) : ScheduleResult
}

fun ScheduleDraft.toSchedule(zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): ScheduleResult =
    when (val r = fieldsToSchedule(zone, now)) {
        is ScheduleResult.Invalid -> r
        // Then the rules every schedule follows: at most every 15 minutes, a cron that parses, a time in the future.
        is ScheduleResult.Ok -> io.github.erkanw.vault.automation.Schedule.fromJson(r.json)?.problem(now)?.let { ScheduleResult.Invalid(it) } ?: r
    }

private fun ScheduleDraft.fieldsToSchedule(zone: ZoneId, now: Long): ScheduleResult = when (kind) {
    "every" -> {
        val n = everyValue.trim().toLongOrNull()
        if (n == null || n <= 0L) ScheduleResult.Invalid("Enter a whole number above zero.")
        else ScheduleResult.Ok(JSONObject().put("kind", "every").put("every_ms", n * everyUnit.ms))
    }
    "cron" -> {
        val expr = cronExpr.trim().replace(Regex("\\s+"), " ")
        when {
            expr.split(" ").size != 5 -> ScheduleResult.Invalid("A cron expression has five parts: minute, hour, day, month, weekday.")
            tz.isNotBlank() && runCatching { ZoneId.of(tz.trim()) }.isFailure -> ScheduleResult.Invalid("That time zone isn’t known. Try one like Europe/Stockholm.")
            else -> ScheduleResult.Ok(JSONObject().put("kind", "cron").put("expr", expr).apply { if (tz.isNotBlank()) put("tz", tz.trim()) })
        }
    }
    else -> {
        val d = runCatching { LocalDate.parse(date.trim(), DATE) }.getOrNull()
        val t = runCatching { LocalTime.parse(time.trim(), TIME) }.getOrNull()
        when {
            d == null -> ScheduleResult.Invalid("Write the date as 2026-11-14.")
            t == null -> ScheduleResult.Invalid("Write the time as 08:30.")
            else -> {
                val at = LocalDateTime.of(d, t).atZone(zone).toInstant().toEpochMilli()
                if (at <= now) ScheduleResult.Invalid("Choose a time in the future.")
                else ScheduleResult.Ok(JSONObject().put("kind", "at").put("at_ms", at))
            }
        }
    }
}

/** Whether [schedule] differs from what [job] runs on now (the WebUI only sends a schedule that changed). */
fun scheduleChanged(job: AutomationJob, schedule: JSONObject): Boolean = when (schedule.optString("kind")) {
    job.scheduleKind -> when (job.scheduleKind) {
        "every" -> schedule.optLong("every_ms") != job.everyMs
        "cron" -> schedule.optString("expr") != job.cronExpr || schedule.optString("tz") != job.tz
        else -> schedule.optLong("at_ms") != job.atMs
    }
    else -> true
}

/** The schedule [job] runs on now, as the server stores it; null for a kind the app doesn't know. */
fun currentSchedule(job: AutomationJob): JSONObject? = when (job.scheduleKind) {
    "every" -> JSONObject().put("kind", "every").put("every_ms", job.everyMs)
    "cron" -> JSONObject().put("kind", "cron").put("expr", job.cronExpr).apply { if (job.tz.isNotBlank()) put("tz", job.tz) }
    "at" -> JSONObject().put("kind", "at").put("at_ms", job.atMs)
    else -> null
}

/**
 * The `values` of `automation.update`, or why the edit can't be saved. A name and an instruction are both needed.
 * [schedule] is what the job will run on (null: a kind the app doesn't know, left as it is).
 */
sealed interface EditResult {
    data class Ok(val values: JSONObject, val schedule: JSONObject?) : EditResult
    data class Invalid(val reason: String) : EditResult
}

/**
 * The edit to send. The schedule is only looked at once you changed one of its fields: until then it is neither checked
 * nor sent, so renaming a job never rewrites a schedule the app can't show (or a one-time job whose time has passed).
 */
fun automationEdit(
    job: AutomationJob,
    name: String,
    message: String,
    draft: ScheduleDraft,
    zone: ZoneId = ZoneId.systemDefault(),
    now: Long = System.currentTimeMillis(),
): EditResult {
    val n = name.trim()
    val m = message.trim()
    if (n.isEmpty()) return EditResult.Invalid("Give it a name.")
    if (m.isEmpty()) return EditResult.Invalid("Write what it should do.")
    val values = JSONObject().put("name", n).put("message", m)
    if (!draft.touched) return EditResult.Ok(values, currentSchedule(job))
    return when (val s = draft.toSchedule(zone, now)) {
        is ScheduleResult.Invalid -> EditResult.Invalid(s.reason)
        is ScheduleResult.Ok -> {
            if (scheduleChanged(job, s.json)) values.put("schedule", s.json)
            EditResult.Ok(values, s.json)
        }
    }
}

/** A job that doesn't exist yet, for the editor's "new" mode: no id, no schedule, on. */
val NEW_JOB = AutomationJob("", "", true, false, "", 0L, 0L, "", "", false, "", "")

/** A new job's schedule fields: every day at 09:00 in the phone's time zone, already counted as chosen. */
fun newScheduleDraft(zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): ScheduleDraft {
    // "Once" starts at the next full hour, so switching to it shows a time that can be saved as it is.
    val next = java.time.Instant.ofEpochMilli(now).atZone(zone).plusHours(1).withMinute(0).withSecond(0).withNano(0)
    return ScheduleDraft(kind = "cron", cronExpr = "0 9 * * *", tz = zone.id, date = next.format(DATE), time = next.format(TIME), touched = true)
}

