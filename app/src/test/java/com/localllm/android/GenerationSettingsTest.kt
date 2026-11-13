package com.localllm.android

import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.KvCacheQuantization
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.model.ToolCallTemplate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationSettingsTest {

    @Test
    fun defaultSettings_hasLocalLoopbackBindAddress() {
        val settings = GenerationSettings()
        assertEquals("127.0.0.1", settings.apiServerBindAddress)
        assertFalse(settings.isApiExternalAccessEnabled)
    }

    @Test
    fun externalAccessEnabled_whenBindAddressIsAllInterfaces() {
        val settings = GenerationSettings(apiServerBindAddress = "0.0.0.0")
        assertEquals("0.0.0.0", settings.apiServerBindAddress)
        assertTrue(settings.isApiExternalAccessEnabled)
    }

    @Test
    fun copySettings_togglesExternalAccessCorrectly() {
        val initial = GenerationSettings()
        assertFalse(initial.isApiExternalAccessEnabled)

        val enabled = initial.copy(apiServerBindAddress = "0.0.0.0")
        assertTrue(enabled.isApiExternalAccessEnabled)

        val disabled = enabled.copy(apiServerBindAddress = "127.0.0.1")
        assertFalse(disabled.isApiExternalAccessEnabled)
    }

    @Test
    fun onlySupportedProductionRuntimesAreExposed() {
        assertEquals(setOf(ModelRuntimeType.LLAMA_CPP, ModelRuntimeType.LITE_RT), ModelRuntimeType.entries.toSet())
    }

    @Test
    fun catalogSchema_hasSafeDefaultsAndParsesToolCallDialects() {
        val probe = LlmModel(
            id = "probe",
            name = "probe",
            repoId = "",
            fileName = "probe.gguf",
            sizeBytes = 1L
        )
        assertEquals(0, probe.maxContextTokens)
        assertEquals(0, probe.minDeviceRamMb)
        assertFalse(probe.supportsNativeAudioInput)
        assertEquals(ToolCallTemplate.AUTO, probe.toolCallTemplate)
        // Unknown/legacy ids fall back to auto-detection.
        assertEquals(ToolCallTemplate.AUTO, ToolCallTemplate.fromId(null))
        assertEquals(ToolCallTemplate.AUTO, ToolCallTemplate.fromId("not-a-dialect"))
        assertEquals(ToolCallTemplate.MISTRAL, ToolCallTemplate.fromId("mistral"))
    }

    @Test
    fun lowEndToggles_defaultToFullPrompt() {
        val settings = GenerationSettings()
        assertTrue(settings.toolCallsEnabled)
        assertTrue(settings.builtInSystemPromptEnabled)
        assertFalse(settings.copy(toolCallsEnabled = false).toolCallsEnabled)
        assertFalse(settings.copy(builtInSystemPromptEnabled = false).builtInSystemPromptEnabled)
    }

    @Test
    fun memoryControls_defaultToLoadingExactlyWhatIsConfigured() {
        val settings = GenerationSettings()
        assertEquals(KvCacheQuantization.F16, settings.kvCacheQuantization)
        assertFalse(settings.ignoreMemoryLimits)
        assertTrue(settings.copy(ignoreMemoryLimits = true).ignoreMemoryLimits)

        // Lossless is the default; lower precision trades RAM for a little quality.
        assertEquals(2.0, KvCacheQuantization.F16.bytesPerElement, 0.0)
        assertEquals(34.0 / 32.0, KvCacheQuantization.Q8_0.bytesPerElement, 0.0)
        assertEquals(18.0 / 32.0, KvCacheQuantization.Q4_0.bytesPerElement, 0.0)

        // ggml_type ids the native bridge accepts (F16 / Q8_0 / Q4_0).
        assertEquals(1, KvCacheQuantization.F16.ggmlTypeId)
        assertEquals(8, KvCacheQuantization.Q8_0.ggmlTypeId)
        assertEquals(2, KvCacheQuantization.Q4_0.ggmlTypeId)

        assertEquals(KvCacheQuantization.Q4_0, KvCacheQuantization.fromId("q4_0"))
        assertEquals(KvCacheQuantization.F16, KvCacheQuantization.fromId("bogus"))
        assertEquals(KvCacheQuantization.F16, KvCacheQuantization.fromId(null))
    }
}
