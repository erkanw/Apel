package io.github.erkanw.vault.tools

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

/** One thing in the Trash: what it was called, where it came from, and when it was deleted. */
data class TrashItem(val id: String, val name: String, val from: String, val deleted: Long, val dir: Boolean)

/**
 * What you do to the workspace from the Files page (PLAN §6.6): new file and folder, edit, rename or move, move to the
 * Trash and back, import from the phone. The same path guard as the agent's file tools; `memory/` and `skills/` are
 * edited on their own pages.
 */
object WorkspaceOps {
    const val TRASH_DAYS = 30
    private val NAME = Regex("""^[^/\\:*?"<>|]{1,120}$""")

    /** True for the folders this page only shows. */
    fun readOnly(path: String) = path.substringBefore('/') in setOf("memory", "skills")

    private val TRASH_ID = Regex("""^\d+-[^/\\]+$""")

    /** [path] in the workspace, for a change: not the workspace itself, and not in memory/ or skills/ (checked on the real path). */
    private fun target(context: Context, path: String): File {
        val f = Workspace.resolve(context, path)
        if (f == Workspace.root(context).canonicalFile) throw ToolException("The workspace itself stays.")
        if (readOnly(Workspace.relative(context, f))) throw ToolException("memory/ and skills/ are edited on their own pages.")
        return f
    }

    private fun checkName(name: String) {
        if (!NAME.matches(name) || name == "." || name == "..") throw ToolException("A name can’t be empty or use / \\ : * ? \" < > |.")
    }

    fun newFile(context: Context, folder: String, name: String): String {
        checkName(name)
        val f = target(context, joinPath(folder, name))
        if (f.exists()) throw ToolException("Something called $name is already here.")
        f.parentFile?.mkdirs()
        f.writeText("")
        return Workspace.relative(context, f)
    }

    fun newFolder(context: Context, folder: String, name: String) {
        checkName(name)
        val f = target(context, joinPath(folder, name))
        if (f.exists()) throw ToolException("Something called $name is already here.")
        if (!f.mkdirs()) throw ToolException("The folder couldn’t be made.")
    }

    fun writeText(context: Context, path: String, text: String) {
        val f = target(context, path)
        if (text.toByteArray().size > Workspace.MAX_WRITE) throw ToolException("That is more than 1 MB.")
        f.writeText(text)
        // You wrote it now: the agent asks before replacing it.
        Workspace.forget(context, Workspace.relative(context, f))
    }

    /** Renames or moves [path] to [to] (a path in the workspace). */
    fun move(context: Context, path: String, to: String): String {
        val from = target(context, path)
        val dest = target(context, to.trim().trim('/'))
        checkName(dest.name)
        if (!from.exists()) throw ToolException("That is gone.")
        if (dest.exists()) throw ToolException("Something is already at ${to.trim()}.")
        if (dest.path.startsWith(from.path + File.separator)) throw ToolException("A folder can’t go inside itself.")
        dest.parentFile?.mkdirs()
        if (!from.renameTo(dest)) throw ToolException("It couldn’t be moved.")
        return Workspace.relative(context, dest)
    }

    fun trash(context: Context, path: String) {
        val f = target(context, path)
        if (!f.exists()) throw ToolException("That is gone.")
        val rel = Workspace.relative(context, f)
        val dest = File(Workspace.trash(context), "${System.currentTimeMillis()}-${f.name}")
        if (!f.renameTo(dest)) throw ToolException("It couldn’t be moved to the Trash.")
        File(dest.path + ".from").writeText(rel)
        Workspace.forget(context, rel)
    }

    /** What is in the Trash, newest first; anything older than [TRASH_DAYS] days is deleted for good first. */
    fun trashList(context: Context): List<TrashItem> {
        val dir = Workspace.trash(context)
        val cutoff = System.currentTimeMillis() - TRASH_DAYS * 86_400_000L
        val items = dir.listFiles().orEmpty().filter { !it.name.endsWith(".from") }.mapNotNull { f ->
            val stamp = f.name.substringBefore('-').toLongOrNull() ?: return@mapNotNull null
            if (stamp < cutoff) { f.deleteRecursively(); File(f.path + ".from").delete(); return@mapNotNull null }
            val from = File(f.path + ".from").takeIf { it.isFile }?.readText()?.trim().orEmpty()
            TrashItem(f.name, f.name.substringAfter('-'), from, stamp, f.isDirectory)
        }
        return items.sortedByDescending { it.deleted }
    }

    /** Puts [id] back where it came from (or beside it, with "(restored)", when that place is taken). */
    fun restore(context: Context, id: String): String {
        if (!TRASH_ID.matches(id)) throw ToolException("That is no longer in the Trash.")
        val f = File(Workspace.trash(context), id)
        if (!f.exists() || f.name != id) throw ToolException("That is no longer in the Trash.")
        val from = File(f.path + ".from").takeIf { it.isFile }?.readText()?.trim().orEmpty().ifEmpty { id.substringAfter('-') }
        var dest = Workspace.resolve(context, from, forWrite = from.substringBefore('/') !in setOf("memory", "skills"))
        if (dest.exists()) {
            dest = if (from.startsWith("skills/")) {
                // A skill’s folder name must stay a valid skill name: name-restored, name-restored-2…
                var n = 1
                var candidate: File
                do { candidate = File(dest.parentFile, dest.name.take(38) + "-restored" + (if (n > 1) "-$n" else "")); n++ } while (candidate.exists())
                candidate
            } else {
                File(dest.parentFile, dest.nameWithoutExtension + " (restored)" + (if (dest.extension.isNotEmpty()) "." + dest.extension else ""))
            }
        }
        dest.parentFile?.mkdirs()
        if (!f.renameTo(dest)) throw ToolException("It couldn’t be put back.")
        File(f.path + ".from").delete()
        return Workspace.relative(context, dest)
    }

    fun deleteForever(context: Context, id: String) {
        if (!TRASH_ID.matches(id)) return
        val f = File(Workspace.trash(context), id)
        if (f.name != id || f.parentFile?.canonicalFile != Workspace.trash(context).canonicalFile) return
        f.deleteRecursively()
        File(f.path + ".from").delete()
    }

    /** Copies a file the user picked on the phone into [folder]; its workspace path. */
    fun import(context: Context, folder: String, uri: Uri): String {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace(Regex("""[/\\:*?"<>|]"""), "_")?.ifBlank { null } ?: "file"
        val dir = target(context, folder.ifEmpty { "downloads" }).apply { mkdirs() }
        var dest = File(dir, name)
        var n = 2
        while (dest.exists()) dest = File(dir, name.substringBeforeLast('.') + " ($n)" + (if ('.' in name) "." + name.substringAfterLast('.') else "")).also { n++ }
        context.contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            ?: throw ToolException("That file couldn’t be read.")
        return Workspace.relative(context, dest)
    }

    private fun joinPath(folder: String, name: String) = if (folder.isEmpty()) name else "$folder/$name"
}
