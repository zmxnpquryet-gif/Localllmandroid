package com.localllm.android.voice

import java.io.File
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Minimal RIFF/WAVE writer for captured speech: 16-bit mono PCM, which is what the
 * on-device recognizer produces and what LiteRT's audio content expects.
 */
object WavWriter {

    const val HEADER_BYTES = 44
    const val BITS_PER_SAMPLE = 16
    const val CHANNELS = 1

    /** Converts normalized floats (-1..1) to little-endian PCM-16, clamping overflow. */
    fun toPcm16(samples: FloatArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        var index = 0
        for (sample in samples) {
            val value = (sample.coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            out[index++] = (value.toInt() and 0xFF).toByte()
            out[index++] = ((value.toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }

    fun write(file: File, samples: FloatArray, sampleRate: Int) {
        val data = toPcm16(samples)
        file.outputStream().buffered().use { out ->
            writeHeader(out, data.size, sampleRate)
            out.write(data)
        }
    }

    private fun writeHeader(out: OutputStream, dataBytes: Int, sampleRate: Int) {
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        out.write(le32(36 + dataBytes))
        out.write("WAVE".toByteArray(StandardCharsets.US_ASCII))
        out.write("fmt ".toByteArray(StandardCharsets.US_ASCII))
        out.write(le32(16)) // fmt chunk size
        out.write(le16(1)) // PCM
        out.write(le16(CHANNELS))
        out.write(le32(sampleRate))
        out.write(le32(sampleRate * CHANNELS * BITS_PER_SAMPLE / 8)) // byte rate
        out.write(le16(CHANNELS * BITS_PER_SAMPLE / 8)) // block align
        out.write(le16(BITS_PER_SAMPLE))
        out.write("data".toByteArray(StandardCharsets.US_ASCII))
        out.write(le32(dataBytes))
    }

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    private fun le16(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )
}
