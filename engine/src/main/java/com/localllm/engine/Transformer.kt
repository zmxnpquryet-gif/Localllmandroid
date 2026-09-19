// SDengine — TEST BUILD. EXPERIMENTAL. See SDEngine.ADVISORIES.
package com.localllm.engine

import kotlin.math.sqrt

data class ModelHyperParams(
    val nLayers: Int,
    val dim: Int,
    val nHeads: Int,
    val nKvHeads: Int,
    val headDim: Int,
    val ffnDim: Int,
    val vocab: Int,
    val rmsNormEps: Float = 1e-5f,
    val ropeTheta: Float = 10000f,
    val nExperts: Int = 0,
    val moeTopK: Int = 0,
    val maxSeq: Int = 512
) {
    val kvDim: Int get() = nKvHeads * headDim
    val isMoe: Boolean get() = nExperts > 0
}

/** Dequantized expert matrices for one expert. Shapes: gate/up [ffnDim × dim], down [dim × ffnDim]. */
interface ExpertSet {
    fun gate(e: Int): FloatArray
    fun up(e: Int): FloatArray
    fun down(e: Int): FloatArray
}

/** Resident dense weights; experts come from [ExpertSet] (paged) or dense FFN fields. */
interface TransformerWeights {
    val hp: ModelHyperParams
    fun embed(id: Int): FloatArray
    fun attnQ(layer: Int): FloatArray
    fun attnK(layer: Int): FloatArray
    fun attnV(layer: Int): FloatArray
    fun attnO(layer: Int): FloatArray
    fun attnNorm(layer: Int): FloatArray
    fun ffnNorm(layer: Int): FloatArray
    fun outNorm(): FloatArray
    fun head(): FloatArray
    fun ffnGate(layer: Int): FloatArray
    fun ffnUp(layer: Int): FloatArray
    fun ffnDown(layer: Int): FloatArray
    fun router(layer: Int): FloatArray
    fun experts(layer: Int): ExpertSet
}

data class StepResult(val logits: FloatArray, val usedExperts: List<Int>)

/**
 * Single-threaded decoder forward pass (dense FFN or top-k MoE).
 * Not optimized — order of magnitude work for [Kernels] NEON ports.
 */
class Transformer(
    private val w: TransformerWeights,
    private val kernels: Kernels = ReferenceKernels,
    maxSeqOverride: Int = 0
) {
    private val hp = w.hp
    private val kv = KvCache(hp.nLayers, hp.nKvHeads, hp.headDim, if (maxSeqOverride > 0) maxSeqOverride else hp.maxSeq)

    val position: Int get() = kv.length

    fun reset() = kv.clear()

    fun decodeStep(tokenId: Int): StepResult {
        val pos = kv.length
        require(pos < kv.maxSeq) { "Context exhausted at $pos" }
        var x = w.embed(tokenId)
        val usedExperts = ArrayList<Int>()

        val q = FloatArray(hp.nHeads * hp.headDim)
        val k = FloatArray(hp.kvDim)
        val v = FloatArray(hp.kvDim)
        val h = FloatArray(hp.dim)
        val attn = FloatArray(hp.nHeads * hp.headDim)

        for (layer in 0 until hp.nLayers) {
            // --- attention ---
            kernels.rmsNorm(h, x, w.attnNorm(layer), hp.rmsNormEps)
            kernels.matVec(q, w.attnQ(layer), h, hp.nHeads * hp.headDim, hp.dim)
            kernels.matVec(k, w.attnK(layer), h, hp.kvDim, hp.dim)
            kernels.matVec(v, w.attnV(layer), h, hp.kvDim, hp.dim)
            kernels.rope(q, pos, hp.headDim, hp.ropeTheta)
            kernels.rope(k, pos, hp.headDim, hp.ropeTheta)
            kv.append(layer, k, v)

            val scale = (1.0 / sqrt(hp.headDim.toDouble())).toFloat()
            val group = hp.nHeads / hp.nKvHeads
            for (head in 0 until hp.nHeads) {
                val kvHead = head / group
                val scores = FloatArray(pos + 1)
                val kBase = kv.kBase(layer)
                val vBase = kv.vBase(layer)
                for (p in 0..pos) {
                    var acc = 0f
                    val qb = head * hp.headDim
                    val kb = (p * hp.nKvHeads + kvHead) * hp.headDim
                    for (d in 0 until hp.headDim) acc += q[qb + d] * kBase[kb + d]
                    scores[p] = acc * scale
                }
                kernels.softmaxInPlace(scores)
                val ob = head * hp.headDim
                for (d in 0 until hp.headDim) {
                    var acc = 0f
                    for (p in 0..pos) {
                        acc += scores[p] * vBase[(p * hp.nKvHeads + kvHead) * hp.headDim + d]
                    }
                    attn[ob + d] = acc
                }
            }
            val o = FloatArray(hp.dim)
            kernels.matVec(o, w.attnO(layer), attn, hp.dim, hp.nHeads * hp.headDim)
            for (i in x.indices) x[i] += o[i]

            // --- FFN / MoE ---
            kernels.rmsNorm(h, x, w.ffnNorm(layer), hp.rmsNormEps)
            val y = if (!hp.isMoe) {
                denseFfn(layer, h)
            } else {
                moeFfn(layer, h, usedExperts)
            }
            for (i in x.indices) x[i] += y[i]
        }

        val normed = FloatArray(hp.dim)
        kernels.rmsNorm(normed, x, w.outNorm(), hp.rmsNormEps)
        val logits = FloatArray(hp.vocab)
        kernels.matVec(logits, w.head(), normed, hp.vocab, hp.dim)
        return StepResult(logits, usedExperts)
    }

    private fun denseFfn(layer: Int, h: FloatArray): FloatArray {
        val gate = FloatArray(hp.ffnDim)
        val up = FloatArray(hp.ffnDim)
        kernels.matVec(gate, w.ffnGate(layer), h, hp.ffnDim, hp.dim)
        kernels.matVec(up, w.ffnUp(layer), h, hp.ffnDim, hp.dim)
        val act = FloatArray(hp.ffnDim)
        kernels.siluMul(act, gate, up)
        val y = FloatArray(hp.dim)
        kernels.matVec(y, w.ffnDown(layer), act, hp.dim, hp.ffnDim)
        return y
    }

    private fun moeFfn(layer: Int, h: FloatArray, usedExperts: MutableList<Int>): FloatArray {
        val scores = FloatArray(hp.nExperts)
        kernels.matVec(scores, w.router(layer), h, hp.nExperts, hp.dim)
        kernels.softmaxInPlace(scores)
        val top = kernels.topK(scores, hp.moeTopK.coerceAtLeast(1).coerceAtMost(hp.nExperts))
        // Renormalize gate over the selected experts (standard Switch-style).
        var mass = 0f
        for (e in top) mass += scores[e]
        val inv = if (mass > 0f) 1f / mass else 1f

        val y = FloatArray(hp.dim)
        val gate = FloatArray(hp.ffnDim)
        val up = FloatArray(hp.ffnDim)
        val act = FloatArray(hp.ffnDim)
        val contrib = FloatArray(hp.dim)
        val experts = w.experts(layer)
        for (e in top) {
            usedExperts.add(e)
            kernels.matVec(gate, experts.gate(e), h, hp.ffnDim, hp.dim)
            kernels.matVec(up, experts.up(e), h, hp.ffnDim, hp.dim)
            kernels.siluMul(act, gate, up)
            kernels.matVec(contrib, experts.down(e), act, hp.dim, hp.ffnDim)
            val weight = scores[e] * inv
            for (i in y.indices) y[i] += contrib[i] * weight
        }
        return y
    }
}
