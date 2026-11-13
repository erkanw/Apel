package io.github.erkanw.vault.chat

import org.json.JSONObject

// Sessions, skills and automations as the screens show them. Pure helpers, so they can be unit-tested on the JVM.

/** A session as the drawer shows it. [key] identifies it in the backend (for a local session, the same as [chatId]). */
data class SessionSummary(
    val chatId: String,
    val key: String,
    val title: String,
    val preview: String,
    val updatedMs: Long,
    val running: Boolean,
    val pinned: Boolean,
    val archived: Boolean = false,
    val modelPreset: String? = null,
    val createdMs: Long = 0L,
)

const val NEW_SESSION_TITLE = "New session"

/** A session's messages as the backend gives them: the messages, how many of yours come before them, where a fork begins. */
data class ThreadPage(
    val messages: List<ChatMessage>,
    /** Your messages before the first one in [messages] (a long thread may be loaded a page at a time). */
    val userOffset: Int,
    /** For a fork: how many of [messages] were carried over from the session it was forked from; null if it isn't one. */
    val forkBoundary: Int?,
    /** Where the next older page starts, and whether there is one. */
    val beforeCursor: String? = null,
    val hasMoreBefore: Boolean = false,
    /** How many of [messages] come before the latest compaction summary, if there is one. */
    val compactedAt: Int? = null,
    /** The size of that summary's text. */
    val compactionChars: Int = 0,
)

/**
 * Which of your messages (counting from 0 across the whole session) the message at [at] is, for forking just before
 * it: the ones on screen before it, plus those before the page that was loaded.
 */
fun userIndexBefore(messages: List<ChatMessage>, at: Int, userOffset: Int): Int =
    userOffset + messages.take(at).count { it.role == Role.User }

/** The message of yours that a reply answers: the nearest one of yours above it. Null if there is none. */
fun promptOf(messages: List<ChatMessage>, replyAt: Int): ChatMessage? =
    messages.take(replyAt).lastOrNull { it.role == Role.User }

/** "now", "5 min", "3 h", "Yesterday", "Mon", or a date: the short time a session row shows. */
fun relativeTime(nowMs: Long, thenMs: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    if (thenMs <= 0L) return ""
    val diff = nowMs - thenMs
    if (diff < 60_000L) return "now"
    if (diff < 3_600_000L) return "${diff / 60_000L} min"
    val cal = java.util.Calendar.getInstance(zone).apply { timeInMillis = nowMs }
    val then = java.util.Calendar.getInstance(zone).apply { timeInMillis = thenMs }
    val dayDiff = dayNumber(cal) - dayNumber(then)
    return when {
        dayDiff <= 0 -> "${diff / 3_600_000L} h"
        dayDiff == 1 -> "Yesterday"
        dayDiff < 7 -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).apply { timeZone = zone }.format(java.util.Date(thenMs))
        else -> java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault()).apply { timeZone = zone }.format(java.util.Date(thenMs))
    }
}

private fun dayNumber(cal: java.util.Calendar): Int = cal.get(java.util.Calendar.YEAR) * 366 + cal.get(java.util.Calendar.DAY_OF_YEAR)

/** A tool step, shortened for the working line. */
fun cleanStep(text: String): String = text.replace(Regex("\\s+"), " ").trim().take(80)

/** A string field of a JSON object; JSON null and a missing field both give "". */
fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key, "")

/**
 * A timestamp as JSON may hold it: seconds or milliseconds since 1970, or an ISO-8601 string (with a zone, or without
 * one, read in [zone]). 0 if unreadable.
 */
fun parseTimeMillis(value: Any?, zone: java.time.ZoneId = java.time.ZoneOffset.UTC): Long = when (value) {
    is Number -> value.toDouble().let { if (it > 1e11) it.toLong() else (it * 1000).toLong() }
    is String -> value.toDoubleOrNull()?.let { parseTimeMillis(it, zone) }
        ?: try {
            java.time.Instant.parse(value).toEpochMilli()
        } catch (e: Exception) {
            try {
                java.time.LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                0L
            }
        }
    else -> 0L
}

// ---- skills and automations -------------------------------------------------------------------

/** One skill. [available] false means it can't run here, for [unavailableReason]. */
data class SkillInfo(
    val name: String,
    val description: String,
    val enabled: Boolean,
    val available: Boolean,
    val deletable: Boolean,
    val source: String,
    val unavailableReason: String,
)

/** One automation, with the schedule already put into words. */
data class AutomationJob(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val protected: Boolean,
    val schedule: String,
    val nextRunMs: Long,
    val lastRunMs: Long,
    val lastStatus: String,
    val lastError: String,
    val pending: Boolean,
    val message: String,
    val channel: String,
    /** The schedule as stored, to copy a job exactly: "cron", "every" or "at", and its fields. */
    val scheduleKind: String = "",
    val cronExpr: String = "",
    val tz: String = "",
    val everyMs: Long = 0L,
    val atMs: Long = 0L,
    /** The session the job runs in and reports into, if it has one. */
    val sessionKey: String = "",
)

/** A schedule in words: "Every day at 20:30", "Every 2 hours", "Once, Sat 10 Oct, 20:30", or the raw cron. */
fun describeSchedule(schedule: JSONObject?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String {
    if (schedule == null) return ""
    return when (schedule.str("kind")) {
        "cron" -> humanCron(schedule.str("expr")) + schedule.str("tz").let { if (it.isBlank()) "" else " ($it)" }
        "every" -> "Every " + humanDuration(schedule.optLong("every_ms", 0L))
        "at" -> "Once, " + shortDateTime(schedule.optLong("at_ms", 0L), zone)
        else -> schedule.str("expr")
    }
}

/** A cron expression in words when it is a simple one: daily, weekly, weekdays, every N minutes or hourly (for example "30 20 daily" reads "Every day at 20:30"); anything else stays as written. */
fun humanCron(expr: String): String {
    val f = expr.trim().split(Regex("""\s+"""))
    if (f.size != 5) return expr
    val (minute, hour, dom, month, dow) = f
    fun two(n: Int) = n.toString().padStart(2, '0')
    val m = minute.toIntOrNull()
    val h = hour.toIntOrNull()
    val step = Regex("""\*/(\d+)""").matchEntire(minute)?.groupValues?.get(1)?.toIntOrNull()
    if (step != null && hour == "*" && dom == "*" && month == "*" && dow == "*") return "Every $step minutes"
    if (m != null && h != null && dom == "*" && month == "*") {
        val time = "${two(h)}:${two(m)}"
        if (dow == "*") return "Every day at $time"
        val days = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
        dow.toIntOrNull()?.takeIf { it in 0..7 }?.let { return "Every ${days[it]} at $time" }
        if (dow == "1-5") return "Weekdays at $time"
    }
    if (m != null && hour == "*" && dom == "*" && month == "*" && dow == "*") return "Every hour at :${two(m)}"
    return expr
}

/** 1_800_000 → "30 minutes"; 7_200_000 → "2 hours"; 86_400_000 → "day". */
fun humanDuration(ms: Long): String {
    if (ms <= 0L) return "?"
    val minutes = ms / 60_000L
    return when {
        minutes < 1L -> "${ms / 1000L} seconds"
        minutes < 60L -> if (minutes == 1L) "minute" else "$minutes minutes"
        minutes % 1440L == 0L -> if (minutes == 1440L) "day" else "${minutes / 1440L} days"
        minutes % 60L == 0L -> if (minutes == 60L) "hour" else "${minutes / 60L} hours"
        else -> "$minutes minutes"
    }
}

/** "Sat 10 Oct, 20:30" in [zone]; "" for 0. */
fun shortDateTime(ms: Long, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): String =
    if (ms <= 0L) "" else java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).apply { timeZone = zone }.format(java.util.Date(ms))
