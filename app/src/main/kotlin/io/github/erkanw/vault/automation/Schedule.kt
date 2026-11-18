package io.github.erkanw.vault.automation

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * When an automation runs (PLAN §8): every N minutes (at least 15), hours or days; a cron expression in a time zone; or
 * once at a moment. As JSON it is the shape the edit sheet already speaks: `{kind: "every", every_ms}`,
 * `{kind: "cron", expr, tz?}` or `{kind: "at", at_ms}`.
 */
data class Schedule(val kind: String, val expr: String = "", val tz: String = "", val everyMs: Long = 0L, val atMs: Long = 0L) {
    fun toJson(): JSONObject = when (kind) {
        EVERY -> JSONObject().put("kind", EVERY).put("every_ms", everyMs)
        CRON -> JSONObject().put("kind", CRON).put("expr", expr).apply { if (tz.isNotBlank()) put("tz", tz) }
        else -> JSONObject().put("kind", AT).put("at_ms", atMs)
    }

    /** The zone its times are read in: its own, else the phone's. */
    fun zone(default: ZoneId = ZoneId.systemDefault()): ZoneId = tz.takeIf { it.isNotBlank() }?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: default

    /**
     * The next time it runs after [after]. [anchor] is the time an "every" schedule counts from (when it was made, or its
     * last planned run), so it keeps its rhythm; a run missed while the phone was off is not made up more than once. Null:
     * it doesn't run again (a one-time job whose moment has passed).
     */
    fun next(after: Long, anchor: Long = 0L, zone: ZoneId = ZoneId.systemDefault()): Long? = when (kind) {
        EVERY -> if (everyMs <= 0L) null else if (anchor <= 0L || anchor > after) (if (anchor > after) anchor else after + everyMs) else {
            val steps = (after - anchor) / everyMs + 1
            anchor + steps * everyMs
        }
        CRON -> Cron.parseOrNull(expr)?.next(Instant.ofEpochMilli(after), zone(zone))?.toEpochMilli()
        AT -> atMs.takeIf { it > after }
        else -> null
    }

    /** Why it can't be used, in a sentence; null when it can. [now]: a one-time job must be in the future. */
    fun problem(now: Long = System.currentTimeMillis()): String? = when (kind) {
        EVERY -> if (everyMs < MIN_EVERY_MS) "Every 15 minutes is the most often a job can run." else null
        CRON -> try {
            val cron = Cron.parse(expr)
            when {
                tz.isNotBlank() && runCatching { ZoneId.of(tz.trim()) }.isFailure -> "That time zone isn’t known. Try one like Europe/Stockholm."
                tooOften(cron, zone(), now) -> "That runs more often than every 15 minutes. Space the times out."
                cron.next(Instant.ofEpochMilli(now), zone()) == null -> "That never runs. Check the day and month."
                else -> null
            }
        } catch (e: IllegalArgumentException) {
            e.message
        }
        AT -> if (atMs <= now) "Choose a time in the future." else null
        else -> "That kind of schedule isn’t known."
    }

    companion object {
        const val EVERY = "every"
        const val CRON = "cron"
        const val AT = "at"
        const val MIN_EVERY_MS = 15 * 60_000L

        fun fromJson(o: JSONObject?): Schedule? = when (o?.optString("kind")) {
            EVERY -> Schedule(EVERY, everyMs = o.optLong("every_ms"))
            CRON -> Schedule(CRON, expr = o.optString("expr").trim().replace(Regex("\\s+"), " "), tz = o.optString("tz").trim())
            AT -> Schedule(AT, atMs = o.optLong("at_ms"))
            else -> null
        }

        /** Whether two runs in a row come less than 15 minutes apart, looking a day ahead from [now]. */
        private fun tooOften(cron: Cron, zone: ZoneId, now: Long): Boolean {
            var t = Instant.ofEpochMilli(now)
            var prev: Instant? = null
            val end = t.plusSeconds(26 * 3600L)
            repeat(200) {
                val n = cron.next(t, zone) ?: return false
                if (n.isAfter(end)) return false
                if (prev != null && n.toEpochMilli() - prev!!.toEpochMilli() < MIN_EVERY_MS) return true
                prev = n
                t = n
            }
            return true
        }
    }
}
