package io.github.erkanw.vault.chat

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The channels you made, and the names, descriptions and colours you gave every channel, kept on the phone. One place for
 * the screens and for the notification worker (which has no view model), so a channel is called the same everywhere.
 */
object ChannelLooks {
    private const val PREFS = "channel_looks"
    private const val KEY = "looks"
    private const val KEY_CUSTOM = "custom"
    private const val KEY_SEEDED = "startersSeeded"

    private val _looks = MutableStateFlow<Map<String, ChannelLook>>(emptyMap())
    private val _custom = MutableStateFlow<List<Channel>>(emptyList())

    /** Channel id → the look you chose; a channel that isn't there has its default. */
    val looks: StateFlow<Map<String, ChannelLook>> = _looks.asStateFlow()

    /** The channels you made, oldest first. */
    val custom: StateFlow<List<Channel>> = _custom.asStateFlow()

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var custom = parseCustomChannels(prefs.getString(KEY_CUSTOM, null))
            // The first launch: the starters become channels of yours (PLAN §2.2), so they can be renamed and deleted.
            if (!prefs.getBoolean(KEY_SEEDED, false)) {
                custom = Channel.starters + custom.filter { c -> Channel.starters.none { it.id == c.id } }
                prefs.edit {
                    putString(KEY_CUSTOM, customChannelsToJson(custom))
                    putBoolean(KEY_SEEDED, true)
                }
            }
            _custom.value = custom
            _looks.value = parseLooks(prefs.getString(KEY, null), Channel.builtIn + custom)
            loaded = true
        }
    }

    /** The channel's look right now. */
    fun look(channel: Channel): ChannelLook = lookOf(_looks.value, channel)

    /** Keeps [look] for [channel]; the default look is not kept, it is simply the absence of one. */
    fun save(context: Context, channel: Channel, look: ChannelLook) {
        ensureLoaded(context)
        val next = _looks.value.toMutableMap()
        if (look == channel.defaultLook) next.remove(channel.id) else next[channel.id] = look
        _looks.value = next
        prefs(context).edit { putString(KEY, looksToJson(next)) }
    }

    fun reset(context: Context, channel: Channel) = save(context, channel, channel.defaultLook)

    /** Makes a channel with [look] (already cleaned) and keeps it; it has no chat until its first message. */
    fun create(context: Context, look: ChannelLook): Channel {
        ensureLoaded(context)
        val channel = Channel(
            id = "ch-" + java.util.UUID.randomUUID().toString().substring(0, 8),
            title = look.title, blurb = look.blurb, intro = Channel.CUSTOM_INTRO, color = look.color, custom = true,
        )
        val next = _custom.value + channel
        _custom.value = next
        prefs(context).edit { putString(KEY_CUSTOM, customChannelsToJson(next)) }
        return channel
    }

    /** Forgets a channel, and the look you gave it. Its session stays as an ordinary one. */
    fun delete(context: Context, channel: Channel) {
        if (!channel.custom) return
        ensureLoaded(context)
        val next = _custom.value.filter { it.id != channel.id }
        _custom.value = next
        val looks = _looks.value - channel.id
        _looks.value = looks
        prefs(context).edit {
            putString(KEY_CUSTOM, customChannelsToJson(next))
            putString(KEY, looksToJson(looks))
        }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
