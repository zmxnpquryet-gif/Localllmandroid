package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class QuantTest {

    @Test
    fun `dtype table matches ggml`() {
        assertEquals(32 to 18, GgmlTypes.of(GgufFormat.Q4_0).blockLength to GgmlTypes.of(GgufFormat.Q4_0).typeSizeBytes)
        assertEquals(32 to 34, GgmlTypes.of(GgufFormat.Q8_0).blockLength to GgmlTypes.of(GgufFormat.Q8_0).typeSizeBytes)
        assertEquals(256 to 144, GgmlTypes.of(GgufFormat.Q4_K).blockLength to GgmlTypes.of(GgufFormat.Q4_K).typeSizeBytes)
        assertEquals(256 to 176, GgmlTypes.of(GgufFormat.Q5_K).blockLength to GgmlTypes.of(GgufFormat.Q5_K).typeSizeBytes)
        assertEquals(256 to 210, GgmlTypes.of(GgufFormat.Q6_K).blockLength to GgmlTypes.of(GgufFormat.Q6_K).typeSizeBytes)
        assertEquals(1 to 4, GgmlTypes.of(GgufFormat.F32).blockLength to GgmlTypes.of(GgufFormat.F32).typeSizeBytes)
    }

    @Test
    fun `half conversion is sane`() {
        assertEquals(1f, Half.toFloat(0x3C00), 0f)
        assertEquals(-2f, Half.toFloat(0xC000), 0f)
        assertEquals(0f, Half.toFloat(0x0000), 0f)
        for (v in listOf(0.5f, 1f, -2.5f, 100f, 0.01f)) {
            assertEquals(v, Half.toFloat(Half.fromFloat(v)), kotlin.math.abs(v) * 0.002f + 1e-3f)
        }
    }

    @Test
    fun `q80 roundtrip is tight`() {
        val rng = Random(7)
        val n = 256
        val src = FloatArray(n) { (rng.nextFloat() * 2 - 1) * 3f }
        val enc = ByteArray(n / 32 * 34)
        Quant.quantizeQ80Row(src, 0, enc, 0, n)
        val out = FloatArray(n)
        Quant.dequantizeRow(GgufFormat.Q8_0, enc, 0, out, 0, n)
        var maxErr = 0f
        for (i in 0 until n) maxErr = maxOf(maxErr, kotlin.math.abs(src[i] - out[i]))
        assertTrue("maxErr=$maxErr", maxErr < 0.03f)
    }

    @Test
    fun `q40 roundtrip is bounded`() {
        val rng = Random(11)
        val n = 256
        val src = FloatArray(n) { (rng.nextFloat() * 2 - 1) * 2f }
        val enc = ByteArray(n / 32 * 18)
        Quant.quantizeQ40Row(src, 0, enc, 0, n)
        val out = FloatArray(n)
        Quant.dequantizeRow(GgufFormat.Q4_0, enc, 0, out, 0, n)
        var maxErr = 0f
        for (i in 0 until n) maxErr = maxOf(maxErr, kotlin.math.abs(src[i] - out[i]))
        assertTrue("maxErr=$maxErr", maxErr < 0.3f)
    }

    @Test
    fun `small quants decode exact hand vectors`() {
        // GGML nibble layout: byte i holds value i (low nibble) and value i+16 (high).
        // Q4_1: d=2, m=1, nibble 0xB -> 11*2+1=23 ; 0xA -> 10*2+1=21
        val q41 = byteArrayOf(0x00, 0x40, 0x00, 0x3C) + ByteArray(16) { 0xAB.toByte() }
        val o41 = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q4_1, q41, 0, o41, 0, 32)
        assertEquals(23f, o41[0], 0f)
        assertEquals(23f, o41[1], 0f)
        assertEquals(21f, o41[16], 0f)
        assertEquals(21f, o41[17], 0f)

        // Q5_0: d=1, qh=0 -> (nibble)-16 ; qh bit0 set -> +16 on value 0 only
        val q50 = byteArrayOf(0x00, 0x3C, 0, 0, 0, 0) + ByteArray(16) { 0x12.toByte() }
        val o50 = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q5_0, q50, 0, o50, 0, 32)
        assertEquals(-14f, o50[0], 0f)
        assertEquals(-14f, o50[1], 0f)
        assertEquals(-15f, o50[16], 0f)
        q50[2] = 1 // qh bit0
        Quant.dequantizeRow(GgufFormat.Q5_0, q50, 0, o50, 0, 32)
        assertEquals(2f, o50[0], 0f)
        assertEquals(-14f, o50[1], 0f)

        // Q8_1: d=2, s=1, q=3 -> 7
        val q81 = byteArrayOf(0x00, 0x40, 0x00, 0x3C) + ByteArray(32) { 3 }
        val o81 = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q8_1, q81, 0, o81, 0, 32)
        assertEquals(7f, o81[0], 0f)
        assertEquals(7f, o81[31], 0f)
    }

    @Test
    fun `q40 follows the ggml nibble layout, not interleaved`() {
        // d=1.0, every byte 0x98: low nibble 8 -> 0.0 on values 0..15,
        // high nibble 9 -> 1.0 on values 16..31.
        val q = ByteArray(18)
        q[0] = 0x00; q[1] = 0x3C // fp16 1.0
        for (i in 0 until 16) q[2 + i] = 0x98.toByte()
        val out = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q4_0, q, 0, out, 0, 32)
        for (i in 0 until 16) assertEquals(0f, out[i], 0f)
        for (i in 16 until 32) assertEquals(1f, out[i], 0f)
    }

    @OptIn(ExperimentalQuant::class)
    @Test
    fun `k-quants zero blocks decode to zero`() {
        for (type in listOf(GgufFormat.Q4_K, GgufFormat.Q5_K, GgufFormat.Q6_K)) {
            val dt = GgmlTypes.of(type)
            val blk = ByteArray(dt.typeSizeBytes) // all zeros
            val out = FloatArray(256)
            Quant.dequantizeRow(type, blk, 0, out, 0, 256)
            for (v in out) assertEquals(0f, v, 0f)
        }
    }

    // Spec vectors for the K-quant layouts, hand-computed from the ggml reference
    // (gguf-py quants.py dequantize_blocks): per 64-value chunk the first 32 values
    // are low nibbles of 32 qs bytes, the next 32 the high nibbles of the same
    // bytes; y = d*sc*q - dmin_stored*mn; Q5_K qh bit g of byte l = value 32*g+l.

    @OptIn(ExperimentalQuant::class)
    @Test
    fun `q4k decodes ggml layout hand vectors`() {
        val q = ByteArray(144)
        q[0] = 0x00; q[1] = 0x3C // d = 1.0
        q[2] = 0x00; q[3] = 0x40 // dmin = 2.0
        q[4] = 1   // sc[0] = 1
        q[5] = 2   // sc[1] = 2
        q[8] = 1   // mn[0] = 1
        q[9] = 1   // mn[1] = 1
        q[12] = 3  // sc[4] = 3 (packed high-half scales)
        q[16] = 0x53 // value 0 = low nibble 3, value 32 = high nibble 5
        q[80] = 0x07 // value 128 = low nibble 7 (qs byte 64, sub-block 4, sc=3)
        val out = FloatArray(256)
        Quant.dequantizeRow(GgufFormat.Q4_K, q, 0, out, 0, 256)
        assertEquals(1f, out[0], 0f)    // 1*1*3 - 2*1
        assertEquals(-2f, out[1], 0f)   // 1*1*0 - 2*1
        assertEquals(8f, out[32], 0f)   // 1*2*5 - 2*1
        assertEquals(21f, out[128], 0f) // 1*3*7 - 2*0
    }

    @OptIn(ExperimentalQuant::class)
    @Test
    fun `q5k decodes ggml layout hand vectors`() {
        val q = ByteArray(176)
        q[0] = 0x00; q[1] = 0x3C // d = 1.0
        q[2] = 0x00; q[3] = 0x40 // dmin = 2.0
        q[4] = 1   // sc[0] = 1
        q[5] = 2   // sc[1] = 2
        q[8] = 1   // mn[0] = 1
        q[12] = 1  // sc[4] = 1
        q[16] = 0x03 // qh[0]: bits 0 and 1 set
        q[23] = 0x10 // qh[7]: bit 4 set -> value 135
        q[48] = 0x53 // value 0 = low 3, value 32 = high 5
        q[119] = 0x02 // value 135 = low nibble 2 (qs byte 71 = 64+7)
        val out = FloatArray(256)
        Quant.dequantizeRow(GgufFormat.Q5_K, q, 0, out, 0, 256)
        assertEquals(17f, out[0], 0f)   // 1*1*(3+16) - 2*1
        assertEquals(42f, out[32], 0f)  // 1*2*(5+16) - 2*0
        assertEquals(18f, out[135], 0f) // 1*1*(2+16) - 2*0
    }

    @OptIn(ExperimentalQuant::class)
    @Test
    fun `q6k decodes ggml layout hand vectors`() {
        val q = ByteArray(210)
        q[208] = 0x00; q[209] = 0x3C // d = 1.0
        q[192] = 1  // sc[0] = 1
        q[193] = 1  // sc[1] = 1
        q[194] = 3  // sc[2] = 3
        q[196] = 1  // sc[4] = 1
        q[198] = 1  // sc[6] = 1
        q[200] = 2  // sc[8] = 2
        q[0] = 0x53   // values 0/64 nibbles 3/5
        q[32] = 0x07  // values 32/96 nibbles 7/0
        q[64] = 0x04  // value 128 low nibble 4
        q[128] = 0x05 // qh[0]: lanes 0 and 1 = 1
        q[160] = 0x02 // qh[32]: lane 0 = 2
        val out = FloatArray(256)
        Quant.dequantizeRow(GgufFormat.Q6_K, q, 0, out, 0, 256)
        assertEquals(-13f, out[0], 0f)   // (3 | 1<<4) - 32
        assertEquals(-27f, out[32], 0f)  // 3*((7 | 1<<4) - 32)
        assertEquals(-27f, out[64], 0f)  // (5 | 0<<4) - 32
        assertEquals(-32f, out[96], 0f)  // (0 | 0<<4) - 32
        assertEquals(8f, out[128], 0f)   // 2*((4 | 2<<4) - 32)
    }

    @Test
    fun `unsupported dtypes throw`() {
        try {
            Quant.dequantizeRow(16 /*IQ2_XXS*/, ByteArray(84), 0, FloatArray(256), 0, 256)
            fail("expected GgufException")
        } catch (e: GgufException) {
        }
        try {
            Quant.dequantizeRow(GgufFormat.Q4_0, ByteArray(18), 0, FloatArray(31), 0, 31)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
        }
    }
}
