package com.localllm.android.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GGUF expert metadata remains descriptive and does not select a runtime.
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

}
