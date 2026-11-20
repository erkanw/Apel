package io.github.erkanw.vault

import io.github.erkanw.vault.agent.ApprovalGate
import io.github.erkanw.vault.agent.ApprovalGate.Answer
import io.github.erkanw.vault.tools.FileTools
import io.github.erkanw.vault.tools.ToolException
import io.github.erkanw.vault.tools.Workspace
import io.github.erkanw.vault.userdb.SqlRules
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The fixes from the November 2026 audit: the workspace write guard, file_edit on big files, Allow behind the fingerprint, stored database names. */
class AuditFixesTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("workspace").toFile()
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    private fun refused(block: () -> Unit) {
        try {
            block()
            fail("should be refused")
        } catch (e: ToolException) {
            // expected
        }
    }

    @Test fun `memory and skills can't be written however the path is spelled`() {
        for (path in listOf("skills/x/SKILL.md", "./skills/x/SKILL.md", "/./skills/x/SKILL.md", "././memory/a/MEMORY.md", ".\\skills\\x\\SKILL.md", "skills", "./memory")) {
            refused { Workspace.resolve(root, path, forWrite = true) }
        }
        // Reading them stays allowed, and so do ordinary writes, with or without "./".
        Workspace.resolve(root, "./skills/x/SKILL.md")
        assertEquals(File(root.canonicalFile, "notes/a.md"), Workspace.resolve(root, "./notes/a.md", forWrite = true))
        Workspace.resolve(root, "skillset/a.md", forWrite = true)
    }

    @Test fun `file_edit keeps the whole of a file bigger than what file_read shows`() {
        val f = File(root, "big.txt")
        val tail = "THE END"
        f.writeText("start\n" + "x".repeat(300_000) + tail)
        FileTools.Edit.replaceOnce(f, "start", "begin")
        val text = f.readText()
        assertTrue(text.startsWith("begin\n"))
        assertTrue(text.endsWith(tail))
        assertEquals(6 + 300_000 + tail.length, text.length)
        assertFalse(text.contains("the file goes on"))
    }

    @Test fun `file_edit refuses files too big to edit in place`() {
        val f = File(root, "huge.txt")
        f.writeText("a" + "x".repeat(Workspace.MAX_WRITE))
        refused { FileTools.Edit.replaceOnce(f, "a", "b") }
        assertEquals(Workspace.MAX_WRITE + 1L, f.length())
    }

    @Test fun `Allow needs the fingerprint only for a request that came by notification`() {
        val watched = ApprovalGate.Request("1", "s", "web_fetch", "Fetch", "", unattended = false)
        val notified = watched.copy(unattended = true)
        assertFalse(ApprovalGate.needsBiometrics(watched, Answer.Allow))
        assertFalse(ApprovalGate.needsBiometrics(watched, Answer.AllowAlways))
        assertTrue(ApprovalGate.needsBiometrics(notified, Answer.Allow))
        assertTrue(ApprovalGate.needsBiometrics(notified, Answer.AllowAlways))
        assertFalse(ApprovalGate.needsBiometrics(notified, Answer.Deny))
    }

    @Test fun `only names the app makes are trusted from the registry`() {
        assertTrue(SqlRules.isDbName("notes_2"))
        for (bad in listOf("../../databases/app", "..", "", "Notes", "a/b", "a\\b")) assertFalse(bad, SqlRules.isDbName(bad))
        assertTrue(SqlRules.isArchivedFileName("notes-20261121-093000.db"))
        for (bad in listOf("../../shared_prefs/x.xml", "..", "notes.db", "notes-20261121-093000.db/..", "../notes-20261121-093000.db")) {
            assertFalse(bad, SqlRules.isArchivedFileName(bad))
        }
    }
}
