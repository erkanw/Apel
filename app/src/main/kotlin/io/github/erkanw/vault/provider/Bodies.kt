package io.github.erkanw.vault.provider

import okhttp3.ResponseBody

/**
 * The body as text, reading at most [max] bytes: null when there is more. It never reads the whole answer to find out,
 * so a server that sends no length, or a wrong one, can't make the app hold more than [max].
 */
fun ResponseBody.textAtMost(max: Long): String? {
    val source = source()
    if (source.request(max + 1)) return null
    return source.buffer.readString(contentType()?.charset() ?: Charsets.UTF_8)
}
