package com.localllm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Correctness proofs + a throughput probe (perf-war baseline for NEON ports). */
class KernelsBenchTest {

    private val k: Kernels = ReferenceKernels

    @Test
    fun `matvec matches naive double accumulation`() {
        val rng = Random(3)
        val rows = 64
        val cols = 96
        val w = FloatArray(rows * cols) { rng.nextFloat() - 0.5f }
        val x = FloatArray(cols) { rng.nextFloat() - 0.5f }
        val y = FloatArray(rows)
        k.matVec(y, w, x, rows, cols)
        for (r in 0 until rows) {
            var acc = 0.0
            for (c in 0 until cols) acc += w[r * cols + c] * x[c]
            assertEquals(acc.toFloat(), y[r], 1e-4f)
        }
    }

    @Test
    fun `matvec quant matches dequantized path`() {
        val rng = Random(4)
        val rows = 4
        val cols = 64
        val f = FloatArray(rows * cols) { (rng.nextFloat() * 2 - 1) * 2f }
        val enc = ByteArray(rows * cols / 32 * 34)
        Quant.quantizeQ80Row(f, 0, enc, 0, rows * cols)
        val x = FloatArray(cols) { rng.nextFloat() - 0.5f }
        val a = FloatArray(rows)
        val b = FloatArray(rows)
        k.matVecQuant(a, enc, GgufFormat.Q8_0, x, rows, cols)
        val tmp = FloatArray(rows * cols)
        Quant.dequantizeRow(GgufFormat.Q8_0, enc, 0, tmp, 0, tmp.size)
        k.matVec(b, tmp, x, rows, cols)
        assertArrayEquals(b, a, 1e-5f)
    }

    @Test
    fun `softmax normalizes and rope preserves norm`() {
        val x = floatArrayOf(1f, 2f, 3f, 4f)
        k.softmaxInPlace(x)
        var sum = 0f
        for (v in x) sum += v
        assertEquals(1f, sum, 1e-6f)
        assertTrue(x[3] > x[2] && x[2] > x[1] && x[1] > x[0])

        val rng = Random(5)
        val h = FloatArray(16) { rng.nextFloat() - 0.5f }
        var before = 0.0
        for (v in h) before += v * v
        k.rope(h, 7, 16, 10000f)
        var after = 0.0
        for (v in h) after += v * v
        assertEquals(before.toFloat(), after.toFloat(), 1e-4f)
    }

    @Test
    fun `topk returns largest descending`() {
        val x = floatArrayOf(0.1f, 0.9f, 0.5f, 0.9f, 0.2f)
        assertArrayEquals(intArrayOf(1, 3, 2), k.topK(x, 3))
        assertArrayEquals(intArrayOf(1), k.topK(x, 1))
    }

    @Test
    fun `rmsnorm scales by weight`() {
        val x = floatArrayOf(1f, 2f, 2f, 0f)
        val y = FloatArray(4)
        k.rmsNorm(y, x, FloatArray(4) { 1f }, 0f)
        // rms = sqrt((1+4+4)/4) = 1.5
        assertArrayEquals(floatArrayOf(1f / 1.5f, 2f / 1.5f, 2f / 1.5f, 0f), y, 1e-6f)
    }

    @Test
    fun `matvec throughput probe`() {
        val rng = Random(6)
        val rows = 512
        val cols = 512
        val w = FloatArray(rows * cols) { rng.nextFloat() - 0.5f }
        val x = FloatArray(cols) { rng.nextFloat() - 0.5f }
        val y = FloatArray(rows)
        repeat(3) { k.matVec(y, w, x, rows, cols) } // warmup
        val iters = 20
        val start = System.nanoTime()
        repeat(iters) { k.matVec(y, w, x, rows, cols) }
        val ms = (System.nanoTime() - start) / 1e6 / iters
        val gflops = (2.0 * rows * cols / ms / 1e6)
        println("matvec 512x512 reference: %.2f ms/iter, %.3f GFLOPS".format(ms, gflops))
        assertTrue(y.any { it != 0f })
    }
}
