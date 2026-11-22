package io.github.erkanw.vault.tools

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The web addresses a turn has seen: in your message, in a search's sources and in what its tools returned (PLAN §10).
 * Once a turn has read something it can't trust, opening an address that isn't among them asks first: a page can then
 * still be followed by its links, but can't make the agent send what it knows (memory, About you) to an address it made
 * up, like `https://example.com/?d=<your notes>`.
 */
object UrlTrail {
    private val URL = Regex("""https?://[^\s<>"'`\]}|\\^]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?’”'\""

    /** Every address in [text], as [key]s. */
    fun find(text: String): List<String> = URL.findAll(text).mapNotNull { m ->
        var s = m.value.trimEnd { it in TRAILING }
        // A Markdown link "(https://…)" ends with a bracket that isn't the address's own: Wikipedia's "Foo_(bar)" keeps its.
        while (s.endsWith(')') && s.count { it == ')' } > s.count { it == '(' }) s = s.dropLast(1).trimEnd { it in TRAILING }
        key(s)
    }.toList()

    /** The site of [url] for "Don’t ask again" ("example.com"), without "www."; null for something that isn't a web address. */
    fun host(url: String): String? {
        val raw = url.trim().let { if ("://" in it) it else "https://$it" }
        val u = raw.toHttpUrlOrNull() ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        return u.host.removePrefix("www.")
    }

    /** Opening pages on the same site: [tool] and the host. */
    fun similar(tool: String, url: String): Similar? = host(url)?.let { Similar("$tool|$it", "pages on $it") }

    /** The approval for opening [url], an address the turn hasn't seen, after reading something untrusted. */
    fun ask(url: String) = ApprovalAsk(
        "Open a new address, after reading the web",
        url.trim() + "\n\nThis address wasn’t in your message or in anything read in this turn. A page may be trying to send your information to it.",
    )

    /**
     * One form for the same address: no scheme (http and https are the same page here), the host in lower case, no
     * fragment, no trailing slash, the encoding as OkHttp writes it. Null for something that isn't a web address.
     */
    fun key(url: String): String? {
        val raw = url.trim().let { if ("://" in it) it else "https://$it" }
        val u = raw.toHttpUrlOrNull() ?: return null
        if (u.scheme != "http" && u.scheme != "https") return null
        val port = if (u.port == HttpUrl.defaultPort(u.scheme)) "" else ":${u.port}"
        return u.host + port + u.encodedPath.removeSuffix("/") + (u.encodedQuery?.let { "?$it" } ?: "")
    }
}
