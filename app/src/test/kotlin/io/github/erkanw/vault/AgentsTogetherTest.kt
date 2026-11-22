package io.github.erkanw.vault

import io.github.erkanw.vault.agent.AgentScope
import io.github.erkanw.vault.agent.ApprovalGate
import io.github.erkanw.vault.agent.ApprovalMemory
import io.github.erkanw.vault.agent.ToolCalls
import io.github.erkanw.vault.agent.messageNote
import io.github.erkanw.vault.agent.subagentPrompt
import io.github.erkanw.vault.automation.BackgroundMode
import io.github.erkanw.vault.tools.AgentTools
import io.github.erkanw.vault.tools.DbTools
import io.github.erkanw.vault.tools.FileTools
import io.github.erkanw.vault.tools.Similar
import io.github.erkanw.vault.tools.ToolLevel
import io.github.erkanw.vault.tools.ToolRegistry
import io.github.erkanw.vault.tools.UrlTrail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** 0.3.0: running in the background, "Don’t ask again" and approving what you allowed before, agents working together. */
class AgentsTogetherTest {

    // ---- what "Don’t ask again" remembers ----

    @Test fun `pages on the same site are alike, wherever on it and with or without www`() {
        val a = UrlTrail.similar("web_fetch", "https://www.example.com/a?x=1")
        val b = UrlTrail.similar("web_fetch", "http://example.com/other/page")
        assertNotNull(a)
        assertEquals(a!!.key, b!!.key)
        assertEquals("pages on example.com", a.label)
        // Another site, or the hidden browser, is another kind of request.
        assertFalse(a.key == UrlTrail.similar("web_fetch", "https://example.org/a")!!.key)
        assertFalse(a.key == UrlTrail.similar("browser_open", "https://example.com/a")!!.key)
        assertNull(UrlTrail.similar("web_fetch", "ftp://example.com/a"))
    }

    @Test fun `database changes are alike by kind and table, and a change to the structure always asks`() {
        val insert = DbTools.similarChange("fitness", "INSERT INTO meals (name) VALUES (?)")!!
        assertEquals("adding rows to meals in “fitness”", insert.label)
        assertEquals(insert.key, DbTools.similarChange("fitness", "insert or replace into Meals VALUES (1)")!!.key)
        assertEquals(insert.key, DbTools.similarChange("fitness", "REPLACE INTO meals VALUES (2)")!!.key)
        assertFalse(insert.key == DbTools.similarChange("fitness", "DELETE FROM meals WHERE id = 1")!!.key)
        assertFalse(insert.key == DbTools.similarChange("other", "INSERT INTO meals VALUES (1)")!!.key)
        assertEquals("changing rows in meals in “fitness”", DbTools.similarChange("fitness", "UPDATE meals SET x = 1")!!.label)
        assertNull(DbTools.similarChange("fitness", "CREATE TABLE t (a)"))
        assertNull(DbTools.similarChange("fitness", "ALTER TABLE meals ADD COLUMN y"))
        assertNull(DbTools.similarChange("fitness", "INSERT INTO meals VALUES (1); DELETE FROM meals"))
        assertNull(DbTools.similarChange("", "INSERT INTO meals VALUES (1)"))
    }

    @Test fun `file requests are alike by folder, but a file you made only by that file`() {
        val a = FileTools.similarFor("file_write", "notes/a.md", "writing files in")
        assertEquals("writing files in notes/", a.label)
        assertEquals(a.key, FileTools.similarFor("file_write", "notes/b.md", "writing files in").key)
        assertFalse(a.key == FileTools.similarFor("file_write", "notes/deep/c.md", "writing files in").key)
        assertEquals("writing files in the workspace’s top folder", FileTools.similarFor("file_write", "todo.md", "writing files in").label)
        val mine = FileTools.similarFor("file_write", "plan.md", "replacing")
        assertEquals("replacing plan.md", mine.label)
        assertFalse(mine.key == FileTools.similarFor("file_write", "other.md", "replacing").key)
        assertFalse(mine.key == FileTools.similarFor("file_write", "plan.md", "writing files in").key)
    }

    // ---- approving what you allowed before ----

    private val pages = Similar("web_fetch|example.com", "pages on example.com")

    @Test fun `don’t ask again always lets it run, allowed once only with the experimental switch`() {
        val once = ApprovalMemory.added(emptyList(), pages, "web_fetch", always = false, now = 1L).single()
        assertFalse(ApprovalMemory.decide(once, auto = false))
        assertTrue(ApprovalMemory.decide(once, auto = true))
        val always = ApprovalMemory.added(emptyList(), pages, "web_fetch", always = true, now = 1L).single()
        assertTrue(ApprovalMemory.decide(always, auto = false))
        // Never seen: it asks, switch or not.
        assertFalse(ApprovalMemory.decide(null, auto = true))
    }

    @Test fun `allowing again keeps don’t ask again, moves it to the top and never repeats it`() {
        var list = ApprovalMemory.added(emptyList(), pages, "web_fetch", always = true, now = 1L)
        list = ApprovalMemory.added(list, Similar("notify", "notifications"), "notify", always = false, now = 2L)
        list = ApprovalMemory.added(list, pages, "web_fetch", always = false, now = 3L)
        assertEquals(2, list.size)
        assertEquals(pages.key, list.first().key)
        assertTrue(list.first().always)
        assertEquals(list, ApprovalMemory.parse(ApprovalMemory.toJson(list)))
        assertEquals(emptyList<ApprovalMemory.Entry>(), ApprovalMemory.parse("not json"))
    }

    @Test fun `past the limit the oldest allowed-once go first, never a don’t ask again`() {
        var list = ApprovalMemory.added(emptyList(), Similar("keep", "kept"), "t", always = true, now = 0L)
        for (i in 1..ApprovalMemory.MAX_ENTRIES + 5) list = ApprovalMemory.added(list, Similar("k$i", "l$i"), "t", always = false, now = i.toLong())
        assertEquals(ApprovalMemory.MAX_ENTRIES, list.size)
        assertTrue(list.any { it.key == "keep" })
        assertFalse(list.any { it.key == "k1" })
        assertTrue(list.any { it.key == "k${ApprovalMemory.MAX_ENTRIES + 5}" })
    }

    @Test fun `don’t ask again from a notification needs the fingerprint too`() {
        val r = ApprovalGate.Request("1", "s", "web_fetch", "Open", "", unattended = true, similar = "pages on example.com")
        assertTrue(ApprovalGate.needsBiometrics(r, ApprovalGate.Answer.AllowSimilar))
        assertFalse(ApprovalGate.needsBiometrics(r.copy(unattended = false), ApprovalGate.Answer.AllowSimilar))
    }

    // ---- agents working together ----

    private fun scope(off: Set<String> = emptySet()) = AgentScope("coach", "Coach", "You are a coach.", toolsOff = off)

    @Test fun `a subagent or a messaged agent can't reach other agents, and keeps to what it may use`() {
        assertTrue(ToolCalls.usable(AgentTools.SUBAGENT, scope(), emptySet(), delegated = false))
        assertTrue(ToolCalls.usable(AgentTools.MESSAGE, scope(), emptySet(), delegated = false))
        assertFalse(ToolCalls.usable(AgentTools.SUBAGENT, scope(), emptySet(), delegated = true))
        assertFalse(ToolCalls.usable(AgentTools.MESSAGE, scope(), emptySet(), delegated = true))
        assertFalse(ToolCalls.usable("memory_write", scope(), AgentTools.SUBAGENT_EXCLUDED, delegated = true))
        assertTrue(ToolCalls.usable("web_fetch", scope(), AgentTools.SUBAGENT_EXCLUDED, delegated = true))
        // A messaged agent keeps its own memory, but makes no automations.
        assertTrue(ToolCalls.usable("memory_write", scope(), AgentTools.MESSAGE_EXCLUDED, delegated = true))
        assertFalse(ToolCalls.usable("automation_create", scope(), AgentTools.MESSAGE_EXCLUDED, delegated = true))
        // Switched off on the agent's page.
        assertFalse(ToolCalls.usable(AgentTools.SUBAGENT, scope(setOf(AgentTools.SUBAGENT)), emptySet(), delegated = false))
    }

    @Test fun `each agent's three switches start on, and choosing the model has its own`() {
        assertTrue(AgentTools.Subagent.choosesModel(scope()))
        assertFalse(AgentTools.Subagent.choosesModel(scope(setOf(AgentTools.SUBAGENT_MODEL))))
        // The model switch isn't a tool: switching it off leaves subagents on.
        assertTrue(scope(setOf(AgentTools.SUBAGENT_MODEL)).toolOn(AgentTools.SUBAGENT))
    }

    @Test fun `both new tools ask first by default, and a backup can't make them Allowed`() {
        assertEquals(ToolLevel.Ask, AgentTools.Message.defaultLevel)
        assertEquals(ToolLevel.Ask, AgentTools.Subagent.defaultLevel)
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel(AgentTools.SUBAGENT, ToolLevel.Allowed))
        assertEquals(ToolLevel.Ask, ToolRegistry.restoredLevel(AgentTools.MESSAGE, ToolLevel.Allowed))
        assertNotNull(ToolRegistry.tool(AgentTools.MESSAGE))
        assertNotNull(ToolRegistry.tool(AgentTools.SUBAGENT))
    }

    @Test fun `an agent is described to the others by its prompt's first sentence`() {
        assertEquals("You are a calm training coach.", AgentTools.blurb("# You are a calm training coach. You plan sessions.\nMore."))
        assertEquals("", AgentTools.blurb(null))
        assertTrue(AgentTools.blurb("x".repeat(300)).length <= 140)
    }

    @Test fun `the step lists the tools a subagent used, once each`() {
        assertEquals(
            "Used: web_fetch, file_write",
            AgentTools.usedLine(listOf("web_fetch https://a.com", "I’ll look further", "web_fetch https://b.com", "file_write notes/x.md")),
        )
        assertEquals("", AgentTools.usedLine(listOf("Just thinking out loud")))
    }

    @Test fun `a subagent's prompt names who started it and leaves out memory and About you`() {
        val p = subagentPrompt("Apel", "Prices", ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, ZoneId.of("Europe/Stockholm")))
        assertTrue(p.contains("helper that Apel"))
        assertTrue(p.contains("“Prices”"))
        assertFalse(p.contains("About the user"))
        assertFalse(p.contains("Your memory"))
        assertTrue(messageNote("Apel").contains("comes from Apel"))
    }

    // ---- running in the background ----

    @Test fun `the background notice says when the next job runs`() {
        val zone = ZoneId.of("Europe/Stockholm")
        val now = ZonedDateTime.of(2026, 11, 22, 9, 0, 0, 0, zone) // a Sunday
        fun at(days: Long, h: Int, m: Int) = now.plusDays(days).withHour(h).withMinute(m).toInstant().toEpochMilli()
        val n = now.toInstant().toEpochMilli()
        assertEquals("at 18:30", BackgroundMode.whenText(at(0, 18, 30), n, zone))
        assertEquals("tomorrow at 07:00", BackgroundMode.whenText(at(1, 7, 0), n, zone))
        assertEquals("Wed at 07:00", BackgroundMode.whenText(at(3, 7, 0), n, zone))
        assertEquals("on 3 Dec at 07:00", BackgroundMode.whenText(at(11, 7, 0), n, zone))
        // A job already due runs in a moment: today.
        assertEquals("at 08:00", BackgroundMode.whenText(at(0, 8, 0), n, zone))
    }
}
