package io.github.erkanw.vault.ui

import android.content.Context
import io.github.erkanw.vault.agent.AgentBackend
import io.github.erkanw.vault.openrouter.OrActivityRow
import io.github.erkanw.vault.openrouter.OrCredits
import io.github.erkanw.vault.openrouter.OrKey
import io.github.erkanw.vault.openrouter.OrKeyUse
import io.github.erkanw.vault.openrouter.OrSnapshot

/**
 * Debug builds only: sample data so every screen can be looked at on a phone or emulator without a provider. It is on
 * when the app's private files folder holds a file called `demo`
 * (`adb shell run-as io.github.erkanw.vault touch files/demo`, then restart the app). Write `busy`, `error` or `empty`
 * into the file for those states. The release build has an empty twin of this object, so none of this data ships.
 */
object DemoMode {
    fun active(context: Context): Boolean = context.filesDir.resolve("demo").exists()

    /** The demo backend while demo mode is on; null otherwise. */
    fun backend(context: Context): AgentBackend? = if (active(context)) DemoBackend(context.applicationContext, mode(context)) else null

    private fun mode(context: Context): String = runCatching { context.filesDir.resolve("demo").readText().trim() }.getOrDefault("")

    /** The demo file says "busy": the sample chat's last reply is still being written. */
    fun busy(context: Context): Boolean = active(context) && mode(context) == "busy"

    /** The demo file says "error": Home says the chats couldn't be loaded. */
    fun unreachable(context: Context): Boolean = active(context) && mode(context) == "error"

    /** The Notes channel shows as working, the Daily brief as having news, so the drawer's states can be seen. */
    fun channelRunning(id: String): Boolean = id == "notes"
    fun channelUnread(id: String): Boolean = id == "daily-brief"

    val automationNames: Map<String, String> = mapOf("demo-brief" to "Morning brief", "demo-tidy" to "Tidy memory")

    /** A sample month of the app's own log: thirty days of answers over four models, from you and two automations. */
    fun usage(now: Long): List<io.github.erkanw.vault.usage.UsageCall> {
        val models = listOf("google/gemini-3-flash" to 0.0021, "qwen/qwen3.8-27b" to 0.0046, "openai/gpt-oss-120b:free" to 0.0, "mistralai/mistral-small-4" to 0.0009)
        val day = 86_400_000L
        val out = ArrayList<io.github.erkanw.vault.usage.UsageCall>()
        for (d in 0 until 30) {
            val wave = 0.55 + 0.45 * kotlin.math.sin(d / 3.0) + if (d % 7 == 5) 0.6 else 0.0
            val base = now - (29 - d) * day
            val answers = (14 * wave).toInt() + 2
            for (a in 0 until answers) {
                val (model, price) = models[(a * 7 + d) % models.size]
                val requests = 1 + (a % 3)
                out += io.github.erkanw.vault.usage.UsageCall(base - a * 600_000L, model, "user", null, requests, 3_000L * requests, 400L, price * requests * wave)
            }
            out += io.github.erkanw.vault.usage.UsageCall(base - 7 * 3_600_000L, "google/gemini-3-flash", "automation", "demo-brief", 3, 12_000L, 900L, 0.006 * wave)
            out += io.github.erkanw.vault.usage.UsageCall(base - 9 * 3_600_000L, "mistralai/mistral-small-4", "automation", "demo-tidy", 1, 6_000L, 700L, 0.0004)
        }
        return out.filter { it.time <= now }
    }

    /** A sample month of OpenRouter use: one key, thirty days over four models. */
    fun openRouter(now: Long): OrSnapshot {
        val end = java.time.LocalDate.now(java.time.ZoneOffset.UTC).minusDays(1)
        val models = listOf("google/gemini-3-flash" to 0.06, "qwen/qwen3.8-27b" to 0.11, "openai/gpt-oss-120b" to 0.03, "mistralai/mistral-small-4" to 0.02)
        val rows = (0 until 30).flatMap { d ->
            val day = end.minusDays((29 - d).toLong()).toString()
            val wave = 0.55 + 0.45 * kotlin.math.sin(d / 3.0) + if (d % 7 == 5) 0.6 else 0.0
            models.mapIndexedNotNull { i, (m, base) ->
                if (d % 9 == 4 && i > 0) null
                else OrActivityRow(day, m, "x", base * wave, (40 * wave).toInt() + 3, (90_000 * wave).toLong(), (12_000 * wave).toLong(), if (i == 0) (4_000 * wave).toLong() else 0L, (30_000 * wave).toLong())
            }
        }
        val key = OrKey("Apel on this phone", 6.4, 0.08, 0.9, 3.1, null, null, management = true)
        val keys = listOf(OrKeyUse("Apel on this phone", "sk-or-v1-a", false, 6.4, 0.08, 0.9, 3.1))
        return OrSnapshot(now - 120_000L, key, OrCredits(10.0, 6.4), keys, rows, now - 3_600_000L)
    }
}
