package io.github.erkanw.vault.agent

import io.github.erkanw.vault.chat.MediaPart
import org.json.JSONArray
import org.json.JSONObject

/** What a file attached to a message becomes for the model (PLAN §6.4). */
enum class PartKind { Image, Pdf, Text, Other }

/** The mime type and the bytes of a `data:` URL; null when it isn't one. */
fun decodeDataUrl(url: String): Pair<String, ByteArray>? {
    if (!url.startsWith("data:")) return null
    val comma = url.indexOf(',')
    if (comma < 0) return null
    val head = url.substring(5, comma)
    val mime = head.substringBefore(';').ifBlank { "application/octet-stream" }
    val bytes = if (head.endsWith(";base64")) {
        try { java.util.Base64.getDecoder().decode(url.substring(comma + 1)) } catch (e: Exception) { return null }
    } else {
        java.net.URLDecoder.decode(url.substring(comma + 1), "UTF-8").toByteArray()
    }
    return mime to bytes
}

private val TEXT_EXTENSIONS = setOf(
    "md", "markdown", "txt", "text", "log", "csv", "tsv", "json", "jsonl", "xml", "yaml", "yml", "toml", "ini", "cfg", "html", "htm",
    "css", "js", "ts", "kt", "kts", "java", "py", "sh", "sql", "c", "h", "cpp", "go", "rs", "rb", "php", "swift", "ics", "vcf", "srt",
)

/** How a file is sent, by its type and name: pictures and PDFs as files the model reads, text inline, the rest saved. */
fun partKindOf(name: String, mime: String): PartKind {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> PartKind.Image
        mime == "application/pdf" || ext == "pdf" -> PartKind.Pdf
        mime.startsWith("text/") || mime == "application/json" || mime == "application/xml" || ext in TEXT_EXTENSIONS -> PartKind.Text
        else -> PartKind.Other
    }
}

/** The largest text file put into a message as it is (PLAN §6.4: 100 KB). */
const val MAX_INLINE_TEXT = 100 * 1024

/**
 * The `content` of the user's message: just the text when there are no files, else an array of parts. Pictures go as
 * `image_url`, PDFs as OpenRouter's `file` part ([pdfAsFile]; elsewhere they are saved like any other file), text files
 * inline up to [MAX_INLINE_TEXT], and anything else is saved by [save] (which returns where) and named in a line.
 */
fun userContent(text: String, media: List<MediaPart>, pdfAsFile: Boolean, save: (String, ByteArray) -> String?): Any {
    if (media.isEmpty()) return text
    val parts = JSONArray()
    val notes = StringBuilder()
    for (m in media) {
        val (mime, bytes) = decodeDataUrl(m.dataUrl) ?: continue
        when (partKindOf(m.name, mime)) {
            PartKind.Image -> parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", m.dataUrl)))
            PartKind.Pdf -> if (pdfAsFile) {
                parts.put(JSONObject().put("type", "file").put("file", JSONObject().put("filename", m.name).put("file_data", m.dataUrl)))
            } else {
                notes.append(savedNote(m.name, save(m.name, bytes)))
            }
            PartKind.Text -> if (bytes.size <= MAX_INLINE_TEXT) {
                notes.append("\n\nAttached file “").append(m.name).append("”:\n```\n").append(String(bytes, Charsets.UTF_8).trimEnd()).append("\n```")
            } else {
                notes.append(savedNote(m.name, save(m.name, bytes)))
            }
            PartKind.Other -> notes.append(savedNote(m.name, save(m.name, bytes)))
        }
    }
    val body = (text + notes).trim()
    if (parts.length() == 0) return body
    return JSONArray().put(JSONObject().put("type", "text").put("text", body.ifEmpty { "(The user sent only the attached files.)" })).also { a ->
        for (i in 0 until parts.length()) a.put(parts.get(i))
    }
}

private fun savedNote(name: String, where: String?): String =
    if (where == null) "\n\n(The user attached “$name”, but it couldn’t be saved.)"
    else "\n\n(The user attached “$name”. It is saved at $where in your workspace.)"
