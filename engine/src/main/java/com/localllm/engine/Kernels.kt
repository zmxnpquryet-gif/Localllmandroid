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

    /**
     * Softmax over the leading `n` elements, ignoring tail scratch.
     * Default runs the scalar path so custom kernels inherit correct behavior;
     * NEON ports override both together (adversarial review: attention must not
     * silently bypass the kernel contract).
     */
    fun softmaxPrefix(x: FloatArray, n: Int) {
        var m = Float.NEGATIVE_INFINITY
        for (i in 0 until n) if (x[i] > m) m = x[i]
        var s = 0.0
        for (i in 0 until n) {
            val e = exp((x[i] - m).toDouble())
            x[i] = e.toFloat()
            s += e
        }
        val inv = (1.0 / s).toFloat()
        for (i in 0 until n) x[i] *= inv
    }

    /** GPT-NeoX-style RoPE over headDim pairs, in place. */
    fun rope(x: FloatArray, pos: Int, headDim: Int, theta: Float)

    /** Indices of the k largest values, descending. */
    fun topK(x: FloatArray, k: Int): IntArray
}

object ReferenceKernels : Kernels {
    override fun matVec(y: FloatArray, w: FloatArray, x: FloatArray, rows: Int, cols: Int) {
        // Double accumulation keeps the proven tolerance (tests pin 1e-4); 4x
        // unrolling + hoisted bounds cut loop overhead on ART. Summation order
        // differs from the serial loop, so results are tolerance-equal, not
        // bit-identical.
        for (r in 0 until rows) {
            val base = r * cols
            var acc0 = 0.0
            var acc1 = 0.0
            var acc2 = 0.0
            var acc3 = 0.0
            var c = 0
            val limit = cols and 3.inv()
            while (c < limit) {
                acc0 += w[base + c] * x[c]
                acc1 += w[base + c + 1] * x[c + 1]
                acc2 += w[base + c + 2] * x[c + 2]
                acc3 += w[base + c + 3] * x[c + 3]
                c += 4
            }
            var acc = acc0 + acc1 + acc2 + acc3
            while (c < cols) {
                acc += w[base + c] * x[c]
                c++
            }
            y[r] = acc.toFloat()
        }
    }

    override fun matVecQuant(
        y: FloatArray, wBytes: ByteArray, wType: Int, x: FloatArray, rows: Int, cols: Int
    ) {
        // Fused streaming dot: dequantize one block (<=256 vals) into a tiny stack
        // buffer and accumulate immediately. The old path materialized the full
        // rows*cols FloatArray first (e.g. 64MB for a 4k*4k tile) per expert per
        // token — the single biggest MoE bottleneck.
        val dt = GgmlTypes.of(wType)
        require((rows * cols) % dt.blockLength == 0)
        // Fail loudly on row widths that are not block-aligned: the streaming
        // loop below addresses blocks per row, and a partial trailing block
        // would otherwise decode silently wrong (adversarial review finding).
        // ggml guarantees ne[0] block alignment, so legit tensors always pass.
        require(cols % dt.blockLength == 0) {
            "matVecQuant: cols=$cols not a multiple of block ${dt.blockLength} (${dt.name})"
        }
        when (wType) {
            GgufFormat.F32 -> {
                val bb = java.nio.ByteBuffer.wrap(wBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = r * cols
                    for (c in 0 until cols) acc += bb.getFloat((base + c) * 4) * x[c]
                    y[r] = acc.toFloat()
                }
                return
            }
            GgufFormat.F16 -> {
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = (r * cols) * 2
                    for (c in 0 until cols) {
                        val lo = wBytes[base + c * 2].toInt() and 0xFF
                        val hi = wBytes[base + c * 2 + 1].toInt() and 0xFF
                        acc += Half.toFloat(lo or (hi shl 8)) * x[c]
                    }
                    y[r] = acc.toFloat()
                }
                return
            }
            GgufFormat.BF16 -> {
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = (r * cols) * 2
                    for (c in 0 until cols) {
                        val lo = wBytes[base + c * 2].toInt() and 0xFF
                        val hi = wBytes[base + c * 2 + 1].toInt() and 0xFF
                        acc += Float.fromBits((hi shl 24) or (lo shl 16)) * x[c]
                    }
                    y[r] = acc.toFloat()
                }
                return
            }
            GgufFormat.I8 -> {
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = r * cols
                    for (c in 0 until cols) acc += wBytes[base + c] * x[c]
                    y[r] = acc.toFloat()
                }
                return
            }
            // Scalar dtypes have blockLength 1: dot straight off the bytes with one
            // wrap per call, dtype branch hoisted out of the loops (a per-element
            // `when` would run ~58M times per 4k*14k tile) and Long offsets so
            // >2GB tensors can't overflow Int (adversarial review finding).
            GgufFormat.I16 -> {
                val bb = java.nio.ByteBuffer.wrap(wBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = r.toLong() * cols
                    for (c in 0 until cols) acc += bb.getShort(((base + c) * 2).toInt()) * x[c]
                    y[r] = acc.toFloat()
                }
                return
            }
            GgufFormat.I32 -> {
                val bb = java.nio.ByteBuffer.wrap(wBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = r.toLong() * cols
                    for (c in 0 until cols) acc += bb.getInt(((base + c) * 4).toInt()) * x[c]
                    y[r] = acc.toFloat()
                }
                return
            }
            GgufFormat.I64 -> {
                // Offsets always fit Int here: they index wBytes (a ByteArray,
                // max Int size), so Int arithmetic cannot overflow for valid inputs.
                val bb = java.nio.ByteBuffer.wrap(wBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = r * cols
                    for (c in 0 until cols) acc += bb.getLong((base + c) * 8) * x[c]
                    y[r] = acc.toFloat()
                }
                return
            }
            GgufFormat.F64 -> {
                val bb = java.nio.ByteBuffer.wrap(wBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                for (r in 0 until rows) {
                    var acc = 0.0
                    val base = r * cols
                    for (c in 0 until cols) acc += bb.getDouble((base + c) * 8) * x[c]
                    y[r] = acc.toFloat()
                }
                return
            }
        }
        // Block-quantized path: one reusable scratch block, no full-matrix tmp.
        val blockLen = dt.blockLength
        val blockBytes = dt.typeSizeBytes
        val scratch = FloatArray(blockLen)
        // Row stride in bytes: blocks per row * blockBytes.
        val blocksPerRow = cols / blockLen
        for (r in 0 until rows) {
            var acc = 0.0
            val rowBase = r * blocksPerRow * blockBytes
            var col = 0
            for (b in 0 until blocksPerRow) {
                Quant.dequantizeRow(wType, wBytes, rowBase + b * blockBytes, scratch, 0, blockLen)
                for (i in 0 until blockLen) acc += scratch[i] * x[col + i]
                col += blockLen
            }
            y[r] = acc.toFloat()
        }
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
        val table = RopeCache.table(headDim, theta, pos)
        val n = x.size / headDim
        val pairs = headDim / 2
        for (h in 0 until n) {
            val base = h * headDim
            for (p in 0 until pairs) {
                val cos = table[p * 2]
                val sin = table[p * 2 + 1]
                val x0 = x[base + p * 2]
                val x1 = x[base + p * 2 + 1]
                x[base + p * 2] = x0 * cos - x1 * sin
                x[base + p * 2 + 1] = x0 * sin + x1 * cos
            }
        }
    }

    override fun topK(x: FloatArray, k: Int): IntArray {
        // Empty input returned empty under the old sorted implementation;
        // coerceIn(1, 0) would throw (adversarial review finding).
        if (x.isEmpty() || k <= 0) return intArrayOf()
        val kk = k.coerceIn(1, x.size)
        // Fast paths: MoE routing is almost always top-1/top-2 over <=128 experts;
        // a full sort is O(n log n) per layer per token for no reason.
        if (kk == 1) {
            var best = 0
            for (i in 1 until x.size) if (x[i] > x[best]) best = i
            return intArrayOf(best)
        }
        if (kk == 2) {
            var b0 = 0
            var b1 = 1
            if (x[b1] > x[b0]) {
                val t = b0; b0 = b1; b1 = t
            }
            for (i in 2 until x.size) {
                val v = x[i]
                if (v > x[b0]) {
                    b1 = b0; b0 = i
                } else if (v > x[b1]) {
                    b1 = i
                }
            }
            return intArrayOf(b0, b1)
        }
        if (kk * 4 <= x.size) {
            // Min-heap of size kk: O(n log k).
            val heap = IntArray(kk)
            for (i in 0 until kk) heap[i] = i
            // Heapify by value ascending.
            for (i in kk / 2 - 1 downTo 0) siftDown(heap, x, i, kk)
            for (i in kk until x.size) {
                if (x[i] > x[heap[0]]) {
                    heap[0] = i
                    siftDown(heap, x, 0, kk)
                }
            }
            // Sort the k winners descending (k is tiny).
            val out = heap.toList().sortedByDescending { x[it] }.toIntArray()
            return out
        }
        return x.indices.sortedByDescending { x[it] }.take(kk).toIntArray()
    }

    private fun siftDown(heap: IntArray, x: FloatArray, root: Int, size: Int) {
        var r = root
        while (true) {
            val l = r * 2 + 1
            val rgt = l + 1
            var smallest = r
            if (l < size && x[heap[l]] < x[heap[smallest]]) smallest = l
            if (rgt < size && x[heap[rgt]] < x[heap[smallest]]) smallest = rgt
            if (smallest == r) break
            val t = heap[r]; heap[r] = heap[smallest]; heap[smallest] = t
            r = smallest
        }
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

/**
 * RoPE cos/sin cache. The old path evaluated `pow(theta, i/headDim)` per pair
 * per head per token per layer — thousands of pow+cos+sin calls per decode step.
 * Frequencies depend only on (headDim, theta); the angle only on pos, so cache
 * per (headDim, theta, pos) and share across layers/heads. Bounded LRU (512
 * positions) so long contexts can't leak.
 */
internal object RopeCache {
    private data class Key(val headDim: Int, val thetaBits: Int, val pos: Int)
    // Both maps are bounded: tables holds 512 positions, invFreq holds 16
    // (headDim, theta) variants. An unbounded invFreq would let model churn
    // grow the process lifetime (adversarial review finding).
    private val invFreq = object : LinkedHashMap<Pair<Int, Int>, DoubleArray>(16, 0.75f, true) {
        override fun removeEldestEntry(e: Map.Entry<Pair<Int, Int>, DoubleArray>?): Boolean = size > 16
    }
    private val tables = object : LinkedHashMap<Key, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(e: Map.Entry<Key, FloatArray>?): Boolean = size > 512
    }

    @Synchronized
    fun table(headDim: Int, theta: Float, pos: Int): FloatArray {
        val key = Key(headDim, theta.toBits(), pos)
        tables[key]?.let { return it }
        val fk = Pair(headDim, theta.toBits())
        var freq = invFreq[fk]
        if (freq == null) {
            val pairs = headDim / 2
            freq = DoubleArray(pairs) { p ->
                1.0 / Math.pow(theta.toDouble(), (p * 2).toDouble() / headDim)
            }
            invFreq[fk] = freq
        }
        val out = FloatArray(headDim)
        for (p in freq.indices) {
            val ang = pos * freq[p]
            out[p * 2] = Math.cos(ang).toFloat()
            out[p * 2 + 1] = Math.sin(ang).toFloat()
        }
        tables[key] = out
        return out
    }

    @Synchronized
    fun clear() {
        tables.clear()
        invFreq.clear()
    }
}
