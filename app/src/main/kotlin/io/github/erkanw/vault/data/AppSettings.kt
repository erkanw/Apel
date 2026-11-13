package io.github.erkanw.vault.data

import android.content.Context
import androidx.core.content.edit

/** What the chat remembers between launches: the session on screen, and which session each channel uses. */
class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** The session being continued, kept across restarts. Null: start a new one. */
    var currentChatId: String?
        get() = prefs.getString(KEY_CHAT, null)
        set(value) = prefs.edit { if (value == null) remove(KEY_CHAT) else putString(KEY_CHAT, value) }

    /** The session each channel uses, by channel id (the ones you made included); one that isn't there hasn't been started. */
    fun channelChats(): Map<String, String> =
        prefs.all.entries.mapNotNull { (key, value) ->
            if (key.startsWith("channel_") && value is String) key.removePrefix("channel_") to value else null
        }.toMap()

    fun setChannelChat(channelId: String, chatId: String?) = prefs.edit {
        if (chatId == null) remove("channel_$channelId") else putString("channel_$channelId", chatId)
    }

    private companion object {
        const val KEY_CHAT = "currentChatId"
    }
}
