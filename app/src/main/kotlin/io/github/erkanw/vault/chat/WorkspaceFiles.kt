package io.github.erkanw.vault.chat

import java.util.Locale

/** One line of a folder of the agent's workspace: a file or a folder. */
data class FileEntry(val name: String, val dir: Boolean, val size: Long, val modified: Long)

/** A folder's content, and where it is in the workspace ("" is the top). */
data class FileListing(val path: String, val entries: List<FileEntry>)

/** The path of [name] inside the folder [parent] ("" is the top). */
fun joinPath(parent: String, name: String): String = if (parent.isEmpty()) name else "$parent/$name"

/** The folder that holds [path]; the top holds the top. */
fun parentPath(path: String): String = path.substringBeforeLast('/', "")

/** "1.4 MB", "820 B": what a size reads like in a list. */
fun humanSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.1f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

/** The MIME type Android should be told for a downloaded file, from its name. */
fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "html", "htm" -> "text/html"
    "txt", "md", "log" -> "text/plain"
    "css" -> "text/css"
    "js", "mjs" -> "text/javascript"
    "json" -> "application/json"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "svg" -> "image/svg+xml"
    "pdf" -> "application/pdf"
    "zip" -> "application/zip"
    else -> "application/octet-stream"
}

/** What the in-app viewer shows a file as: [Markdown] rendered, [Text] as prose, [Code] in monospace; null: it can't. */
enum class TextKind(val label: String) { Markdown("Markdown"), Text("Text"), Data("Data"), Code("Code") }

private val proseExtensions = setOf("txt", "text", "log")

/** Data in a text format: tables, records, settings, calendars, subtitles (shown in monospace, labelled Data). */
private val dataExtensions = setOf(
    "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "cfg", "conf", "env", "properties",
    "ics", "vcf", "srt", "vtt",
)
private val markdownExtensions = setOf("md", "markdown", "mdown", "mkd")
private val codeExtensions = setOf(
    "html", "htm", "sql", "sh", "bash", "zsh", "ps1", "bat", "py", "kt", "kts", "java", "js", "mjs", "ts",
    "tsx", "jsx", "css", "scss", "c", "h", "cpp", "hpp", "cs", "go", "rs", "rb", "php", "swift", "lua", "r", "gradle",
    "diff", "patch",
)

/** How the in-app viewer can show [name], by its extension; null for a file it can't show as text (open it elsewhere). */
fun textKindOf(name: String): TextKind? {
    val ext = name.substringAfterLast('.', "").lowercase()
    val base = name.substringAfterLast('/').lowercase()
    return when {
        ext in markdownExtensions -> TextKind.Markdown
        ext in proseExtensions -> TextKind.Text
        ext in dataExtensions -> TextKind.Data
        ext in codeExtensions -> TextKind.Code
        base in setOf("readme", "license", "makefile", "dockerfile", "agents", "notes") -> TextKind.Text
        else -> null
    }
}

/** The most text the viewer reads from one file; past this it says so and offers the download. */
const val VIEWER_MAX_BYTES = 512 * 1024

/**
 * [bytes] as text for the viewer: UTF-8 (a leading byte-order mark dropped, bad bytes replaced), cut at [VIEWER_MAX_BYTES]
 * on a character boundary, and whether it was cut. Null when it looks binary (a NUL byte early on), so it isn't shown as
 * garbage.
 */
fun decodeForViewer(bytes: ByteArray, total: Long = bytes.size.toLong()): Pair<String, Boolean>? {
    val head = bytes.copyOf(minOf(bytes.size, 8192))
    if (head.any { it == 0.toByte() }) return null
    var n = minOf(bytes.size, VIEWER_MAX_BYTES)
    // Don't cut a character in two: step back over UTF-8 continuation bytes.
    if (n < bytes.size) while (n > 0 && (bytes[n].toInt() and 0xC0) == 0x80) n--
    var text = String(bytes, 0, n, Charsets.UTF_8)
    if (text.startsWith('﻿')) text = text.substring(1)
    return text to (total > n)
}
