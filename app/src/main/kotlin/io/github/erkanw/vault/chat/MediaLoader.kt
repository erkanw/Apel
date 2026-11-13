package io.github.erkanw.vault.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pictures and videos of the chat: a copy kept on the phone of what was sent from it, and pictures fetched
 * from the agent by their link (the links are signed, so nothing but the address is sent).
 */
object MediaLoader {
    private const val MAX_DOWNLOAD = 25 * 1024 * 1024
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun sentDir(context: Context): File = File(context.cacheDir, "sent").also { it.mkdirs() }

    /** Keeps [data] as a file of its own and returns its path, so a picture just sent can be opened without the network. */
    fun keepCopy(context: Context, data: ByteArray, name: String): String? = try {
        val extension = name.substringAfterLast('.', "jpg").lowercase().take(5)
        File(sentDir(context), UUID.randomUUID().toString() + "." + extension).also { it.writeBytes(data) }.absolutePath
    } catch (e: Exception) {
        null
    }

    fun deleteCopy(path: String?) {
        if (path != null) try { File(path).delete() } catch (e: Exception) { /* nothing to do */ }
    }

    /** Where a video can be played from: the copy on the phone if there is one, else the agent’s link. */
    fun playableUri(base: String?, file: MessageAttachment): String? {
        file.localPath?.takeIf { File(it).exists() }?.let { return android.net.Uri.fromFile(File(it)).toString() }
        return resolveMediaUrl(base, file.url)
    }

    /** The picture, shrunk to at most [maxSide] pixels on its longest side; null if it can't be had. */
    suspend fun bitmap(base: String?, file: MessageAttachment, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) {
        val local = file.localPath?.takeIf { File(it).exists() }
        val key = (local ?: resolveMediaUrl(base, file.url) ?: return@withContext null) + "@" + maxSide
        cache.get(key)?.let { return@withContext it }
        try {
            val source = if (local != null) {
                ImageDecoder.createSource(File(local))
            } else {
                ImageDecoder.createSource(ByteBuffer.wrap(download(resolveMediaUrl(base, file.url) ?: return@withContext null)))
            }
            val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > maxSide) {
                    val scale = maxSide.toFloat() / longest
                    decoder.setTargetSize(
                        max(1, (info.size.width * scale).roundToInt()),
                        max(1, (info.size.height * scale).roundToInt()),
                    )
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
            cache.put(key, bitmap)
            bitmap
        } catch (e: Exception) {
            null
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /**
     * Up to [max] bytes from the start of [file] (the copy on the phone, or the agent’s link) and the file's whole size when it
     * is known (else what was read); null when there is neither. For the text viewer, which never needs more.
     */
    suspend fun readHead(base: String?, file: MessageAttachment, max: Int): Pair<ByteArray, Long>? = withContext(Dispatchers.IO) {
        val local = file.localPath?.let { File(it) }?.takeIf { it.exists() }
        if (local != null) {
            val bytes = local.inputStream().use { input -> readUpTo(input, max) }
            return@withContext bytes to local.length()
        }
        val address = resolveMediaUrl(base, file.url) ?: return@withContext null
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            val length = conn.contentLengthLong
            val bytes = conn.inputStream.use { input -> readUpTo(input, max) }
            bytes to if (length >= 0) length else bytes.size.toLong()
        } finally {
            conn.disconnect()
        }
    }

    /** Streams what is behind the agent’s signed link [address] into [out] (the link is the whole credential). */
    fun downloadTo(address: String, out: java.io.OutputStream) {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            var total = 0L
            conn.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DOWNLOAD) throw java.io.IOException("too large")
                    out.write(buffer, 0, n)
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun download(address: String): ByteArray {
        val conn = URL(address).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        try {
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_DOWNLOAD) throw java.io.IOException("too large")
                }
                out.toByteArray()
            }
        } finally {
            conn.disconnect()
        }
    }
}

/** At most [max] bytes from [input] (InputStream.readNBytes needs Android 13). */
internal fun readUpTo(input: java.io.InputStream, max: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream(minOf(max, 64 * 1024))
    val buffer = ByteArray(16 * 1024)
    while (out.size() < max) {
        val n = input.read(buffer, 0, minOf(buffer.size, max - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
