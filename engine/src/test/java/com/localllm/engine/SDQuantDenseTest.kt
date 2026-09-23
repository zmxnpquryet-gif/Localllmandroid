package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quantized-dense residency: dense matrices stay stored-quantized in RAM and
 * compute through fused matvec, so resident bytes track the GGUF payload —
 * not a 4x F32 expansion. A Q8_0 tiny MoE must generate the same greedy token
 * stream as its F32 twin while reporting a smaller resident footprint, and
 * [SDEngine.estimateResident] must split dense vs SSD-streamed expert bytes.
 *
 * Dims are multiples of 32 throughout (Q8_0 block length).
 */
class SDQuantDenseTest {

    companion object {
        private const val VOCAB = 8
        private const val DIM = 32
        private const val FFN = 32
        private const val N_EXPERTS = 4
        private const val TARGET_TOKEN = 5

        private val TOKENS = listOf("<unk>", "<s>", "</s>", "a", "b", "ab", " ", ".")

        private fun matOf(rows: Int, cols: Int, fill: (Int) -> Float = { 0f }) =
            FloatArray(rows * cols, fill)

        fun buildTinyMoe(file: File, quantized: Boolean): File {
            val writer = GgufTestWriter()
                .metaString("general.architecture", "llama")
                .metaU32("general.alignment", 32)
                .metaU32("llama.embedding_length", DIM.toLong())
                .metaU32("llama.block_count", 1)
                .metaU32("llama.feed_forward_length", FFN.toLong())
                .metaU32("llama.attention.head_count", 2)
                .metaU32("llama.attention.head_count_kv", 2)
                .metaU32("llama.attention.key_length", 16)
                .metaU32("llama.expert_count", N_EXPERTS.toLong())
                .metaU32("llama.expert_used_count", 1)
                .metaF32("llama.attention.layer_norm_rms_epsilon", 1e-5f)
                .metaF32("llama.rope.freq_base", 10000f)
                .metaU32("llama.context_length", 256)
                .metaStringArray("tokenizer.ggml.tokens", TOKENS)
                .metaI32("tokenizer.ggml.bos_token_id", 1)
                .metaI32("tokenizer.ggml.eos_token_id", 2)

            fun tensor(name: String, dims: LongArray, values: FloatArray) {
                if (quantized) writer.q80Tensor(name, dims, values)
                else writer.f32Tensor(name, dims, values)
            }

            val embed = FloatArray(VOCAB * DIM)
            for (i in 0 until DIM) embed[TARGET_TOKEN * DIM + i] = 1f
            tensor("token_embd.weight", longArrayOf(VOCAB.toLong(), DIM.toLong()), embed)

            // Norm vectors always stay F32 (mirrors the engine: vectors are tiny).
            writer.f32Tensor("output_norm.weight", longArrayOf(DIM.toLong()), FloatArray(DIM) { 1f })
            writer.f32Tensor("blk.0.attn_norm.weight", longArrayOf(DIM.toLong()), FloatArray(DIM) { 1f })
            writer.f32Tensor("blk.0.ffn_norm.weight", longArrayOf(DIM.toLong()), FloatArray(DIM) { 1f })

            tensor("blk.0.attn_q.weight", longArrayOf(DIM.toLong(), DIM.toLong()), matOf(DIM, DIM))
            tensor("blk.0.attn_k.weight", longArrayOf(DIM.toLong(), DIM.toLong()), matOf(DIM, DIM))
            tensor("blk.0.attn_v.weight", longArrayOf(DIM.toLong(), DIM.toLong()), matOf(DIM, DIM))
            tensor("blk.0.attn_o.weight", longArrayOf(DIM.toLong(), DIM.toLong()), matOf(DIM, DIM))
            tensor(
                "blk.0.ffn_gate_inp.weight",
                longArrayOf(N_EXPERTS.toLong(), DIM.toLong()),
                matOf(N_EXPERTS, DIM)
            )
            val expertTiles = longArrayOf(FFN.toLong(), DIM.toLong(), N_EXPERTS.toLong())
            tensor("blk.0.ffn_gate_exps.weight", expertTiles, matOf(FFN * DIM * N_EXPERTS, 1))
            tensor("blk.0.ffn_up_exps.weight", expertTiles, matOf(FFN * DIM * N_EXPERTS, 1))
            tensor(
                "blk.0.ffn_down_exps.weight",
                longArrayOf(DIM.toLong(), FFN.toLong(), N_EXPERTS.toLong()),
                matOf(DIM * FFN * N_EXPERTS, 1)
            )
            return writer.build(file)
        }

        private fun temporaryModel(quantized: Boolean): File {
            val file = File.createTempFile("sdengine-qdense-$quantized-", ".gguf")
            file.deleteOnExit()
            return buildTinyMoe(file, quantized)
        }

        private fun generateText(file: File): Pair<String, Long> {
            SDEngine().use { engine ->
                engine.openModel(file, residentCapBytes = 64L * 1024L * 1024L)
                val deltas = mutableListOf<String>()
                engine.generate(
                    prompt = "ab",
                    maxTokens = 3,
                    sample = SampleConfig(temperature = 0f),
                    addBos = true,
                    onToken = { deltas.add(it) }
                )
                // Dense tiles load lazily on first decode: read resident after.
                return deltas.joinToString("") to engine.residentBytes()
            }
        }
    }

    @Test
    fun `q80 dense generates the same greedy stream as f32`() {
        val f32 = temporaryModel(quantized = false)
        val q80 = temporaryModel(quantized = true)
        try {
            val (f32Text, f32Resident) = generateText(f32)
            val (q80Text, q80Resident) = generateText(q80)
            assertEquals("ababab", f32Text)
            assertEquals(f32Text, q80Text)
            assertTrue("q80 resident ($q80Resident) must be smaller than f32 ($f32Resident)",
                q80Resident in 1 until f32Resident)
        } finally {
            f32.delete()
            q80.delete()
        }
    }

    @Test
    fun `estimateResident splits dense and expert bytes`() {
        val file = temporaryModel(quantized = true)
        try {
            val est = SDEngine().estimateResident(file)
            assertEquals("llama", est.arch)
            assertEquals(1, est.nLayers)
            assertEquals(N_EXPERTS, est.nExperts)
            assertTrue(est.denseFileBytes > 0)
            assertTrue(est.expertFileBytes > 0)
            GgufReader.open(file).use { reader ->
                val total = reader.tensors.sumOf { it.byteSize }
                assertEquals(total, est.denseFileBytes + est.expertFileBytes)
            }
            // kvBytes(256) = 2 layers? no: 2 * nLayers * kvHeads * headDim * ctx * 4
            assertEquals(2L * 1 * 2 * 16 * 256 * 4, est.kvBytes(256))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `quantized resident still enforces the cap`() {
        val file = temporaryModel(quantized = true)
        try {
            SDEngine().use { engine ->
                engine.openModel(file, residentCapBytes = 64L)
                val failure = runCatching {
                    engine.generate(prompt = "ab", maxTokens = 1, sample = SampleConfig(temperature = 0f))
                }.exceptionOrNull()
                assertTrue("expected ModelTooLargeException, got $failure", failure is ModelTooLargeException)
            }
        } finally {
            file.delete()
        }
    }
}
