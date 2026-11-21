package io.github.erkanw.vault

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.erkanw.vault.store.AppDatabase
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A backup's database is opened the way the app opens it before anything is replaced (audit item 8): a file the app
 * couldn't open is refused instead of crashing every start. Works in a folder under the app's cache, never on app.db.
 */
@RunWith(AndroidJUnit4::class)
class RestoreCheckTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(context.cacheDir, "restore-check-${System.nanoTime()}").apply { mkdirs() }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun sqlite(name: String, version: Int, vararg sql: String): File {
        val file = File(dir, name)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            sql.forEach { db.execSQL(it) }
            db.version = version
        }
        return file
    }

    @Test fun aForeignDatabaseAtTheRightVersionIsRefused() {
        val file = sqlite("foreign.db", 9, "CREATE TABLE notes (id INTEGER PRIMARY KEY, text TEXT)")
        try {
            AppDatabase.checkAt(context, file)
            fail("a database the app can't open should be refused")
        } catch (e: Exception) {
            // expected: Room's schema check
        }
    }

    @Test fun theAppsOwnDatabaseIsAccepted() {
        // A fresh database made by the app itself, closed, then checked as a restore would.
        val file = File(dir, "own.db")
        androidx.room.Room.databaseBuilder(context, AppDatabase::class.java, file.absolutePath).build().apply {
            openHelper.writableDatabase
            close()
        }
        AppDatabase.checkAt(context, file)
    }
}
