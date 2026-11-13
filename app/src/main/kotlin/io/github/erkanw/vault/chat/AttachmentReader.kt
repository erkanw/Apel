package io.github.erkanw.vault.chat

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** A file the user picked, as the phone describes it. */
class PickedFile(val uri: Uri, val name: String, val mime: String?, val size: Long)

/** A file ready to send: its [dataUrl], the [name] to send it under, its size, and for a picture a small [thumbnail]. */
class EncodedFile(
    val name: String,
    val dataUrl: String,
    val bytes: Long,
    val image: Boolean,
    val thumbnail: Bitmap?,
    /** The prepared picture itself, so a copy can be kept on the phone to view later. */
    val data: ByteArray? = null,
)

/** Reads the files the picker returns and turns them into what the message frame carries. Runs off the main thread. */
object AttachmentReader {
    private const val MAX_SIDE = 2048
    private const val THUMBNAIL_SIDE = 192
    private const val JPEG_QUALITY = 85

    /** Name, type and size of what [uri] points at (the size is -1 if the provider doesn't say). */
    fun describe(resolver: ContentResolver, uri: Uri): PickedFile {
        var name = ""
        var size = -1L
        try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val s = c.getColumnIndex(OpenableColumns.SIZE)
                    if (n >= 0 && !c.isNull(n)) name = c.getString(n).orEmpty()
                    if (s >= 0 && !c.isNull(s)) size = c.getLong(s)
                }
            }
        } catch (e: Exception) {
            // A provider that won't say: the name falls back to the last part of the address.
        }
        if (name.isBlank()) name = uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "file" }
        return PickedFile(uri, name, resolver.getType(uri), size)
    }

    /**
     * A picture, the way the WebUI prepares one: no side longer than 2048 pixels, photos as JPEG and
     * screenshots (PNG, GIF, anything transparent) as PNG. Null if the phone can't decode it.
     */
    fun encodeImage(resolver: ContentResolver, file: PickedFile, maxBytes: Long): EncodedFile? {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, file.uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > MAX_SIDE) {
                    val scale = MAX_SIDE.toFloat() / longest
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (e: Exception) {
            return null
        } catch (e: OutOfMemoryError) {
            return null
        }
        val lossless = file.mime == "image/png" || file.mime == "image/gif" || bitmap.hasAlpha()
        var bytes = compress(bitmap, lossless)
        var png = lossless
        if (png && bytes.size > maxBytes) {
            // A big screenshot as PNG can outgrow the limit; as a JPEG it will not.
            bytes = compress(bitmap, lossless = false)
            png = false
        }
        val thumbnail = thumbnailOf(bitmap)
        if (bitmap !== thumbnail) bitmap.recycle()
        val mime = if (png) "image/png" else "image/jpeg"
        return EncodedFile(
            name = withExtension(file.name, if (png) ".png" else ".jpg"),
            dataUrl = dataUrl(mime, Base64.encodeToString(bytes, Base64.NO_WRAP)),
            bytes = bytes.size.toLong(),
            image = true,
            thumbnail = thumbnail,
            data = bytes,
        )
    }

    /** A document, as it is. Null if it is over [maxBytes] or can't be read. */
    fun encodeDocument(resolver: ContentResolver, file: PickedFile, maxBytes: Long): EncodedFile? {
        val mime = fileMimeFor(file.name) ?: return null
        val bytes: ByteArray = (try {
            resolver.openInputStream(file.uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > maxBytes) return null
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            null
        }) ?: return null
        if (bytes.isEmpty()) return null
        return EncodedFile(file.name, dataUrl(mime, Base64.encodeToString(bytes, Base64.NO_WRAP)), bytes.size.toLong(), image = false, thumbnail = null)
    }

    private fun compress(bitmap: Bitmap, lossless: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (lossless) bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        else bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
        return out.toByteArray()
    }

    private fun thumbnailOf(bitmap: Bitmap): Bitmap {
        val longest = max(bitmap.width, bitmap.height)
        if (longest <= THUMBNAIL_SIDE) return bitmap
        val scale = THUMBNAIL_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            max(1, (bitmap.width * scale).roundToInt()),
            max(1, (bitmap.height * scale).roundToInt()),
            true,
        )
    }
}

/** [name] with its extension replaced by [extension] (".jpg"), so the name matches what is sent. */
fun withExtension(name: String, extension: String): String {
    val dot = name.lastIndexOf('.')
    val stem = if (dot > 0) name.substring(0, dot) else name
    return stem + extension
}
