package io.github.erkanw.vault.tools

import android.content.Context
import androidx.core.content.edit
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/**
 * The agent's workspace (`files/workspace/`, PLAN §6.6) and its path guard: every path the agent names stays inside it,
 * no symlink leads out, and `memory/` and `skills/` are left to their own tools.
 */
object Workspace {
    const val MAX_WRITE = 1_000_000
    private const val MAX_READ = 200_000
    private const val PREFS = "workspace"
    private const val KEY_MADE = "agentMade"

    fun root(context: Context) = File(context.filesDir, "workspace").apply { mkdirs() }
    fun trash(context: Context) = File(context.filesDir, "trash").apply { mkdirs() }

    /** [path] (relative, as the agent writes it) inside the workspace, or a [ToolException]. */
    fun resolve(context: Context, path: String, forWrite: Boolean = false): File = resolve(root(context), path, forWrite)

    /** [resolve] against the folder [workspace] itself (the unit tests use a temporary one). */
    internal fun resolve(workspace: File, path: String, forWrite: Boolean = false): File {
        val clean = path.trim().trimStart('/').replace('\\', '/')
        if (clean.split('/').any { it == ".." }) throw ToolException("Paths stay inside the workspace: no “..”.")
        val root = workspace.canonicalFile
        val file = File(root, clean)
        val canonical = file.canonicalFile
        if (canonical != root && !canonical.path.startsWith(root.path + File.separator)) throw ToolException("That path is outside the workspace.")
        // A link anywhere along the way could lead out of the workspace.
        var f: File? = file
        while (f != null && f.path.length > root.path.length) {
            if (Files.isSymbolicLink(f.toPath())) throw ToolException("Links aren’t followed.")
            f = f.parentFile
        }
        // The top folder of the resolved path, not of what the agent typed: "./skills/x" lands in skills/ too.
        val top = canonical.relativeTo(root).invariantSeparatorsPath.substringBefore('/')
        if (forWrite && isGuardedTop(top)) throw ToolException("Use the memory tools for memory/; skills come later.")
        return canonical
    }

    /** `memory/` and `skills/` are written only by their own tools. */
    internal fun isGuardedTop(top: String) = top == "memory" || top == "skills"

    fun relative(context: Context, file: File): String = file.canonicalFile.relativeTo(root(context).canonicalFile).path.replace('\\', '/')

    fun readText(file: File): String {
        if (!file.isFile) throw ToolException("No file there.")
        if (file.length() > MAX_READ) return file.inputStream().use { String(it.readNBytes(MAX_READ)) } + "\n…(the file goes on; ${file.length()} bytes in all)"
        return file.readText()
    }

    // Files the agent wrote: overwriting anything else asks first (PLAN §7.5).
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun agentMade(context: Context, rel: String) = rel in prefs(context).getStringSet(KEY_MADE, emptySet()).orEmpty()
    fun markAgentMade(context: Context, rel: String) {
        val set = prefs(context).getStringSet(KEY_MADE, emptySet()).orEmpty().toMutableSet()
        if (set.add(rel)) prefs(context).edit { putStringSet(KEY_MADE, set) }
    }

    fun forget(context: Context, rel: String) {
        val set = prefs(context).getStringSet(KEY_MADE, emptySet()).orEmpty().toMutableSet()
        if (set.remove(rel)) prefs(context).edit { putStringSet(KEY_MADE, set) }
    }

    fun isLink(f: File) = Files.isSymbolicLink(f.toPath())
}

/** The file tools (PLAN §7.4). Paths are relative to the workspace: `notes/ideas.md`. */
object FileTools {
    private val pathArg = str("A path inside the workspace, like notes/ideas.md.")

    object List : Tool {
        override val name = "files_list"
        override val title = "List files"
        override val description = "Sees which files and folders are in the workspace."
        override val instructions = "List the files and folders at a path in the workspace (\"\" for the top). Folders end in /."
        override val schema = params("path" to str("A folder inside the workspace; \"\" for the top."), required = emptyList())
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Looking at the files"
        override fun step(args: JSONObject) = "files_list " + args.optString("path").ifBlank { "/" }
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val dir = Workspace.resolve(ctx.app, args.optString("path"))
            if (!dir.isDirectory) throw ToolException("No folder there.")
            val items = dir.listFiles().orEmpty().filter { !Workspace.isLink(it) }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            if (items.isEmpty()) return "(empty)"
            return items.joinToString("\n") { if (it.isDirectory) it.name + "/" else "${it.name} (${it.length()} bytes)" }
        }
    }

    object Read : Tool {
        override val name = "file_read"
        override val title = "Read files"
        override val description = "Reads a file in the workspace."
        override val instructions = "Read a text file in the workspace."
        override val schema = params("path" to pathArg)
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Reading ${args.optString("path").substringAfterLast('/')}"
        override fun step(args: JSONObject) = "file_read " + args.optString("path")
        override suspend fun run(args: JSONObject, ctx: ToolContext) = Workspace.readText(Workspace.resolve(ctx.app, args.need("path")))
    }

    object Write : Tool {
        override val name = "file_write"
        override val title = "Write files"
        override val description = "Makes or replaces a file in the workspace. Replacing one you made asks first."
        override val instructions = "Create a text file in the workspace, or replace one. Folders are made as needed. Keep notes in notes/."
        override val schema = params("path" to pathArg, "content" to str("The whole text of the file."))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Writing ${args.optString("path").substringAfterLast('/')}"
        override fun step(args: JSONObject) = "file_write " + args.optString("path")
        override fun mustAsk(args: JSONObject, ctx: ToolContext): ApprovalAsk? {
            val path = args.optString("path")
            val file = runCatching { Workspace.resolve(ctx.app, path, forWrite = true) }.getOrNull() ?: return null
            if (!file.exists() || Workspace.agentMade(ctx.app, Workspace.relative(ctx.app, file))) return null
            return ApprovalAsk("Replace ${file.name}, a file you made", path)
        }
        override fun ask(args: JSONObject) = ApprovalAsk("Write ${args.optString("path").substringAfterLast('/')}", args.optString("path"))
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val file = Workspace.resolve(ctx.app, args.need("path"), forWrite = true)
            val content = args.optString("content")
            if (content.toByteArray().size > Workspace.MAX_WRITE) throw ToolException("That is more than 1 MB; write it in parts.")
            if (file.isDirectory) throw ToolException("That path is a folder.")
            file.parentFile?.mkdirs()
            val existed = file.exists()
            file.writeText(content)
            Workspace.markAgentMade(ctx.app, Workspace.relative(ctx.app, file))
            return (if (existed) "Replaced " else "Wrote ") + Workspace.relative(ctx.app, file) + " (${content.length} characters)."
        }
    }

    object Edit : Tool {
        override val name = "file_edit"
        override val title = "Edit files"
        override val description = "Changes part of a file in the workspace."
        override val instructions = "Replace one exact piece of text in a workspace file with another. old_text must appear exactly once."
        override val schema = params("path" to pathArg, "old_text" to str("The exact text to replace."), "new_text" to str("What replaces it."))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Editing ${args.optString("path").substringAfterLast('/')}"
        override fun step(args: JSONObject) = "file_edit " + args.optString("path")
        override fun mustAsk(args: JSONObject, ctx: ToolContext): ApprovalAsk? = Write.mustAsk(args, ctx)?.let { ApprovalAsk("Change ${args.optString("path").substringAfterLast('/')}, a file you made", args.optString("path")) }
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val file = Workspace.resolve(ctx.app, args.need("path"), forWrite = true)
            replaceOnce(file, args.need("old_text"), args.optString("new_text"))
            return "Edited " + Workspace.relative(ctx.app, file) + "."
        }

        /** Replaces the one [old] in [file] with [new], reading the whole file: never readText's shortened copy, which would cut it off. */
        internal fun replaceOnce(file: File, old: String, new: String) {
            if (old.isEmpty()) throw ToolException("Missing “old_text”.")
            if (!file.isFile) throw ToolException("No file there.")
            if (file.length() > Workspace.MAX_WRITE) throw ToolException("That file is more than 1 MB; it can’t be edited in place.")
            val text = file.readText()
            var count = 0
            var at = text.indexOf(old)
            while (at >= 0) { count++; at = text.indexOf(old, at + 1) }
            if (count == 0) throw ToolException("old_text isn’t in the file. Read it again and copy the text exactly.")
            if (count > 1) throw ToolException("old_text appears $count times; include more of the text around it.")
            file.writeText(text.replace(old, new))
        }
    }

    object Move : Tool {
        override val name = "file_move"
        override val title = "Move files"
        override val description = "Renames or moves a file or folder in the workspace."
        override val instructions = "Rename or move a file or folder inside the workspace. Won’t replace something already there."
        override val schema = params("from" to pathArg, "to" to str("The new path inside the workspace."))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Moving ${args.optString("from").substringAfterLast('/')}"
        override fun step(args: JSONObject) = "file_move ${args.optString("from")} → ${args.optString("to")}"
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val from = Workspace.resolve(ctx.app, args.need("from"), forWrite = true)
            val to = Workspace.resolve(ctx.app, args.need("to"), forWrite = true)
            if (!from.exists()) throw ToolException("Nothing at “${args.optString("from")}”.")
            if (to.exists()) throw ToolException("Something is already at “${args.optString("to")}”.")
            to.parentFile?.mkdirs()
            if (!from.renameTo(to)) throw ToolException("It couldn’t be moved.")
            val old = Workspace.relative(ctx.app, from)
            if (Workspace.agentMade(ctx.app, old)) { Workspace.forget(ctx.app, old); Workspace.markAgentMade(ctx.app, Workspace.relative(ctx.app, to)) }
            return "Moved to " + Workspace.relative(ctx.app, to) + "."
        }
    }

    object Delete : Tool {
        override val name = "file_delete"
        override val title = "Delete files"
        override val description = "Moves a file or folder to the Trash."
        override val instructions = "Move a file or folder in the workspace to the Trash (the user can restore it)."
        override val schema = params("path" to pathArg)
        override val defaultLevel = ToolLevel.Ask
        override fun hint(args: JSONObject) = "Deleting ${args.optString("path").substringAfterLast('/')}"
        override fun step(args: JSONObject) = "file_delete " + args.optString("path")
        override fun ask(args: JSONObject) = ApprovalAsk("Move ${args.optString("path").substringAfterLast('/')} to the Trash", args.optString("path"))
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val file = Workspace.resolve(ctx.app, args.need("path"), forWrite = true)
            if (!file.exists()) throw ToolException("Nothing there.")
            if (file == Workspace.root(ctx.app).canonicalFile) throw ToolException("The workspace itself stays.")
            val rel = Workspace.relative(ctx.app, file)
            val dest = File(Workspace.trash(ctx.app), "${System.currentTimeMillis()}-${file.name}")
            if (!file.renameTo(dest)) throw ToolException("It couldn’t be moved to the Trash.")
            // Where it came from, for restoring (the Trash page, Phase 4).
            File(dest.path + ".from").writeText(rel)
            Workspace.forget(ctx.app, rel)
            return "Moved $rel to the Trash."
        }
    }

    object Search : Tool {
        override val name = "file_search"
        override val title = "Search files"
        override val description = "Finds files in the workspace by name or text."
        override val instructions = "Find workspace files whose name or text contains the query (case-insensitive). Returns paths and matching lines."
        override val schema = params("query" to str("Words to look for."))
        override val defaultLevel = ToolLevel.Allowed
        override fun hint(args: JSONObject) = "Searching the files"
        override fun step(args: JSONObject) = "file_search \"" + args.optString("query") + "\""
        override suspend fun run(args: JSONObject, ctx: ToolContext): String {
            val q = args.need("query").lowercase()
            val root = Workspace.root(ctx.app)
            val out = StringBuilder()
            var hits = 0
            root.walkTopDown().onEnter { !Workspace.isLink(it) }.filter { it.isFile && !Workspace.isLink(it) }.forEach { f ->
                if (hits >= 40) return@forEach
                val rel = Workspace.relative(ctx.app, f)
                val lines = if (f.length() < 500_000) runCatching { f.readLines() }.getOrDefault(emptyList()) else emptyList()
                val matching = lines.withIndex().filter { it.value.lowercase().contains(q) }.take(3)
                if (rel.lowercase().contains(q) || matching.isNotEmpty()) {
                    hits++
                    out.append(rel).append('\n')
                    matching.forEach { out.append("  ").append(it.index + 1).append(": ").append(it.value.trim().take(160)).append('\n') }
                }
            }
            return if (hits == 0) "Nothing found." else out.toString().trimEnd()
        }
    }
}
