package io.github.erkanw.vault.chat

import android.content.Context
import androidx.core.content.edit

// Which sessions have a message you haven't seen: a session updated after you last had it open (or were told about it).
// The agent runs on the phone, so a reply that finishes in the background is announced at once (ChatAlerts); this marks
// the drawer and Home.

/** A chat with news: updated since you last looked or were told. [channel] is set when it is one of the channels. */
data class ChatNews(val session: SessionSummary, val channel: Channel?) {
    val chatId: String get() = session.chatId

    /** What the notification is called: the channel's name, or the session's title. */
    val title: String get() = channel?.let { ChannelLooks.look(it).title } ?: session.title.ifBlank { NEW_SESSION_TITLE }
}

/** Updates this close to the mark are the same moment (the phone's clock and the server's differ a little). */
const val NEWS_TOLERANCE_MS = 2_000L

/** Whether [session] has been updated since the latest of the marks (see [chatsWithNews]). */
fun hasNews(session: SessionSummary, seen: Map<String, Long>, told: Map<String, Long>, baselineMs: Long): Boolean {
    if (session.running || session.archived) return false
    val mark = maxOf(seen[session.chatId] ?: 0L, told[session.chatId] ?: 0L, baselineMs)
    return session.updatedMs > mark + NEWS_TOLERANCE_MS
}

/**
 * The chats with news: updated after the latest of [seen] (when you last had it open), [told] (the last time a
 * notification was made for it) and [baselineMs] (when watching began, so what was already there is never news). A chat
 * The agent is still working in waits until it has finished, and an archived one is left alone. [bound] is channel id → chat id.
 */
fun chatsWithNews(
    sessions: List<SessionSummary>,
    bound: Map<String, String>,
    seen: Map<String, Long>,
    told: Map<String, Long>,
    baselineMs: Long,
    channels: List<Channel> = Channel.all,
): List<ChatNews> = sessions
    .filter { hasNews(it, seen, told, baselineMs) }
    .map { ChatNews(it, channelOf(bound, it.chatId, channels)) }

/** What is kept about watching: the switch, when each chat was last open here, and when each was last announced. */
class SeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("seen", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit { putBoolean("enabled", value) }

    /** When watching began: set the first time it is asked, and never moved. */
    fun baseline(now: Long = System.currentTimeMillis()): Long {
        val kept = prefs.getLong("baseline", 0L)
        if (kept > 0L) return kept
        prefs.edit { putLong("baseline", now) }
        return now
    }

    /** Chat id → when it was last open here. */
    fun seen(): Map<String, Long> = read("seenChat_")

    /** Chat id → the update a notification was last made for. */
    fun told(): Map<String, Long> = read("toldChat_")

    fun markSeen(chatId: String, ms: Long) = raise("seenChat_$chatId", ms)
    fun markTold(chatId: String, ms: Long) = raise("toldChat_$chatId", ms)

    private fun read(prefix: String): Map<String, Long> =
        prefs.all.mapNotNull { (key, value) ->
            if (key.startsWith(prefix) && value is Long && value > 0L) key.removePrefix(prefix) to value else null
        }.toMap()

    /** A mark only ever moves forward. */
    private fun raise(key: String, ms: Long) {
        if (ms > prefs.getLong(key, 0L)) prefs.edit { putLong(key, ms) }
    }
}
