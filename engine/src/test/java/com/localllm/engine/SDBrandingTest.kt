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
}
