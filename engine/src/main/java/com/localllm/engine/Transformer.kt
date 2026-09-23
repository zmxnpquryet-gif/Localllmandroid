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

    /**
     * Fused matvec hooks. Default materializes the tile then dots (correctness
     * path for tests); paged sets override to dot directly off quantized bytes
     * via [Kernels.matVecQuant] with zero full-matrix allocation.
     */
    fun matVecGate(e: Int, x: FloatArray, y: FloatArray, kernels: Kernels) {
        kernels.matVec(y, gate(e), x, y.size, x.size)
    }

    fun matVecUp(e: Int, x: FloatArray, y: FloatArray, kernels: Kernels) {
        kernels.matVec(y, up(e), x, y.size, x.size)
    }

    fun matVecDown(e: Int, x: FloatArray, y: FloatArray, kernels: Kernels) {
        kernels.matVec(y, down(e), x, y.size, x.size)
    }

    /** Best-effort prefetch of expert tiles; default no-op. */
    fun prefetch(experts: IntArray) {}
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

/**
 * One decode step result. `logits` aliases Transformer scratch memory and is
 * valid only until the next [Transformer.decodeStep] call on the same instance —
 * sample or copy it immediately. `usedExperts` is a fresh list per call.
 */
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

    // Scratch buffers reused across layers and decode steps. The old code
    // allocated ~10 FloatArrays per layer per token (q/k/v/attn/gate/up/act +
    // per-head scores), churning tens of MB per second through the GC on every
    // decode step. These are sized to the model hyperparams once.
    private val q = FloatArray(hp.nHeads * hp.headDim)
    private val kRow = FloatArray(hp.kvDim)
    private val vRow = FloatArray(hp.kvDim)
    private val hBuf = FloatArray(hp.dim)
    private val attnBuf = FloatArray(hp.nHeads * hp.headDim)
    private val oBuf = FloatArray(hp.dim)
    private val yBuf = FloatArray(hp.dim)
    private val gateBuf = FloatArray(hp.ffnDim)
    private val upBuf = FloatArray(hp.ffnDim)
    private val actBuf = FloatArray(hp.ffnDim)
    private val contribBuf = FloatArray(hp.dim)
    private val routerScores = FloatArray(maxOf(1, hp.nExperts))
    private val attnScores = FloatArray(if (maxSeqOverride > 0) maxSeqOverride else hp.maxSeq)
    private val normedBuf = FloatArray(hp.dim)
    private var logitsBuf = FloatArray(0)

    fun reset() = kv.clear()

    fun decodeStep(tokenId: Int): StepResult {
        val pos = kv.length
        require(pos < kv.maxSeq) { "Context exhausted at $pos" }
        var x = w.embed(tokenId)
        val usedExperts = ArrayList<Int>()

        val q = this.q
        val k = this.kRow
        val v = this.vRow
        val h = this.hBuf
        val attn = this.attnBuf

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
            val kBase = kv.kBase(layer)
            val vBase = kv.vBase(layer)
            for (head in 0 until hp.nHeads) {
                val kvHead = head / group
                val scores = this.attnScores
                val qb = head * hp.headDim
                for (p in 0..pos) {
                    var acc = 0f
                    val kb = (p * hp.nKvHeads + kvHead) * hp.headDim
                    for (d in 0 until hp.headDim) acc += q[qb + d] * kBase[kb + d]
                    scores[p] = acc * scale
                }
                // Softmax over [0, pos] only; reuse the leading slice.
                kernels.softmaxPrefix(scores, pos + 1)
                val ob = head * hp.headDim
                for (d in 0 until hp.headDim) {
                    var acc = 0f
                    for (p in 0..pos) {
                        acc += scores[p] * vBase[(p * hp.nKvHeads + kvHead) * hp.headDim + d]
                    }
                    attn[ob + d] = acc
                }
            }
            val o = this.oBuf
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

        val normed = this.normedBuf
        kernels.rmsNorm(normed, x, w.outNorm(), hp.rmsNormEps)
        if (logitsBuf.size != hp.vocab) logitsBuf = FloatArray(hp.vocab)
        val logits = logitsBuf
        kernels.matVec(logits, w.head(), normed, hp.vocab, hp.dim)
        // No copy: StepResult.logits aliases scratch and is valid only until the
        // next decodeStep call (documented on StepResult). All in-repo consumers
        // sample immediately; copying a 150k-vocab array per token is the largest
        // remaining per-token allocation (adversarial review finding).
        return StepResult(logits, usedExperts)
    }

    private fun denseFfn(layer: Int, h: FloatArray): FloatArray {
        val gate = this.gateBuf
        val up = this.upBuf
        kernels.matVec(gate, w.ffnGate(layer), h, hp.ffnDim, hp.dim)
        kernels.matVec(up, w.ffnUp(layer), h, hp.ffnDim, hp.dim)
        val act = this.actBuf
        kernels.siluMul(act, gate, up)
        val y = this.yBuf
        java.util.Arrays.fill(y, 0f)
        kernels.matVec(y, w.ffnDown(layer), act, hp.dim, hp.ffnDim)
        return y
    }

    private fun moeFfn(layer: Int, h: FloatArray, usedExperts: MutableList<Int>): FloatArray {
        val scores = this.routerScores
        kernels.matVec(scores, w.router(layer), h, hp.nExperts, hp.dim)
        kernels.softmaxInPlace(scores)
        val top = kernels.topK(scores, hp.moeTopK.coerceAtLeast(1).coerceAtMost(hp.nExperts))
        // Renormalize gate over the selected experts (standard Switch-style).
        var mass = 0f
        for (e in top) mass += scores[e]
        val inv = if (mass > 0f) 1f / mass else 1f

        val y = this.yBuf
        java.util.Arrays.fill(y, 0f)
        val gate = this.gateBuf
        val up = this.upBuf
        val act = this.actBuf
        val contrib = this.contribBuf
        val experts = w.experts(layer)
        // Prefetch next layer's same-index experts while we compute this layer.
        // Best-effort cache warm; only IOException is caught so cancellation
        // and interrupts propagate (adversarial review finding).
        try {
            if (layer + 1 < hp.nLayers) w.experts(layer + 1).prefetch(top)
        } catch (_: java.io.IOException) {
        }
        for (e in top) {
            usedExperts.add(e)
            // Fused path dots directly off quantized tile bytes (paged); resident
            // path falls back to materialized matvec with identical numerics.
            experts.matVecGate(e, h, gate, kernels)
            experts.matVecUp(e, h, up, kernels)
            kernels.siluMul(act, gate, up)
            experts.matVecDown(e, act, contrib, kernels)
            val weight = scores[e] * inv
            for (i in y.indices) y[i] += contrib[i] * weight
        }
        return y
    }
}
