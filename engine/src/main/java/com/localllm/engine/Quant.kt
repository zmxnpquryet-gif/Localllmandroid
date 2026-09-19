package com.localllm.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** IEEE-754 half precision helpers. */
object Half {
    fun toFloat(bits: Int): Float {
        val h = bits and 0xFFFF
        val sign = (h and 0x8000) shl 16
        var exp = (h ushr 10) and 0x1F
        var mant = h and 0x3FF
        val f: Int = when {
            exp == 0 -> {
                if (mant == 0) sign
                else {
                    // Subnormal: normalize.
                    exp = 1
                    while (mant and 0x400 == 0) {
                        mant = mant shl 1
                        exp--
                    }
                    mant = mant and 0x3FF
                    sign or ((exp + 112) shl 23) or (mant shl 13)
                }
            }
            exp == 31 -> sign or (0xFF shl 23) or (mant shl 13)
            else -> sign or ((exp + 112) shl 23) or (mant shl 13)
        }
        return Float.fromBits(f)
    }

    fun fromFloat(v: Float): Int {
        val f = v.toBits()
        val sign = (f ushr 16) and 0x8000
        var exp = ((f ushr 23) and 0xFF) - 112
        val mant = f and 0x7FFFFF
        return when {
            exp <= 0 -> sign // Underflow flush (tests use tame magnitudes).
            exp >= 31 -> sign or 0x7BFF
            else -> sign or (exp shl 10) or (mant ushr 13)
        }
    }
}

/**
 * Scalar (correctness-first) dequantizers. Layouts follow ggml; NEON/GPU kernels
 * must reproduce these exact semantics — see [Kernels].
 */
object Quant {

    /**
     * Dequantizes [n] values. Scalar types accept any [n]; block types require
     * multiples of the block length. NOTE: K-quant (Q4_K/Q5_K/Q6_K) layouts are
     * implemented best-effort — bit-exactness against ggml golden vectors is still
     * pending (see [ExperimentalQuant]); verify before real-model runs.
     */
    @OptIn(ExperimentalQuant::class)
    fun dequantizeRow(typeId: Int, src: ByteArray, srcOff: Int, out: FloatArray, outOff: Int, n: Int) {
        require(n >= 0)
        when (typeId) {
            GgufFormat.F32 -> {
                val bb = ByteBuffer.wrap(src, srcOff, n * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out[outOff + i] = bb.float
            }
            GgufFormat.F16 -> {
                for (i in 0 until n) {
                    val lo = src[srcOff + i * 2].toInt() and 0xFF
                    val hi = src[srcOff + i * 2 + 1].toInt() and 0xFF
                    out[outOff + i] = Half.toFloat(lo or (hi shl 8))
                }
            }
            GgufFormat.BF16 -> {
                for (i in 0 until n) {
                    val lo = src[srcOff + i * 2].toInt() and 0xFF
                    val hi = src[srcOff + i * 2 + 1].toInt() and 0xFF
                    out[outOff + i] = Float.fromBits((hi shl 24) or (lo shl 16))
                }
            }
            GgufFormat.I8 -> for (i in 0 until n) out[outOff + i] = src[srcOff + i].toFloat()
            GgufFormat.I16 -> {
                val bb = ByteBuffer.wrap(src, srcOff, n * 2).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out[outOff + i] = bb.short.toFloat()
            }
            GgufFormat.I32 -> {
                val bb = ByteBuffer.wrap(src, srcOff, n * 4).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out[outOff + i] = bb.int.toFloat()
            }
            GgufFormat.I64 -> {
                val bb = ByteBuffer.wrap(src, srcOff, n * 8).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out[outOff + i] = bb.long.toFloat()
            }
            GgufFormat.F64 -> {
                val bb = ByteBuffer.wrap(src, srcOff, n * 8).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until n) out[outOff + i] = bb.double.toFloat()
            }
            GgufFormat.Q4_0 -> dequantBlocks(src, srcOff, out, outOff, n, 32, 18, ::dequantQ40)
            GgufFormat.Q4_1 -> dequantBlocks(src, srcOff, out, outOff, n, 32, 20, ::dequantQ41)
            GgufFormat.Q5_0 -> dequantBlocks(src, srcOff, out, outOff, n, 32, 22, ::dequantQ50)
            GgufFormat.Q5_1 -> dequantBlocks(src, srcOff, out, outOff, n, 32, 24, ::dequantQ51)
            GgufFormat.Q8_0 -> dequantBlocks(src, srcOff, out, outOff, n, 32, 34, ::dequantQ80)
            GgufFormat.Q8_1 -> dequantBlocks(src, srcOff, out, outOff, n, 32, 36, ::dequantQ81)
            GgufFormat.Q4_K -> dequantBlocks(src, srcOff, out, outOff, n, 256, 144, ::dequantQ4K)
            GgufFormat.Q5_K -> dequantBlocks(src, srcOff, out, outOff, n, 256, 176, ::dequantQ5K)
            GgufFormat.Q6_K -> dequantBlocks(src, srcOff, out, outOff, n, 256, 210, ::dequantQ6K)
            else -> throw GgufException("Dequantization not implemented for dtype id=$typeId")
        }
    }

    private inline fun dequantBlocks(
        src: ByteArray, srcOff: Int, out: FloatArray, outOff: Int, n: Int,
        blockLen: Int, blockBytes: Int,
        crossinline block: (ByteArray, Int, FloatArray, Int) -> Unit
    ) {
        require(n % blockLen == 0) { "n=$n not a multiple of block $blockLen" }
        var s = srcOff
        var o = outOff
        repeat(n / blockLen) {
            block(src, s, out, o)
            s += blockBytes
            o += blockLen
        }
    }

    private fun u16(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8)

    private fun dequantQ40(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val d = Half.toFloat(u16(src, off))
        for (i in 0 until 16) {
            val b = src[off + 2 + i].toInt() and 0xFF
            out[o + 2 * i] = ((b and 0x0F) - 8) * d
            out[o + 2 * i + 1] = ((b ushr 4) - 8) * d
        }
    }

    private fun dequantQ41(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val d = Half.toFloat(u16(src, off))
        val m = Half.toFloat(u16(src, off + 2))
        for (i in 0 until 16) {
            val b = src[off + 4 + i].toInt() and 0xFF
            out[o + 2 * i] = (b and 0x0F) * d + m
            out[o + 2 * i + 1] = (b ushr 4) * d + m
        }
    }

    private fun dequantQ50(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val d = Half.toFloat(u16(src, off))
        val qh = le32(src, off + 2)
        for (i in 0 until 16) {
            val b = src[off + 6 + i].toInt() and 0xFF
            val h0 = ((qh ushr i) and 1) shl 4
            val h1 = ((qh ushr (i + 16)) and 1) shl 4
            out[o + 2 * i] = (((b and 0x0F) or h0) - 16) * d
            out[o + 2 * i + 1] = (((b ushr 4) or h1) - 16) * d
        }
    }

    private fun dequantQ51(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val d = Half.toFloat(u16(src, off))
        val m = Half.toFloat(u16(src, off + 2))
        val qh = le32(src, off + 4)
        for (i in 0 until 16) {
            val b = src[off + 8 + i].toInt() and 0xFF
            val h0 = ((qh ushr i) and 1) shl 4
            val h1 = ((qh ushr (i + 16)) and 1) shl 4
            out[o + 2 * i] = ((b and 0x0F) or h0) * d + m
            out[o + 2 * i + 1] = ((b ushr 4) or h1) * d + m
        }
    }

    private fun dequantQ80(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val d = Half.toFloat(u16(src, off))
        for (i in 0 until 32) out[o + i] = src[off + 2 + i] * d
    }

    private fun dequantQ81(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val d = Half.toFloat(u16(src, off))
        val s = Half.toFloat(u16(src, off + 2))
        for (i in 0 until 32) out[o + i] = src[off + 4 + i] * d + s
    }

    private fun le32(src: ByteArray, off: Int): Int =
        (src[off].toInt() and 0xFF) or ((src[off + 1].toInt() and 0xFF) shl 8) or
                ((src[off + 2].toInt() and 0xFF) shl 16) or ((src[off + 3].toInt() and 0xFF) shl 24)

    // ---- K-quants (super-block = 256 values, 8 sub-blocks of 32) ----

    private fun scaleMinK4(sub: Int, scales: ByteArray, off: Int): Pair<Int, Int> {
        return if (sub < 4) {
            Pair(scales[off + sub].toInt() and 63, scales[off + sub + 4].toInt() and 63)
        } else {
            val sc = (scales[off + sub + 4].toInt() and 0x0F) or
                    (((scales[off + sub - 4].toInt() and 0xFF) ushr 6) shl 4)
            val mn = ((scales[off + sub + 4].toInt() and 0xFF) ushr 4) or
                    (((scales[off + sub].toInt() and 0xFF) ushr 6) shl 4)
            Pair(sc, mn)
        }
    }

    @ExperimentalQuant
    private fun dequantQ4K(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val dall = Half.toFloat(u16(src, off))
        val dmin = -Half.toFloat(u16(src, off + 2))
        for (sub in 0 until 8) {
            val (sc, mn) = scaleMinK4(sub, src, off + 4)
            val dl = dall * sc
            val ml = dmin * mn
            for (l in 0 until 16) {
                val b = src[off + 16 + sub * 16 + l].toInt() and 0xFF
                out[o + sub * 32 + 2 * l] = dl * (b and 0x0F) - ml
                out[o + sub * 32 + 2 * l + 1] = dl * (b ushr 4) - ml
            }
        }
    }

    @ExperimentalQuant
    private fun dequantQ5K(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val dall = Half.toFloat(u16(src, off))
        val dmin = -Half.toFloat(u16(src, off + 2))
        // qh: 256 bits, bit v belongs to value v (best-effort order; see ExperimentalQuant).
        for (sub in 0 until 8) {
            val (sc, mn) = scaleMinK4(sub, src, off + 4)
            val dl = dall * sc
            val ml = dmin * mn
            for (l in 0 until 16) {
                val v0 = sub * 32 + 2 * l
                val v1 = v0 + 1
                val b = src[off + 48 + sub * 16 + l].toInt() and 0xFF
                val h0 = (((src[off + 16 + v0 / 8].toInt() and 0xFF) ushr (v0 % 8)) and 1) shl 4
                val h1 = (((src[off + 16 + v1 / 8].toInt() and 0xFF) ushr (v1 % 8)) and 1) shl 4
                out[o + v0] = dl * ((b and 0x0F) or h0) - ml
                out[o + v1] = dl * ((b ushr 4) or h1) - ml
            }
        }
    }

    @ExperimentalQuant
    private fun dequantQ6K(src: ByteArray, off: Int, out: FloatArray, o: Int) {
        val dAll = Half.toFloat(u16(src, off + 208))
        for (sub in 0 until 16) {
            val sc = src[off + 192 + sub].toInt() // int8 scale
            val dl = dAll * sc
            for (l in 0 until 16) {
                // ql packs two 4-bit values per byte; qh packs four 2-bit lanes per byte.
                // best-effort layout; see ExperimentalQuant
                val v = sub * 16 + l
                val qlByte = src[off + v / 2].toInt() and 0xFF
                val low = if (v % 2 == 0) qlByte and 0x0F else qlByte ushr 4
                val lane = (v % 4) * 2
                val high = (((src[off + 128 + v / 4].toInt() and 0xFF) ushr lane) and 3) shl 4
                out[o + v] = dl * ((low or high) - 32)
            }
        }
    }

    // ---- naive encoders (tests / tooling only) ----

    /** Naive symmetric Q8_0 encoder, one block per 32 values. */
    fun quantizeQ80Row(src: FloatArray, srcOff: Int, dst: ByteArray, dstOff: Int, n: Int) {
        require(n % 32 == 0)
        var s = srcOff
        var d = dstOff
        repeat(n / 32) {
            var amax = 0f
            for (i in 0 until 32) amax = maxOf(amax, kotlin.math.abs(src[s + i]))
            val scale = if (amax == 0f) 1f else amax / 127f
            val bits = Half.fromFloat(scale)
            dst[d] = (bits and 0xFF).toByte()
            dst[d + 1] = ((bits ushr 8) and 0xFF).toByte()
            for (i in 0 until 32) {
                dst[d + 2 + i] = (src[s + i] / scale).toInt().coerceIn(-127, 127).toByte()
            }
            s += 32
            d += 34
        }
    }

    /** Naive symmetric Q4_0 encoder matching the (q - 8) * d decoder semantics. */
    fun quantizeQ40Row(src: FloatArray, srcOff: Int, dst: ByteArray, dstOff: Int, n: Int) {
        require(n % 32 == 0)
        var s = srcOff
        var d = dstOff
        repeat(n / 32) {
            var amax = 0f
            for (i in 0 until 32) amax = maxOf(amax, kotlin.math.abs(src[s + i]))
            val scale = if (amax == 0f) 1f else amax / 8f
            val bits = Half.fromFloat(scale)
            dst[d] = (bits and 0xFF).toByte()
            dst[d + 1] = ((bits ushr 8) and 0xFF).toByte()
            for (i in 0 until 16) {
                val q0 = (src[s + 2 * i] / scale + 8.5f).toInt().coerceIn(0, 15)
                val q1 = (src[s + 2 * i + 1] / scale + 8.5f).toInt().coerceIn(0, 15)
                dst[d + 2 + i] = ((q1 shl 4) or q0).toByte()
            }
            s += 32
            d += 18
        }
    }
}
