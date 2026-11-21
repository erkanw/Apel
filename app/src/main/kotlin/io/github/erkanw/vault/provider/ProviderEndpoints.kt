package io.github.erkanw.vault.provider

import java.net.URI

/**
 * Where a provider key may go (PLAN §10). The OpenRouter key only ever goes to openrouter.ai; a custom server's key only
 * to the exact address it was saved for. https is required, except for this phone and private addresses the user typed
 * (a PC at home running Ollama or LM Studio), which are allowed over http with a warning.
 */
object ProviderEndpoints {
    const val OPENROUTER = "https://openrouter.ai/api/v1"

    /** A checked server address: [url] without a trailing slash, and a [warning] when it is plain http. */
    data class Checked(val url: String, val warning: String? = null)

    /** Tidies what was typed and checks it. Throws [IllegalArgumentException] with a line to show when it can't be used. */
    fun check(typed: String): Checked {
        val raw = typed.trim().trimEnd('/')
        if (raw.isEmpty()) throw IllegalArgumentException("Type the server’s address.")
        val withScheme = when {
            raw.startsWith("https://", ignoreCase = true) || raw.startsWith("http://", ignoreCase = true) -> raw
            // No scheme: http for this phone or the home network, https anywhere else.
            isPrivateHost(hostOf("http://$raw")) -> "http://$raw"
            else -> "https://$raw"
        }
        val uri = try { URI(withScheme) } catch (e: Exception) { throw IllegalArgumentException("That isn’t a web address.") }
        val host = uri.host ?: throw IllegalArgumentException("That isn’t a web address.")
        if (uri.userInfo != null) throw IllegalArgumentException("Leave the key out of the address: it has its own field.")
        if (uri.query != null || uri.fragment != null) throw IllegalArgumentException("Leave out anything after ? or #.")
        val https = uri.scheme.equals("https", ignoreCase = true)
        if (!https && !isPrivateHost(host)) {
            throw IllegalArgumentException("Use https for a server on the internet. Plain http is only for this phone or your home network.")
        }
        val warning = if (https) null else "This address uses plain http: only use it on your own network."
        return Checked(withScheme, warning)
    }

    /** True when [url] is exactly [boundTo] (same scheme, host, port and path): a key never follows another address. */
    fun mayCarryKey(url: String, boundTo: String): Boolean = url.trimEnd('/').startsWith(boundTo.trimEnd('/')) &&
        hostOf(url) == hostOf(boundTo) && schemeOf(url) == schemeOf(boundTo)

    fun hostOf(url: String): String? = try { URI(url).host?.lowercase() } catch (e: Exception) { null }
    private fun schemeOf(url: String): String? = try { URI(url).scheme?.lowercase() } catch (e: Exception) { null }

    /** This phone, or an address on a private network: 10/8, 172.16/12, 192.168/16, 100.64/10, link-local, `.local`, IPv6 ULA. */
    fun isPrivateHost(host: String?): Boolean {
        val h = host?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.removeSuffix(".") ?: return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h == "::1" || h == "::") return true
        if (h.startsWith("fc") || h.startsWith("fd") || h.startsWith("fe80:")) return h.contains(':')
        // An IPv4 address written as IPv6 (::ffff:192.168.1.1) is that IPv4 address.
        if (h.startsWith("::ffff:")) return isPrivateHost(h.removePrefix("::ffff:")) || ':' in h.removePrefix("::ffff:")
        val parts = h.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        // 0.0.0.0/8 reaches this phone on many systems.
        return a == 0 || a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) ||
            (a == 100 && b in 64..127) || (a == 169 && b == 254)
    }
}
