package com.localllm.android.voice

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

class WavWriterTest {

    @Test
    fun `header describes 16 bit mono pcm at the capture rate`() {
        val file = File.createTempFile("voice-turn", ".wav")
        try {
            WavWriter.write(file, floatArrayOf(0f, 0.5f, -0.5f, 1f, -1f), 16_000)
            val bytes = file.readBytes()

            assertEquals(WavWriter.HEADER_BYTES + 5 * 2, bytes.size)
            assertEquals("RIFF", ascii(bytes, 0, 4))
            assertEquals("WAVE", ascii(bytes, 8, 4))
            assertEquals("fmt ", ascii(bytes, 12, 4))
            assertEquals(16, le32(bytes, 16))
            assertEquals(1, le16(bytes, 20))   // PCM
            assertEquals(1, le16(bytes, 22))   // mono
            assertEquals(16_000, le32(bytes, 24))
            assertEquals(32_000, le32(bytes, 28)) // byte rate
            assertEquals(2, le16(bytes, 32))   // block align
            assertEquals(16, le16(bytes, 34))  // bits per sample
            assertEquals("data", ascii(bytes, 36, 4))
            assertEquals(10, le32(bytes, 40))
            assertEquals(36 + 10, le32(bytes, 4))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `samples clamp and keep their sign`() {
        val pcm = WavWriter.toPcm16(floatArrayOf(2f, -2f, 0f, 0.5f))
        assertEquals(4 * 2, pcm.size)
        assertEquals(Short.MAX_VALUE.toInt(), sampleAt(pcm, 0))
        assertEquals(-Short.MAX_VALUE.toInt(), sampleAt(pcm, 1))
        assertEquals(0, sampleAt(pcm, 2))
        assertEquals((0.5f * Short.MAX_VALUE).toInt(), sampleAt(pcm, 3))
    }

    private fun sampleAt(bytes: ByteArray, index: Int): Int {
        val lo = bytes[index * 2].toInt() and 0xFF
        val hi = bytes[index * 2 + 1].toInt()
        return (hi shl 8) or lo
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        String(bytes, offset, length, StandardCharsets.US_ASCII)

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
