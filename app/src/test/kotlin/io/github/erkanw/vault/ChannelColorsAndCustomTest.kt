package io.github.erkanw.vault

import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelLook
import io.github.erkanw.vault.chat.channelOf
import io.github.erkanw.vault.chat.cleanLook
import io.github.erkanw.vault.chat.customChannelsToJson
import io.github.erkanw.vault.chat.nextChannelColor
import io.github.erkanw.vault.chat.parseCustomChannels
import io.github.erkanw.vault.chat.parseLooks
import io.github.erkanw.vault.chat.looksToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelColorsAndCustomTest {
    private val brief = Channel.DailyBrief

    @Test
    fun `the two channels that come with the app keep their colours until you pick another`() {
        assertEquals(0, brief.color)
        assertEquals(1, Channel.Notes.color)
        assertEquals(5, cleanLook(brief, "Brief", "", 5).color)
        // A colour out of range falls back to the channel's own.
        assertEquals(brief.color, cleanLook(brief, "Brief", "", 99).color)
        assertEquals(brief.color, cleanLook(brief, "Brief", "", -1).color)
    }

    @Test
    fun `a chosen colour is kept, and a look kept before colours could be chosen keeps the channel's own`() {
        val back = parseLooks(looksToJson(mapOf(brief.id to ChannelLook("Daily brief", "Your day", 6))))
        assertEquals(6, back.getValue(brief.id).color)
        val old = parseLooks("""{"notes":{"title":"Daily logging","blurb":"Training"}}""")
        assertEquals(Channel.Notes.color, old.getValue(Channel.Notes.id).color)
        assertEquals("Daily logging", old.getValue(Channel.Notes.id).title)
    }

    @Test
    fun `a colour change alone is kept, since it differs from the default`() {
        val recoloured = cleanLook(brief, brief.title, brief.blurb, 3)
        assertEquals(recoloured, parseLooks(looksToJson(mapOf(brief.id to recoloured))).getValue(brief.id))
    }

    @Test
    fun `channels you made survive being kept, and broken or clashing entries are left out`() {
        val made = listOf(
            Channel("ch-1", "Reading", "Books", Channel.CUSTOM_INTRO, 4, custom = true),
            Channel("ch-2", "Finance", "", Channel.CUSTOM_INTRO, 7, custom = true),
        )
        assertEquals(made, parseCustomChannels(customChannelsToJson(made)))
        val messy = """[{"id":"ch-3","title":"  "},{"title":"No id"},{"id":"ch-4","title":"Ok","color":42},{"id":"ch-4","title":"Clash"}]"""
        val back = parseCustomChannels(messy)
        assertEquals(listOf("ch-4"), back.map { it.id })
        assertEquals(0, back.single().color)
        assertTrue(back.single().custom)
        assertTrue(parseCustomChannels("not json").isEmpty())
    }

    @Test
    fun `looks of channels you made are read back too`() {
        val mine = Channel("ch-9", "Reading", "", Channel.CUSTOM_INTRO, 2, custom = true)
        val looks = mapOf(mine.id to ChannelLook("Books", "", 2))
        assertEquals("Books", parseLooks(looksToJson(looks), Channel.builtIn + mine).getValue(mine.id).title)
        // Without the channel in the list, its look is dropped like any unknown one.
        assertTrue(parseLooks(looksToJson(looks)).isEmpty())
    }

    @Test
    fun `a new channel gets the first colour no channel wears`() {
        assertEquals(2, nextChannelColor(listOf(ChannelLook("a", "", 0), ChannelLook("b", "", 1))))
        assertEquals(0, nextChannelColor(emptyList()))
        val all = (0 until Channel.COLORS).map { ChannelLook("x$it", "", it) }
        assertEquals(0, nextChannelColor(all))
    }

    @Test
    fun `a chat belongs to a channel you made as well`() {
        val mine = Channel("ch-1", "Reading", "", Channel.CUSTOM_INTRO, 4, custom = true)
        val bound = mapOf(brief.id to "a", mine.id to "b")
        assertEquals(mine, channelOf(bound, "b", Channel.builtIn + mine))
        assertNull(channelOf(bound, "b", Channel.builtIn))
    }
}
