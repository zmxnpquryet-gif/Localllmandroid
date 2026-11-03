package com.localllm.android.ui.markdown

/** Parsed Markdown blocks. A block remains representable while its source is streaming. */
data class MarkdownDocument(val blocks: List<MarkdownBlock>)

sealed interface MarkdownBlock {
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownBlock
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownBlock
    data class Blockquote(val blocks: List<MarkdownBlock>) : MarkdownBlock
    data class ListBlock(
        val ordered: Boolean,
        val start: Int,
        val items: List<MarkdownListItem>
    ) : MarkdownBlock
    data class CodeBlock(
        val language: String?,
        val code: String,
        val isClosed: Boolean
    ) : MarkdownBlock
    data class Table(
        val headers: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
        val alignments: List<TableAlignment>
    ) : MarkdownBlock
    data class MathBlock(val expression: String, val isClosed: Boolean) : MarkdownBlock
    data object HorizontalRule : MarkdownBlock
}

data class MarkdownListItem(
    val content: List<MarkdownInline>,
    val children: List<MarkdownBlock> = emptyList()
)

enum class TableAlignment { START, CENTER, END }

enum class InlineStyle { BOLD, ITALIC, STRIKETHROUGH, CODE, MATH }

data class MarkdownInline(
    val text: String,
    val styles: Set<InlineStyle> = emptySet(),
    val href: String? = null
)
