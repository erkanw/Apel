package io.github.erkanw.vault.store

import android.app.Application
import android.content.Context
import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import io.github.erkanw.vault.SavedFile
import io.github.erkanw.vault.saveInDownloads
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Backup export and import (PLAN §6.12): everything except keys and tokens, as one zip in Downloads/Apel; a restore
 * replaces everything in the app with a backup and restarts it. The layout is [BackupFormat].
 */
object Backup {
    /** The database version this build reads; a backup from a newer one is refused. Keep it equal to [AppDatabase]'s. */
    const val DB_VERSION = 8

    private const val STAGING = "restore"

    data class Made(val file: SavedFile, val bytes: Long)

    /** A backup that was read and checked, waiting for the user's OK in [restore]. */
    data class Checked(val zip: File, val manifest: BackupFormat.Manifest, val bytes: Long)

    // ---- export -------------------------------------------------------------------------------------------------

    /** Writes a backup into Downloads/Apel. */
    suspend fun export(app: Application): Made = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(app)
        // Fold the write-ahead log into the file, so the copy is whole (the log is copied too, for what came after).
        db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        val chats = db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM sessions").use { if (it.moveToFirst()) it.getInt(0) else 0 }
        val files = filesToSave(app)
        val info = app.packageManager.getPackageInfo(app.packageName, 0)
        val manifest = BackupFormat.Manifest(
            app = app.packageName, format = BackupFormat.FORMAT, versionName = info.versionName.orEmpty(),
            versionCode = androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toInt(), dbVersion = DB_VERSION,
            created = System.currentTimeMillis(), chats = chats, files = files.size,
        )
        var written = 0L
        val saved = saveInDownloads(app, BackupFormat.fileName(manifest.created), "application/zip") { out ->
            val counting = CountingStream(out)
            ZipOutputStream(counting.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST))
                zip.write(BackupFormat.manifestJson(manifest).toByteArray())
                zip.closeEntry()
                for (name in BackupFormat.DB_FILES) {
                    val f = app.getDatabasePath(name)
                    if (f.isFile && f.length() > 0) addFile(zip, "db/$name", f)
                }
                for (name in BackupFormat.PREFS) {
                    val values = app.getSharedPreferences(name, Context.MODE_PRIVATE).all
                    if (values.isEmpty()) continue
                    zip.putNextEntry(ZipEntry("prefs/$name.json"))
                    zip.write(BackupFormat.prefsToJson(values).toString(2).toByteArray())
                    zip.closeEntry()
                }
                for ((path, f) in files) addFile(zip, "files/$path", f)
                File(app.cacheDir, BackupFormat.SENT).listFiles().orEmpty().filter { it.isFile }.forEach { addFile(zip, "cache/${BackupFormat.SENT}/${it.name}", it) }
            }
            written = counting.count
        }
        Made(saved, written)
    }

    /** The app's files that go in a backup, by their path under the files folder (the -shm files are left out). */
    private fun filesToSave(context: Context): List<Pair<String, File>> {
        val base = context.filesDir
        val out = ArrayList<Pair<String, File>>()
        for (root in BackupFormat.FILE_ROOTS) {
            val dir = File(base, root)
            if (!dir.isDirectory) continue
            dir.walkTopDown().onEnter { !java.nio.file.Files.isSymbolicLink(it.toPath()) }.forEach { f ->
                if (f.isFile && !f.name.endsWith("-shm") && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
                    out += f.relativeTo(base).invariantSeparatorsPath to f
                }
            }
        }
        for (single in BackupFormat.FILE_SINGLES) File(base, single).takeIf { it.isFile }?.let { out += single to it }
        return out
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name).apply { time = file.lastModified() })
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private class CountingStream(private val out: OutputStream) : OutputStream() {
        var count = 0L
        override fun write(b: Int) { out.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len); count += len }
        override fun flush() = out.flush()
        override fun close() = out.close()
    }

    // ---- import -------------------------------------------------------------------------------------------------

    /** Copies the chosen file in, reads its manifest and checks every entry. Nothing in the app changes yet. */
    suspend fun check(context: Context, uri: Uri): Checked = withContext(Dispatchers.IO) {
        val zip = File(context.cacheDir, "restore.zip")
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw BackupException(BackupProblem.Unreadable)
            input.use { i -> zip.outputStream().use { i.copyTo(it) } }
        } catch (e: BackupException) {
            throw e
        } catch (e: Exception) {
            zip.delete()
            throw BackupException(BackupProblem.Unreadable, e)
        }
        try {
            // Not a zip at all: some other file was picked, not a damaged backup.
            val opened = try { ZipFile(zip) } catch (e: java.util.zip.ZipException) { throw BackupException(BackupProblem.NotABackup, e) }
            val manifest = opened.use { z ->
                val entry = z.getEntry(BackupFormat.MANIFEST) ?: throw BackupException(BackupProblem.NotABackup)
                val text = z.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                val m = BackupFormat.parseManifest(text, context.packageName, DB_VERSION)
                var total = 0L
                var count = 0
                for (e in z.entries()) {
                    if (e.isDirectory) continue
                    if (++count > BackupFormat.MAX_ENTRIES) throw BackupException(BackupProblem.TooBig)
                    if (BackupFormat.target(e.name) == null) throw BackupException(BackupProblem.Damaged)
                    total += e.size.coerceAtLeast(0)
                }
                if (total > BackupFormat.MAX_TOTAL_BYTES) throw BackupException(BackupProblem.TooBig)
                if (context.dataDir.usableSpace < total + 50L * 1024 * 1024) throw BackupException(BackupProblem.NoSpace)
                if (z.getEntry("db/app.db") == null) throw BackupException(BackupProblem.Damaged)
                m
            }
            Checked(zip, manifest, zip.length())
        } catch (e: BackupException) {
            zip.delete()
            throw e
        } catch (e: Exception) {
            zip.delete()
            throw BackupException(BackupProblem.Damaged, e)
        }
    }

    /** Forgets a checked backup the user didn't restore. */
    fun discard(checked: Checked) {
        checked.zip.delete()
    }

    /**
     * Replaces everything in the app with [checked] and restarts the app. Unpacks to a folder beside the app's own
     * first and checks the database there; only then is anything replaced. Keys and tokens on this phone stay.
     * Returns only on failure (throws); on success the process ends.
     */
    suspend fun restore(context: Context, checked: Checked): Nothing = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val staging = File(app.dataDir, STAGING)
        staging.deleteRecursively()
        staging.mkdirs()
        val prefs = HashMap<String, Map<String, Any>>()
        try {
            ZipFile(checked.zip).use { z ->
                val root = staging.canonicalFile
                for (e in z.entries()) {
                    if (e.isDirectory) continue
                    val rel = BackupFormat.target(e.name) ?: throw BackupException(BackupProblem.Damaged)
                    if (rel == BackupFormat.MANIFEST) continue
                    if (rel.startsWith("prefs/")) {
                        val text = z.getInputStream(e).use { it.readBytes().toString(Charsets.UTF_8) }
                        prefs[rel.removePrefix("prefs/").removeSuffix(".json")] = BackupFormat.prefsFromJson(JSONObject(text))
                        continue
                    }
                    val out = File(staging, rel).canonicalFile
                    if (!out.path.startsWith(root.path + File.separator)) throw BackupException(BackupProblem.Damaged)
                    out.parentFile?.mkdirs()
                    z.getInputStream(e).use { i -> out.outputStream().use { i.copyTo(it) } }
                }
            }
            checkDatabase(File(staging, "db/app.db"))
        } catch (e: BackupException) {
            staging.deleteRecursively()
            throw e
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw BackupException(BackupProblem.Damaged, e)
        }

        // The point of no return: the database is closed and kept closed until the process ends.
        AppDatabase.closeForRestore {
            val dbDir = app.getDatabasePath("app.db").parentFile!!
            for (name in listOf("app.db", "app.db-wal", "app.db-shm", "app.db-journal")) File(dbDir, name).delete()
            for (name in BackupFormat.DB_FILES) File(staging, "db/$name").takeIf { it.isFile }?.let { move(it, File(dbDir, name)) }

            val base = app.filesDir
            for (root in BackupFormat.FILE_ROOTS) {
                File(base, root).deleteRecursively()
                File(staging, "files/$root").takeIf { it.exists() }?.let { move(it, File(base, root)) }
            }
            File(app.cacheDir, BackupFormat.SENT).deleteRecursively()
            File(staging, "cache/${BackupFormat.SENT}").takeIf { it.exists() }?.let { move(it, File(app.cacheDir, BackupFormat.SENT)) }
            for (single in BackupFormat.FILE_SINGLES) {
                File(base, single).delete()
                File(staging, "files/$single").takeIf { it.isFile }?.let { move(it, File(base, single)) }
            }

            for (name in BackupFormat.PREFS) {
                val editor = app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear()
                prefs[name]?.forEach { (key, value) ->
                    @Suppress("UNCHECKED_CAST")
                    when (value) {
                        is String -> editor.putString(key, value)
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is Set<*> -> editor.putStringSet(key, value as Set<String>)
                    }
                }
                editor.commit()
            }
            staging.deleteRecursively()
            checked.zip.delete()
            restart(app)
        }
    }

    /** Opens the unpacked database read-only: a SQLite file at a version this build can migrate, and whole. */
    private fun checkDatabase(file: File) {
        if (!file.isFile) throw BackupException(BackupProblem.Damaged)
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            throw BackupException(BackupProblem.Damaged, e)
        }
        db.use {
            // Read-write so the copied log is folded in here, not on the first start.
            it.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { c -> c.moveToFirst() }
            val version = it.version
            if (version < 1) throw BackupException(BackupProblem.Damaged)
            if (version > DB_VERSION) throw BackupException(BackupProblem.Newer)
            val ok = it.rawQuery("PRAGMA quick_check", null).use { c -> c.moveToFirst() && c.getString(0) == "ok" }
            if (!ok) throw BackupException(BackupProblem.Damaged)
        }
    }

    private fun move(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        if (from.isDirectory) from.copyRecursively(to, overwrite = true) else from.copyTo(to, overwrite = true)
        from.deleteRecursively()
    }

    /** Starts the app again from its launcher screen and ends this process, so every part reads the restored data. */
    private fun restart(app: Context): Nothing {
        val launch = app.packageManager.getLaunchIntentForPackage(app.packageName)
        if (launch?.component != null) app.startActivity(Intent.makeRestartActivityTask(launch.component))
        // A kill, not exit(): exit runs shutdown hooks, which can stall while this thread holds the database lock.
        android.os.Process.killProcess(android.os.Process.myPid())
        throw IOException("unreachable")
    }
}
