package com.localllm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/** Synthetic GGUF writer + reader round-trip. */
class GgufCodecTest {

    private class Writer {
        val out = ByteArrayOutputStream()
        private fun le(bytes: ByteArray) = out.write(bytes)
        fun u8(v: Int) = out.write(v)
        fun u16(v: Int) {
            out.write(v and 0xFF)
            out.write((v ushr 8) and 0xFF)
        }
        fun u32(v: Long) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
            le(b)
        }
        fun i32(v: Int) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
            le(b)
        }
        fun f32(v: Float) {
            val b = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
            le(b)
        }
        fun u64(v: Long) {
            val b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array()
            le(b)
        }
        fun str(s: String) {
            val b = s.toByteArray(Charsets.UTF_8)
            u64(b.size.toLong())
            le(b)
        }
        fun bytes(b: ByteArray) = le(b)
        fun size(): Long = out.size().toLong()
    }

    private fun syntheticFile(): File {
        val w = Writer()
        w.bytes("GGUF".toByteArray(Charsets.US_ASCII))
        w.i32(3) // version
        w.u64(2) // tensors
        w.u64(5) // metadata
        // meta 1: arch string
        w.str("general.architecture")
        w.u32(8); w.str("llama")
        // meta 2: alignment u32
        w.str("general.alignment")
        w.u32(4); w.u32(32)
        // meta 3: f32 scalar
        w.str("llama.rope.freq_base")
        w.u32(6); w.f32(10000f)
        // meta 4: bool
        w.str("general.quantized")
        w.u32(7); w.u8(1)
        // meta 5: string array
        w.str("tokenizer.ggml.tokens")
        w.u32(9); w.u32(8); w.u64(3)
        w.str("a"); w.str("b"); w.str("ab")
        // tensor 0: F32 [16]
        w.str("token_embd.weight")
        w.u32(1); w.u64(16)
        w.u32(0)
        w.u64(0) // rel offset
        // tensor 1: Q4_0 [32]
        w.str("blk.0.attn_q.weight")
        w.u32(1); w.u64(32)
        w.u32(2)
        w.u64(64) // rel offset (after 16 floats = 64 bytes)
        // data (already 32-aligned? pad if needed)
        while (w.size() % 32 != 0L) w.u8(0)
        val dataBase = w.size()
        val f32payload = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 16) f32payload.putFloat(i * 0.5f)
        w.bytes(f32payload.array())
        // q4_0 block: d=1.0, nibbles 8..9 pattern
        val q = ByteArray(18)
        q[0] = 0x00; q[1] = 0x3C // fp16 1.0
        for (i in 0 until 16) q[2 + i] = 0x98.toByte() // low=8 -> 0.0, high=9 -> 1.0
        w.bytes(q)
        val f = Files.createTempFile("gguf-test", ".gguf").toFile()
        f.writeBytes(w.out.toByteArray())
        // stash expected base for assertions via file tag
        f.appendBytes(byteArrayOf())
        return f
    }

    @Test
    fun `header metadata and tensors roundtrip`() {
        val f = syntheticFile()
        try {
            GgufReader.open(f).use { r ->
                assertEquals(3, r.header.version)
                assertEquals(2L, r.header.tensorCount)
                assertEquals(5L, r.header.metadataCount)
                assertEquals("llama", r.architecture())
                assertEquals(32L, r.alignment)
                assertEquals(10000f, r.archF32("rope.freq_base"))
                assertEquals(true, r.metadata["general.quantized"]?.let { (it as GgufValue.Bool).v })
                assertEquals(listOf("a", "b", "ab"), r.stringArray("tokenizer.ggml.tokens"))
                assertEquals(0L, r.dataBaseOffset % 32)

                assertEquals(2, r.tensors.size)
                val t0 = r.tensors[0]
                assertEquals("token_embd.weight", t0.name)
                assertArrayEquals(longArrayOf(16), t0.dims)
                assertEquals(GgufFormat.F32, t0.dtypeId)
                assertEquals(r.dataBaseOffset, t0.dataOffset)
                assertEquals(64L, t0.byteSize)

                val raw = r.tensorBytes(t0)
                val bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until 16) assertEquals(i * 0.5f, bb.float, 0f)

                val t1 = r.tensors[1]
                assertEquals(GgufFormat.Q4_0, t1.dtypeId)
                assertEquals(18L, t1.byteSize)
                assertEquals(r.dataBaseOffset + 64, t1.dataOffset)
                val out = FloatArray(32)
                Quant.dequantizeRow(t1.dtypeId, r.tensorBytes(t1), 0, out, 0, 32)
                for (i in 0 until 32 step 2) {
                    assertEquals(0f, out[i], 0f)
                    assertEquals(1f, out[i + 1], 0f)
                }
            }
        } finally {
            f.delete()
        }
    }

    @Test
    fun `bad magic and truncation are rejected`() {
        val bad = Files.createTempFile("gguf-bad", ".gguf").toFile()
        try {
            bad.writeBytes("NOPE".toByteArray() + ByteArray(64))
            try {
                GgufReader.open(bad)
                fail("expected GgufException")
            } catch (e: GgufException) {
                assertTrue(e.message!!.contains("magic"))
            }
        } finally {
            bad.delete()
        }
        val tiny = Files.createTempFile("gguf-tiny", ".gguf").toFile()
        try {
            tiny.writeBytes(ByteArray(10))
            try {
                GgufReader.open(tiny)
                fail("expected GgufException")
            } catch (e: GgufException) {
            }
        } finally {
            tiny.delete()
        }
    }

    @Test
    fun alignUp() {
        assertEquals(32L, GgufReader.alignUp(1, 32))
        assertEquals(32L, GgufReader.alignUp(32, 32))
        assertEquals(64L, GgufReader.alignUp(33, 32))
        assertEquals(7L, GgufReader.alignUp(7, 0))
    }
}
