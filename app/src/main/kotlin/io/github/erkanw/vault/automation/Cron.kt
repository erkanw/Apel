package io.github.erkanw.vault.automation

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A five-field cron expression (PLAN §8): minute, hour, day of month, month, day of week. Each field takes `*`, numbers,
 * ranges (`1-5`), steps (a star with `/15`, `8-18/2`, `5/10`) and lists of those (`0,30`); months and weekdays also take names
 * (`JAN`, `MON`), and Sunday is 0 or 7. `@hourly`, `@daily`, `@weekly`, `@monthly` and `@yearly` are short for the usual.
 * As in classic cron, when both the day of month and the day of week are set, a day that matches either one runs.
 *
 * Time changes: a time that doesn't exist on the day the clocks go forward runs at the moment after the jump (02:30 →
 * 03:30); a time that happens twice when they go back runs once, the first time.
 */
class Cron private constructor(
    val expr: String,
    private val minutes: BooleanArray,
    private val hours: BooleanArray,
    private val days: BooleanArray,
    private val months: BooleanArray,
    private val weekdays: BooleanArray,
    private val anyDay: Boolean,
    private val anyWeekday: Boolean,
) {
    private val minuteList = (0..59).filter { minutes[it] }
    private val hourList = (0..23).filter { hours[it] }

    /** Whether [date] is a day it runs on. */
    fun matchesDay(date: LocalDate): Boolean {
        if (!months[date.monthValue]) return false
        val dom = days[date.dayOfMonth]
        val dow = weekdays[date.dayOfWeek.value % 7]
        return when {
            anyDay && anyWeekday -> true
            anyDay -> dow
            anyWeekday -> dom
            else -> dom || dow
        }
    }

    /** The first time it runs strictly after [after], in [zone]; null if it never does within about five years. */
    fun next(after: Instant, zone: ZoneId): Instant? {
        val from = after.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
        var date = from.toLocalDate()
        val last = date.plusYears(5)
        while (!date.isAfter(last)) {
            if (matchesDay(date)) {
                for (h in hourList) for (m in minuteList) {
                    val local = LocalDateTime.of(date, java.time.LocalTime.of(h, m))
                    if (!local.isAfter(from)) continue
                    // In a gap this moves forward by the gap; in an overlap it is the earlier of the two.
                    val zoned = ZonedDateTime.ofLocal(local, zone, null)
                    if (zoned.toInstant().isAfter(after)) return zoned.toInstant()
                    val later = zoned.withLaterOffsetAtOverlap().toInstant()
                    if (later.isAfter(after)) return later
                }
            }
            date = date.plusDays(1)
        }
        return null
    }

    companion object {
        private val MONTHS = listOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
        private val WEEKDAYS = listOf("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT")
        private val MACROS = mapOf(
            "@hourly" to "0 * * * *", "@daily" to "0 0 * * *", "@midnight" to "0 0 * * *",
            "@weekly" to "0 0 * * 0", "@monthly" to "0 0 1 * *", "@yearly" to "0 0 1 1 *", "@annually" to "0 0 1 1 *",
        )

        /** Reads [expr], or throws [IllegalArgumentException] with a sentence on what is wrong. */
        fun parse(expr: String): Cron {
            val text = expr.trim().replace(Regex("\\s+"), " ")
            val expanded = MACROS[text.lowercase()] ?: text
            val f = expanded.split(" ")
            require(f.size == 5) { "A cron expression has five parts: minute, hour, day, month, weekday." }
            val dow = field(f[4], 0, 7, WEEKDAYS, "weekday")
            // 7 is Sunday too.
            if (dow[7]) dow[0] = true
            return Cron(
                expanded,
                field(f[0], 0, 59, null, "minute"),
                field(f[1], 0, 23, null, "hour"),
                field(f[2], 1, 31, null, "day"),
                field(f[3], 1, 12, MONTHS, "month"),
                dow.copyOf(7),
                anyDay = f[2] == "*" || f[2] == "?",
                anyWeekday = f[4] == "*" || f[4] == "?",
            )
        }

        fun parseOrNull(expr: String): Cron? = try { parse(expr) } catch (e: IllegalArgumentException) { null }

        private fun field(text: String, min: Int, max: Int, names: List<String>?, what: String): BooleanArray {
            val out = BooleanArray(max + 1)
            fun value(s: String): Int {
                val upper = s.uppercase()
                val named = names?.indexOf(upper)?.takeIf { it >= 0 }?.let { if (names.size == 12) it + 1 else it }
                val n = named ?: s.toIntOrNull() ?: throw IllegalArgumentException("“$s” isn’t a valid $what.")
                require(n in min..max) { "The $what must be between $min and $max (“$s”)." }
                return n
            }
            for (part in text.split(",")) {
                require(part.isNotEmpty()) { "The $what has an empty part." }
                val (range, stepText) = part.split("/", limit = 2).let { it[0] to it.getOrNull(1) }
                val step = stepText?.let { s -> s.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("“$s” isn’t a valid step for the $what.") } ?: 1
                val (lo, hi) = when {
                    range == "*" || range == "?" -> min to max
                    range.contains("-") -> range.split("-", limit = 2).let { value(it[0]) to value(it[1]) }
                    stepText != null -> value(range) to max
                    else -> value(range).let { it to it }
                }
                require(lo <= hi) { "The $what range “$range” goes backwards." }
                var v = lo
                while (v <= hi) { out[v] = true; v += step }
            }
            return out
        }
    }
}
