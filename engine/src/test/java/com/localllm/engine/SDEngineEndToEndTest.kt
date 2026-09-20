package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * End-to-end SDengine run over a synthetic gated-expert MoE GGUF: open the model,
 * stream tokens through [SDEngine.generate], honour cancellation, and keep the
 * resident-memory cap enforced. This is the on-device path the app now routes
 * auto-detected MoE models to.
 */
class SDEngineEndToEndTest {

    companion object {
        private const val VOCAB = 8
        private const val DIM = 16
        private const val FFN = 8
        private const val N_EXPERTS = 4
        private const val TARGET_TOKEN = 5

        private val TOKENS = listOf("<unk>", "<s>", "</s>", "a", "b", "ab", " ", ".")

        /**
         * Every weight is zero except the target token embedding, so with greedy
         * sampling the argmax is the target token and the output is deterministic.
         */
        fun buildTinyMoe(file: File): File {
            val writer = GgufTestWriter()
                .metaString("general.architecture", "llama")
                .metaU32("general.alignment", 32)
                .metaU32("llama.embedding_length", DIM.toLong())
                .metaU32("llama.block_count", 1)
                .metaU32("llama.feed_forward_length", FFN.toLong())
                .metaU32("llama.attention.head_count", 2)
                .metaU32("llama.attention.head_count_kv", 2)
                .metaU32("llama.attention.key_length", 8)
                .metaU32("llama.expert_count", N_EXPERTS.toLong())
                .metaU32("llama.expert_used_count", 1)
                .metaF32("llama.attention.layer_norm_rms_epsilon", 1e-5f)
                .metaF32("llama.rope.freq_base", 10000f)
                .metaU32("llama.context_length", 256)
                .metaStringArray("tokenizer.ggml.tokens", TOKENS)
                .metaI32("tokenizer.ggml.bos_token_id", 1)
                .metaI32("tokenizer.ggml.eos_token_id", 2)

            val embed = FloatArray(VOCAB * DIM)
            for (i in 0 until DIM) embed[TARGET_TOKEN * DIM + i] = 1f
            writer.f32Tensor("token_embd.weight", longArrayOf(VOCAB.toLong(), DIM.toLong()), embed)

            writer.f32Tensor("output_norm.weight", longArrayOf(DIM.toLong()), FloatArray(DIM) { 1f })
            writer.f32Tensor("blk.0.attn_norm.weight", longArrayOf(DIM.toLong()), FloatArray(DIM) { 1f })
            writer.f32Tensor("blk.0.ffn_norm.weight", longArrayOf(DIM.toLong()), FloatArray(DIM) { 1f })
            writer.f32Tensor("blk.0.attn_q.weight", longArrayOf(DIM.toLong(), DIM.toLong()), FloatArray(DIM * DIM))
            writer.f32Tensor("blk.0.attn_k.weight", longArrayOf(DIM.toLong(), DIM.toLong()), FloatArray(DIM * DIM))
            writer.f32Tensor("blk.0.attn_v.weight", longArrayOf(DIM.toLong(), DIM.toLong()), FloatArray(DIM * DIM))
            writer.f32Tensor("blk.0.attn_o.weight", longArrayOf(DIM.toLong(), DIM.toLong()), FloatArray(DIM * DIM))
            writer.f32Tensor(
                "blk.0.ffn_gate_inp.weight",
                longArrayOf(N_EXPERTS.toLong(), DIM.toLong()),
                FloatArray(N_EXPERTS * DIM)
            )
            val expertTiles = longArrayOf(FFN.toLong(), DIM.toLong(), N_EXPERTS.toLong())
            writer.f32Tensor("blk.0.ffn_gate_exps.weight", expertTiles, FloatArray(FFN * DIM * N_EXPERTS))
            writer.f32Tensor("blk.0.ffn_up_exps.weight", expertTiles, FloatArray(FFN * DIM * N_EXPERTS))
            writer.f32Tensor(
                "blk.0.ffn_down_exps.weight",
                longArrayOf(DIM.toLong(), FFN.toLong(), N_EXPERTS.toLong()),
                FloatArray(DIM * FFN * N_EXPERTS)
            )
            return writer.build(file)
        }

        private fun temporaryModel(): File {
            val file = File.createTempFile("sdengine-moe-", ".gguf")
            file.deleteOnExit()
            return buildTinyMoe(file)
        }
    }

    @Test
    fun `moe gguf opens and streams deterministic tokens`() {
        val file = temporaryModel()
        try {
            SDEngine().use { engine ->
                val info = engine.openModel(file, residentCapBytes = 64L * 1024L * 1024L)
                assertEquals("llama", info.arch)
                assertEquals(1, info.nLayers)
                assertEquals(N_EXPERTS, info.nExperts)
                assertEquals(VOCAB, info.vocabSize)
                assertNotNull("the MoE pager must be attached", engine.expertStats())

                val deltas = mutableListOf<String>()
                val stats = engine.generate(
                    prompt = "ab",
                    maxTokens = 3,
                    sample = SampleConfig(temperature = 0f),
                    addBos = true,
                    onToken = { deltas.add(it) }
                )

                assertEquals(3, stats.generatedTokens)
                assertEquals("ababab", deltas.joinToString(""))
                assertFalse(stats.stopped)
                assertTrue(stats.promptTokens >= 2)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `shouldStop cancels the decode loop and is reported`() {
        val file = temporaryModel()
        try {
            SDEngine().use { engine ->
                engine.openModel(file, residentCapBytes = 64L * 1024L * 1024L)
                var checks = 0
                val stats = engine.generate(
                    prompt = "ab",
                    maxTokens = 16,
                    sample = SampleConfig(temperature = 0f),
                    shouldStop = { checks++ >= 1 }
                )
                assertTrue(stats.stopped)
                assertEquals(1, stats.generatedTokens)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `resident cap rejection surfaces as ModelTooLargeException`() {
        val file = temporaryModel()
        try {
            SDEngine().use { engine ->
                engine.openModel(file, residentCapBytes = 64L)
                val failure = runCatching {
                    engine.generate(prompt = "ab", maxTokens = 1, sample = SampleConfig(temperature = 0f))
                }.exceptionOrNull()
                assertTrue(
                    "expected ModelTooLargeException, got $failure",
                    failure is ModelTooLargeException
                )
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `expert tiles stay contiguous so the pager can stream them`() {
        val file = temporaryModel()
        try {
            GgufReader.open(file).use { reader ->
                assertEquals(4L, reader.archU32("expert_count"))
                val tensor = reader.tensors.first { it.name == "blk.0.ffn_gate_exps.weight" }
                assertEquals(listOf(FFN.toLong(), DIM.toLong(), N_EXPERTS.toLong()), tensor.dims.toList())
                val tile = expertTileRange(tensor, 2, N_EXPERTS.toLong())
                val tileBytes = FFN.toLong() * DIM * 4L
                assertEquals(tensor.dataOffset + 2 * tileBytes, tile.offset)
                assertEquals(tileBytes, tile.length)
            }
        } finally {
            file.delete()
        }
    }
}
