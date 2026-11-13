package io.github.erkanw.vault

import io.github.erkanw.vault.chat.Channel
import io.github.erkanw.vault.chat.LOCAL_COMMANDS
import io.github.erkanw.vault.chat.LocalCommand
import io.github.erkanw.vault.chat.appendTranscript
import io.github.erkanw.vault.chat.elapsedLabel
import io.github.erkanw.vault.chat.levelOfRms
import io.github.erkanw.vault.chat.localCommand
import io.github.erkanw.vault.chat.matchingCommands
import io.github.erkanw.vault.chat.parseCustomChannels
import io.github.erkanw.vault.chat.customChannelsToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts added when the server client was replaced by the on-device seam. */
class PhaseZeroTest {
    @Test
    fun `the app's own commands are the plan's, and typing a slash offers them`() {
        assertEquals(listOf("/new", "/stop", "/model", "/compact", "/skill", "/help"), LOCAL_COMMANDS.map { it.name })
        assertEquals(listOf("/stop", "/skill"), matchingCommands(LOCAL_COMMANDS, "/s").map { it.name })
        assertTrue(matchingCommands(LOCAL_COMMANDS, "/skill web").isEmpty())
        assertEquals(LocalCommand.NewSession, localCommand(" /new "))
        assertEquals(LocalCommand.PickModel, localCommand("/model"))
        assertEquals(null, localCommand("/model fast"))
    }

    @Test
    fun `dictation loudness runs from silence to full, and words join what is typed`() {
        assertEquals(0f, levelOfRms(-2f), 0.001f)
        assertEquals(0f, levelOfRms(-10f), 0.001f)
        assertEquals(1f, levelOfRms(10f), 0.001f)
        assertEquals(1f, levelOfRms(14f), 0.001f)
        assertEquals("1:23", elapsedLabel(83_000))
        assertEquals("Hello there", appendTranscript("Hello ", " there "))
        assertEquals("there", appendTranscript("", "there"))
    }

    @Test
    fun `the starters are generic, keep their own intro, and survive being kept as yours`() {
        assertEquals(listOf("Daily brief", "Notes"), Channel.starters.map { it.title })
        assertTrue(Channel.starters.all { it.custom })
        assertTrue(Channel.builtIn.isEmpty())
        val back = parseCustomChannels(customChannelsToJson(Channel.starters))
        assertEquals(Channel.starters, back)
        assertEquals(Channel.CUSTOM_INTRO, Channel.introOf("ch-1234"))
    }
}
