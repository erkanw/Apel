package io.github.erkanw.vault

import io.github.erkanw.vault.chat.appendTranscript
import io.github.erkanw.vault.chat.elapsedLabel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoiceTest {

    @Test
    fun `the timer reads minutes and seconds`() {
        assertEquals("0:00", elapsedLabel(0))
        assertEquals("0:07", elapsedLabel(7_400))
        assertEquals("1:23", elapsedLabel(83_000))
        assertEquals("0:00", elapsedLabel(-5))
    }

    @Test
    fun `heard text joins what was already typed`() {
        assertEquals("hello", appendTranscript("", "  hello "))
        assertEquals("hello", appendTranscript("   ", "hello"))
        assertEquals("note: buy milk", appendTranscript("note: ", "buy milk"))
        assertEquals("note", appendTranscript("note", "   "))
    }

    // The shape of the real /api/settings "transcription" block, shortened.
    private val settings = """{"transcription":{"enabled":true,"provider":"groq","provider_configured":false,
        "model":"whisper-large-v3","max_duration_sec":120,"max_upload_mb":25,
        "providers":[{"name":"groq","label":"Groq","configured":false},{"name":"openrouter","label":"OpenRouter","configured":true}]}}"""

}
