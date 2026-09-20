package com.localllm.android.engine

import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gated-expert MoE GGUFs are routed to SDengine; deferred architectures are not. */
class MoeRoutingTest {

    private fun detection(expertCount: Int, architecture: String?) = GgufFeatureDetection(
        hasVisionTower = false,
        hasDrafter = false,
        expertCount = expertCount,
        architecture = architecture
    )

    @Test
    fun `gated expert moe is an sdengine candidate`() {
        val detected = detection(expertCount = 8, architecture = "qwen3moe")
        assertTrue(detected.isMoe)
        assertTrue(detected.isSdEngineCandidate)
    }

    @Test
    fun `dense model is not routed to sdengine`() {
        val detected = detection(expertCount = 0, architecture = "llama")
        assertFalse(detected.isMoe)
        assertFalse(detected.isSdEngineCandidate)
    }

    @Test
    fun `deferred architectures stay on llama cpp`() {
        listOf("deepseek2", "deepseek3", "qwen4_exp", "mamba", "lfm2").forEach { arch ->
            val detected = detection(expertCount = 8, architecture = arch)
            assertTrue("$arch should still be counted as MoE", detected.isMoe)
            assertFalse("$arch must not be routed to SDengine", detected.isSdEngineCandidate)
        }
    }

    @Test
    fun `unknown architecture is not routed without metadata`() {
        assertFalse(detection(expertCount = 8, architecture = null).isSdEngineCandidate)
    }

    @Test
    fun `auto detection never claims a user override`() {
        val model = LlmModel(
            id = "custom-1",
            name = "imported",
            repoId = "local/imported",
            fileName = "model.gguf",
            runtimeType = ModelRuntimeType.SD_ENGINE,
            sizeBytes = 1L
        )
        assertFalse(model.runtimeTypeOverrideByUser)
    }
}
