package com.localllm.android

import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
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
    fun sdEngine_isLabeledTestEverywhere() {
        assertEquals("SDengine (TEST)", ModelRuntimeType.SD_ENGINE.label)
        assertEquals("SDengine (TEST)", ModelRuntimeType.SD_ENGINE.badge)
        val probe = LlmModel(
            id = "probe",
            name = "probe",
            repoId = "",
            fileName = "probe.gguf",
            runtimeType = ModelRuntimeType.SD_ENGINE,
            sizeBytes = 1L
        )
        assertEquals("SDengine (TEST)", probe.runtimeBadge)
        val sdSettings = GenerationSettings().copy(runtime = ModelRuntimeType.SD_ENGINE)
        assertEquals(ModelRuntimeType.SD_ENGINE, sdSettings.runtime)
    }
}
