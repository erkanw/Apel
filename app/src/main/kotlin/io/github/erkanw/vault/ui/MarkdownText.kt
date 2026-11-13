package io.github.erkanw.vault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erkanw.vault.chat.MdAlign
import io.github.erkanw.vault.chat.MdBlock
import io.github.erkanw.vault.chat.Span
import io.github.erkanw.vault.chat.parseBlocks
import io.github.erkanw.vault.chat.parseInline
import kotlinx.coroutines.delay

/**
 * A model's answer with its formatting: bold, italics, headings, lists, quotes, code, links and tables.
 * It reads the text again on every change, so it also works while the answer is still arriving.
 */
@Composable
fun MarkdownText(text: String, color: Color, modifier: Modifier = Modifier, fontSize: Float = 15f) {
    val k = androidx.compose.material3.MaterialTheme.vault
    val blocks = remember(text) { parseBlocks(text) }
    val link = k.accent
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) RenderBlock(block, color, link, fontSize)
    }
}

@Composable
private fun RenderBlock(block: MdBlock, color: Color, link: Color, size: Float) {
    val k = androidx.compose.material3.MaterialTheme.vault
    when (block) {
        is MdBlock.Heading -> Text(
            styled(block.text, color, link),
            color = color,
            fontWeight = FontWeight.Bold,
            fontSize = when (block.level) { 1 -> size + 7; 2 -> size + 4; 3 -> size + 2; else -> size + 1 }.sp,
            lineHeight = (size + 9).sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        is MdBlock.Paragraph -> Text(styled(block.text, color, link), color = color, fontSize = size.sp, lineHeight = (size + 6).sp)
        is MdBlock.Code -> CodeBlock(block.language, block.code, color)
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(k.muted.copy(alpha = 0.5f), RoundedCornerShape(2.dp)))
            Column(
                Modifier.padding(start = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (inner in block.blocks) RenderBlock(inner, color.copy(alpha = 0.8f), link, size)
            }
        }
        is MdBlock.Items -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (item in block.items) {
                Row(Modifier.padding(start = (item.level * 16).dp)) {
                    Text(
                        item.marker,
                        color = color.copy(alpha = 0.7f),
                        fontSize = size.sp,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(if (item.marker.length > 1 && item.marker[0].isDigit()) 26.dp else 18.dp).padding(end = 6.dp),
                    )
                    Text(styled(item.text, color, link), color = color, fontSize = size.sp, lineHeight = (size + 6).sp)
                }
            }
        }
        is MdBlock.Table -> TableBlock(block, color, link, size)
        MdBlock.Rule -> HorizontalDivider(color = color.copy(alpha = 0.25f), modifier = Modifier.padding(vertical = 4.dp))
    }
}

/** Fenced code in monospace, scrolling sideways, with a Copy button of its own. */
@Composable
private fun CodeBlock(language: String, code: String, color: Color) {
    val clipboard = LocalClipboardManager.current
    val view = LocalView.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1500)
            copied = false
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.vault.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(language.ifBlank { "code" }, color = color.copy(alpha = 0.6f), fontSize = 11.sp)
            Text(
                if (copied) "Copied" else "Copy",
                color = color.copy(alpha = 0.8f),
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        clipboard.setText(AnnotatedString(code))
                        Haptics.tap(view)
                        copied = true
                    }
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            )
        }
        val codeScroll = rememberScrollState()
        Box {
            Text(
                code,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                softWrap = false,
                modifier = Modifier
                    .horizontalScroll(codeScroll)
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
            if (codeScroll.canScrollForward) EdgeFade(MaterialTheme.vault.surface, Modifier.matchParentSize())
        }
    }
}

/**
 * A table: each column as wide as its widest cell (at least 64 dp), one line per cell, scrolling sideways when the
 * whole is wider than the answer; then its right edge fades, to show there is more.
 */
@Composable
private fun TableBlock(table: MdBlock.Table, color: Color, link: Color, size: Float) {
    val k = androidx.compose.material3.MaterialTheme.vault
    val scroll = rememberScrollState()
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(k.surface)) {
        // Column by column, so each takes the width of its own content; every cell is one line, so the rows line up.
        Row(Modifier.horizontalScroll(scroll)) {
            table.header.indices.forEach { c ->
                val align = when (table.aligns.getOrElse(c) { MdAlign.Start }) {
                    MdAlign.Start -> TextAlign.Start
                    MdAlign.Center -> TextAlign.Center
                    MdAlign.End -> TextAlign.End
                }
                Column(Modifier.width(androidx.compose.foundation.layout.IntrinsicSize.Max).widthIn(min = 64.dp)) {
                    Text(
                        styled(table.header[c], color, link), color = color, fontSize = (size - 1).sp,
                        fontWeight = FontWeight.SemiBold, textAlign = align, maxLines = 1, softWrap = false,
                        modifier = Modifier.fillMaxWidth().background(k.surfaceHigh.copy(alpha = 0.6f)).padding(horizontal = 12.dp, vertical = 9.dp),
                    )
                    table.rows.forEach { cells ->
                        Box(Modifier.fillMaxWidth().height(1.dp).background(color.copy(alpha = 0.08f)))
                        Text(
                            styled(cells.getOrElse(c) { "" }, color, link), color = color, fontSize = (size - 1).sp,
                            textAlign = align, maxLines = 1, softWrap = false,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                        )
                    }
                }
            }
        }
        if (scroll.canScrollForward) EdgeFade(k.surface, Modifier.matchParentSize())
    }
}

/** The right edge of something that scrolls sideways fading into [ground]: there is more to see. */
@Composable
private fun EdgeFade(ground: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.background(
            androidx.compose.ui.graphics.Brush.horizontalGradient(
                0f to ground.copy(alpha = 0f), 0.86f to ground.copy(alpha = 0f), 1f to ground,
            ),
        ),
    )
}

/** The styled text of one paragraph, cell or list entry. */
@Composable
private fun styled(text: String, color: Color, link: Color): AnnotatedString {
    val spans = remember(text) { parseInline(text) }
    return remember(spans, color, link) {
        buildAnnotatedString {
            for (span in spans) append(span, color, link)
        }
    }
}

private fun AnnotatedString.Builder.append(span: Span, color: Color, linkColor: Color) {
    val style = SpanStyle(
        fontWeight = if (span.bold) FontWeight.Bold else null,
        fontStyle = if (span.italic) FontStyle.Italic else null,
        textDecoration = if (span.strike) TextDecoration.LineThrough else null,
        fontFamily = if (span.code) FontFamily.Monospace else null,
        background = if (span.code) color.copy(alpha = 0.14f) else Color.Unspecified,
        fontSize = if (span.code) 13.5.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
    )
    val url = span.url
    if (url == null) {
        withStyle(style) { append(span.text) }
    } else {
        withLink(
            LinkAnnotation.Url(
                url,
                TextLinkStyles(style = style.merge(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))),
            ),
        ) { append(span.text) }
    }
}
