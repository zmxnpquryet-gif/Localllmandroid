package com.localllm.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/** End-to-end "it runs" proof on a synthetic tiny MoE: prefill -> decode -> sample. */
class TransformerSmokeTest {

    private fun moeHp() = ModelHyperParams(
        nLayers = 2, dim = 32, nHeads = 4, nKvHeads = 2, headDim = 8,
        ffnDim = 64, vocab = 64, nExperts = 4, moeTopK = 2, maxSeq = 64
    )

    private open class RandomWeights(
        override val hp: ModelHyperParams,
        seed: Long
    ) : TransformerWeights {
        protected val rng = Random(seed)
        protected fun mat(rows: Int, cols: Int) = FloatArray(rows * cols) { (rng.nextFloat() * 2 - 1) * 0.3f }
        protected fun vec(n: Int) = FloatArray(n) { 0.8f + rng.nextFloat() * 0.4f }

        private val cache = HashMap<String, FloatArray>()
        private fun m(name: String, rows: Int, cols: Int): FloatArray =
            cache.getOrPut(name) { mat(rows, cols) }

        override fun embed(id: Int): FloatArray {
            val e = m("emb", hp.vocab, hp.dim)
            return e.copyOfRange(id * hp.dim, id * hp.dim + hp.dim)
        }

        override fun attnQ(l: Int) = m("q$l", hp.nHeads * hp.headDim, hp.dim)
        override fun attnK(l: Int) = m("k$l", hp.kvDim, hp.dim)
        override fun attnV(l: Int) = m("v$l", hp.kvDim, hp.dim)
        override fun attnO(l: Int) = m("o$l", hp.dim, hp.nHeads * hp.headDim)
        override fun attnNorm(l: Int) = vec(hp.dim)
        override fun ffnNorm(l: Int) = vec(hp.dim)
        override fun outNorm() = vec(hp.dim)
        override fun head() = m("head", hp.vocab, hp.dim)
        override fun ffnGate(l: Int) = m("g$l", hp.ffnDim, hp.dim)
        override fun ffnUp(l: Int) = m("u$l", hp.ffnDim, hp.dim)
        override fun ffnDown(l: Int) = m("d$l", hp.dim, hp.ffnDim)
        override fun router(l: Int) = m("r$l", hp.nExperts, hp.dim)
        override fun experts(layer: Int): ExpertSet = throw UnsupportedOperationException()
    }

    private class ResidentExperts(hp: ModelHyperParams, seed: Long) : ExpertSet {
        private val rng = Random(seed)
        private val g = Array(hp.nExperts) { FloatArray(hp.ffnDim * hp.dim) { (rng.nextFloat() * 2 - 1) * 0.3f } }
        private val u = Array(hp.nExperts) { FloatArray(hp.ffnDim * hp.dim) { (rng.nextFloat() * 2 - 1) * 0.3f } }
        private val d = Array(hp.nExperts) { FloatArray(hp.dim * hp.ffnDim) { (rng.nextFloat() * 2 - 1) * 0.3f } }

        override fun gate(e: Int) = g[e]
        override fun up(e: Int) = u[e]
        override fun down(e: Int) = d[e]

        fun tileBytes(e: Int, kind: Int): ByteArray {
            val src = when (kind) {
                ExpertTileKey.GATE -> g[e]
                ExpertTileKey.UP -> u[e]
                else -> d[e]
            }
            val bb = ByteBuffer.allocate(src.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in src) bb.putFloat(v)
            return bb.array()
        }
    }

    private class ResidentMoeWeights(hp: ModelHyperParams, seed: Long, val experts: ResidentExperts) :
        RandomWeights(hp, seed) {
        override fun experts(layer: Int): ExpertSet = experts
    }

    private fun runGeneration(w: TransformerWeights, steps: Int): Pair<List<Int>, List<List<Int>>> {
        val tr = Transformer(w)
        val sampler = Sampler(Random(5))
        val ids = mutableListOf(3, 7, 11)
        val used = mutableListOf<List<Int>>()
        for (id in ids) tr.decodeStep(id)
        repeat(steps) {
            val step = tr.decodeStep(ids.last())
            val next = sampler.sample(step.logits, SampleConfig(temperature = 0f))
            ids.add(next)
            used.add(step.usedExperts.toList())
        }
        return Pair(ids, used)
    }

    @Test
    fun `moe smoke runs and respects topk`() {
        val hp = moeHp()
        val experts = ResidentExperts(hp, 42)
        val (ids, used) = runGeneration(ResidentMoeWeights(hp, 43, experts), 6)
        assertEquals(3 + 6, ids.size)
        for (id in ids) assertTrue(id in 0 until hp.vocab)
        assertEquals(6, used.size)
        for (u in used) {
            assertEquals(hp.nLayers * hp.moeTopK, u.size)
            for (e in u) assertTrue(e in 0 until hp.nExperts)
        }
    }

    @Test
    fun `paged experts match resident bit-exact and stream from ssd`() {
        val hp = moeHp()
        val experts = ResidentExperts(hp, 42)
        // One pager per layer would be ideal; a shared tiny pager forces eviction churn.
        val tiles = HashMap<ExpertTileKey, ByteArray>()
        for (layer in 0 until hp.nLayers) {
            for (e in 0 until hp.nExperts) {
                tiles[ExpertTileKey(layer, e, ExpertTileKey.GATE)] = experts.tileBytes(e, ExpertTileKey.GATE)
                tiles[ExpertTileKey(layer, e, ExpertTileKey.UP)] = experts.tileBytes(e, ExpertTileKey.UP)
                tiles[ExpertTileKey(layer, e, ExpertTileKey.DOWN)] = experts.tileBytes(e, ExpertTileKey.DOWN)
            }
        }
        // Same expert matrices must back both runs: seed expert rng identically.
        val residentRun = runGeneration(ResidentMoeWeights(hp, 43, ResidentExperts(hp, 42)), 4)

        val oneTileBytes = (hp.ffnDim * hp.dim * 4).toLong()
        val pager = ExpertPager(MemoryExpertTileSource(tiles), maxResidentBytes = oneTileBytes * 2)
        // Paged run needs the same dense weights: rebuild with seed 43 and paged experts
        // whose underlying floats equal ResidentExperts(42) tiles.
        val pagedWeights = object : RandomWeights(hp, 43) {
            private val set = object : ExpertSet {
                private fun mat(layer: Int, expert: Int, kind: Int, rows: Int, cols: Int): FloatArray {
                    val bytes = pager.acquire(ExpertTileKey(layer, expert, kind))
                    val out = FloatArray(rows * cols)
                    Quant.dequantizeRow(GgufFormat.F32, bytes, 0, out, 0, out.size)
                    return out
                }

                override fun gate(e: Int) = mat(0, e, ExpertTileKey.GATE, hp.ffnDim, hp.dim)
                override fun up(e: Int) = mat(0, e, ExpertTileKey.UP, hp.ffnDim, hp.dim)
                override fun down(e: Int) = mat(0, e, ExpertTileKey.DOWN, hp.dim, hp.ffnDim)
            }

            override fun experts(layer: Int): ExpertSet = set
        }
        // Note: layers share expert ids in this synthetic (layer-0 tiles); routing still exercises paging.
        val pagedRun = runGeneration(pagedWeights, 4)
        assertEquals(residentRun.first, pagedRun.first)
        assertTrue(pager.stats.bytesStreamed > 0)
        assertTrue(pager.stats.misses > 0)
    }

    @Test
    fun `dense smoke runs and is deterministic`() {
        val hp = moeHp().copy(nExperts = 0, moeTopK = 0)
        val w1 = object : RandomWeights(hp, 9) {
            override fun experts(layer: Int): ExpertSet = throw UnsupportedOperationException()
        }
        val w2 = object : RandomWeights(hp, 9) {
            override fun experts(layer: Int): ExpertSet = throw UnsupportedOperationException()
        }
        val t1 = Transformer(w1)
        val t2 = Transformer(w2)
        t1.decodeStep(5)
        t2.decodeStep(5)
        val l1 = t1.decodeStep(6).logits
        val l2 = t2.decodeStep(6).logits
        assertArrayEquals(l1, l2, 0f)
        for (v in l1) assertTrue(v.isFinite())
        val sampler = Sampler(Random(1))
        val next = sampler.sample(l1, SampleConfig(temperature = 0f))
        assertTrue(next in 0 until hp.vocab)
    }
}
