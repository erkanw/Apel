package io.github.erkanw.vault.chat

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Editing a scheduled job, as the agent’s WebUI does it: `automation.update` with `{name, message, schedule}`, where the
 * schedule is sent only when it changed and is one of `{kind: "every", every_ms}`, `{kind: "cron", expr, tz?}` or
 * `{kind: "at", at_ms}`. The server can't move a job to another chat (a job belongs to the chat it was made from), so a
 * new channel is reached by asking the agent, in that channel, to make the job there and remove this one ([moveJobRequest]).
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

fun ScheduleDraft.toSchedule(zone: ZoneId = ZoneId.systemDefault(), now: Long = System.currentTimeMillis()): ScheduleResult = when (kind) {
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

/** The chat a job reports into, from its session key (`websocket:<chat id>`); null for a job from elsewhere. */
fun AutomationJob.chatId(): String? = sessionKey.ifBlank { null }

/**
 * The message that asks the agent, from inside [channelTitle]'s chat, to make [job] there (with the name, schedule and
 * instruction as edited) and then remove the original, so the job moves. Put in the message box to read before sending.
 */
fun moveJobRequest(job: AutomationJob, name: String, message: String, schedule: JSONObject?, channelTitle: String): String {
    val s = if (schedule == null) "exactly the schedule of the existing job" + if (job.schedule.isNotBlank()) " (${job.schedule})" else "" else when (schedule.optString("kind")) {
        "cron" -> "cron_expr \"${schedule.optString("expr")}\"" + schedule.optString("tz").let { if (it.isBlank()) "" else " with tz \"$it\"" }
        "every" -> "every_seconds ${schedule.optLong("every_ms") / 1000L}"
        else -> "at ${java.time.Instant.ofEpochMilli(schedule.optLong("at_ms"))} (one time only)"
    }
    val described = schedule?.let { describeSchedule(it) }.orEmpty()
    return buildString {
        appendLine("Please move one of my scheduled jobs into this chat ($channelTitle), with your cron tool: first add a new job from this chat so that it reports here, then remove my existing job \"${job.name}\" (id ${job.id}). Copy the instruction exactly: do not reword, shorten or improve it, and do not change, disable or delete any other job.")
        appendLine()
        appendLine("Name: ${name.trim()}")
        appendLine("Schedule: $s" + if (described.isNotBlank()) " ($described)" else "")
        // A paused job stays paused where it goes.
        if (!job.enabled) appendLine("Create it turned off, as the current one is off.")
        appendLine("Instruction, word for word between the markers:")
        appendLine("<<<")
        appendLine(message.trim())
        appendLine(">>>")
        appendLine()
        append("When it is done, tell me the new job's id and when it will next run.")
    }
}

/** A job that doesn't exist yet, for the editor's "new" mode: no id, no schedule, on. */
val NEW_JOB = AutomationJob("", "", true, false, "", 0L, 0L, "", "", false, "", "")

/** A new job's schedule fields: every day at 09:00 in the phone's time zone, already counted as chosen. */
fun newScheduleDraft(zone: ZoneId = ZoneId.systemDefault()): ScheduleDraft =
    ScheduleDraft(kind = "cron", cronExpr = "0 9 * * *", tz = zone.id, touched = true)

/**
 * The message that asks the agent, from inside the chat it should report into, to add a new scheduled job. The server has no
 * call to create one (the WebUI leaves it to the agent too), so the app writes the request and you send it.
 */
fun createJobRequest(name: String, message: String, schedule: JSONObject, chatTitle: String?): String {
    val s = when (schedule.optString("kind")) {
        "cron" -> "cron_expr \"${schedule.optString("expr")}\"" + schedule.optString("tz").let { if (it.isBlank()) "" else " with tz \"$it\"" }
        "every" -> "every_seconds ${schedule.optLong("every_ms") / 1000L}"
        else -> "at ${java.time.Instant.ofEpochMilli(schedule.optLong("at_ms"))} (one time only)"
    }
    val described = describeSchedule(schedule)
    val where = if (chatTitle.isNullOrBlank()) "this chat" else "this chat ($chatTitle)"
    return buildString {
        appendLine("Please add a new scheduled job with your cron tool, created from $where so that it reports here. Use the instruction exactly as written: do not reword, shorten or improve it, and do not change, disable or delete any other job.")
        appendLine()
        appendLine("Name: ${name.trim()}")
        appendLine("Schedule: $s" + if (described.isNotBlank()) " ($described)" else "")
        appendLine("Instruction, word for word between the markers:")
        appendLine("<<<")
        appendLine(message.trim())
        appendLine(">>>")
        appendLine()
        append("When it is created, tell me its id and when it will first run.")
    }
}
