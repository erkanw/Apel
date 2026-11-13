package io.github.erkanw.vault

import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.channelOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelsTest {
    @Test
    fun `a chat belongs to the channel it was bound to, and to no other`() {
        val bound = mapOf(Channel.DailyBrief.id to "chat-a", Channel.Notes.id to "chat-b")
        assertEquals(Channel.DailyBrief, channelOf(bound, "chat-a", Channel.starters))
        assertEquals(Channel.Notes, channelOf(bound, "chat-b", Channel.starters))
        assertNull(channelOf(bound, "chat-c", Channel.starters))
        // A new chat that has no id yet is nobody's.
        assertNull(channelOf(bound, null))
        assertNull(channelOf(emptyMap(), "chat-a"))
    }

    @Test
    fun `channel ids are what the chats are remembered under, so they stay unique and unchanged`() {
        val ids = Channel.starters.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // Renaming one would orphan the chat saved under the old id.
        assertEquals(listOf("daily-brief", "notes"), ids)
        assertTrue(Channel.starters.all { it.title.isNotBlank() && it.blurb.isNotBlank() && it.intro.isNotBlank() })
    }
}
