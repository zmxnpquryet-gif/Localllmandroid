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
 * Dense (non-expert) weights stay resident quantized as stored (no F32
 * expansion) and compute through fused matvec; expert tiles stay on SSD and
 * stream through [ExpertPager]. Only dense + KV cache must fit RAM, so MoE
 * models larger than memory can load. Reference scalar kernels prove the
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
            "TEST build: SDengine is an experimental in-house engine. It runs, but do not trust its output quality.",
            "Optimized kernels (fused quantized matvec, RoPE cache, scratch reuse, top-k heap) are applied. Performance is unmeasured and K-quant bit-exact validation is pending, so it is slower than llama.cpp and may have numeric drift.",
            "SDengine is MoE-only. Dense-only models and non-MoE hybrids are out of scope; a failed load falls back to llama.cpp.",
            "Dense weights stay resident in quantized form while expert tiles stream from SSD. Only dense+KV needs to fit in RAM (not the whole file), so MoEs larger than memory can load; a load is refused when dense+KV exceeds available RAM."
        )

        /**
         * Coverage contract. Patterns the pager/forward pass are built for, and
         * architectures deliberately deferred with reasons. Tests pin this list
         * so scope changes stay explicit.
         */
        val SUPPORTED_PATTERNS: List<String> = listOf(
            "llama-family gated-expert MoE: GQA attention + SwiGLU experts + arch-matched RoPE (NeoX half-split for qwen/gemma, NORM interleaved for llama/deepseek2) + RMSNorm"
        )

        /**
         * RoPE pairing convention per GGUF architecture, mirroring llama.cpp's
         * `llama_model_rope_type()`. NORM arches have Q/K stored PERMUTED by
         * convert_hf_to_gguf.py (stored[2j]=q[j], stored[2j+1]=q[j+d/2]) and
         * rotate adjacent lane pairs; NEOX arches (qwen2/qwen2moe/qwen3/
         * qwen3moe/gemma/phi/...) are stored unpermuted and rotate half-split
         * pairs. Mismatching this silently scrambles attention, so this list
         * must track the llama.cpp table — never guess per model name.
         */
        private val INTERLEAVED_ROPE_ARCHES = setOf(
            "llama", "baichuan", "starcoder", "internlm2", "minicpm", "xverse",
            "command-r", "command-r7b", "cohere2", "olmo", "arctic", "chatglm",
            "granite", "granitemoe", "granitehybrid", "chameleon", "deepseek",
            "deepseek2", "deepseek2ocr", "deepseek32", "smollm3", "ernie4_5",
            "ernie4_5moe", "mistral3", "mistral4", "plamo", "orion"
        )

        /** True for GGML ROPE_TYPE_NORM arches (interleaved pairs); false for NeoX half-split. */
        fun ropeInterleaved(arch: String): Boolean =
            arch.trim().lowercase() in INTERLEAVED_ROPE_ARCHES

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

        /**
         * GGUF `general.architecture` values that need the deferred kernels above.
         * Mirrors the app-layer `SD_DEFERRED_ARCHITECTURES` (GgufMetadataDetector):
         * keep both lists in sync when scope changes.
         */
        val DEFERRED_ARCHES: Set<String> = setOf(
            "deepseek2", "deepseek3", "qwen4_exp", "mamba", "mamba2", "lfm2", "jamba", "granitehybrid"
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
        /** Quantized dense bytes actually resident in RAM (experts excluded). */
        val denseResidentBytes: Long,
        /** Expert bytes that stay on SSD and stream through the pager. */
        val expertBytes: Long,
        val advisories: List<String> = ADVISORIES
    )

    data class ResidentEstimate(
        val arch: String,
        val nLayers: Int,
        val nExperts: Int,
        /** Dense (non-expert) tensor bytes: what SDengine keeps resident (quantized, as stored). */
        val denseFileBytes: Long,
        /** Expert tensor bytes: streamed from SSD, never resident. */
        val expertFileBytes: Long,
        val kvHeads: Int,
        val headDim: Int
    ) {
        /** F32 KV cache bytes for [contextWindow] (matches [KvCache] layout). */
        fun kvBytes(contextWindow: Int): Long =
            2L * nLayers * kvHeads * headDim * contextWindow * 4L
    }

    /**
     * Pre-load footprint probe: reads only GGUF metadata (no weights) and splits
     * tensors into resident-dense vs SSD-streamed expert bytes. Lets the app gate
     * SDengine loads on dense+KV instead of the file size — the whole point of
     * expert paging is running models larger than RAM. Throws [GgufException] /
     * [UnsupportedArchException] like [openModel]; callers fall back to the
     * generic file-size gate when this fails.
     */
    fun estimateResident(file: File): ResidentEstimate {
        GgufReader.open(file).use { r ->
            val arch = r.architecture() ?: throw GgufException("general.architecture missing")
            if (arch.lowercase() in DEFERRED_ARCHES) {
                throw UnsupportedArchException("SDengine unsupported architecture '$arch' (run with llama.cpp)")
            }
            val nLayers = r.archU32("block_count")?.toInt()
                ?: throw GgufException("block_count missing")
            val nHeads = r.archU32("attention.head_count")?.toInt()
                ?: throw GgufException("attention.head_count missing")
            val dim = r.archU32("embedding_length")?.toInt() ?: 0
            val nKvHeads = r.archU32("attention.head_count_kv")?.toInt() ?: nHeads
            val headDim = r.archU32("attention.key_length")?.toInt()
                ?: if (nHeads > 0 && dim > 0) dim / nHeads else 0
            if (nLayers <= 0 || nKvHeads <= 0 || headDim <= 0) {
                throw GgufException("invalid attention hyperparams")
            }
            val nExperts = r.archU32("expert_count")?.toInt() ?: 0
            var dense = 0L
            var expert = 0L
            for (t in r.tensors) {
                if (nExperts > 0 && isExpertTensor(t.name)) expert += t.byteSize else dense += t.byteSize
            }
            return ResidentEstimate(
                arch = arch, nLayers = nLayers, nExperts = nExperts,
                denseFileBytes = dense, expertFileBytes = expert,
                kvHeads = nKvHeads, headDim = headDim
            )
        }
    }

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
    private var loaderRef: ResidentLoader? = null

    /** Live dense-resident bytes (tiles load lazily on first decode). */
    fun residentBytes(): Long = loaderRef?.usedBytes ?: 0

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
        // Fail fast with a clear message instead of a cryptic "Missing tensor":
        // MLA/SSM/convolution hybrids need dedicated kernels (see DEFERRED).
        // The app routes these to llama.cpp; a manual SDengine attempt lands here.
        if (arch.lowercase() in DEFERRED_ARCHES) {
            val reason = DEFERRED.entries.firstOrNull { (k, _) ->
                k.contains(arch, ignoreCase = true) || arch.contains(k.substringBefore(" "), ignoreCase = true)
            }?.value ?: "needs dedicated kernels (milestone)"
            try { r.close() } catch (_: Exception) {}
            reader = null
            throw UnsupportedArchException("SDengine unsupported architecture '$arch': $reason (run with llama.cpp)")
        }
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
            ropeInterleavedPairs = ropeInterleaved(arch),
            nExperts = nExperts, moeTopK = moeTopK,
            maxSeq = ctxLen.coerceIn(256, 8192)
        )
        val byName = r.tensors.associateBy { it.name }
        val loader = ResidentLoader(r, byName, residentCapBytes)
        loaderRef = loader
        val weights = GgufTransformerWeights(r, hp, byName, loader, file) { key, info ->
            registerTile(key, info)
        }
        transformer = Transformer(weights)
        return ModelInfo(
            arch = arch, vocabSize = tok.vocabSize, nLayers = nLayers, nExperts = nExperts,
            tensorCount = r.tensors.size, fileBytes = file.length(),
            tensorBytes = r.tensors.sumOf { it.byteSize },
            denseResidentBytes = loader.usedBytes,
            expertBytes = r.tensors.sumOf {
                if (nExperts > 0 && isExpertTensor(it.name)) it.byteSize else 0L
            }
        )
    }

    private fun isExpertTensor(name: String): Boolean {
        // blk.{l}.ffn_{gate,up,down}_exps.weight — shared by estimateResident
        // and ModelInfo so both split dense/expert identically.
        if (!name.startsWith("blk.") || !name.endsWith(".weight")) return false
        val mid = name.removePrefix("blk.").removeSuffix(".weight")
        val dot = mid.indexOf('.')
        if (dot < 0) return false
        if (mid.substring(0, dot).toIntOrNull() == null) return false
        return when (mid.substring(dot + 1)) {
            "ffn_gate_exps", "ffn_up_exps", "ffn_down_exps" -> true
            else -> false
        }
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
        val tok = tokenizer ?: throw IllegalStateException("openModel() first")
        return generateFromTokenIds(
            tok.encode(prompt, addBos), maxTokens, sample, shouldStop, onToken
        ).stats
    }

    /** Tokenizes [prompt] with the loaded model's tokenizer. */
    fun encodePrompt(prompt: String, addBos: Boolean = true): IntArray =
        tokenizer?.encode(prompt, addBos) ?: throw IllegalStateException("openModel() first")

    /** Result of [generateFromTokenIds]: run stats plus the generated token ids. */
    data class TokenGeneration(val stats: GenerateStats, val tokenIds: List<Int>)

    /**
     * Token-id entry point (golden harness, programmatic callers): prefills [ids]
     * and continues generation. `onLogits(logits, step)` observes the logits after
     * the last prompt token (step 0) and after each generated token; the array
     * aliases engine scratch and is only valid inside the callback.
     */
    fun generateFromTokenIds(
        ids: IntArray,
        maxTokens: Int = 128,
        sample: SampleConfig = SampleConfig(),
        shouldStop: () -> Boolean = { false },
        onToken: (String) -> Unit = {},
        onLogits: ((logits: FloatArray, step: Int) -> Unit)? = null
    ): TokenGeneration {
        warn("generateFromTokenIds")
        val tr = transformer ?: throw IllegalStateException("openModel() first")
        val tok = tokenizer ?: throw IllegalStateException("openModel() first")
        require(ids.isNotEmpty()) { "empty token id list" }
        val sampler = Sampler(java.util.Random(sample.seed))
        tr.reset()
        val start = System.currentTimeMillis()
        val all = ids.toMutableList()
        val promptTokens = all.size
        // Prefill (no sampling).
        var step: StepResult? = null
        for (id in all) step = tr.decodeStep(id)
        var logits = step?.logits ?: throw IllegalStateException("Empty prompt produced no logits")
        onLogits?.invoke(logits, 0)
        val streamDecoder = TokenStreamDecoder(tok)
        streamDecoder.pushStored(tok.storedBytes(all.toIntArray()))
        var generated = 0
        var stopped = false
        while (generated < maxTokens) {
            if (shouldStop()) {
                stopped = true
                break
            }
            val next = sampler.sample(logits, sample)
            if (next == tok.eosId) break
            all.add(next)
            generated++
            val delta = streamDecoder.pushStored(tok.storedBytes(all.toIntArray()))
            if (delta.isNotEmpty()) onToken(delta)
            logits = tr.decodeStep(next).logits
            onLogits?.invoke(logits, generated)
        }
        streamDecoder.flush().takeIf { it.isNotEmpty() }?.let { onToken(it) }
        val pg = pager?.stats
        return TokenGeneration(
            GenerateStats(
                promptTokens = promptTokens, generatedTokens = generated,
                ms = System.currentTimeMillis() - start, stopped = stopped,
                expertHits = pg?.hits ?: 0, expertMisses = pg?.misses ?: 0,
                expertBytesStreamed = pg?.bytesStreamed ?: 0
            ),
            all.drop(promptTokens)
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
        loaderRef = null
        tileLayouts.clear()
        tileDtypes.clear()
        try {
            reader?.close()
        } catch (_: Exception) {
        }
        reader = null
    }

    // ---- weight binding ----

    /** One dense matrix kept resident exactly as stored (quantized, no F32 expansion). */
    data class QuantTile(val bytes: ByteArray, val dtypeId: Int, val rows: Int, val cols: Int)

    private class ResidentLoader(
        val reader: GgufReader,
        val byName: Map<String, TensorInfo>,
        val cap: Long
    ) {
        var usedBytes: Long = 0
            private set

        /** Raw stored bytes of one matrix; counted 1:1 against the cap. */
        fun rawMatrix(name: String, rows: Int, cols: Int): QuantTile {
            val info = byName[name] ?: throw GgufException("Missing tensor: $name")
            val bytes = reader.tensorBytes(info)
            usedBytes += bytes.size
            if (usedBytes > cap) {
                throw ModelTooLargeException(
                    "Resident ${(usedBytes / 1048576)}MB exceeds cap ${cap / 1048576}MB at $name " +
                            "(experts stay on SSD; shrink the model or raise the cap)"
                )
            }
            return QuantTile(bytes, info.dtypeId, rows, cols)
        }

        /** Tiny vectors (norms) stay F32 — negligible next to the matrices. */
        fun vector(name: String): FloatArray {
            val info = byName[name] ?: throw GgufException("Missing tensor: $name")
            val elements = info.elementCount
            if (elements > Int.MAX_VALUE) throw GgufException("Vector too large: $name")
            val out = FloatArray(elements.toInt())
            val bytes = reader.tensorBytes(info)
            Quant.dequantizeRow(info.dtypeId, bytes, 0, out, 0, out.size)
            usedBytes += out.size * 4L
            if (usedBytes > cap) {
                throw ModelTooLargeException(
                    "Resident ${(usedBytes / 1048576)}MB exceeds cap ${cap / 1048576}MB at $name"
                )
            }
            return out
        }
    }

    private inner class GgufTransformerWeights(
        val reader: GgufReader,
        override val hp: ModelHyperParams,
        val byName: Map<String, TensorInfo>,
        val loader: ResidentLoader,
        val file: File,
        val onTile: (ExpertTileKey, TensorInfo) -> Unit
    ) : TransformerWeights {
        private val tileCache = HashMap<String, QuantTile>()
        private fun t(name: String, rows: Int, cols: Int): QuantTile =
            tileCache.getOrPut(name) { loader.rawMatrix(name, rows, cols) }

        private val vecCache = HashMap<String, FloatArray>()
        private fun v(name: String): FloatArray = vecCache.getOrPut(name) { loader.vector(name) }

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

        private fun fusedTile(name: String, rows: Int, cols: Int, x: FloatArray, y: FloatArray, kernels: Kernels) {
            val tile = t(name, rows, cols)
            kernels.matVecQuant(y, tile.bytes, tile.dtypeId, x, rows, cols)
        }

        /**
         * Single-row gather off a quantized [vocab × dim] matrix: one row's blocks,
         * not the whole (vocab × dim × 4B) table. Without this the embedding alone
         * (e.g. 128k × 2k F32 = 1GB) would defeat expert paging for big MoEs.
         */
        private fun dequantRow(tile: QuantTile, row: Int, out: FloatArray) {
            require(out.size == tile.cols) { "row buffer ${out.size} != cols ${tile.cols}" }
            val dt = GgmlTypes.of(tile.dtypeId)
            if (tile.cols % dt.blockLength != 0) {
                throw GgufException("Embedding width ${tile.cols} not a multiple of ${dt.name} block ${dt.blockLength}")
            }
            when (tile.dtypeId) {
                GgufFormat.F32 -> {
                    val bb = java.nio.ByteBuffer.wrap(tile.bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    for (c in 0 until tile.cols) out[c] = bb.getFloat((row * tile.cols + c) * 4)
                    return
                }
            }
            val blocksPerRow = tile.cols / dt.blockLength
            val rowBase = row.toLong() * blocksPerRow * dt.typeSizeBytes
            if (rowBase + blocksPerRow * dt.typeSizeBytes > tile.bytes.size) {
                throw GgufException("Embedding row $row out of bounds")
            }
            var col = 0
            for (b in 0 until blocksPerRow) {
                Quant.dequantizeRow(
                    tile.dtypeId, tile.bytes, (rowBase + b * dt.typeSizeBytes).toInt(),
                    out, col, dt.blockLength
                )
                col += dt.blockLength
            }
        }

        override fun embed(id: Int): FloatArray {
            val out = FloatArray(hp.dim)
            embedInto(id, out)
            return out
        }

        override fun embedInto(id: Int, out: FloatArray) {
            require(id in 0 until hp.vocab) { "token id $id out of vocab ${hp.vocab}" }
            require(out.size == hp.dim)
            dequantRow(t("token_embd.weight", hp.vocab, hp.dim), id, out)
        }

        override fun attnQ(layer: Int) = dequantFull("blk.$layer.attn_q.weight", hp.nHeads * hp.headDim, hp.dim)
        override fun attnK(layer: Int) = dequantFull("blk.$layer.attn_k.weight", hp.kvDim, hp.dim)
        override fun attnV(layer: Int) = dequantFull("blk.$layer.attn_v.weight", hp.kvDim, hp.dim)
        override fun attnO(layer: Int) = dequantFull("blk.$layer.attn_o.weight", hp.dim, hp.nHeads * hp.headDim)
        override fun attnNorm(layer: Int) = v("blk.$layer.attn_norm.weight")
        override fun ffnNorm(layer: Int) = v("blk.$layer.ffn_norm.weight")
        override fun outNorm() = v("output_norm.weight")
        override fun head(): FloatArray {
            val name = if (byName.containsKey("output.weight")) "output.weight" else "token_embd.weight"
            return dequantFull(name, hp.vocab, hp.dim)
        }

        override fun ffnGate(layer: Int) = dequantFull("blk.$layer.ffn_gate.weight", hp.ffnDim, hp.dim)
        override fun ffnUp(layer: Int) = dequantFull("blk.$layer.ffn_up.weight", hp.ffnDim, hp.dim)
        override fun ffnDown(layer: Int) = dequantFull("blk.$layer.ffn_down.weight", hp.dim, hp.ffnDim)
        override fun router(layer: Int) = dequantFull("blk.$layer.ffn_gate_inp.weight", hp.nExperts, hp.dim)

        /** Materialized copy for interface compat (tests / non-fused kernels). */
        private fun dequantFull(name: String, rows: Int, cols: Int): FloatArray {
            val tile = t(name, rows, cols)
            val out = FloatArray(rows * cols)
            Quant.dequantizeRow(tile.dtypeId, tile.bytes, 0, out, 0, out.size)
            return out
        }

        override fun matVecAttnQ(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.attn_q.weight", hp.nHeads * hp.headDim, hp.dim, x, y, kernels)

        override fun matVecAttnK(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.attn_k.weight", hp.kvDim, hp.dim, x, y, kernels)

        override fun matVecAttnV(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.attn_v.weight", hp.kvDim, hp.dim, x, y, kernels)

        override fun matVecAttnO(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.attn_o.weight", hp.dim, hp.nHeads * hp.headDim, x, y, kernels)

        override fun matVecFfnGate(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.ffn_gate.weight", hp.ffnDim, hp.dim, x, y, kernels)

        override fun matVecFfnUp(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.ffn_up.weight", hp.ffnDim, hp.dim, x, y, kernels)

        override fun matVecFfnDown(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.ffn_down.weight", hp.dim, hp.ffnDim, x, y, kernels)

        override fun matVecRouter(layer: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
            fusedTile("blk.$layer.ffn_gate_inp.weight", hp.nExperts, hp.dim, x, y, kernels)

        override fun matVecHead(x: FloatArray, y: FloatArray, kernels: Kernels) {
            val name = if (byName.containsKey("output.weight")) "output.weight" else "token_embd.weight"
            fusedTile(name, hp.vocab, hp.dim, x, y, kernels)
        }

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

            // Fused path: dot directly off the quantized tile bytes via borrow()
            // (no copy, no full-tile FloatArray). Delegates to the configured
            // kernels instance so future NEON/GPU ports stay fused instead of
            // silently falling back to the materialized path (adversarial finding).
            private fun fused(key: ExpertTileKey, x: FloatArray, y: FloatArray, kernels: Kernels) {
                val bytes = p.borrow(key)
                kernels.matVecQuant(y, bytes, tileDtype(key), x, y.size, x.size)
            }

            override fun matVecGate(e: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
                fused(ExpertTileKey(layer, e, ExpertTileKey.GATE), x, y, kernels)

            override fun matVecUp(e: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
                fused(ExpertTileKey(layer, e, ExpertTileKey.UP), x, y, kernels)

            override fun matVecDown(e: Int, x: FloatArray, y: FloatArray, kernels: Kernels) =
                fused(ExpertTileKey(layer, e, ExpertTileKey.DOWN), x, y, kernels)

            override fun prefetch(experts: IntArray) {
                // Next-layer same-index lookahead: best-effort, failures swallowed
                // inside the pager. Warms the SSD streaming cache by one layer.
                // Flat loop, no intermediate lists: this runs per layer per token.
                // Only IOException is caught: cancellation/interrupts must propagate.
                try {
                    val keys = ArrayList<ExpertTileKey>(experts.size * 3)
                    for (e in experts) {
                        keys.add(ExpertTileKey(layer, e, ExpertTileKey.GATE))
                        keys.add(ExpertTileKey(layer, e, ExpertTileKey.UP))
                        keys.add(ExpertTileKey(layer, e, ExpertTileKey.DOWN))
                    }
                    p.advise(keys)
                } catch (_: java.io.IOException) {
                }
            }
        }
    }
}
