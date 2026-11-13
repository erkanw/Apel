package io.github.erkanw.vault.chat

import java.util.TimeZone

/** A run of chats under one label on the home screen ("Today", "Yesterday", ...). */
data class SessionGroup(val label: String, val sessions: List<SessionSummary>)

private const val DAY_MS = 86_400_000L

/**
 * The chats [sessions] (already ordered, pinned first and then most recent first) as the home screen lists them: the
 * pinned ones under "Pinned", then the rest by how long ago they were last used. Order inside a group is kept, groups
 * with nothing in them are left out, and the last label, "Earlier", also holds chats with no known time.
 */
fun groupSessions(sessions: List<SessionSummary>, nowMs: Long, zone: TimeZone = TimeZone.getDefault()): List<SessionGroup> {
    fun epochDay(ms: Long): Long = Math.floorDiv(ms + zone.getOffset(ms), DAY_MS)
    val today = epochDay(nowMs)
    val buckets = linkedMapOf("Pinned" to ArrayList<SessionSummary>(), "Today" to ArrayList(), "Yesterday" to ArrayList(), "Previous 7 days" to ArrayList(), "Earlier" to ArrayList())
    for (s in sessions) {
        val label = when {
            s.pinned -> "Pinned"
            s.updatedMs <= 0L -> "Earlier"
            else -> when (today - epochDay(s.updatedMs)) {
                in Long.MIN_VALUE..0L -> "Today"
                1L -> "Yesterday"
                in 2L..7L -> "Previous 7 days"
                else -> "Earlier"
            }
        }
        buckets.getValue(label).add(s)
    }
    return buckets.filterValues { it.isNotEmpty() }.map { (label, list) -> SessionGroup(label, list) }
}
