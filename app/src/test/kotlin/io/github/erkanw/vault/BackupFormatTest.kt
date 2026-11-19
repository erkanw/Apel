package io.github.erkanw.vault

import io.github.erkanw.vault.store.Backup
import io.github.erkanw.vault.store.BackupException
import io.github.erkanw.vault.store.BackupFormat
import io.github.erkanw.vault.store.BackupProblem
import io.github.erkanw.vault.ui.reflowLicence
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.TimeZone

class BackupFormatTest {
    private val app = "io.github.erkanw.vault"

    private fun manifest(format: Int = 1, db: Int = 7, appId: String = app) = BackupFormat.manifestJson(
        BackupFormat.Manifest(appId, format, "0.1.0", 1, db, 1_763_500_000_000, 12, 40),
    )

    private fun problem(block: () -> Unit): BackupProblem {
        try {
            block()
        } catch (e: BackupException) {
            return e.problem
        }
        fail("no BackupException")
        error("unreachable")
    }

    @Test fun manifestRoundTrips() {
        val m = BackupFormat.parseManifest(manifest(), app, 7)
        assertEquals(12, m.chats)
        assertEquals(40, m.files)
        assertEquals("0.1.0", m.versionName)
        assertEquals(1_763_500_000_000, m.created)
    }

    @Test fun manifestRefusals() {
        assertEquals(BackupProblem.NotABackup, problem { BackupFormat.parseManifest("not json", app, 7) })
        assertEquals(BackupProblem.NotABackup, problem { BackupFormat.parseManifest("{}", app, 7) })
        assertEquals(BackupProblem.OtherApp, problem { BackupFormat.parseManifest(manifest(appId = "com.example"), app, 7) })
        assertEquals(BackupProblem.Newer, problem { BackupFormat.parseManifest(manifest(db = 8), app, 7) })
        assertEquals(BackupProblem.Newer, problem { BackupFormat.parseManifest(manifest(format = 2), app, 7) })
        // An older database is fine: Room migrates it on the first start.
        assertEquals(3, BackupFormat.parseManifest(manifest(db = 3), app, 7).dbVersion)
    }

    @Test fun entriesThatBelong() {
        assertEquals("apel-backup.json", BackupFormat.target("apel-backup.json"))
        assertEquals("db/app.db", BackupFormat.target("db/app.db"))
        assertEquals("db/app.db-wal", BackupFormat.target("db/app.db-wal"))
        assertEquals("prefs/agent.json", BackupFormat.target("prefs/agent.json"))
        assertEquals("files/workspace/notes/a.md", BackupFormat.target("files/workspace/notes/a.md"))
        assertEquals("files/dbs/reading_log.db", BackupFormat.target("files/dbs/reading_log.db"))
        assertEquals("files/chat.json", BackupFormat.target("files/chat.json"))
        assertEquals("cache/sent/x.jpg", BackupFormat.target("cache/sent/x.jpg"))
    }

    @Test fun entriesThatDont() {
        listOf(
            "../evil", "files/../../shared_prefs/x.xml", "files/workspace/../../x", "/files/workspace/a",
            "files\\workspace\\a", "db/app.db-shm", "db/other.db", "db/sub/app.db", "prefs/openrouter.json",
            "prefs/openrouter_secret.json", "prefs/mcp_1234.json", "prefs/agent.xml", "shared_prefs/agent.xml",
            "files/icon/a.png", "files/chat.json/x", "files/./workspace/a", "cache/other/a", "cache/sent/",
            "databases/app.db", "", "files/",
        ).forEach { assertNull(it, BackupFormat.target(it)) }
    }

    @Test fun secretsAreNeverBackedUp() {
        assertTrue(BackupFormat.PREFS.none { it.contains("secret") || it.startsWith("mcp_") || it == "openrouter" })
    }

    @Test fun prefsRoundTripWithTypes() {
        val values = mapOf(
            "name" to "Pip", "on" to true, "count" to 3, "since" to 1_763_500_000_000L, "scale" to 1.5f,
            "set" to setOf("b", "a"), "skipped" to Any(),
        )
        val back = BackupFormat.prefsFromJson(JSONObject(BackupFormat.prefsToJson(values).toString()))
        assertEquals("Pip", back["name"])
        assertEquals(true, back["on"])
        assertEquals(3, back["count"])
        assertEquals(1_763_500_000_000L, back["since"])
        assertEquals(1.5f, back["scale"])
        assertEquals(setOf("a", "b"), back["set"])
        assertNull(back["skipped"])
    }

    @Test fun fileNameUsesThePhonesTime() {
        val zone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            assertEquals("apel-backup-2025-11-18-2053.zip", BackupFormat.fileName(1_763_499_180_000))
        } finally {
            TimeZone.setDefault(zone)
        }
    }

    @Test fun backupReadsTheNewestSchema() {
        val dir = listOf(File("schemas"), File("app/schemas")).first { it.isDirectory }
        val newest = dir.walkTopDown().filter { it.extension == "json" }.maxOf { it.nameWithoutExtension.toInt() }
        assertEquals(newest, Backup.DB_VERSION)
    }

    @Test fun licenceTextsReflow() {
        val text = "MIT License\n\nCopyright (c) 2026 Someone\n\n" +
            "Permission is hereby granted, free of charge, to any person obtaining a copy\n" +
            "  of this software and associated documentation files (the \"Software\"), to deal\nin the Software.\n\n---\n\nEnd."
        assertEquals(
            listOf(
                "MIT License", "Copyright (c) 2026 Someone",
                "Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the \"Software\"), to deal in the Software.",
                "End.",
            ),
            reflowLicence(text),
        )
        // A block of short lines keeps its breaks; list items start their own paragraph, their continuations join.
        assertEquals(listOf("Apel\nCopyright 2026 Erkan Wong (erkanw)"), reflowLicence("Apel\r\nCopyright 2026 Erkan Wong (erkanw)\r\n"))
        val notice = "- The mascot is a Kotlin port of Blob Atelier by Victoria Wu\n  (https://github.com/victoriawu-77/blob-atelier), MIT License.\n" +
            "- Inter, by The Inter Project Authors (https://github.com/rsms/inter),\n  SIL Open Font License 1.1."
        assertEquals(2, reflowLicence(notice).size)
        assertTrue(reflowLicence(notice)[1].startsWith("- Inter") && reflowLicence(notice)[1].endsWith("License 1.1."))
    }
}
