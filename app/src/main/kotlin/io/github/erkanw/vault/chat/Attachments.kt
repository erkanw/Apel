package io.github.erkanw.vault.chat

import org.json.JSONArray
import org.json.JSONObject

// Files sent with a message, as the WebUI does it: the message frame gets a `media` list of
// `{data_url: "data:<mime>;base64,…", name}`. Pure helpers here; reading the phone's files is AttachmentReader.

const val MIB = 1024L * 1024L

/**
 * What the agent accepts in one message (`limits` of the login). The defaults are the ones of the user's server;
 * [maxFrameBytes] is only known after a login.
 */
data class IngressLimits(
    val maxCount: Int = 4,
    val maxFileBytes: Long = 6 * MIB,
    val maxTotalBytes: Long = 24 * MIB,
    val maxTextBytes: Long = 64 * 1024L,
    val maxFrameBytes: Long? = null,
    val envelopeReserveBytes: Long = 0L,
) {
    /** How much encoded media fits in one WebSocket frame next to the text, or null if the server names no frame limit. */
    val mediaBudget: Long? get() = maxFrameBytes?.let { (it - maxTextBytes - envelopeReserveBytes).coerceAtLeast(0L) }
}

fun parseLimits(limits: JSONObject?): IngressLimits {
    if (limits == null) return IngressLimits()
    fun number(section: String, key: String): Long? {
        val o = limits.optJSONObject(section) ?: return null
        if (o.isNull(key)) return null
        return o.optLong(key, 0L).takeIf { it > 0L }
    }
    val d = IngressLimits()
    return IngressLimits(
        maxCount = number("attachments", "max_count")?.toInt() ?: d.maxCount,
        maxFileBytes = number("attachments", "max_file_bytes") ?: d.maxFileBytes,
        maxTotalBytes = number("attachments", "max_total_bytes") ?: d.maxTotalBytes,
        maxTextBytes = number("message", "max_text_bytes") ?: d.maxTextBytes,
        maxFrameBytes = number("transport", "max_frame_bytes"),
        envelopeReserveBytes = number("transport", "envelope_reserve_bytes") ?: 0L,
    )
}

enum class AttachmentKind { Image, File }

/** Extension → type of the documents the agent takes (the WebUI's list). Images are accepted by their type instead. */
private val FILE_TYPES = mapOf(
    ".pdf" to "application/pdf",
    ".docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    ".xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    ".pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    ".txt" to "text/plain",
    ".md" to "text/markdown",
    ".csv" to "text/csv",
    ".json" to "application/json",
    ".xml" to "application/xml",
    ".html" to "text/html",
    ".htm" to "text/html",
    ".log" to "text/plain",
    ".yaml" to "application/yaml",
    ".yml" to "application/yaml",
    ".toml" to "application/toml",
    ".ini" to "text/plain",
    ".cfg" to "text/plain",
)


private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot < 0) "" else name.substring(dot).lowercase()
}

/** The type a document is sent as, from its extension; null if the agent doesn't take that kind of file. */
fun fileMimeFor(name: String): String? = FILE_TYPES[extensionOf(name)]

/** Pictures (anything the phone can decode, SVG aside) are sent as images; documents by extension; the rest not at all. */
fun classifyAttachment(name: String, mime: String?): AttachmentKind? = when {
    mime != null && mime.startsWith("image/") && !mime.contains("svg") -> AttachmentKind.Image
    fileMimeFor(name) != null -> AttachmentKind.File
    else -> null
}

/** Base64 grows data by a third, rounded up to groups of three bytes. */
fun encodedSize(bytes: Long): Long = 4L * ((bytes + 2L) / 3L)

fun dataUrl(mime: String, base64: String): String = "data:$mime;base64,$base64"

enum class Rejection { Unsupported, Empty, TooLarge, TooMany, TotalTooLarge, FrameTooLarge, Unreadable }

/**
 * Whether a file of [size] bytes can be added to what is already attached ([count] files, [usedBytes] of
 * them, [usedEncoded] once encoded), the way the WebUI checks it. A picture is shrunk before sending, so
 * only up to the per-file limit of it counts.
 */
fun checkAdding(
    kind: AttachmentKind?,
    size: Long,
    count: Int,
    usedBytes: Long,
    usedEncoded: Long,
    limits: IngressLimits,
): Rejection? {
    if (kind == null) return Rejection.Unsupported
    if (size <= 0L) return Rejection.Empty
    if (kind == AttachmentKind.File && size > limits.maxFileBytes) return Rejection.TooLarge
    if (count >= limits.maxCount) return Rejection.TooMany
    val counted = if (kind == AttachmentKind.Image) minOf(size, limits.maxFileBytes) else size
    if (usedBytes + counted > limits.maxTotalBytes) return Rejection.TotalTooLarge
    val budget = limits.mediaBudget
    if (budget != null && usedEncoded + encodedSize(counted) > budget) return Rejection.FrameTooLarge
    return null
}

/** What to tell the user about a refused file. */
fun rejectionMessage(reason: Rejection, name: String, limits: IngressLimits): String = when (reason) {
    Rejection.Unsupported -> "“$name” can’t be sent. Photos, PDF, Word, Excel, PowerPoint and text files (TXT, MD, CSV, JSON, HTML…) work."
    Rejection.Empty -> "“$name” is empty."
    Rejection.TooLarge -> "“$name” is over ${megabytes(limits.maxFileBytes)}."
    Rejection.TooMany -> "Up to ${limits.maxCount} files per message."
    Rejection.TotalTooLarge -> "That is more than ${megabytes(limits.maxTotalBytes)} in one message."
    Rejection.FrameTooLarge -> "That is too much to send in one message."
    Rejection.Unreadable -> "Couldn't read “$name”."
}

private fun megabytes(bytes: Long): String = "${bytes / MIB} MB"

/** One file as the message frame carries it. */
class MediaPart(val name: String, val dataUrl: String)

/** The `media` list of a message frame. */
fun mediaJson(media: List<MediaPart>): JSONArray {
    val array = JSONArray()
    for (m in media) array.put(JSONObject().put("data_url", m.dataUrl).put("name", m.name))
    return array
}

/**
 * A file sent with (or found in) a message, as the chat shows it: its name and what it is. [url] is where
 * The agent keeps it (a signed link, relative to its address), [localPath] a copy this phone made when sending it.
 */
data class MessageAttachment(
    val name: String,
    val image: Boolean,
    val video: Boolean = false,
    val url: String? = null,
    val localPath: String? = null,
) {
    /** A picture or a video can be opened in the app's viewer. */
    val viewable: Boolean get() = image || video
}

/**
 * The files of a thread entry, from whatever it carries under `media` or `images` (the server writes a
 * sent picture under both): objects with a name, a url and a kind, or plain urls. Each file is listed once.
 */
fun parseMessageAttachments(entry: JSONObject): List<MessageAttachment> {
    val out = ArrayList<MessageAttachment>()
    val seen = HashSet<String>()
    // A live message lists the files as paths on the server under `media`, and their signed links under `media_urls`.
    val signed = HashMap<String, String>()
    entry.optJSONArray("media_urls")?.let { links ->
        for (i in 0 until links.length()) {
            val link = links.optJSONObject(i) ?: continue
            val address = link.str("url")
            val label = link.str("name")
            if (address.isNotBlank() && label.isNotBlank()) signed[label] = address
        }
    }
    for (key in listOf("media", "images")) {
        val array = entry.optJSONArray(key) ?: continue
        for (i in 0 until array.length()) {
            val item = array.opt(i)
            val url: String
            val name: String
            val kind: String
            when (item) {
                is JSONObject -> {
                    url = item.str("url").ifBlank { item.str("path") }
                    name = item.str("name").ifBlank { lastSegment(url) }
                    kind = item.str("kind")
                }
                is String -> {
                    name = lastSegment(item)
                    url = signed[name] ?: item
                    kind = ""
                }
                else -> continue
            }
            if (name.isBlank()) continue
            if (!seen.add(url.ifBlank { name })) continue
            // The extension decides first (the WebUI does the same), then the kind the server gave.
            val ext = extensionOf(name).ifEmpty { extensionOf(lastSegment(url)) }
            val image = if (ext.isNotEmpty()) ext in IMAGE_EXTENSIONS else kind == "image" || key == "images"
            val video = !image && (if (ext.isNotEmpty()) ext in VIDEO_EXTENSIONS else kind == "video")
            out.add(MessageAttachment(name, image, video, url = url.ifBlank { null }))
        }
    }
    return out
}

private val IMAGE_EXTENSIONS = setOf(".png", ".jpg", ".jpeg", ".webp", ".gif", ".heic", ".heif", ".bmp")
private val VIDEO_EXTENSIONS = setOf(".mp4", ".mov", ".webm", ".mkv", ".m4v", ".3gp")

private fun lastSegment(path: String): String = path.substringBefore('?').substringAfterLast('/').substringAfterLast('\\')

/**
 * The address to fetch an attachment from: the agent’s links are relative to its own address ("/api/media/…"),
 * and an absolute one is only followed if it is on that same host, so nothing is fetched from elsewhere.
 * Null if it can't be placed.
 */
fun resolveMediaUrl(base: String?, url: String?): String? {
    val link = url?.trim().orEmpty()
    if (link.isEmpty() || base.isNullOrBlank()) return null
    val root = base.trimEnd('/')
    return when {
        link.startsWith("//") -> null
        link.startsWith("/") -> root + link
        link.startsWith("http://", ignoreCase = true) || link.startsWith("https://", ignoreCase = true) -> {
            val here = try { java.net.URI(root).host } catch (e: Exception) { null }
            val there = try { java.net.URI(link).host } catch (e: Exception) { null }
            if (here != null && here.equals(there, ignoreCase = true)) link else null
        }
        link.contains(":") -> null
        else -> "$root/$link"
    }
}
