// SDengine — TEST BUILD. EXPERIMENTAL. NOT FOR PRODUCTION.
// 자체 추론엔진 실험체. 실기기 추론 미지원, 수치 검증 진행 중.
// See SDEngine.ADVISORIES before any use.
package com.localllm.engine

import java.io.File

class ModelTooLargeException(message: String) : Exception(message)

class UnsupportedArchException(message: String) : Exception(message)

/**
 * SDengine — first-party MoE-only inference facade (project "끔찍한 일").
 *
 * SCOPE (deliberate): gated-expert Mixture-of-Experts on Android, in Kotlin/JVM.
 * Dense-only models, non-MoE hybrids and exotic mixers are OUT:
 *
 * WARNING (TEST BUILD): this engine is an experiment scaffold. It RUNS, but with
 * scalar reference kernels (no NEON/GPU yet), so expect single-digit tokens per
 * second on a real MoE. Every entry point logs [ADVISORIES]; the app layer must
 * keep these warnings visible next to the output.
 *
 * Today: GGUF metadata + tokenizer + full forward pass with paged MoE experts.
 * Dense (non-expert) weights are decoded resident under a cap; expert tiles stay
 * on SSD and stream through [ExpertPager]. Reference scalar kernels prove the
 * math; NEON/GPU ports plug into [Kernels] without touching this logic.
 */
class SDEngine : AutoCloseable {

    companion object {
        const val ENGINE_NAME = "SDengine"
        const val STAGE = "TEST"

        /**
         * Adhesive warnings. Displayed by every consumer surface (settings card,
         * chat banner, load status). Keep in sync, keep them loud.
         */
        val ADVISORIES: List<String> = listOf(
            "TEST 빌드: SDengine은 실험 단계의 자체 추론엔진입니다. 실행은 되지만 출력 품질을 신뢰하지 마세요.",
            "스칼라 레퍼런스 커널만 구현되어 실기기에서는 매우 느립니다(토큰/초 단위). NEON 커널과 K-퀀트 수치 검증이 남은 마일스톤입니다.",
            "SDengine은 MoE 전용입니다. Dense 전용 모델과 MoE가 아닌 하이브리드는 범위 밖이며, 로드에 실패하면 llama.cpp로 대체 실행됩니다.",
            "dense 가중치는 상주 메모리에, expert 타일은 SSD에서 스트리밍됩니다. 상주 한도를 넘는 모델은 로드가 거부됩니다."
        )

        /**
         * Coverage contract. Patterns the pager/forward pass are built for, and
         * architectures deliberately deferred with reasons. Tests pin this list
         * so scope changes stay explicit.
         */
        val SUPPORTED_PATTERNS: List<String> = listOf(
            "llama-family gated-expert MoE: GQA attention + SwiGLU experts + NeoX RoPE + RMSNorm",
            "dense-prefix layers inside MoE models (first_k_dense_replace style)"
        )

        val DEFERRED: Map<String, String> = mapOf(
            "qwen4_exp (Qwen4 preview: GDN linear attention + QSA sparse attention + host-offloaded N-gram)" to
                "deferred until the official Qwen4 release",
            "MLA / compressed-KV attention (DeepSeek family)" to
                "needs dedicated MLA kernels (milestone)",
            "sliding-window attention variants" to
                "needs windowed mask path (milestone)",
            "Mamba / SSM / recurrent mixers" to
                "out of scope: MoE-only engine",
            "short-convolution hybrids (LFM2 family)" to
                "out of scope: MoE-only engine"
        )

        fun advisoryText(): String = "[SDengine][$STAGE] " + ADVISORIES.joinToString(" ")

        internal fun warn(where: String) {
            val msg = "$ENGINE_NAME[$STAGE] $where — " + ADVISORIES.joinToString(" ")
            try {
                android.util.Log.w(ENGINE_NAME, msg)
            } catch (_: Throwable) {
                println(msg)
            }
        }
    }

    data class ModelInfo(
        val arch: String,
        val vocabSize: Int,
        val nLayers: Int,
        val nExperts: Int,
        val tensorCount: Int,
        val fileBytes: Long,
        val tensorBytes: Long,
        val advisories: List<String> = ADVISORIES
    )

    data class GenerateStats(
        val promptTokens: Int,
        val generatedTokens: Int,
        val ms: Long,
        val stopped: Boolean,
        val expertHits: Long,
        val expertMisses: Long,
        val expertBytesStreamed: Long
    )

    private var reader: GgufReader? = null
    private var tokenizer: BpeTokenizer? = null
    private var transformer: Transformer? = null
    private var pager: ExpertPager? = null
    private var tileSource: FileExpertTileSource? = null
    private var residentBytes: Long = 0

    fun residentBytes(): Long = residentBytes

    fun expertStats(): ExpertPager.Stats? = pager?.stats

    fun openModel(file: File, residentCapBytes: Long = 1_500_000_000L): ModelInfo {
        warn("openModel")
        close()
        val r = GgufReader.open(file)
        reader = r
        // No architecture allowlist, no model names: any GGUF whose hyperparams
        // follow the {arch}.* convention and whose tensors use the llama-family
        // layout is attempted. Missing keys fail loudly below with the key name.
        val arch = r.architecture() ?: throw UnsupportedArchException("general.architecture missing")
        val tok = BpeTokenizer.fromMetadata(r)
            ?: throw GgufException("tokenizer.ggml.tokens missing")
        tokenizer = tok

        val dim = r.archU32("embedding_length")?.toInt()
            ?: throw GgufException("embedding_length missing")
        val nLayers = r.archU32("block_count")?.toInt()
            ?: throw GgufException("block_count missing")
        val ffnDim = r.archU32("feed_forward_length")?.toInt()
            ?: throw GgufException("feed_forward_length missing")
        val nHeads = r.archU32("attention.head_count")?.toInt()
            ?: throw GgufException("attention.head_count missing")
        val nKvHeads = r.archU32("attention.head_count_kv")?.toInt() ?: nHeads
        val headDim = r.archU32("attention.key_length")?.toInt() ?: (dim / nHeads)
        val nExperts = r.archU32("expert_count")?.toInt() ?: 0
        val moeTopK = r.archU32("expert_used_count")?.toInt() ?: minOf(2, nExperts)
        val eps = r.archF32("attention.layer_norm_rms_epsilon") ?: 1e-5f
        val theta = r.archF32("rope.freq_base") ?: 10000f
        val ctxLen = r.archU32("context_length")?.toInt() ?: 2048

        val hp = ModelHyperParams(
            nLayers = nLayers, dim = dim, nHeads = nHeads, nKvHeads = nKvHeads,
            headDim = headDim, ffnDim = ffnDim, vocab = tok.vocabSize,
            rmsNormEps = eps, ropeTheta = theta,
            nExperts = nExperts, moeTopK = moeTopK,
            maxSeq = ctxLen.coerceIn(256, 8192)
        )
        val byName = r.tensors.associateBy { it.name }
        val loader = ResidentLoader(r, byName, residentCapBytes)
        val weights = GgufTransformerWeights(r, hp, byName, loader, file) { key, info ->
            registerTile(key, info)
        }
        residentBytes = loader.usedBytes
        transformer = Transformer(weights)
        return ModelInfo(
            arch = arch, vocabSize = tok.vocabSize, nLayers = nLayers, nExperts = nExperts,
            tensorCount = r.tensors.size, fileBytes = file.length(),
            tensorBytes = r.tensors.sumOf { it.byteSize }
        )
    }

    private val tileLayouts = HashMap<ExpertTileKey, Pair<Long, Int>>()
    private val tileDtypes = HashMap<ExpertTileKey, Int>()

    private fun registerTile(key: ExpertTileKey, info: TensorInfo) {
        if (info.byteSize > Int.MAX_VALUE) throw GgufException("Tile too large: ${info.name}")
        tileLayouts[key] = Pair(info.dataOffset, info.byteSize.toInt())
        tileDtypes[key] = info.dtypeId
    }

    fun tileDtype(key: ExpertTileKey): Int = tileDtypes[key] ?: GgufFormat.F32

    internal fun attachPager(p: ExpertPager, src: FileExpertTileSource) {
        pager = p
        tileSource = src
    }

    fun generate(
        prompt: String,
        maxTokens: Int = 128,
        sample: SampleConfig = SampleConfig(),
        addBos: Boolean = true,
        shouldStop: () -> Boolean = { false },
        onToken: (String) -> Unit = {}
    ): GenerateStats {
        warn("generate")
        val tr = transformer ?: throw IllegalStateException("openModel() first")
        val tok = tokenizer ?: throw IllegalStateException("openModel() first")
        val sampler = Sampler(java.util.Random(sample.seed))
        tr.reset()
        val start = System.currentTimeMillis()
        val ids = tok.encode(prompt, addBos).toMutableList()
        val promptTokens = ids.size
        // Prefill (no sampling).
        var step: StepResult? = null
        for (id in ids) step = tr.decodeStep(id)
        var logits = step?.logits ?: throw IllegalStateException("Empty prompt produced no logits")
        var prevText = tok.decode(ids.toIntArray())
        var generated = 0
        var stopped = false
        while (generated < maxTokens) {
            if (shouldStop()) {
                stopped = true
                break
            }
            val next = sampler.sample(logits, sample)
            if (next == tok.eosId) break
            ids.add(next)
            generated++
            val full = tok.decode(ids.toIntArray())
            val delta = if (full.startsWith(prevText)) full.substring(prevText.length) else full
            prevText = full
            if (delta.isNotEmpty()) onToken(delta)
            logits = tr.decodeStep(next).logits
        }
        val pg = pager?.stats
        return GenerateStats(
            promptTokens = promptTokens, generatedTokens = generated,
            ms = System.currentTimeMillis() - start, stopped = stopped,
            expertHits = pg?.hits ?: 0, expertMisses = pg?.misses ?: 0,
            expertBytesStreamed = pg?.bytesStreamed ?: 0
        )
    }

    override fun close() {
        try {
            transformer?.reset()
        } catch (_: Exception) {
        }
        transformer = null
        tokenizer = null
        try {
            tileSource?.close()
        } catch (_: Exception) {
        }
        tileSource = null
        pager = null
        tileLayouts.clear()
        tileDtypes.clear()
        residentBytes = 0
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
    }

    // ---- weight binding ----

    private class ResidentLoader(
        val reader: GgufReader,
        val byName: Map<String, TensorInfo>,
        val cap: Long
    ) {
        var usedBytes: Long = 0
            private set

        fun matrix(name: String): FloatArray {
            val info = byName[name] ?: throw GgufException("Missing tensor: $name")
            val dt = info.dtype()
            val elements = info.elementCount
            val out = FloatArray(elements.toInt())
            val bytes = reader.tensorBytes(info)
            Quant.dequantizeRow(info.dtypeId, bytes, 0, out, 0, out.size)
            usedBytes += out.size * 4L
            if (usedBytes > cap) {
                throw ModelTooLargeException(
                    "Resident ${(usedBytes / 1048576)}MB exceeds cap ${cap / 1048576}MB at $name " +
                            "(experts stay on SSD; shrink the model or raise the cap)"
                )
            }
            return out
        }

        fun vector(name: String): FloatArray = matrix(name)
    }

    private inner class GgufTransformerWeights(
        val reader: GgufReader,
        override val hp: ModelHyperParams,
        val byName: Map<String, TensorInfo>,
        val loader: ResidentLoader,
        val file: File,
        val onTile: (ExpertTileKey, TensorInfo) -> Unit
    ) : TransformerWeights {
        private val cache = HashMap<String, FloatArray>()
        private fun m(name: String): FloatArray = cache.getOrPut(name) { loader.matrix(name) }

        private val expertSets = HashMap<Int, ExpertSet>()

        init {
            if (hp.isMoe) {
                val layout = HashMap<ExpertTileKey, Pair<Long, Int>>()
                for (layer in 0 until hp.nLayers) {
                    for (e in 0 until hp.nExperts) {
                        val g = tile("blk.$layer.ffn_gate_exps.weight", layer, e, ExpertTileKey.GATE)
                        val u = tile("blk.$layer.ffn_up_exps.weight", layer, e, ExpertTileKey.UP)
                        val d = tile("blk.$layer.ffn_down_exps.weight", layer, e, ExpertTileKey.DOWN)
                        layout[g.first] = g.second
                        layout[u.first] = u.second
                        layout[d.first] = d.second
                    }
                }
                val src = FileExpertTileSource(file, layout)
                val p = ExpertPager(src, maxResidentBytes = 256L * 1024 * 1024)
                attachPager(p, src)
            }
        }

        private fun tile(
            base: String, layer: Int, expert: Int, kind: Int
        ): Pair<ExpertTileKey, Pair<Long, Int>> {
            val info = byName[base] ?: throw GgufException("Missing MoE tensor: $base")
            val range = expertTileRange(info, expert, hp.nExperts.toLong())
            if (range.length > Int.MAX_VALUE) throw GgufException("Tile too large: $base")
            val key = ExpertTileKey(layer, expert, kind)
            onTile(key, info.copy(dataOffset = range.offset, byteSize = range.length))
            return Pair(key, Pair(range.offset, range.length.toInt()))
        }

        override fun embed(id: Int): FloatArray {
            val e = m("token_embd.weight")
            require(id in 0 until hp.vocab)
            return e.copyOfRange(id * hp.dim, id * hp.dim + hp.dim)
        }

        override fun attnQ(layer: Int) = m("blk.$layer.attn_q.weight")
        override fun attnK(layer: Int) = m("blk.$layer.attn_k.weight")
        override fun attnV(layer: Int) = m("blk.$layer.attn_v.weight")
        override fun attnO(layer: Int) = m("blk.$layer.attn_o.weight")
        override fun attnNorm(layer: Int) = m("blk.$layer.attn_norm.weight")
        override fun ffnNorm(layer: Int) = m("blk.$layer.ffn_norm.weight")
        override fun outNorm() = m("output_norm.weight")
        override fun head(): FloatArray =
            if (byName.containsKey("output.weight")) m("output.weight")
            else m("token_embd.weight")

        override fun ffnGate(layer: Int) = m("blk.$layer.ffn_gate.weight")
        override fun ffnUp(layer: Int) = m("blk.$layer.ffn_up.weight")
        override fun ffnDown(layer: Int) = m("blk.$layer.ffn_down.weight")
        override fun router(layer: Int) = m("blk.$layer.ffn_gate_inp.weight")

        override fun experts(layer: Int): ExpertSet =
            expertSets.getOrPut(layer) { PagedExpertSet(layer) }

        private inner class PagedExpertSet(val layer: Int) : ExpertSet {
            private val p: ExpertPager =
                pager ?: throw IllegalStateException("MoE pager not attached")

            private fun mat(key: ExpertTileKey, rows: Int, cols: Int): FloatArray {
                val bytes = p.acquire(key)
                val out = FloatArray(rows * cols)
                Quant.dequantizeRow(tileDtype(key), bytes, 0, out, 0, out.size)
                return out
            }

            override fun gate(e: Int) = mat(ExpertTileKey(layer, e, ExpertTileKey.GATE), hp.ffnDim, hp.dim)
            override fun up(e: Int) = mat(ExpertTileKey(layer, e, ExpertTileKey.UP), hp.ffnDim, hp.dim)
            override fun down(e: Int) = mat(ExpertTileKey(layer, e, ExpertTileKey.DOWN), hp.dim, hp.ffnDim)
        }
    }
}
