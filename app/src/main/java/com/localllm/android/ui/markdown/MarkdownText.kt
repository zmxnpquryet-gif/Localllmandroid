package com.localllm.android.ui.markdown

import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.withStyle
import com.localllm.android.ui.glass.GlassTheme

private const val LINK_TAG = "offcon_link"

/**
 * Offline Compose Markdown renderer. It intentionally does not execute raw HTML.
 * Images are displayed as links rather than fetched, keeping chat rendering offline.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassTheme.type.bodyLarge.copy(lineHeight = 24.sp),
    color: Color = GlassTheme.colors.onBackground,
    isStreaming: Boolean = false
) {
    val document = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(
        blocks = document.blocks,
        modifier = modifier,
        style = style,
        color = color,
        isStreaming = isStreaming
    )
}

/**
 * Renders blocks that were already parsed, used when the caller has split the
 * answer into prose and artifact listings.
 */
@Composable
fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassTheme.type.bodyLarge.copy(lineHeight = 24.sp),
    color: Color = GlassTheme.colors.onBackground,
    isStreaming: Boolean = false
) {
    SelectionContainer {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            blocks.forEach { block ->
                MarkdownBlockView(block, style, color, isStreaming)
            }
            if (isStreaming) {
                Box(
                    modifier = Modifier
                        .width(7.dp)
                        .height(14.dp)
                        .background(GlassTheme.colors.primary.copy(alpha = 0.8f), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    style: TextStyle,
    color: Color,
    isStreaming: Boolean
) {
    when (block) {
        is MarkdownBlock.Heading -> InlineMarkdown(
            spans = block.content,
            style = style.copy(
                fontWeight = FontWeight.Bold,
                fontSize = when (block.level) {
                    1 -> 24.sp
                    2 -> 21.sp
                    3 -> 18.sp
                    4 -> 16.sp
                    else -> 15.sp
                }
            ),
            color = color
        )
        is MarkdownBlock.Paragraph -> InlineMarkdown(block.content, style, color)
        is MarkdownBlock.Blockquote -> {
            val quoteColor = GlassTheme.colors.primary.copy(alpha = 0.55f)
            Column(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRect(
                        color = quoteColor,
                        topLeft = Offset.Zero,
                        size = size.copy(width = 3.dp.toPx())
                    )
                }
                .padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            block.blocks.forEach { child -> MarkdownBlockView(child, style, color.copy(alpha = 0.82f), isStreaming) }
        }
        }
        is MarkdownBlock.ListBlock -> MarkdownListView(block, style, color, isStreaming)
        is MarkdownBlock.CodeBlock -> CodeBlockView(block, style, color)
        is MarkdownBlock.Table -> TableView(block, style, color)
        is MarkdownBlock.MathBlock -> {
            Box(modifier = Modifier.fillMaxWidth()) {
                InlineMarkdown(
                    spans = listOf(MarkdownInline(block.expression, setOf(InlineStyle.MATH))),
                    style = style.copy(fontSize = 18.sp, fontFamily = FontFamily.Serif, textAlign = TextAlign.Center),
                    color = color
                )
            }
        }
        MarkdownBlock.HorizontalRule -> Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(GlassTheme.colors.outline.copy(alpha = 0.25f))
        )
    }
}

@Composable
private fun MarkdownListView(
    list: MarkdownBlock.ListBlock,
    style: TextStyle,
    color: Color,
    isStreaming: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        list.items.forEachIndexed { index, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                ClickableText(
                    text = AnnotatedString(if (list.ordered) "${list.start + index}." else "•"),
                    style = style.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.width(if (list.ordered) 30.dp else 20.dp),
                    onClick = {}
                )
                Column(modifier = Modifier.weight(1f)) {
                    InlineMarkdown(item.content, style, color)
                    if (item.children.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        item.children.forEach { child -> MarkdownBlockView(child, style, color, isStreaming) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.CodeBlock, style: TextStyle, color: Color) {
    val codeStyle = style.copy(
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
        lineHeight = 19.sp
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(GlassTheme.colors.surface.copy(alpha = 0.78f), RoundedCornerShape(10.dp))
            .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.28f), RoundedCornerShape(10.dp))
    ) {
        if (!block.language.isNullOrBlank()) {
            ClickableText(
                text = AnnotatedString(block.language),
                style = style.copy(fontSize = 11.sp, color = GlassTheme.colors.onSurfaceVariant),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                onClick = {}
            )
        }
        ClickableText(
            text = highlightedCode(block.code, block.language, color),
            style = codeStyle.copy(color = color),
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
            onClick = {}
        )
    }
}

@Composable
private fun TableView(table: MarkdownBlock.Table, style: TextStyle, color: Color) {
    val columns = table.headers.size.coerceAtLeast(1)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
    ) {
        TableRow(table.headers, columns, table.alignments, style, color, header = true)
        table.rows.forEach { row -> TableRow(row, columns, table.alignments, style, color, header = false) }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MarkdownInline>>,
    columns: Int,
    alignments: List<TableAlignment>,
    style: TextStyle,
    color: Color,
    header: Boolean
) {
    Row(modifier = Modifier.fillMaxWidth()) {
        repeat(columns) { column ->
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (header) GlassTheme.colors.primary.copy(alpha = 0.1f) else Color.Transparent)
                    .border(0.5.dp, GlassTheme.colors.outline.copy(alpha = 0.2f))
                    .padding(horizontal = 7.dp, vertical = 6.dp)
            ) {
                val align = when (alignments.getOrNull(column) ?: TableAlignment.START) {
                    TableAlignment.START -> TextAlign.Start
                    TableAlignment.CENTER -> TextAlign.Center
                    TableAlignment.END -> TextAlign.End
                }
                InlineMarkdown(
                    spans = cells.getOrNull(column).orEmpty(),
                    style = style.copy(
                        fontSize = 13.sp,
                        fontWeight = if (header) FontWeight.SemiBold else style.fontWeight,
                        textAlign = align
                    ),
                    color = color
                )
            }
        }
    }
}

@Composable
private fun InlineMarkdown(
    spans: List<MarkdownInline>,
    style: TextStyle,
    color: Color
) {
    val uriHandler = LocalUriHandler.current
    val linkColor = GlassTheme.colors.primary
    val codeBackground = GlassTheme.colors.surface.copy(alpha = 0.7f)
    val annotated = remember(spans, color, linkColor, codeBackground) {
        buildInline(spans, color, linkColor, codeBackground)
    }
    ClickableText(
        text = annotated,
        style = style.copy(color = color),
        modifier = Modifier.fillMaxWidth(),
        onClick = { offset ->
            val url = annotated.getStringAnnotations(LINK_TAG, offset, offset).firstOrNull()?.item ?: return@ClickableText
            if (isSafeLink(url)) uriHandler.openUri(url)
        }
    )
}

private fun isSafeLink(url: String): Boolean {
    val parsed = url.toUri()
    return parsed.scheme?.lowercase() in setOf("http", "https", "mailto")
}

private fun highlightedCode(code: String, language: String?, base: Color): AnnotatedString =
    buildAnnotatedString {
        append(code)
        val palette = mapOf(
            HighlightKind.KEYWORD to Color(0xFFC586C0),
            HighlightKind.STRING to Color(0xFFCE9178),
            HighlightKind.NUMBER to Color(0xFFB5CEA8),
            HighlightKind.COMMENT to Color(0xFF6A9955),
            HighlightKind.TYPE to Color(0xFF4EC9B0),
            HighlightKind.BOOLEAN to Color(0xFF569CD6)
        )
        CodeSyntaxHighlighter.highlight(code, language).forEach { span ->
            addStyle(SpanStyle(color = palette[span.kind] ?: base), span.start, span.end)
        }
    }

private fun buildInline(
    spans: List<MarkdownInline>,
    color: Color,
    linkColor: Color,
    codeBackground: Color
): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val start = length
        val styles = span.styles
        withStyle(
            SpanStyle(
                color = if (span.href != null) linkColor else color,
                fontWeight = if (InlineStyle.BOLD in styles) FontWeight.Bold else null,
                fontStyle = if (InlineStyle.ITALIC in styles) FontStyle.Italic else null,
                textDecoration = when {
                    InlineStyle.STRIKETHROUGH in styles -> TextDecoration.LineThrough
                    span.href != null -> TextDecoration.Underline
                    else -> null
                },
                fontFamily = when {
                    InlineStyle.CODE in styles -> FontFamily.Monospace
                    InlineStyle.MATH in styles -> FontFamily.Serif
                    else -> null
                },
                fontSize = if (InlineStyle.CODE in styles) 0.92.em else TextUnit.Unspecified,
                background = if (InlineStyle.CODE in styles) codeBackground else Color.Unspecified
            )
        ) {
            val text = if (InlineStyle.MATH in styles) SimpleMathFormatter.format(span.text) else span.text
            append(text)
            span.href?.let { addStringAnnotation(LINK_TAG, it, start, length) }
        }
    }
}
