package io.github.erkanw.vault.chat

import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** What the chat list is made of: a day divider, or a message with what to show around it. */
sealed interface ChatRow {
    val key: String

    data class Day(val label: String, override val key: String) : ChatRow

    /** A line between the messages a forked chat came with and the ones written in it. */
    data class Marker(val label: String, override val key: String) : ChatRow

    /**
     * [firstOfRun]: the first message of a run by the same side, which is where the agent’s avatar goes.
     * [showTime]: the last of a run within five minutes, which is where its time goes.
     */
    data class Msg(val message: ChatMessage, val firstOfRun: Boolean, val showTime: Boolean, val repeat: Int = 0) : ChatRow {
        override val key: String get() = if (repeat == 0) "m${message.id}" else "m${message.id}#$repeat"
    }
}

/** "14:05", or "2:05 PM", as the phone's language writes the time of day. */
fun clockTime(ms: Long, zone: TimeZone = TimeZone.getDefault()): String =
    if (ms <= 0L) "" else DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).apply { timeZone = zone }.format(Date(ms))

private fun dayNumber(ms: Long, zone: TimeZone): Int {
    val cal = Calendar.getInstance(zone).apply { timeInMillis = ms }
    return cal.get(Calendar.YEAR) * 400 + cal.get(Calendar.DAY_OF_YEAR)
}

/** "Today", "Yesterday", a weekday within the last week, else "12 Sep" (with the year if it isn't this one). */
fun dayLabel(nowMs: Long, thenMs: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val back = dayNumber(nowMs, zone) - dayNumber(thenMs, zone)
    val sameYear = Calendar.getInstance(zone).apply { timeInMillis = nowMs }.get(Calendar.YEAR) ==
        Calendar.getInstance(zone).apply { timeInMillis = thenMs }.get(Calendar.YEAR)
    fun fmt(pattern: String) = SimpleDateFormat(pattern, Locale.getDefault()).apply { timeZone = zone }.format(Date(thenMs))
    return when {
        back <= 0 -> "Today"
        back == 1 -> "Yesterday"
        back < 7 && sameYear -> fmt("EEEE")
        sameYear -> fmt("d MMM")
        else -> fmt("d MMM yyyy")
    }
}

private const val RUN_GAP_MS = 5 * 60_000L

/**
 * The chat list: [messages] with a divider where the day changes, and the time under the last of each run.
 *
 * Every row's key is unique: the list refuses two rows with the same one (and the app with it). Messages are not always
 * in strict time order (a reply stamped by the server, a message kept from an earlier visit), so a message is dated no
 * earlier than the one before it and a divider only ever comes where the day moves forward; an id can still turn up twice,
 * and the second one is numbered ("m284#1"), which stays the same as more messages arrive.
 */
fun chatRows(
    messages: List<ChatMessage>,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
    forkBoundary: Int? = null,
): List<ChatRow> {
    val rows = ArrayList<ChatRow>()
    val daysSeen = HashMap<Int, Int>()
    val idsSeen = HashMap<Long, Int>()
    var currentDay = -1
    // The latest time so far. A message stamped earlier than that (a clock a little off, a time the server guessed) stays under
    // the day it is already in: the day only moves forward, so the dividers can't jump back and forth.
    var latest = 0L
    for ((i, m) in messages.withIndex()) {
        if (forkBoundary != null && i == forkBoundary) rows.add(ChatRow.Marker("Forked from history", "fork"))
        val known = m.createdAt > 0L
        var dayChanged = false
        if (known) {
            latest = maxOf(latest, m.createdAt)
            val day = dayNumber(latest, zone)
            if (day != currentDay) {
                val round = daysSeen.merge(day, 1, Int::plus)!! - 1
                rows.add(ChatRow.Day(dayLabel(nowMs, latest, zone), if (round == 0) "d$day" else "d$day#$round"))
                currentDay = day
                dayChanged = true
            }
        }
        val previous = messages.getOrNull(i - 1)
        val next = messages.getOrNull(i + 1)
        val firstOfRun = previous == null || previous.role != m.role || dayChanged || (forkBoundary != null && i == forkBoundary)
        val endsRun = next == null || next.role != m.role || !sameRun(m, next, zone)
        val repeat = idsSeen.merge(m.id, 1, Int::plus)!! - 1
        rows.add(ChatRow.Msg(m, firstOfRun = firstOfRun, showTime = known && endsRun, repeat = repeat))
    }
    return rows
}

private fun sameRun(a: ChatMessage, b: ChatMessage, zone: TimeZone): Boolean =
    a.createdAt > 0L && b.createdAt > 0L && b.createdAt - a.createdAt < RUN_GAP_MS &&
        dayNumber(a.createdAt, zone) == dayNumber(b.createdAt, zone)
