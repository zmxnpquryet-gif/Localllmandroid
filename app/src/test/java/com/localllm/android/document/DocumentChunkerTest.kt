package com.localllm.android.document

import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentChunkerTest {

    @Test
    fun `long text is split into bounded chunks and keeps the tail`() {
        val text = (1..40).joinToString("\n\n") { "Paragraph $it " + "lorem ipsum ".repeat(10) }
        val chunks = DocumentChunker.chunk(text, maxChars = 300, overlap = 40)

        assertTrue("expected several chunks, got ${chunks.size}", chunks.size > 3)
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 300) }
        assertTrue(chunks.joinToString(" ").contains("Paragraph 40"))
    }

    @Test
    fun `a paragraph longer than the limit is cut without exceeding it`() {
        val chunks = DocumentChunker.chunk("word ".repeat(1000), maxChars = 200, overlap = 20)

        assertTrue(chunks.isNotEmpty())
        chunks.forEach { assertTrue("chunk too long: ${it.length}", it.length <= 200) }
    }

    @Test
    fun `blank text produces no chunks`() {
        assertTrue(DocumentChunker.chunk(" \n\n\t ").isEmpty())
        assertTrue(DocumentChunker.chunk("").isEmpty())
    }
}
