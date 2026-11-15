package io.github.erkanw.vault.tools

import io.github.erkanw.vault.provider.ProviderEndpoints
import kotlinx.coroutines.Dispatchers
import io.github.erkanw.vault.provider.cancelling
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.InetSocketAddress
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit

/**
 * `web_fetch` (PLAN §7.4): a page as readable text. It refuses the local network (checked on every address the name
 * resolves to, and on every redirect), reads at most 5 MB, and turns HTML into text with [HtmlText].
 */
object WebFetchTool : Tool {
    private const val MAX_BYTES = 5L * 1024 * 1024
    private const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36"

    /** Resolves names, then drops every private or local address; a name with only those is refused. */
    private val publicDns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            val ok = Dns.SYSTEM.lookup(hostname).filter { isPublic(it) }
            if (ok.isEmpty()) throw IOException("local")
            return ok
        }
    }

    private val http = OkHttpClient.Builder()
        .dns(publicDns)
        // No proxy, so the address connected to is the one checked.
        .proxy(Proxy.NO_PROXY)
        // Checked again on the socket itself, before it connects, for every hop: an address written as a number (a
        // redirect to http://192.168.1.1/) never goes through the resolver above.
        .socketFactory(PublicOnlySockets)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    override val name = "web_fetch"
    override val title = "Read web pages"
    override val description = "Opens a web page and reads its text. Never reaches your home network."
    override val instructions = "Read a web page (http or https) as plain text, to read a source or check a fact. Returns the page's title and text."
    override val schema = params("url" to str("The page's full address."))
    override val defaultLevel = ToolLevel.Allowed
    override fun hint(args: JSONObject) = "Reading " + (args.optString("url").toHttpUrlOrNull()?.host?.removePrefix("www.") ?: "a page")
    override fun step(args: JSONObject) = "web_fetch " + args.optString("url")

    fun isPublic(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return false
        val b = a.address
        if (a is Inet4Address) {
            val x = b[0].toInt() and 255
            val y = b[1].toInt() and 255
            if (x == 0 || x == 127 || x == 10 || x >= 224 || (x == 172 && y in 16..31) || (x == 192 && y == 168)) return false
            if (x == 100 && y in 64..127) return false // carrier-grade NAT
            if (x == 169 && y == 254) return false
        }
        if (a is Inet6Address) {
            val x = b[0].toInt() and 255
            if ((x and 0xFE) == 0xFC) return false // unique local fc00::/7
            if (a.isIPv4CompatibleAddress) return false
            // NAT64 (64:ff9b::/96) carries an IPv4 address that may be a private one.
            if (x == 0 && (b[1].toInt() and 255) == 0x64 && (b[2].toInt() and 255) == 0xff && (b[3].toInt() and 255) == 0x9b) return false
        }
        return true
    }

    override suspend fun run(args: JSONObject, ctx: ToolContext): String = withContext(Dispatchers.IO) {
        val raw = args.need("url").trim().let { if ("://" in it) it else "https://$it" }
        val url = raw.toHttpUrlOrNull() ?: throw ToolException("That isn’t a web address.")
        if (url.scheme != "http" && url.scheme != "https") throw ToolException("Only http and https pages.")
        if (url.host.endsWith(".local") || url.host == "localhost" || ProviderEndpoints.isPrivateHost(url.host)) {
            throw ToolException("Pages on this phone or the home network can’t be read.")
        }
        val call = http.newCall(Request.Builder().url(url).header("User-Agent", UA).header("Accept", "text/html,text/plain,application/xhtml+xml;q=0.9,*/*;q=0.5").build())
        try {
            cancelling(call) { call.execute().use { response ->
                // Every hop of a redirect went through the socket check, so a redirect home was refused too.
                if (!response.isSuccessful) throw ToolException("The page answered ${response.code}.")
                val body = response.body ?: throw ToolException("The page was empty.")
                val type = body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty()
                if (type == "application/pdf") throw ToolException("That is a PDF, which can’t be read here yet.")
                if (type.isNotEmpty() && !type.startsWith("text/") && !type.contains("html") && !type.contains("xml") && !type.contains("json")) {
                    throw ToolException("That address is a $type file, not a page.")
                }
                val bytes = body.source().let { src -> src.request(MAX_BYTES); src.buffer.let { b -> b.readByteArray(minOf(b.size, MAX_BYTES)) } }
                val text = String(bytes, body.contentType()?.charset() ?: Charsets.UTF_8)
                val final = response.request.url.toString()
                if (type.contains("html") || text.trimStart().startsWith("<")) {
                    val page = HtmlText.extract(text)
                    buildString {
                        append("URL: ").append(final).append('\n')
                        if (page.title.isNotBlank()) append("Title: ").append(page.title).append('\n')
                        append('\n').append(page.text)
                    }
                } else {
                    "URL: $final\n\n$text"
                }
            } }
        } catch (e: IOException) {
            if (e.message == "local") throw ToolException("Pages on this phone or the home network can’t be read.")
            throw ToolException("The page couldn’t be reached (${e.javaClass.simpleName}).")
        }
    }
}

/** Sockets that refuse to connect anywhere but the public internet (the local-network guard of [WebFetchTool]). */
object PublicOnlySockets : SocketFactory() {
    private class Guarded : Socket() {
        override fun connect(endpoint: SocketAddress?, timeout: Int) {
            val address = (endpoint as? InetSocketAddress)?.address
            if (address == null || !WebFetchTool.isPublic(address)) throw IOException("local")
            super.connect(endpoint, timeout)
        }
    }

    override fun createSocket(): Socket = Guarded()
    override fun createSocket(host: String?, port: Int): Socket = Guarded().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        Guarded().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }
    override fun createSocket(host: InetAddress?, port: Int): Socket = Guarded().apply { connect(InetSocketAddress(host, port)) }
    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        Guarded().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
}

/** HTML to readable text: drops scripts, styles and page chrome, keeps headings, paragraphs, list items and links' text. */
object HtmlText {
    class Page(val title: String, val text: String)

    private val drop = Regex("(?is)<(script|style|noscript|svg|template|iframe|head|nav|footer|form)\\b.*?</\\1>")
    private val comment = Regex("(?s)<!--.*?-->")
    private val block = Regex("(?i)</?(p|div|section|article|main|header|br|tr|table|ul|ol|blockquote|pre|h[1-6]|li|dd|dt|figcaption)\\b[^>]*>")
    private val tag = Regex("<[^>]+>")
    private val titleRe = Regex("(?is)<title[^>]*>(.*?)</title>")
    private val heading = Regex("(?is)<h([1-3])[^>]*>(.*?)</h\\1>")
    private val item = Regex("(?i)<li\\b[^>]*>")

    fun extract(html: String): Page {
        val title = titleRe.find(html)?.groupValues?.get(1)?.let { decode(it.replace(tag, "")).trim() }.orEmpty()
        var s = html.replace(comment, " ")
        // The page's main part, when it marks one.
        Regex("(?is)<(main|article)\\b.*?</\\1>").find(s)?.let { if (it.value.length > 600) s = it.value }
        s = s.replace(drop, " ")
        s = s.replace(heading) { "\n\n" + "#".repeat(it.groupValues[1].toInt()) + " " + it.groupValues[2] + "\n\n" }
        s = s.replace(item, "\n- ")
        s = s.replace(block, "\n")
        s = decode(s.replace(tag, ""))
        val text = s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
            .fold(mutableListOf<String>()) { acc, line -> if (line.isEmpty() && acc.lastOrNull().isNullOrEmpty()) acc else acc.apply { add(line) } }
            .joinToString("\n").trim()
        return Page(title, text)
    }

    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")
    private val named = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“", "copy" to "©")

    fun decode(s: String): String = s.replace(entity) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) } ?: m.value
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) } ?: m.value
            else -> named[e.lowercase()] ?: m.value
        }
    }
}
