package com.localllm.android.markdown

import com.localllm.android.markdown.MarkdownInlineStyle.BOLD
import com.localllm.android.markdown.MarkdownInlineStyle.CODE
import com.localllm.android.markdown.MarkdownInlineStyle.ITALIC
import com.localllm.android.markdown.MarkdownInlineStyle.STRIKETHROUGH
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {

    // ------------------------------------------------------------- blocks

    @Test
    fun `plain text keeps its line breaks`() {
        val blocks = MarkdownParser.parse("hello\nworld")
        assertEquals(1, blocks.size)
        val paragraph = blocks.single() as MarkdownBlock.Paragraph
        assertEquals("hello\nworld", paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun `headings are recognized only with a space and up to six levels`() {
        val blocks = MarkdownParser.parse("# Title ###\n## Sub\n#tag\n####### seven")
        assertEquals(MarkdownBlock.Heading(1, listOf(MarkdownSpan("Title"))), blocks[0])
        with(blocks[1] as MarkdownBlock.Heading) {
            assertEquals(2, level)
            assertEquals("Sub", spans.single().text)
        }
        val rest = blocks[2] as MarkdownBlock.Paragraph
        assertTrue(rest.spans.single().text.contains("#tag"))
        assertTrue(rest.spans.single().text.contains("#######"))
    }

    @Test
    fun `closed fence keeps code verbatim and reports the language`() {
        val blocks = MarkdownParser.parse("before\n```kotlin\nval x = 1\n\nval y = 2\n```\nafter")
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownSpan("before"))), blocks[0])
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1\n\nval y = 2", code.code)
        assertTrue(code.closed)
        assertEquals(MarkdownBlock.Paragraph(listOf(MarkdownSpan("after"))), blocks[2])
    }

    @Test
    fun `an unclosed fence renders the rest of the answer as code`() {
        val blocks = MarkdownParser.parse("before\n```kotlin\nval x = 1")
        val code = blocks[1] as MarkdownBlock.Code
        assertEquals("kotlin", code.language)
        assertEquals("val x = 1", code.code)
        assertFalse(code.closed)
    }

    @Test
    fun `bullet and ordered lists become list items`() {
        val blocks = MarkdownParser.parse("- one\n- two\n\n1. first\n2) second")
        val items = blocks.filterIsInstance<MarkdownBlock.ListItem>()
        assertEquals(listOf("-", "-", "1.", "2)"), items.map { it.marker })
        assertEquals(listOf("one", "two", "first", "second"), items.map { it.spans.single().text })
        assertTrue(items.all { it.depth == 0 })
    }

    @Test
    fun `nested list items record their depth`() {
        val blocks = MarkdownParser.parse("- top\n    - nested")
        val items = blocks.filterIsInstance<MarkdownBlock.ListItem>()
        assertEquals(listOf(0, 2), items.map { it.depth })
    }

    @Test
    fun `block quotes and rules are separate blocks`() {
        val blocks = MarkdownParser.parse("> quoted\n> more\n\n---")
        assertEquals(
            MarkdownBlock.Quote(listOf(MarkdownSpan("quoted\nmore"))),
            blocks[0]
        )
        assertEquals(MarkdownBlock.Rule, blocks[1])
    }

    // ------------------------------------------------------------- inline

    @Test
    fun `inline styles are applied to their own runs`() {
        val spans = MarkdownParser.parseInline("a **b** _c_ `d` ~~e~~")
        assertEquals("a ", spans[0].text)
        assertEquals(setOf(BOLD), spans.first { it.text == "b" }.styles)
        assertEquals(setOf(ITALIC), spans.first { it.text == "c" }.styles)
        assertEquals(setOf(CODE), spans.first { it.text == "d" }.styles)
        assertEquals(setOf(STRIKETHROUGH), spans.first { it.text == "e" }.styles)
    }

    @Test
    fun `triple markers apply bold and italic together`() {
        val spans = MarkdownParser.parseInline("***both***")
        assertEquals("both", spans.single().text)
        assertEquals(setOf(BOLD, ITALIC), spans.single().styles)
    }

    @Test
    fun `an unclosed marker stays literal instead of eating the answer`() {
        val bold = MarkdownParser.parseInline("**bold")
        assertEquals("**bold", bold.single().text)
        assertTrue(bold.single().styles.isEmpty())

        val code = MarkdownParser.parseInline("`code")
        assertEquals("`code", code.single().text)
        assertTrue(code.single().styles.isEmpty())

        val trailing = MarkdownParser.parseInline("text **")
        assertEquals("text **", trailing.joinToString("") { it.text })
    }

    @Test
    fun `identifiers and math with underscores or asterisks are left alone`() {
        assertEquals("snake_case_name", MarkdownParser.parseInline("snake_case_name").joinToString("") { it.text })
        assertEquals("2 * 3 * 4 = 24", MarkdownParser.parseInline("2 * 3 * 4 = 24").joinToString("") { it.text })
    }

    @Test
    fun `links keep their label and url`() {
        val span = MarkdownParser.parseInline("[docs](https://example.com)").single()
        assertEquals("docs", span.text)
        assertEquals("https://example.com", span.linkUrl)
    }

    @Test
    fun `an unclosed link stays literal`() {
        val spans = MarkdownParser.parseInline("[docs](https://example.com")
        assertEquals("[docs](https://example.com", spans.joinToString("") { it.text })
        assertNull(spans.firstOrNull()?.linkUrl)
    }

    // ----------------------------------------------------------- streaming

    @Test
    fun `every prefix of a streaming answer parses without leaking structure`() {
        val answer = """
            # Title

            Intro with **bold**, `code` and a [link](https://example.com).

            - first
            - second
              1. nested

            > quoted

            ```kotlin
            val x = 1
            ```

            Done.
        """.trimIndent()

        for (end in 0..answer.length) {
            val prefix = answer.substring(0, end)
            val rendered = MarkdownParser.parse(prefix).joinToString("") { it.plainText() }
            assertFalse("prefix @$end leaked a fence: <$prefix>", rendered.contains("```"))
        }
    }

    @Test
    fun `text content survives parsing unchanged`() {
        val source = "Hello, world! 2 * 3 = 6. snake_case_name and **bold** done."
        val rendered = MarkdownParser.parse(source).joinToString("") { it.plainText() }
        assertEquals("Hello, world! 2 * 3 = 6. snake_case_name and bold done.", rendered)
    }

    private fun MarkdownBlock.plainText(): String = when (this) {
        is MarkdownBlock.Paragraph -> spans.joinToString("") { it.text }
        is MarkdownBlock.Heading -> spans.joinToString("") { it.text }
        is MarkdownBlock.Code -> code
        is MarkdownBlock.ListItem -> marker + " " + spans.joinToString("") { it.text }
        is MarkdownBlock.Quote -> spans.joinToString("") { it.text }
        MarkdownBlock.Rule -> ""
    }
}
