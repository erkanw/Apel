package io.github.erkanw.vault

import io.github.erkanw.vault.chat.EditResult
import io.github.erkanw.vault.chat.NEW_JOB
import io.github.erkanw.vault.chat.automationEdit
import io.github.erkanw.vault.chat.createJobRequest
import io.github.erkanw.vault.chat.createSkillRequest
import io.github.erkanw.vault.chat.newScheduleDraft
import io.github.erkanw.vault.chat.skillMarkdown
import io.github.erkanw.vault.chat.skillProblem
import io.github.erkanw.vault.chat.skillSlug
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CreateRequestsTest {
    private val zone = ZoneId.of("Europe/Stockholm")

    @Test
    fun `a new job starts as every day at nine, and its schedule is always sent`() {
        val draft = newScheduleDraft(zone)
        assertTrue(draft.touched)
        val ok = automationEdit(NEW_JOB, "Brief", "Send me the news.", draft, zone) as EditResult.Ok
        assertEquals("0 9 * * *", ok.schedule!!.getString("expr"))
        assertEquals("Europe/Stockholm", ok.schedule!!.getString("tz"))
        assertTrue(ok.values.has("schedule"))
    }

    @Test
    fun `the create request names the chat, the schedule and the instruction word for word`() {
        val ok = automationEdit(NEW_JOB, "Brief", "Send me the news.\nThree \"links\".", newScheduleDraft(zone), zone) as EditResult.Ok
        val inChannel = createJobRequest("Brief", "Send me the news.\nThree \"links\".", ok.schedule!!, "Morning brief")
        assertTrue(inChannel.contains("this chat (Morning brief)"))
        assertTrue(inChannel.contains("cron_expr \"0 9 * * *\" with tz \"Europe/Stockholm\""))
        assertTrue(inChannel.contains("<<<\nSend me the news.\nThree \"links\".\n>>>"))
        assertTrue(createJobRequest("Brief", "x", ok.schedule!!, null).contains("created from this chat so"))
    }

    @Test
    fun `a skill's name becomes its folder name`() {
        assertEquals("study-planner", skillSlug("  Study Planner! "))
        assertEquals("a-b-c", skillSlug("a--b__c"))
        assertEquals("", skillSlug("!!!"))
        assertEquals(48, skillSlug("x".repeat(80)).length)
    }

    @Test
    fun `the skill file has its front matter, with the description quoted on one line`() {
        val md = skillMarkdown("study-planner", "Plans: \"spaced\"\nreviews", "Step 1.\nStep 2.")
        assertTrue(md.startsWith("---\nname: study-planner\ndescription: \"Plans: \\\"spaced\\\" reviews\"\n---\n\nStep 1.\nStep 2.\n"))
    }

    @Test
    fun `a skill needs a free name, a description and instructions`() {
        val taken = setOf("web-search")
        assertEquals("Give it a name.", skillProblem("  ", "d", "i", taken))
        assertTrue(skillProblem("Web Search", "d", "i", taken)!!.contains("already a skill called web-search"))
        assertTrue(skillProblem("new", " ", "i", taken)!!.startsWith("Say in one line"))
        assertTrue(skillProblem("new", "d", " ", taken)!!.startsWith("Write the instructions"))
        assertNull(skillProblem("new", "d", "i", taken))
        val request = createSkillRequest("study-planner", "Plans", "Do it.")
        assertTrue(request.contains("skills/study-planner/SKILL.md"))
        assertTrue(request.contains("stop and tell me instead of overwriting it"))
    }
}
