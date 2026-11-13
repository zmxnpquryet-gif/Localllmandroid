package com.localllm.android.document

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HashedTextEmbedderTest {

    private val embedder = HashedTextEmbedder()

    @Test
    fun `related text ranks above an unrelated passage`() {
        val query = embedder.embed("quarterly revenue report")
        val related = embedder.embed("revenue report for the quarter")
        val unrelated = embedder.embed("weather forecast for tomorrow")

        assertTrue(
            "related=${embedder.similarity(query, related)} unrelated=${embedder.similarity(query, unrelated)}",
            embedder.similarity(query, related) > embedder.similarity(query, unrelated)
        )
    }

    @Test
    fun `korean text matches through character ngrams`() {
        val query = embedder.embed("분기 매출 보고서")
        val related = embedder.embed("매출 보고서 요약")
        val unrelated = embedder.embed("오늘의 날씨 예보")

        assertTrue(
            "related=${embedder.similarity(query, related)} unrelated=${embedder.similarity(query, unrelated)}",
            embedder.similarity(query, related) > embedder.similarity(query, unrelated)
        )
    }

    @Test
    fun `embedding is deterministic and empty text is a zero vector`() {
        val first = embedder.embed("same text")
        val second = embedder.embed("same text")
        assertTrue(first.contentEquals(second))
        assertEquals(1f, first.sumOf { (it * it).toDouble() }.toFloat(), 0.001f)

        val empty = embedder.embed("   ")
        assertTrue(empty.all { it == 0f })
        assertEquals(0f, embedder.similarity(empty, first), 0f)
    }
}
