package io.github.erkanw.vault

import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.ChannelLook
import io.github.erkanw.vault.chat.LOOK_BLURB_MAX
import io.github.erkanw.vault.chat.LOOK_TITLE_MAX
import io.github.erkanw.vault.chat.cleanLook
import io.github.erkanw.vault.chat.defaultLook
import io.github.erkanw.vault.chat.introFor
import io.github.erkanw.vault.chat.lookOf
import io.github.erkanw.vault.chat.looksToJson
import io.github.erkanw.vault.chat.parseLooks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLooksTest {
    private val brief = Channel.DailyBrief

    @Test
    fun `fields left empty go back to the channel's own`() {
        assertEquals(brief.defaultLook, cleanLook(brief, "  ", ""))
    }

    @Test
    fun `a name is tidied and cut to length`() {
        val look = cleanLook(brief, "  Daily    start  ", "x".repeat(100))
        assertEquals("Daily start", look.title)
        assertEquals(LOOK_BLURB_MAX, look.blurb.length)
        assertEquals(LOOK_TITLE_MAX, cleanLook(brief, "n".repeat(60), "").title.length)
    }

    @Test
    fun `looks survive being kept, and only what differs is kept`() {
        val chosen = ChannelLook("Morning", "Start of day", 4)
        val back = parseLooks(looksToJson(mapOf(brief.id to chosen, Channel.Notes.id to Channel.Notes.defaultLook)))
        assertEquals(chosen, back[brief.id])
        assertEquals(null, back[Channel.Notes.id])
        assertEquals(Channel.Notes.defaultLook, lookOf(back, Channel.Notes))
    }

    @Test
    fun `an old look with an emoji or a skin keeps its name`() {
        val back = parseLooks("""{"daily-brief":{"title":"Sunrise","blurb":"Hello","glyph":"🌅","skin":"crown"}}""")
        assertEquals(ChannelLook("Sunrise", "Hello", brief.color), back.getValue(brief.id))
    }

    @Test
    fun `unreadable or unknown looks are ignored`() {
        assertTrue(parseLooks(null).isEmpty())
        assertTrue(parseLooks("not json").isEmpty())
        assertTrue(parseLooks("""{"nope":{"title":"x","blurb":"y","skin":"z"}}""").isEmpty())
    }

    @Test
    fun `the empty chat says your description, or its own sentence if you left it alone`() {
        assertEquals(brief.intro, brief.introFor(brief.defaultLook))
        assertEquals("Start of day", brief.introFor(ChannelLook("Morning", "Start of day", 4)))
    }
}
