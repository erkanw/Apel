package io.github.erkanw.vault.chat

/**
 * A channel is an ordinary session that the app keeps apart from the others: the same conversation every time you open
 * it, under its own name and colour, out of the normal list. The first time you open one it is an empty chat; the
 * session made for your first message is then remembered as the channel's (see ChatViewModel.openChannel).
 *
 * Two starters come with the app ([DailyBrief], [Notes]), generic and with no automations attached (PLAN §2.2). They are
 * put among yours on the first launch ([ChannelLooks.ensureLoaded]), so they can be renamed and deleted like any channel
 * you make. This is what a channel is when it is made; what you changed since (name, line, colour) is its [ChannelLook].
 */
data class Channel(
    /** Never changes: it is what the channel's chat is remembered under. */
    val id: String,
    val title: String,
    /** The line under the name in the drawer. */
    val blurb: String,
    /** What the empty channel says, under its name. */
    val intro: String,
    /** Its colour until you pick another: an index into the eight channel colours. */
    val color: Int,
    /** One of yours (the starters included), so it can be deleted. */
    val custom: Boolean = false,
) {
    companion object {
        val DailyBrief = Channel(
            id = "daily-brief",
            title = "Daily brief",
            blurb = "Your day, in short",
            intro = "Ask for your brief any time. Whatever is posted here stays in one place.",
            color = 0,
            custom = true,
        )
        val Notes = Channel(
            id = "notes",
            title = "Notes",
            blurb = "Things to keep",
            intro = "Jot things down or ask about them later. They stay in this one chat.",
            color = 1,
            custom = true,
        )

        /** The starters, put among your channels on the first launch. */
        val starters: List<Channel> = listOf(DailyBrief, Notes)

        /** Channels that can't be deleted: none (the starters are yours). Kept so lists can add the ones you made to it. */
        val builtIn: List<Channel> = emptyList()

        /** Every channel: yours, oldest first. */
        val all: List<Channel> get() = builtIn + ChannelLooks.custom.value

        /** What a channel you make says when it is still empty. */
        const val CUSTOM_INTRO = "Whatever is posted here stays in this one chat."

        /** What an empty channel says: a starter's own sentence, else [CUSTOM_INTRO]. */
        fun introOf(id: String): String = starters.firstOrNull { it.id == id }?.intro ?: CUSTOM_INTRO

        /** How many colours there are to choose from (ChannelColors in the UI). */
        const val COLORS = 8
    }
}

/** What a channel is called and its colour: what you chose, or what it came with. */
data class ChannelLook(val title: String, val blurb: String, val color: Int)

const val LOOK_TITLE_MAX = 24
const val LOOK_BLURB_MAX = 40

val Channel.defaultLook: ChannelLook get() = ChannelLook(title, blurb, color)

/** The channel's look: what you chose, or the default. [looks] is channel id → what you chose. */
fun lookOf(looks: Map<String, ChannelLook>, channel: Channel): ChannelLook = looks[channel.id] ?: channel.defaultLook

/** What the empty channel says: the description you wrote, or the built-in sentence if you left the description alone. */
fun Channel.introFor(look: ChannelLook): String = if (look.blurb != blurb) look.blurb else intro

/** Tidies what was typed: trimmed, cut to length, a field left empty goes back to its default, and the colour stays in range. */
fun cleanLook(channel: Channel, title: String, blurb: String, color: Int = channel.color): ChannelLook {
    fun field(text: String, max: Int, fallback: String) = text.trim().replace(Regex("\\s+"), " ").take(max).trim().ifEmpty { fallback }
    return ChannelLook(
        field(title, LOOK_TITLE_MAX, channel.title),
        field(blurb, LOOK_BLURB_MAX, channel.blurb),
        if (color in 0 until Channel.COLORS) color else channel.color,
    )
}

/** The looks as they are kept on the phone. Only what differs from the default is written. (Older versions also kept a "skin", which is now ignored.) */
fun looksToJson(looks: Map<String, ChannelLook>): String = org.json.JSONObject().also { root ->
    for ((id, look) in looks) {
        root.put(id, org.json.JSONObject().put("title", look.title).put("blurb", look.blurb).put("color", look.color))
    }
}.toString()

/**
 * Reads [looksToJson] back, cleaned again; anything unreadable, or for a channel that isn't in [channels], is left out. A
 * look kept before colours could be chosen keeps the channel's own colour.
 */
fun parseLooks(json: String?, channels: List<Channel> = Channel.builtIn + Channel.starters): Map<String, ChannelLook> {
    if (json.isNullOrBlank()) return emptyMap()
    val root = try { org.json.JSONObject(json) } catch (e: Exception) { return emptyMap() }
    val out = LinkedHashMap<String, ChannelLook>()
    for (channel in channels) {
        val o = root.optJSONObject(channel.id) ?: continue
        val look = cleanLook(channel, o.optString("title"), o.optString("blurb"), o.optInt("color", channel.color))
        if (look != channel.defaultLook) out[channel.id] = look
    }
    return out
}

/** The channels you made, as they are kept on the phone. */
fun customChannelsToJson(channels: List<Channel>): String = org.json.JSONArray().also { array ->
    for (c in channels) array.put(org.json.JSONObject().put("id", c.id).put("title", c.title).put("blurb", c.blurb).put("color", c.color))
}.toString()

/** Reads [customChannelsToJson] back; an entry without an id or a name, or with the id of another channel, is left out. */
fun parseCustomChannels(json: String?): List<Channel> {
    if (json.isNullOrBlank()) return emptyList()
    val array = try { org.json.JSONArray(json) } catch (e: Exception) { return emptyList() }
    val taken = Channel.builtIn.map { it.id }.toMutableSet()
    val out = ArrayList<Channel>()
    for (i in 0 until array.length()) {
        val o = array.optJSONObject(i) ?: continue
        val id = o.optString("id").trim()
        val title = o.optString("title").trim().take(LOOK_TITLE_MAX)
        if (id.isEmpty() || title.isEmpty() || !taken.add(id)) continue
        val color = o.optInt("color", 0).let { if (it in 0 until Channel.COLORS) it else 0 }
        out += Channel(id, title, o.optString("blurb").trim().take(LOOK_BLURB_MAX), Channel.introOf(id), color, custom = true)
    }
    return out
}

/** A new channel's colour: the first one no channel wears yet, or the one after the last channel's when all are taken. */
fun nextChannelColor(looks: List<ChannelLook>): Int {
    val used = looks.map { it.color }.toSet()
    return (0 until Channel.COLORS).firstOrNull { it !in used } ?: ((looks.lastOrNull()?.color ?: -1) + 1).mod(Channel.COLORS)
}

/** A channel as the drawer shows it. [chatId] is null until its first message has been sent. */
data class ChannelEntry(
    val channel: Channel,
    val look: ChannelLook = channel.defaultLook,
    val chatId: String?,
    val selected: Boolean,
    val running: Boolean,
    /** Something was posted in it since you last had it open. */
    val unread: Boolean = false,
)

/** The channel whose chat is [chatId], or null (an ordinary session). [bound] is channel id → chat id. */
fun channelOf(bound: Map<String, String>, chatId: String?, channels: List<Channel> = Channel.all): Channel? =
    if (chatId == null) null else channels.firstOrNull { bound[it.id] == chatId }
