package com.localllm.android.engine.llama

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LlamaRuntimeInstrumentedTest {

    @Test
    fun packagedRuntimeReportsItsRealCapabilities() {
        val capabilities = LlamaRuntime.capabilities()
        assertNotNull(capabilities.systemInfo)
        assertTrue(capabilities.systemInfo.isNotBlank())
        // The checked-in CMake file builds CPU only. If acceleration is added later,
        // this assertion and the user-facing runtime copy must be updated together.
        assertFalse(capabilities.gpuOffload)
    }
}
