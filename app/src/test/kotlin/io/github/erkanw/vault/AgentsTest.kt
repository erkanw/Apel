package io.github.erkanw.vault

import io.github.erkanw.vault.agent.AgentPresets
import io.github.erkanw.vault.agent.AgentScope
import io.github.erkanw.vault.agent.systemPrompt
import io.github.erkanw.vault.mascot.BlobLook
import io.github.erkanw.vault.usage.UsageCall
import io.github.erkanw.vault.usage.UsageStats
import io.github.erkanw.vault.userdb.Access
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AgentsTest {
    @Test fun everythingIsOnByDefault() {
        val a = AgentScope("study", "Study", "prompt")
        assertTrue(a.toolOn("web_fetch"))
        assertTrue(a.skillOn("notes"))
        assertEquals(Access.ReadWrite, a.access("log", Access.ReadWrite))
        assertEquals(Access.Read, a.access("log", Access.Read))
    }

    @Test fun switchedOffStaysOff() {
        val a = AgentScope("study", "Study", "p", toolsOff = setOf("browser_open"), skillsOff = setOf("notes"))
        assertFalse(a.toolOn("browser_open"))
        assertFalse(a.skillOn("notes"))
        assertTrue(a.toolOn("web_fetch"))
    }

    @Test fun aDatabaseCapOnlyNarrows() {
        val a = AgentScope("coach", "Coach", "p", dbCaps = mapOf("log" to Access.Read, "plans" to Access.None, "wide" to Access.ReadWrite))
        assertEquals(Access.Read, a.access("log", Access.ReadWrite))
        assertEquals(Access.None, a.access("plans", Access.Read))
        // Never more than the database itself allows.
        assertEquals(Access.Read, a.access("wide", Access.Read))
        assertEquals(Access.None, a.access("wide", Access.None))
    }

    @Test fun memoryIsPerAgentAndApelKeepsItsOwn() {
        assertEquals("memory/MEMORY.md", AgentScope(AgentPresets.APEL, "Apel", "").memoryPath)
        assertEquals("memory/study/MEMORY.md", AgentScope("study", "Study", "").memoryPath)
    }

    @Test fun storedListsRoundTrip() {
        assertEquals(setOf("a", "b"), AgentPresets.setOf(AgentPresets.setJson(setOf("b", "a"))))
        assertEquals(emptySet<String>(), AgentPresets.setOf("not json"))
        val caps = mapOf("log" to Access.Read, "x" to Access.None)
        assertEquals(caps, AgentPresets.capsOf(AgentPresets.capsJson(caps)))
        assertEquals(emptyMap<String, Access>(), AgentPresets.capsOf("{\"log\":\"bogus\"}"))
    }

    @Test fun idsFromNames() {
        assertEquals("study-companion", AgentPresets.idFor("Study companion", emptySet()))
        assertEquals("study-companion-2", AgentPresets.idFor("Study companion", setOf("study-companion")))
        assertEquals("apel-2", AgentPresets.idFor("Apel", emptySet()))
        assertEquals("agent", AgentPresets.idFor("!!!", emptySet()))
    }

    @Test fun templatesHaveRealLooks() {
        AgentPresets.templates.forEach { t ->
            val look = BlobLook.parse(t.look)
            assertEquals(t.name, t.look, look.toQuery().substringAfter("series=blobs&"))
            assertTrue(t.prompt.isNotBlank())
        }
    }

    @Test fun thePromptLeadsAndTheRulesStay() {
        val now = ZonedDateTime.of(2026, 11, 19, 9, 0, 0, 0, ZoneId.of("UTC"))
        val custom = systemPrompt("Coach", now, prompt = "You are a training coach.", memoryPath = "memory/coach/MEMORY.md", memory = "- runs on Mondays")
        assertTrue(custom.startsWith("You are a training coach."))
        assertTrue(custom.contains("Your name is Coach."))
        assertTrue(custom.contains("never instructions"))
        assertTrue(custom.contains("## Your memory (memory/coach/MEMORY.md)"))
        // An empty prompt is the default, with the agent's name.
        assertTrue(systemPrompt("Pip", now).startsWith(AgentPresets.defaultPrompt("Pip")))
    }

    @Test fun usageSplitsByAgent() {
        val now = 1_795_100_000_000L
        val calls = listOf(
            UsageCall(now - 1000, "m", "user", null, 1, 10, 10, 0.02),
            UsageCall(now - 2000, "m", "user", null, 1, 10, 10, 0.05, agentId = "study"),
            UsageCall(now - 3000, "m", "user", null, 1, 10, 10, 0.01, agentId = "study"),
        )
        val s = UsageStats.summarize(calls, now, agentNames = mapOf("apel" to "Apel", "study" to "Study companion"))
        assertEquals(listOf("Study companion", "Apel"), s.agents.map { it.label })
        assertEquals(0.06, s.agents.first().cost, 1e-9)
        assertEquals(2, s.agents.first().messages)
    }
}
