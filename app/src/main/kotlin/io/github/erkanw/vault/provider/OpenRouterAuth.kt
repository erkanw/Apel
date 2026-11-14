package io.github.erkanw.vault.provider


import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * OpenRouter sign-in with OAuth PKCE (PLAN §2.1): the browser opens openrouter.ai/auth, the user approves, and a
 * single-use code (10 minutes) is exchanged for a key in their own account. The way back is a loopback address on this
 * phone (OpenRouter accepts localhost on any port; it refused an `apel-vault://` app link in the Phase 1 spike), or the
 * code shown by OpenRouter, pasted into the app. `apel-vault://oauth` only brings the app to the front afterwards.
 */
object OpenRouterAuth {
    private const val AUTH = "https://openrouter.ai/auth"
    private const val EXCHANGE = "${ProviderEndpoints.OPENROUTER}/auth/keys"

    enum class Method { Loopback, Code }

    /** One sign-in in progress: what was sent, kept until the code comes back. */
    data class Attempt(val verifier: String, val state: String, val method: Method, val callback: String?)

    private val random = SecureRandom()

    /** A PKCE verifier: 64 random bytes, base64url without padding (86 characters, within RFC 7636's 43 to 128). */
    fun newVerifier(): String = b64url(ByteArray(64).also(random::nextBytes))

    /** The S256 challenge of [verifier]. */
    fun challengeOf(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun newState(): String = b64url(ByteArray(16).also(random::nextBytes))

    /** The address to open in the browser. [callback] null is the headless (paste the code) way. */
    fun authUrl(attempt: Attempt, keyLabel: String): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        val parts = mutableListOf(
            "code_challenge=" + enc(challengeOf(attempt.verifier)),
            "code_challenge_method=S256",
            "key_label=" + enc(keyLabel),
            "state=" + enc(attempt.state),
        )
        attempt.callback?.let { parts.add(0, "callback_url=" + enc(it)) }
        return AUTH + "?" + parts.joinToString("&")
    }

    /** Reads the code from a callback address; null when it isn't one of ours or its state doesn't match. */
    fun codeFrom(callbackUrl: String, attempt: Attempt): String? {
        val query = callbackUrl.substringAfter('?', "").substringBefore('#')
        val params = query.split('&').mapNotNull { p ->
            val k = p.substringBefore('=', "")
            if (k.isEmpty()) null else k to java.net.URLDecoder.decode(p.substringAfter('=', ""), "UTF-8")
        }.toMap()
        val state = params["state"]
        if (state != null && state != attempt.state) return null
        return params["code"]?.takeIf { it.isNotBlank() }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** Swaps [code] for a key. Throws [ProviderException] with a line to show. */
    suspend fun exchange(code: String, verifier: String): String = withContext(Dispatchers.IO) {
        val body = JSONObject().put("code", code.trim()).put("code_verifier", verifier).put("code_challenge_method", "S256").toString()
        val request = Request.Builder().url(EXCHANGE)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw ProviderException(
                        when (response.code) {
                            400, 403 -> "That sign-in code didn’t work (it is used once and lasts ten minutes). Try signing in again."
                            else -> "OpenRouter couldn’t finish the sign-in (${response.code}). Try again."
                        },
                        status = response.code,
                    )
                }
                JSONObject(text).optString("key").ifBlank { throw ProviderException("OpenRouter sent no key. Try signing in again.") }
            }
        } catch (e: java.io.IOException) {
            throw networkError(e, "openrouter.ai")
        }
    }

    /**
     * A one-shot receiver on 127.0.0.1 for the loopback way: open it, send the browser to [callbackUrl], then [await]
     * the code. It answers the browser with a short page and closes.
     */
    class Loopback : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val callbackUrl: String = "http://localhost:${server.localPort}/callback"

        /** Waits (up to ten minutes) for the browser's request and returns its path and query. */
        suspend fun await(): String = withContext(Dispatchers.IO) {
            server.soTimeout = 10 * 60_000
            server.accept().use { socket ->
                val line = socket.getInputStream().bufferedReader().readLine().orEmpty()
                // The button opens the app (its apel-vault://oauth intent filter); the code was received here already.
                val back = "intent://oauth#Intent;scheme=apel-vault;package=io.github.erkanw.vault;end"
                val page = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
                    "<body style='font:17px sans-serif;padding:32px;background:#111113;color:#F5F5F7'>" +
                    "<p>Signed in. Go back to the app to finish.</p>" +
                    "<p><a href='$back' style='display:inline-block;margin-top:16px;padding:14px 28px;border-radius:999px;" +
                    "background:#F5F5F7;color:#111113;text-decoration:none'>Back to the app</a></p></body>"
                val bytes = page.toByteArray()
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(bytes)
                    flush()
                }
                // "GET /callback?code=…&state=… HTTP/1.1"
                "http://localhost" + line.substringAfter(' ').substringBeforeLast(' ')
            }
        }

        override fun close() = server.close()
    }

    private fun b64url(bytes: ByteArray): String = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}
