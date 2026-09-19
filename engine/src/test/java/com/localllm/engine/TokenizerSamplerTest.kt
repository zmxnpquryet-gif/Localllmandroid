package com.localllm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TokenizerSamplerTest {

    /** U+0120 (GPT-2 space marker). Built from codepoint: raw source literals
     *  for rare non-ASCII have proven unreliable in this toolchain. */
    private fun gDot(): String = String(Character.toChars(0x120))

    /** U+C548 U+B155. Same reason as above. */
    private fun koreanSample(): String =
        String(Character.toChars(0xC548)) + String(Character.toChars(0xB155))

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
        val text = koreanSample() + " hi 123"
        assertEquals(text, tok.decode(tok.encode(text)))
    }

    @Test
    fun `gpt2 table sanity ascii only`() {
        // 0xE5 (229) is in the printable-retained range -> stays U+00E5 (C3 A5).
        assertEquals(
            listOf(0xC3, 0xA5),
            BpeTokenizer.mapToGpt2(byteArrayOf(0xE5.toByte())).map { it.toInt() and 0xFF }
        )
        // U+0120 walks back to 0x20.
        assertEquals(
            listOf(0x20),
            BpeTokenizer.unmapFromGpt2(gDot()).map { it.toInt() and 0xFF }
        )
    }

    @Test
    fun `gguf byte spellings and g-spaces roundtrip`() {
        // "<0x20>" placeholder resolves to a real space byte.
        val tokens = listOf("<0x20>".toByteArray(Charsets.UTF_8), "a".toByteArray(Charsets.UTF_8))
        val tok = BpeTokenizer(tokens, emptyList(), bosId = -1, eosId = -1)
        assertEquals("a a", tok.decode(tok.encode("a a")))
        // GPT-2 G-dot convention decodes back to spaces.
        val gTokens = listOf("H".toByteArray(Charsets.UTF_8), (gDot() + "world").toByteArray(Charsets.UTF_8))
        val gtok = BpeTokenizer(gTokens, emptyList(), bosId = -1, eosId = -1)
        assertEquals("H world", gtok.decode(intArrayOf(0, 1)))
        assertEquals(byteArrayOf(0x20.toByte()).toList(), BpeTokenizer.unveilByteToken("<0x20>".toByteArray()).toList())
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
    fun `sampler deterministic per seed`() {
        val logits = FloatArray(50) { it * 0.1f }
        val a = Sampler(Random(123)).sample(logits, SampleConfig(temperature = 1f, topK = 50, topP = 1f))
        val b = Sampler(Random(123)).sample(logits, SampleConfig(temperature = 1f, topK = 50, topP = 1f))
        assertEquals(a, b)
    }
}
