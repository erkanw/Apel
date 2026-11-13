package io.github.erkanw.vault

import io.github.erkanw.vault.chat.MdAlign
import io.github.erkanw.vault.chat.MdBlock
import io.github.erkanw.vault.chat.MdItem
import io.github.erkanw.vault.chat.Span
import io.github.erkanw.vault.chat.isSafeUrl
import io.github.erkanw.vault.chat.parseBlocks
import io.github.erkanw.vault.chat.parseInline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {

    @Test
    fun `a typical answer becomes headings, paragraphs, lists, a quote, a rule, code and a table`() {
        val md = "# Title\n\nSome **bold** text.\n\n- one\n- two\n  - nested\n\n1. first\n2. second\n\n> quoted\n\n---\n\n" +
            "```kotlin\nval x = 1\n```\n\n| a | b |\n|---|:-:|\n| 1 | 2 |"
        val blocks = parseBlocks(md)
        assertEquals(
            listOf(
                MdBlock.Heading(1, "Title"),
                MdBlock.Paragraph("Some **bold** text."),
                MdBlock.Items(listOf(MdItem(0, "•", "one"), MdItem(0, "•", "two"), MdItem(1, "◦", "nested"))),
                MdBlock.Items(listOf(MdItem(0, "1.", "first"), MdItem(0, "2.", "second"))),
                MdBlock.Quote(listOf(MdBlock.Paragraph("quoted"))),
                MdBlock.Rule,
                MdBlock.Code("kotlin", "val x = 1"),
                MdBlock.Table(listOf("a", "b"), listOf(MdAlign.Start, MdAlign.Center), listOf(listOf("1", "2"))),
            ),
            blocks,
        )
    }

    @Test
    fun `heading levels, task lists and loose numbered lists`() {
        assertEquals(MdBlock.Heading(3, "Three"), parseBlocks("### Three").single())
        assertEquals(
            MdBlock.Items(listOf(MdItem(0, "☐", "todo"), MdItem(0, "☑", "done"))),
            parseBlocks("- [ ] todo\n- [x] done").single(),
        )
        // A blank line between numbered items keeps them one list.
        assertEquals(
            MdBlock.Items(listOf(MdItem(0, "1.", "a"), MdItem(0, "2.", "b"))),
            parseBlocks("1. a\n\n2. b").single(),
        )
        // Bullets under a number are part of it.
        val nested = parseBlocks("1. a\n\n  - sub\n2. b").single() as MdBlock.Items
        assertEquals(listOf("1.", "◦", "2."), nested.items.map { it.marker })
    }

    @Test
    fun `text that is still arriving never breaks`() {
        // A fence that isn't closed yet: the code runs to the end.
        assertEquals(MdBlock.Code("py", "print(1)"), parseBlocks("```py\nprint(1)").single())
        // A table row without its separator is only a paragraph.
        assertEquals(MdBlock.Paragraph("a | b\nno separator"), parseBlocks("a | b\nno separator").single())
        // A half-written header.
        assertEquals(MdBlock.Paragraph("##"), parseBlocks("##").single())
        assertTrue(parseBlocks("").isEmpty())
        assertTrue(parseBlocks("\n\n  \n").isEmpty())
    }

    @Test
    fun `a paragraph keeps its line breaks, and stops where another block starts`() {
        val blocks = parseBlocks("line one\nline two\n- item")
        assertEquals(MdBlock.Paragraph("line one\nline two"), blocks[0])
        assertEquals(MdBlock.Items(listOf(MdItem(0, "•", "item"))), blocks[1])
    }

    @Test
    fun `bold, italic, strike and code`() {
        assertEquals(
            listOf(
                Span("a "), Span("b", bold = true), Span(" "), Span("c", italic = true), Span(" "),
                Span("d", code = true), Span(" "), Span("e", strike = true),
            ),
            parseInline("a **b** *c* `d` ~~e~~"),
        )
        // Nested: italic inside bold.
        assertEquals(
            listOf(Span("a ", bold = true), Span("b", bold = true, italic = true), Span(" c", bold = true)),
            parseInline("**a *b* c**"),
        )
        // Code is shown as written.
        assertEquals(listOf(Span("**not bold**", code = true)), parseInline("`**not bold**`"))
    }

    @Test
    fun `bold and italic work together, in either order and at once`() {
        assertEquals(listOf(Span("both", bold = true, italic = true)), parseInline("***both***"))
        assertEquals(listOf(Span("both", bold = true, italic = true)), parseInline("___both___"))
        // Bold inside italics: the stars of the bold run don't close the italics.
        assertEquals(
            listOf(Span("one ", italic = true), Span("two", bold = true, italic = true), Span(" three", italic = true)),
            parseInline("*one **two** three*"),
        )
        // Italics inside bold, with underscores.
        assertEquals(
            listOf(Span("one ", bold = true), Span("two", bold = true, italic = true), Span(" three", bold = true)),
            parseInline("**one _two_ three**"),
        )
        // And in a sentence.
        assertEquals(
            listOf(Span("a "), Span("b", bold = true, italic = true), Span(" c")),
            parseInline("a ***b*** c"),
        )
        // Unfinished ones stay as written.
        assertEquals(listOf(Span("***x")), parseInline("***x"))
    }

    @Test
    fun `underscores in a word are not italics, but around a word they are`() {
        assertEquals(listOf(Span("my_var_name")), parseInline("my_var_name"))
        assertEquals(listOf(Span("this is "), Span("it", italic = true), Span(" ok")), parseInline("this is _it_ ok"))
    }

    @Test
    fun `links, bare links and escapes`() {
        assertEquals(
            listOf(Span("see "), Span("the docs", url = "https://example.com/a"), Span(" now")),
            parseInline("see [the docs](https://example.com/a) now"),
        )
        assertEquals(
            listOf(Span("go to "), Span("https://example.com/x", url = "https://example.com/x"), Span(", then")),
            parseInline("go to https://example.com/x, then"),
        )
        assertEquals(listOf(Span("*not italic*")), parseInline("\\*not italic\\*"))
    }

    @Test
    fun `only web and mail links are links`() {
        val spans = parseInline("[x](javascript:alert(1))")
        assertTrue(spans.all { it.url == null })
        assertEquals("[x](javascript:alert(1))", spans.joinToString("") { it.text })
        assertTrue(isSafeUrl("https://a.b"))
        assertTrue(isSafeUrl("HTTP://a.b"))
        assertTrue(isSafeUrl("mailto:a@b.c"))
        assertFalse(isSafeUrl("javascript:alert(1)"))
        assertFalse(isSafeUrl("intent://x"))
        assertFalse(isSafeUrl("file:///etc/passwd"))
    }

    @Test
    fun `an unfinished marker stays as written`() {
        assertEquals(listOf(Span("**bold")), parseInline("**bold"))
        assertEquals(listOf(Span("a `b")), parseInline("a `b"))
        assertEquals(listOf(Span("[label](")), parseInline("[label]("))
    }
}
