// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Compute-kernel contract. [ReferenceKernels] is the slow, obviously-correct
 * scalar implementation every test proves against; production NEON/GPU kernels
 * must reproduce its numerics bit-approximately (tolerance documented per op).
 */
interface Kernels {
    /** y = W·x, W row-major [rows × cols]. */
    fun matVec(y: FloatArray, w: FloatArray, x: FloatArray, rows: Int, cols: Int)

    /**
     * y = W·x with W stored quantized ([rows × cols] logical). Reference
     * implementation dequantizes then calls [matVec]; fused kernels must match
     * within 1e-3 relative on Q8_0 and 5e-2 on Q4_0/Q4_K.
     */
    fun matVecQuant(y: FloatArray, wBytes: ByteArray, wType: Int, x: FloatArray, rows: Int, cols: Int)

    fun rmsNorm(y: FloatArray, x: FloatArray, weight: FloatArray, eps: Float)

    /** y = silu(gate) * up, elementwise. */
    fun siluMul(y: FloatArray, gate: FloatArray, up: FloatArray)

    fun softmaxInPlace(x: FloatArray)

    /** GPT-NeoX-style RoPE over headDim pairs, in place. */
    fun rope(x: FloatArray, pos: Int, headDim: Int, theta: Float)

    /** Indices of the k largest values, descending. */
    fun topK(x: FloatArray, k: Int): IntArray
}

object ReferenceKernels : Kernels {
    override fun matVec(y: FloatArray, w: FloatArray, x: FloatArray, rows: Int, cols: Int) {
        for (r in 0 until rows) {
            var acc = 0.0
            val base = r * cols
            for (c in 0 until cols) acc += w[base + c] * x[c]
            y[r] = acc.toFloat()
        }
    }

    override fun matVecQuant(
        y: FloatArray, wBytes: ByteArray, wType: Int, x: FloatArray, rows: Int, cols: Int
    ) {
        val dt = GgmlTypes.of(wType)
        require((rows * cols) % dt.blockLength == 0)
        val tmp = FloatArray(rows * cols)
        Quant.dequantizeRow(wType, wBytes, 0, tmp, 0, rows * cols)
        matVec(y, tmp, x, rows, cols)
    }

    override fun rmsNorm(y: FloatArray, x: FloatArray, weight: FloatArray, eps: Float) {
        var ss = 0.0
        for (v in x) ss += v * v
        val inv = 1.0 / sqrt(ss / x.size + eps)
        for (i in x.indices) y[i] = (x[i] * inv * weight[i]).toFloat()
    }

    override fun siluMul(y: FloatArray, gate: FloatArray, up: FloatArray) {
        for (i in y.indices) {
            val g = gate[i].toDouble()
            y[i] = ((g / (1.0 + exp(-g))) * up[i]).toFloat()
        }
    }

    override fun softmaxInPlace(x: FloatArray) {
        var m = Float.NEGATIVE_INFINITY
        for (v in x) if (v > m) m = v
        var s = 0.0
        for (i in x.indices) {
            val e = exp((x[i] - m).toDouble())
            x[i] = e.toFloat()
            s += e
        }
        val inv = (1.0 / s).toFloat()
        for (i in x.indices) x[i] *= inv
    }

    override fun rope(x: FloatArray, pos: Int, headDim: Int, theta: Float) {
        require(headDim % 2 == 0)
        val n = x.size / headDim
        for (h in 0 until n) {
            val base = h * headDim
            for (i in 0 until headDim step 2) {
                val freq = 1.0 / Math.pow(theta.toDouble(), i.toDouble() / headDim)
                val ang = pos * freq
                val cos = Math.cos(ang).toFloat()
                val sin = Math.sin(ang).toFloat()
                val x0 = x[base + i]
                val x1 = x[base + i + 1]
                x[base + i] = x0 * cos - x1 * sin
                x[base + i + 1] = x0 * sin + x1 * cos
            }
        }
    }

    override fun topK(x: FloatArray, k: Int): IntArray {
        val kk = k.coerceIn(1, x.size)
        return x.indices.sortedByDescending { x[it] }.take(kk).toIntArray()
    }

    /** Stable cross-entropy helper for tests. */
    fun logSoftmax(x: FloatArray): FloatArray {
        var m = Float.NEGATIVE_INFINITY
        for (v in x) if (v > m) m = v
        var s = 0.0
        for (v in x) s += exp((v - m).toDouble())
        val lse = m + ln(s).toFloat()
        return FloatArray(x.size) { x[it] - lse }
    }
}
