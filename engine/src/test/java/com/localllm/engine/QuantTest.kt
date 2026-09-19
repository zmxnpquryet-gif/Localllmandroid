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
        // Q4_1: d=2, m=1, nibble 0xB -> 11*2+1=23 ; 0xA -> 21
        val q41 = byteArrayOf(0x00, 0x40, 0x00, 0x3C) + ByteArray(16) { 0xAB.toByte() }
        val o41 = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q4_1, q41, 0, o41, 0, 32)
        assertEquals(23f, o41[0], 0f)
        assertEquals(21f, o41[1], 0f)

        // Q5_0: d=1, qh=0 -> (nibble)-16 ; qh bit0 set -> +16 on value 0
        val q50 = byteArrayOf(0x00, 0x3C, 0, 0, 0, 0) + ByteArray(16) { 0x12.toByte() }
        val o50 = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q5_0, q50, 0, o50, 0, 32)
        assertEquals(-14f, o50[0], 0f)
        assertEquals(-15f, o50[1], 0f)
        q50[2] = 1 // qh bit0
        Quant.dequantizeRow(GgufFormat.Q5_0, q50, 0, o50, 0, 32)
        assertEquals(2f, o50[0], 0f)

        // Q8_1: d=2, s=1, q=3 -> 7
        val q81 = byteArrayOf(0x00, 0x40, 0x00, 0x3C) + ByteArray(32) { 3 }
        val o81 = FloatArray(32)
        Quant.dequantizeRow(GgufFormat.Q8_1, q81, 0, o81, 0, 32)
        assertEquals(7f, o81[0], 0f)
        assertEquals(7f, o81[31], 0f)
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
