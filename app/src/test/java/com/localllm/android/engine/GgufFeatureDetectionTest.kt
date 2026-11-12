package com.localllm.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MoE detection on [GgufFeatureDetection].
 *
 * isMoe is driven purely by expertCount (> 1); the architecture is only reported
 * for display, never used to route a model to another engine.
 */
class GgufFeatureDetectionTest {

    private fun detection(expertCount: Int, architecture: String?) = GgufFeatureDetection(
        hasVisionTower = false,
        hasDrafter = false,
        expertCount = expertCount,
        architecture = architecture
    )

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

    @Test
    fun `moe detection does not depend on a known architecture`() {
        // The architecture is informational: a MoE is a MoE whether or not the app
        // recognizes the family, and runtime routing stays container-based.
        assertTrue(detection(expertCount = 8, architecture = null).isMoe)
        assertTrue(detection(expertCount = 8, architecture = "mamba").isMoe)
    }
}
