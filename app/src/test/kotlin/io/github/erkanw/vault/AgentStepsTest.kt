package io.github.erkanw.vault

import io.github.erkanw.vault.chat.AgentStep
import io.github.erkanw.vault.chat.MAX_STEPS
import io.github.erkanw.vault.chat.MAX_STEP_CHARS
import io.github.erkanw.vault.chat.StepKind
import io.github.erkanw.vault.chat.agentStep
import io.github.erkanw.vault.chat.stepsFromJson
import io.github.erkanw.vault.chat.stepsSummary
import io.github.erkanw.vault.chat.stepsToJson
import io.github.erkanw.vault.chat.withStep
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentStepsTest {
    @Test
    fun `steps survive being kept and read back, and nothing empty is kept`() {
        val steps = listOf(AgentStep(StepKind.Tool, "cron add every day"), AgentStep(StepKind.Thinking, "hmm\nokay"), AgentStep(StepKind.Note, "done"))
        assertEquals(steps, stepsFromJson(stepsToJson(steps)))
        assertNull(agentStep(StepKind.Note, "   \n"))
        assertTrue(stepsFromJson(null).isEmpty())
        // A kind this version doesn't know becomes a plain note, not a lost step.
        assertEquals(StepKind.Note, stepsFromJson(org.json.JSONArray("""[{"k":"Mystery","x":"?"}]""")).single().kind)
    }

    @Test
    fun `a runaway step is cut and a runaway list keeps the newest`() {
        val long = agentStep(StepKind.Tool, "x".repeat(MAX_STEP_CHARS * 3))!!
        assertEquals(MAX_STEP_CHARS + 1, long.text.length)
        var steps = emptyList<AgentStep>()
        repeat(MAX_STEPS + 25) { i -> steps = withStep(steps, AgentStep(StepKind.Note, "n$i")) }
        assertEquals(MAX_STEPS, steps.size)
        assertEquals("n${MAX_STEPS + 24}", steps.last().text)
    }

    @Test
    fun `the line above an answer counts the tools`() {
        assertEquals("1 tool", stepsSummary(listOf(AgentStep(StepKind.Tool, "a"))))
        assertEquals("3 tools", stepsSummary(List(3) { AgentStep(StepKind.Tool, "t") }))
        assertEquals("2 steps", stepsSummary(listOf(AgentStep(StepKind.Note, "a"), AgentStep(StepKind.Note, "b"))))
        assertEquals("2 tools · thinking", stepsSummary(listOf(AgentStep(StepKind.Tool, "a"), AgentStep(StepKind.Tool, "b"), AgentStep(StepKind.Thinking, "c"))))
    }

}
