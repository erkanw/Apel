package io.github.erkanw.vault

import io.github.erkanw.vault.chat.LocalCommand
import io.github.erkanw.vault.chat.ModelChoices
import io.github.erkanw.vault.chat.commandDraft
import io.github.erkanw.vault.chat.localCommand
import io.github.erkanw.vault.chat.matchingCommands
import io.github.erkanw.vault.chat.shownPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandsAndModelsTest {
    // The shape of the real /api/commands, shortened.
    private val commandsBody = """{"commands":[
        {"command":"/new","title":"New chat","description":"Reset this chat.","icon":"square-pen","arg_hint":"","lifecycle":"finalize_active_turn","accepts_args":false},
        {"command":"/status","title":"Show status","description":"Display runtime status.","icon":"activity","arg_hint":"","lifecycle":"side_channel","accepts_args":false},
        {"command":"/model","title":"Switch model preset","description":"Show or switch the active model preset.","icon":"brain","arg_hint":"[preset]","lifecycle":"side_channel","accepts_args":true},
        {"command":"/goal","title":"Start long-running goal","description":"Long-running goal.","icon":"activity","arg_hint":"<goal>","lifecycle":"agent_turn_with_args","accepts_args":true},
        {"command":"/history","title":"Show conversation history","description":"Last N messages.","icon":"history","arg_hint":"[n]","lifecycle":"side_channel","accepts_args":true}]}"""

    @Test
    fun `the app does a few commands itself`() {
        assertEquals(LocalCommand.NewSession, localCommand("/new"))
        assertEquals(LocalCommand.NewSession, localCommand("  /NEW "))
        assertEquals(LocalCommand.Stop, localCommand("/stop"))
        assertEquals(LocalCommand.PickModel, localCommand("/model"))
        assertNull(localCommand("/model flash"))
        assertNull(localCommand("/status"))
        assertNull(localCommand("new"))
    }

    // The shape of the real /api/settings, shortened to what is read.
    private val settingsBody = """{"agent":{"model":"qwen/qwen3.7-flash","provider":"openrouter","model_preset":"flash"},
        "model_presets":[
            {"name":"default","label":"Default","active":false,"model":"apodex/apodex-1.1-mini:free"},
            {"name":"flash","label":"flash","active":true,"model":"qwen/qwen3.7-flash"},
            {"name":"sonnet","label":"sonnet","active":false,"model":"anthropic/claude-sonnet-5.5"}],
        "providers":[{"name":"openrouter","api_key_hint":"sk-o"}]}"""

}
