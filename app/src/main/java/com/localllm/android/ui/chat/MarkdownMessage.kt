package com.localllm.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.markdown.MarkdownBlock
import com.localllm.android.markdown.MarkdownInlineStyle
import com.localllm.android.markdown.MarkdownParser
import com.localllm.android.markdown.MarkdownSpan
import com.localllm.android.ui.glass.GlassTheme

/**
 * Renders markdown as Compose content. Safe to call on every streaming frame: the
 * parser only applies a construct once its closing marker has arrived, so a partial
 * answer re-renders without flicker or dropped characters.
 */
@Composable
fun MarkdownMessage(
    markdown: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassTheme.type.bodyLarge,
    color: Color = GlassTheme.colors.onBackground
) {
    val blocks = remember(markdown) { MarkdownParser.parse(markdown) }
    MarkdownBlocks(blocks = blocks, modifier = modifier, style = style, color = color)
}

/** Renders already-parsed blocks; used when the caller splits artifacts first. */
@Composable
fun MarkdownBlocks(
    blocks: List<MarkdownBlock>,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassTheme.type.bodyLarge,
    color: Color = GlassTheme.colors.onBackground
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Paragraph -> BlockText(block.spans, style, color)
                is MarkdownBlock.Heading -> BlockText(
                    spans = block.spans,
                    style = headingStyle(block.level),
                    color = color,
                    modifier = Modifier.padding(top = 4.dp)
                )
                is MarkdownBlock.Code -> CodeBlockView(block, color)
                is MarkdownBlock.ListItem -> ListItemView(block, style, color)
                is MarkdownBlock.Quote -> QuoteView(block.spans, style)
                MarkdownBlock.Rule -> RuleView()
            }
        }
    }
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> GlassTheme.type.headlineMedium.copy(fontWeight = FontWeight.Bold)
    2 -> GlassTheme.type.titleLarge.copy(fontWeight = FontWeight.Bold)
    3 -> GlassTheme.type.titleMedium.copy(fontWeight = FontWeight.Bold)
    else -> GlassTheme.type.titleSmall.copy(fontWeight = FontWeight.Bold)
}

@Composable
private fun BlockText(
    spans: List<MarkdownSpan>,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier
) {
    if (spans.isEmpty()) return
    BasicText(
        text = spans.annotated(style, color),
        modifier = modifier.fillMaxWidth(),
        style = style.copy(color = color)
    )
}

@Composable
private fun ListItemView(block: MarkdownBlock.ListItem, style: TextStyle, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (block.depth * 14).dp),
        verticalAlignment = Alignment.Top
    ) {
        BasicText(
            text = AnnotatedString(block.marker),
            modifier = Modifier
                .widthIn(min = 18.dp)
                .padding(end = 6.dp),
            style = style.copy(color = color, fontWeight = FontWeight.Medium)
        )
        BasicText(
            text = block.spans.annotated(style, color),
            modifier = Modifier.weight(1f),
            style = style.copy(color = color)
        )
    }
}

@Composable
private fun QuoteView(spans: List<MarkdownSpan>, style: TextStyle) {
    if (spans.isEmpty()) return
    Row(modifier = Modifier
        .fillMaxWidth()
        .height(IntrinsicSize.Min)) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(GlassTheme.colors.primary.copy(alpha = 0.55f))
        )
        Spacer(modifier = Modifier.width(10.dp))
        BasicText(
            text = spans.annotated(style, GlassTheme.colors.onSurfaceVariant),
            modifier = Modifier.weight(1f),
            style = style.copy(color = GlassTheme.colors.onSurfaceVariant)
        )
    }
}

@Composable
private fun RuleView() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(GlassTheme.colors.outline.copy(alpha = 0.4f))
    )
}

@Composable
private fun CodeBlockView(block: MarkdownBlock.Code, color: Color) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.3f), shape)
            .padding(10.dp)
    ) {
        if (block.language.isNotBlank()) {
            BasicText(
                text = AnnotatedString(block.language.uppercase()),
                style = GlassTheme.type.labelSmall.copy(color = GlassTheme.colors.onSurfaceVariant)
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        BasicText(
            text = AnnotatedString(block.code),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                color = color
            )
        )
    }
}

/** Applies inline styles (and link annotations) to a run list. */
@Composable
private fun List<MarkdownSpan>.annotated(base: TextStyle, color: Color): AnnotatedString {
    val linkColor = GlassTheme.colors.primary
    val codeBackground = GlassTheme.colors.surfaceVariant.copy(alpha = 0.7f)
    return buildAnnotatedString {
        for (span in this@annotated) {
            var spanStyle = SpanStyle()
            if (MarkdownInlineStyle.BOLD in span.styles) {
                spanStyle = spanStyle.copy(fontWeight = FontWeight.SemiBold)
            }
            if (MarkdownInlineStyle.ITALIC in span.styles) {
                spanStyle = spanStyle.copy(fontStyle = FontStyle.Italic)
            }
            if (MarkdownInlineStyle.STRIKETHROUGH in span.styles) {
                spanStyle = spanStyle.copy(textDecoration = TextDecoration.LineThrough)
            }
            if (MarkdownInlineStyle.CODE in span.styles) {
                spanStyle = spanStyle.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = if (base.fontSize.isSp) base.fontSize * 0.92f else 13.sp,
                    background = codeBackground
                )
            }
            val url = span.linkUrl
            if (url != null) {
                val linkStyle = spanStyle.copy(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline
                )
                withLink(LinkAnnotation.Url(url, TextLinkStyles(style = linkStyle))) {
                    withStyle(linkStyle) { append(span.text) }
                }
            } else {
                withStyle(spanStyle) { append(span.text) }
            }
        }
    }
}
