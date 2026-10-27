package com.localllm.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gated-expert (MoE) detection and SDengine candidacy on [GgufFeatureDetection].
 *
 * isMoe is driven purely by expertCount (> 1); isSdEngineCandidate additionally
 * requires a known, non-deferred architecture.
 */
class GgufFeatureDetectionTest {

    private fun detection(expertCount: Int, architecture: String?) = GgufFeatureDetection(
        hasVisionTower = false,
        hasDrafter = false,
        expertCount = expertCount,
        architecture = architecture
    )

    // ------------------------------------------------------------------
    // isMoe
    // ------------------------------------------------------------------

    @Test
    fun `expert count of one or below is not moe`() {
        assertFalse(detection(expertCount = 0, architecture = "llama").isMoe)
        assertFalse(detection(expertCount = 1, architecture = "llama").isMoe)
    }

    @Test
    fun `expert count above one is moe`() {
        assertTrue(detection(expertCount = 2, architecture = "qwen2_moe").isMoe)
        assertTrue(detection(expertCount = 8, architecture = "qwen3moe").isMoe)
        assertTrue(detection(expertCount = 256, architecture = "deepseek2").isMoe)
    }

    // ------------------------------------------------------------------
    // isSdEngineCandidate
    // ------------------------------------------------------------------

    @Test
    fun `supported moe architecture is an sdengine candidate`() {
        val detected = detection(expertCount = 8, architecture = "qwen2_moe")
        assertTrue(detected.isMoe)
        assertTrue(detected.isSdEngineCandidate)
    }

    @Test
    fun `deferred architecture is never an sdengine candidate`() {
        // deepseek2 (MLA/compressed-KV) is deferred: stays on llama.cpp.
        val deepseek = detection(expertCount = 8, architecture = "deepseek2")
        assertTrue(deepseek.isMoe)
        assertFalse(deepseek.isSdEngineCandidate)
    }

    @Test
    fun `mamba architecture is never an sdengine candidate`() {
        val mamba = detection(expertCount = 8, architecture = "mamba")
        assertTrue(mamba.isMoe)
        assertFalse(mamba.isSdEngineCandidate)
    }

    @Test
    fun `dense model is never an sdengine candidate`() {
        assertFalse(detection(expertCount = 1, architecture = "qwen2_moe").isSdEngineCandidate)
        assertFalse(detection(expertCount = 0, architecture = "qwen2_moe").isSdEngineCandidate)
    }

    @Test
    fun `missing architecture is never an sdengine candidate`() {
        val detected = detection(expertCount = 8, architecture = null)
        assertTrue(detected.isMoe)
        assertFalse(detected.isSdEngineCandidate)
    }
}
