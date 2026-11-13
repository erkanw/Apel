package io.github.erkanw.vault.chat

// A small Markdown reader for chat answers: what a model writes most often, and nothing more.
// Blocks: headings, paragraphs, fenced code, quotes, lists (bulleted, numbered, task), tables,
// rules. Inline: **bold**, *italic*, ~~strike~~, `code`, [links](https://…) and bare links.
// Pure Kotlin, so it is tested without a phone. A text that is still arriving (an unclosed fence,
// a half-written table, a lone `**`) never breaks: unfinished things are shown as written, or the
// code block just runs to the end.

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Code(val language: String, val code: String) : MdBlock
    data class Quote(val blocks: List<MdBlock>) : MdBlock
    data class Items(val items: List<MdItem>) : MdBlock
    data class Table(val header: List<String>, val aligns: List<MdAlign>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

/** One list entry: how deep it sits (0 to 4), its marker ("•", "◦", "3.", "☐", "☑") and its text. */
data class MdItem(val level: Int, val marker: String, val text: String)

enum class MdAlign { Start, Center, End }

private val HEADING = Regex("""^(#{1,6})\s+(.+?)\s*#*\s*$""")
private val RULE = Regex("""^([-*_])(\s*\1){2,}$""")
private val ITEM = Regex("""^(\s*)([-*+]|\d{1,9}[.)])\s+(.*)$""")
private val TABLE_SEPARATOR = Regex("""^\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?$""")

fun parseBlocks(source: String): List<MdBlock> {
    val lines = source.replace("\r\n", "\n").split('\n')
    val out = ArrayList<MdBlock>()
    var i = 0

    fun startsTable(at: Int): Boolean =
        at + 1 < lines.size && lines[at].contains('|') && lines[at + 1].contains('|') && TABLE_SEPARATOR.matches(lines[at + 1].trim())

    fun startsSpecial(at: Int): Boolean {
        val line = lines[at]
        val t = line.trim()
        return t.startsWith("```") || t.startsWith("~~~") || HEADING.matches(t) || t.startsWith(">") ||
            ITEM.matches(line) || RULE.matches(t) || startsTable(at)
    }

    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }

        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = trimmed.take(3)
            val language = trimmed.removePrefix(fence).trim()
            val code = ArrayList<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(fence)) {
                code.add(lines[i])
                i++
            }
            if (i < lines.size) i++  // the closing fence; absent while the text is still arriving
            out.add(MdBlock.Code(language, code.joinToString("\n")))
            continue
        }

        val heading = HEADING.matchEntire(trimmed)
        if (heading != null) {
            out.add(MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]))
            i++
            continue
        }

        if (RULE.matches(trimmed)) {
            out.add(MdBlock.Rule)
            i++
            continue
        }

        if (startsTable(i)) {
            val header = splitRow(lines[i])
            val aligns = splitRow(lines[i + 1]).map { cell ->
                when {
                    cell.startsWith(":") && cell.endsWith(":") -> MdAlign.Center
                    cell.endsWith(":") -> MdAlign.End
                    else -> MdAlign.Start
                }
            }
            i += 2
            val rows = ArrayList<List<String>>()
            while (i < lines.size && lines[i].isNotBlank() && lines[i].contains('|')) {
                val cells = splitRow(lines[i])
                rows.add(List(header.size) { c -> cells.getOrElse(c) { "" } })
                i++
            }
            out.add(MdBlock.Table(header, List(header.size) { aligns.getOrElse(it) { MdAlign.Start } }, rows))
            continue
        }

        if (trimmed.startsWith(">")) {
            val inner = ArrayList<String>()
            while (i < lines.size && lines[i].trim().startsWith(">")) {
                inner.add(lines[i].trim().removePrefix(">").removePrefix(" "))
                i++
            }
            out.add(MdBlock.Quote(parseBlocks(inner.joinToString("\n"))))
            continue
        }

        if (ITEM.matches(line)) {
            val firstLine = i
            val items = ArrayList<MdItem>()
            while (i < lines.size) {
                val l = lines[i]
                val m = ITEM.matchEntire(l)
                if (m != null) {
                    val level = (m.groupValues[1].replace("\t", "    ").length / 2).coerceIn(0, 4)
                    val bullet = m.groupValues[2]
                    var text = m.groupValues[3]
                    var marker = if (bullet[0].isDigit()) bullet.replace(")", ".") else if (level % 2 == 0) "•" else "◦"
                    when {
                        text.startsWith("[ ] ") -> { marker = "☐"; text = text.removePrefix("[ ] ") }
                        text.startsWith("[x] ", ignoreCase = true) -> { marker = "☑"; text = text.substring(4) }
                    }
                    items.add(MdItem(level, marker, text))
                    i++
                } else if (l.isBlank()) {
                    // A blank line between items (a "loose" list) goes on if another item of the same kind follows,
                    // or a nested one; a bulleted list then a numbered one are two lists.
                    var j = i
                    while (j < lines.size && lines[j].isBlank()) j++
                    val next = if (j < lines.size) ITEM.matchEntire(lines[j]) else null
                    val firstOrdered = lines[firstLine].trim().firstOrNull()?.isDigit() == true
                    val nextLevel = next?.groupValues?.get(1)?.replace("\t", "    ")?.length ?: 0
                    val nextOrdered = next?.groupValues?.get(2)?.firstOrNull()?.isDigit() == true
                    if (next != null && (nextLevel >= 2 || nextOrdered == firstOrdered)) i = j else break
                } else if (l.startsWith("  ") && items.isNotEmpty() && !startsSpecial(i)) {
                    val last = items.removeAt(items.lastIndex)
                    items.add(last.copy(text = last.text + "\n" + l.trim()))
                    i++
                } else {
                    break
                }
            }
            out.add(MdBlock.Items(items))
            continue
        }

        val paragraph = ArrayList<String>()
        while (i < lines.size && lines[i].isNotBlank() && (paragraph.isEmpty() || !startsSpecial(i))) {
            paragraph.add(lines[i].trim())
            i++
        }
        out.add(MdBlock.Paragraph(paragraph.joinToString("\n")))
    }
    return out
}

private fun splitRow(line: String): List<String> {
    var t = line.trim()
    if (t.startsWith("|")) t = t.substring(1)
    if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
    return t.split(Regex("""(?<!\\)\|""")).map { it.trim().replace("\\|", "|") }
}

/** A run of text with its style. [url] is set for a link. */
data class Span(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val url: String? = null,
)

private const val ESCAPABLE = "\\`*_{}[]()#+-.!|~>"

fun parseInline(source: String): List<Span> = merge(inlineSpans(source, Span("")))

private fun merge(spans: List<Span>): List<Span> {
    val out = ArrayList<Span>()
    for (s in spans) {
        val last = out.lastOrNull()
        if (last != null && !s.code && last.copy(text = "") == s.copy(text = "")) {
            out[out.lastIndex] = last.copy(text = last.text + s.text)
        } else if (s.text.isNotEmpty()) {
            out.add(s)
        }
    }
    return out
}

private fun inlineSpans(s: String, base: Span): List<Span> {
    val out = ArrayList<Span>()
    val buf = StringBuilder()
    fun flush() {
        if (buf.isNotEmpty()) {
            out.add(base.copy(text = buf.toString()))
            buf.setLength(0)
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        when {
            c == '\\' && i + 1 < s.length && s[i + 1] in ESCAPABLE -> {
                buf.append(s[i + 1])
                i += 2
            }
            c == '`' -> {
                val end = s.indexOf('`', i + 1)
                if (end > i) {
                    flush()
                    out.add(base.copy(text = s.substring(i + 1, end), code = true))
                    i = end + 1
                } else {
                    buf.append(c)
                    i++
                }
            }
            s.startsWith("***", i) || s.startsWith("___", i) -> {
                // Bold and italic at once: ***both***.
                val d = s.substring(i, i + 3)
                val end = s.indexOf(d, i + 3)
                if (end > i + 3) {
                    flush()
                    out += inlineSpans(s.substring(i + 3, end), base.copy(bold = true, italic = true))
                    i = end + 3
                } else {
                    buf.append(c)
                    i++
                }
            }
            s.startsWith("**", i) || s.startsWith("__", i) -> {
                val d = s.substring(i, i + 2)
                val end = s.indexOf(d, i + 2)
                if (end > i + 2) {
                    flush()
                    out += inlineSpans(s.substring(i + 2, end), base.copy(bold = true))
                    i = end + 2
                } else {
                    buf.append(d)
                    i += 2
                }
            }
            s.startsWith("~~", i) -> {
                val end = s.indexOf("~~", i + 2)
                if (end > i + 2) {
                    flush()
                    out += inlineSpans(s.substring(i + 2, end), base.copy(strike = true))
                    i = end + 2
                } else {
                    buf.append("~~")
                    i += 2
                }
            }
            (c == '*' || (c == '_' && (i == 0 || !s[i - 1].isLetterOrDigit()))) &&
                i + 1 < s.length && !s[i + 1].isWhitespace() -> {
                val end = closingItalic(s, i + 1, c)
                if (end > i + 1) {
                    flush()
                    out += inlineSpans(s.substring(i + 1, end), base.copy(italic = true))
                    i = end + 1
                } else {
                    buf.append(c)
                    i++
                }
            }
            c == '[' -> {
                val mid = s.indexOf("](", i + 1)
                val close = if (mid > i) s.indexOf(')', mid + 2) else -1
                val url = if (close > mid) s.substring(mid + 2, close).trim() else ""
                if (close > mid && isSafeUrl(url) && mid > i + 1) {
                    flush()
                    out += inlineSpans(s.substring(i + 1, mid), base.copy(url = url))
                    i = close + 1
                } else {
                    buf.append(c)
                    i++
                }
            }
            c == 'h' && base.url == null && (s.startsWith("http://", i) || s.startsWith("https://", i)) -> {
                var end = i
                while (end < s.length && !s[end].isWhitespace() && s[end] != '<' && s[end] != '>') end++
                // Punctuation after a link belongs to the sentence, not the link.
                while (end > i && s[end - 1] in ".,;:!?)]}'\"") end--
                flush()
                val url = s.substring(i, end)
                out.add(base.copy(text = url, url = url))
                i = end
            }
            else -> {
                buf.append(c)
                i++
            }
        }
    }
    flush()
    return out
}

private fun closingItalic(s: String, from: Int, mark: Char): Int {
    var j = from
    while (j < s.length) {
        // A bold run inside the italics (*one **two** three*) is skipped whole: its stars don't close the italics.
        if (s[j] == mark && j + 1 < s.length && s[j + 1] == mark) {
            val boldEnd = s.indexOf("$mark$mark", j + 2)
            j = if (boldEnd >= 0) boldEnd + 2 else j + 2
            continue
        }
        if (s[j] == mark && !s[j - 1].isWhitespace() && (j + 1 >= s.length || s[j + 1] != mark)) {
            if (mark != '_' || j + 1 >= s.length || !s[j + 1].isLetterOrDigit()) return j
        }
        j++
    }
    return -1
}

/** Only links that open a web page or a mail app: never `javascript:` or anything else. */
fun isSafeUrl(url: String): Boolean {
    val u = url.lowercase()
    return u.startsWith("http://") || u.startsWith("https://") || u.startsWith("mailto:")
}
