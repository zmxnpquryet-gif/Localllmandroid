package com.localllm.android.ui.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    @Test
    fun `headings lists quotes and rules parse as blocks`() {
        val doc = MarkdownParser.parse(
            """
            ## Models
            - Qwen
            - Llama

            1. Download
            2. Run

            > local only

            ---
            """.trimIndent()
        )
        assertEquals(5, doc.blocks.size)
        assertTrue(doc.blocks[0] is MarkdownBlock.Heading)
        assertTrue(doc.blocks[1] is MarkdownBlock.ListBlock)
        assertTrue(doc.blocks[2] is MarkdownBlock.ListBlock)
        assertTrue(doc.blocks[3] is MarkdownBlock.Blockquote)
        assertEquals(MarkdownBlock.HorizontalRule, doc.blocks[4])
    }

    @Test
    fun `table keeps escaped pipes and alignment`() {
        val table = MarkdownParser.parse(
            """
            | Model | Size |
            | :--- | ---: |
            | a\|b | 2B |
            """.trimIndent()
        ).blocks.single() as MarkdownBlock.Table
        assertEquals(2, table.headers.size)
        assertEquals(TableAlignment.START, table.alignments[0])
        assertEquals(TableAlignment.END, table.alignments[1])
        assertEquals("a|b", table.rows.single()[0].joinToString("") { it.text })
    }

    @Test
    fun `unclosed fence stays a code block during streaming`() {
        val block = MarkdownParser.parse("Before\n```kotlin\nval x = 1\n").blocks.last() as MarkdownBlock.CodeBlock
        assertEquals("kotlin", block.language)
        assertEquals("val x = 1\n", block.code)
        assertTrue(!block.isClosed)
    }

    @Test
    fun `inline formatting composes and partial markers remain literal`() {
        val spans = MarkdownParser.parseInline("**bold** and *italic* ~~gone~~ `code` [site](https://example.com)")
        assertTrue(spans.any { InlineStyle.BOLD in it.styles && it.text == "bold" })
        assertTrue(spans.any { InlineStyle.ITALIC in it.styles && it.text == "italic" })
        assertTrue(spans.any { InlineStyle.STRIKETHROUGH in it.styles && it.text == "gone" })
        assertTrue(spans.any { InlineStyle.CODE in it.styles && it.text == "code" })
        assertTrue(spans.any { it.href == "https://example.com" && it.text == "site" })
        assertEquals("**unfinished", MarkdownParser.parseInline("**unfinished").joinToString("") { it.text })
    }

    @Test
    fun `math block and inline math retain expressions`() {
        val doc = MarkdownParser.parse(
            "Energy ${'$'}E=mc^2${'$'}\n${'$'}${'$'}\n\\frac{a}{b}\n${'$'}${'$'}"
        )
        assertTrue((doc.blocks[0] as MarkdownBlock.Paragraph).content.any { InlineStyle.MATH in it.styles })
        assertEquals("\\frac{a}{b}", (doc.blocks[1] as MarkdownBlock.MathBlock).expression)
        assertEquals("(a)/(b) + α²", SimpleMathFormatter.format("\\frac{a}{b} + \\alpha^2"))
    }

    @Test
    fun `syntax highlighting is bounded and deterministic`() {
        val spans = CodeSyntaxHighlighter.highlight("val answer = 42 // note", "kotlin")
        assertTrue(spans.any { it.kind == HighlightKind.KEYWORD })
        assertTrue(spans.any { it.kind == HighlightKind.NUMBER })
        assertTrue(spans.any { it.kind == HighlightKind.COMMENT })
        assertTrue(spans.all { it.start >= 0 && it.end <= "val answer = 42 // note".length && it.start < it.end })
        assertTrue(CodeSyntaxHighlighter.highlight("plain", "unknown").isEmpty())
    }
}
