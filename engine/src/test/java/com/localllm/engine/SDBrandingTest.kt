// SDengine — TEST BUILD. Branding/advisory guard: the TEST labels must stay loud.
package com.localllm.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SDBrandingTest {

    @Test
    fun `engine name and stage are stamped`() {
        assertEquals("SDengine", SDEngine.ENGINE_NAME)
        assertEquals("TEST", SDEngine.STAGE)
    }

    @Test
    fun `advisories are loud and non-empty`() {
        assertTrue(SDEngine.ADVISORIES.size >= 3)
        val text = SDEngine.advisoryText()
        assertTrue(text.contains("SDengine"))
        assertTrue(text.contains("TEST"))
        for (advisory in SDEngine.ADVISORIES) {
            assertTrue(advisory.isNotBlank())
        }
    }

    @Test
    fun `scope is moe-only with explicit deferrals`() {
        assertTrue(SDEngine.SUPPORTED_PATTERNS.isNotEmpty())
        assertTrue(SDEngine.SUPPORTED_PATTERNS.any { it.contains("MoE") || it.contains("MoE-only") || it.contains("gated-expert") })
        assertTrue(SDEngine.DEFERRED.keys.any { it.startsWith("qwen4_exp") })
        assertTrue(SDEngine.DEFERRED.keys.any { it.contains("Mamba") })
        assertTrue(SDEngine.DEFERRED.keys.any { it.contains("LFM2") })
    }
}
