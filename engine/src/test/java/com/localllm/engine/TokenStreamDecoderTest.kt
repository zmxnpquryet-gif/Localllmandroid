package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests for [TokenStreamDecoder], focusing on the `completeUtf8PrefixLength`
 * algorithm and the end-to-end hold-back behaviour when streaming tokens that
 * split multi-byte UTF-8 characters.
 */
class TokenStreamDecoderTest {

    private val ga = byteArrayOf(0xEA.toByte(), 0xB0.toByte(), 0x80.toByte()) // '가'
    private val rocket = byteArrayOf(0xF0.toByte(), 0x9F.toByte(), 0x9A.toByte(), 0x80.toByte()) // '🚀'

    // ------------------------------------------------------------------
    // completeUtf8PrefixLength
    // ------------------------------------------------------------------

    @Test
    fun `empty byte array returns zero`() {
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(ByteArray(0)))
    }

    @Test
    fun `pure ascii returns the full length`() {
        val bytes = "Hello, world!".toByteArray(Charsets.US_ASCII)
        assertEquals(bytes.size, TokenStreamDecoder.completeUtf8PrefixLength(bytes))
    }

    @Test
    fun `single ascii byte is complete`() {
        assertEquals(1, TokenStreamDecoder.completeUtf8PrefixLength(byteArrayOf('A'.code.toByte())))
    }

    @Test
    fun `three byte hangul holds back incomplete prefixes`() {
        // 1 byte received: lead byte only, needs 2 more.
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(ga.copyOfRange(0, 1)))
        // 2 bytes received: still one continuation byte missing.
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(ga.copyOfRange(0, 2)))
        // 3 bytes received: '가' is complete.
        assertEquals(3, TokenStreamDecoder.completeUtf8PrefixLength(ga))
    }

    @Test
    fun `four byte emoji holds back incomplete prefixes`() {
        for (n in 1..3) {
            assertEquals(
                "first $n byte(s) of 🚀 must not be complete",
                0,
                TokenStreamDecoder.completeUtf8PrefixLength(rocket.copyOfRange(0, n))
            )
        }
        assertEquals(4, TokenStreamDecoder.completeUtf8PrefixLength(rocket))
    }

    @Test
    fun `two byte sequence completes on the second byte`() {
        val e = byteArrayOf(0xC3.toByte(), 0xA9.toByte()) // 'é'
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(e.copyOfRange(0, 1)))
        assertEquals(2, TokenStreamDecoder.completeUtf8PrefixLength(e))
    }

    @Test
    fun `mixed stream with truncated tail keeps only the complete prefix`() {
        // "한a🚀" fully: 한(3) + a(1) + 🚀(4) = 8 bytes, then truncate the rocket.
        val full = "한a🚀".toByteArray(Charsets.UTF_8)
        assertEquals(8, full.size)
        // Complete up to and including 'a' (4 bytes); the rocket's first byte is held.
        assertEquals(4, TokenStreamDecoder.completeUtf8PrefixLength(full.copyOfRange(0, 5)))
        // One byte less: the whole 'a' plus '한' still complete.
        assertEquals(4, TokenStreamDecoder.completeUtf8PrefixLength(full.copyOfRange(0, 4)))
        // Truncating inside '한' leaves nothing complete.
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(full.copyOfRange(0, 2)))
        // Full stream is complete as-is.
        assertEquals(8, TokenStreamDecoder.completeUtf8PrefixLength(full))
    }

    @Test
    fun `complete text followed by partial hangul returns text length`() {
        val text = "ok".toByteArray(Charsets.US_ASCII) + ga.copyOfRange(0, 2)
        assertEquals(2, TokenStreamDecoder.completeUtf8PrefixLength(text))
    }

    @Test
    fun `pure continuation bytes yield zero`() {
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(byteArrayOf(0x80.toByte())))
        assertEquals(
            0,
            TokenStreamDecoder.completeUtf8PrefixLength(
                byteArrayOf(0x80.toByte(), 0x80.toByte(), 0x80.toByte())
            )
        )
    }

    @Test
    fun `invalid trailing continuation bytes are included like String would`() {
        // A lead byte followed by stray continuation bytes is not a complete
        // sequence, but it is included wholesale so String() renders U+FFFD
        // replacement chars for it — exactly like decoding the whole array.
        assertEquals(3, TokenStreamDecoder.completeUtf8PrefixLength(
            byteArrayOf('A'.code.toByte(), 0x80.toByte(), 0x80.toByte())
        ))
    }

    @Test
    fun `invalid lead byte is included so it surfaces as a replacement char`() {
        // 0xFF is not a valid lead: the algorithm includes it (String would show U+FFFD).
        assertEquals(1, TokenStreamDecoder.completeUtf8PrefixLength(byteArrayOf(0xFF.toByte())))
        assertEquals(
            3,
            TokenStreamDecoder.completeUtf8PrefixLength(
                byteArrayOf('a'.code.toByte(), 'b'.code.toByte(), 0xFE.toByte())
            )
        )
        // Overlong 5-byte lead (0xF8) matches no need-case: included as invalid.
        assertEquals(1, TokenStreamDecoder.completeUtf8PrefixLength(byteArrayOf(0xF8.toByte())))
    }

    @Test
    fun `overlong lead byte expecting more bytes holds back`() {
        // 0xC0 is a 2-byte lead with only one byte present.
        assertEquals(0, TokenStreamDecoder.completeUtf8PrefixLength(byteArrayOf(0xC0.toByte())))
    }

    // ------------------------------------------------------------------
    // End-to-end streaming through a real tokenizer
    // ------------------------------------------------------------------

    /** Literal-family tokenizer over all 256 single-byte tokens, no merges. */
    private fun byteTokenizer(): BpeTokenizer {
        val tokens = (0 until 256).map { b ->
            String.format("<0x%02X>", b).toByteArray(Charsets.US_ASCII)
        }
        return BpeTokenizer(tokens, emptyList())
    }

    @Test
    fun `decoder emits text only on complete characters`() {
        val tok = byteTokenizer()
        val decoder = TokenStreamDecoder(tok)
        val text = "가a🚀"
        val ids = tok.encode(text)

        // Feed one token (one byte) at a time and collect each emission.
        val emissions = ArrayList<String>()
        for (i in ids.indices) {
            emissions.add(decoder.pushStored(tok.storedBytes(ids.copyOfRange(0, i + 1))))
        }
        // Bytes 5-7 (a truncated 🚀 lead) must not leak anything…
        assertEquals(listOf("", "", ""), emissions.subList(4, 7))
        // …and each complete character is emitted exactly once, in order.
        assertEquals(listOf("", "", "가", "a", "", "", "", "🚀"), emissions)
        assertEquals(text, emissions.joinToString(""))
    }

    @Test
    fun `decoder holds back a split hangul character`() {
        val tok = byteTokenizer()
        val decoder = TokenStreamDecoder(tok)
        // '가' = 3 single-byte tokens. After two bytes nothing may be emitted.
        assertEquals("", decoder.pushStored(tok.storedBytes(intArrayOf(tok.encode("가")[0]))))
        assertEquals("", decoder.pushStored(tok.storedBytes(tok.encode("가").copyOfRange(0, 2))))
        // The third byte completes the character.
        assertEquals("가", decoder.pushStored(tok.storedBytes(tok.encode("가"))))
    }

    @Test
    fun `decoder interleaves ascii without waiting on multibyte tails`() {
        val tok = byteTokenizer()
        val decoder = TokenStreamDecoder(tok)
        val ids = tok.encode("a한b")
        // After "a" + first byte of '한': only "a" is complete.
        val twoTokens = tok.storedBytes(ids.copyOfRange(0, 2))
        assertEquals("a", decoder.pushStored(twoTokens))
        // Complete '한' and 'b': the remaining text arrives at once.
        val rest = tok.storedBytes(ids)
        assertEquals("한b", decoder.pushStored(rest))
    }

    @Test
    fun `flush emits whatever was held back`() {
        val tok = byteTokenizer()
        val decoder = TokenStreamDecoder(tok)
        // Feed only the first two bytes of '가', then end the stream.
        assertEquals("", decoder.pushStored(tok.storedBytes(tok.encode("가").copyOfRange(0, 2))))
        // flush() emits the truncated character (as a replacement char, like String would).
        val flushed = decoder.flush()
        assertEquals("\uFFFD", flushed)
        // After a flush the decoder can be reused for a fresh stream.
        assertEquals("hi", decoder.pushStored(tok.storedBytes(tok.encode("hi"))))
    }

    @Test
    fun `flush on empty decoder returns empty string`() {
        val tok = byteTokenizer()
        val decoder = TokenStreamDecoder(tok)
        assertEquals("", decoder.flush())
    }
}
