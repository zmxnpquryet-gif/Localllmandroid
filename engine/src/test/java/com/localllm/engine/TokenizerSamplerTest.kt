package com.localllm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TokenizerSamplerTest {

    private fun tinyBpe(): BpeTokenizer {
        val tokens = listOf("a", "b", "c", " ", "ab", "abc").map { it.toByteArray(Charsets.UTF_8) }
        return BpeTokenizer(tokens, listOf("a b", "ab c"), bosId = 99, eosId = 98)
    }

    @Test
    fun `bpe merges apply in rank order`() {
        val tok = tinyBpe()
        // "abc": a+b -> ab (rank 0), then ab+c -> abc (rank 1)
        assertArrayEquals(intArrayOf(5), tok.encode("abc"))
        // "cab": c stays, ab merges
        assertArrayEquals(intArrayOf(2, 4), tok.encode("cab"))
        assertEquals("abc", tok.decode(intArrayOf(5)))
        assertEquals("cab", tok.decode(intArrayOf(2, 4)))
    }

    @Test
    fun `maximal munch without merges`() {
        val tokens = listOf("a", "b", "ab").map { it.toByteArray(Charsets.UTF_8) }
        val tok = BpeTokenizer(tokens, emptyList(), bosId = -1, eosId = -1)
        assertArrayEquals(intArrayOf(2, 0), tok.encode("aba"))
        assertEquals("aba", tok.decode(intArrayOf(2, 0)))
    }

    @Test
    fun `byte fallback roundtrips multibyte text`() {
        val byteTokens = (0 until 256).map { byteArrayOf(it.toByte()) }
        val tok = BpeTokenizer(byteTokens, emptyList())
        val text = "안녕 hi 123"
        assertEquals(text, tok.decode(tok.encode(text)))
    }

    @Test
    fun `sampler greedy and topk`() {
        val logits = floatArrayOf(1f, 5f, 3f, 5f) // tie between 1 and 3 -> first wins
        val s = Sampler(Random(0))
        assertEquals(1, s.sample(logits, SampleConfig(temperature = 0f)))
        assertEquals(1, s.sample(logits, SampleConfig(temperature = 0.7f, topK = 1)))
        // topK=2 restricts to {1,3}
        repeat(20) {
            val id = s.sample(logits, SampleConfig(temperature = 0.7f, topK = 2, topP = 1f, seed = it.toLong()))
            assertTrue(id == 1 || id == 3)
        }
    }

    @Test
    fun `sampler is deterministic per seed`() {
        val logits = FloatArray(50) { it * 0.1f }
        val a = Sampler(Random(123)).sample(logits, SampleConfig(temperature = 1f, topK = 50, topP = 1f))
        val b = Sampler(Random(123)).sample(logits, SampleConfig(temperature = 1f, topK = 50, topP = 1f))
        assertEquals(a, b)
    }
}
