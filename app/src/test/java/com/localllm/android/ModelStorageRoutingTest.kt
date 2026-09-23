package com.localllm.android

import com.localllm.android.data.ModelStorageManager
import com.localllm.android.engine.GgufFeatureDetection
import com.localllm.android.model.ModelRuntimeType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the default-to-llama.cpp routing rule (P2-7): auto-detection never
 * assigns SDengine on its own, even for gated-expert MoE. SDengine is strictly
 * opt-in via [com.localllm.android.model.LlmModel.runtimeTypeOverrideByUser].
 */
class ModelStorageRoutingTest {

    private fun detection(
        expertCount: Int,
        architecture: String? = "qwen3moe",
        runtime: ModelRuntimeType = ModelRuntimeType.LLAMA_CPP
    ) = GgufFeatureDetection(
        hasVisionTower = false,
        hasDrafter = false,
        detectedRuntime = runtime,
        expertCount = expertCount,
        architecture = architecture
    )

    @Test
    fun `moe without override defaults to llama cpp`() {
        assertEquals(
            ModelRuntimeType.LLAMA_CPP,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 8),
                isCustom = true,
                userOverride = false,
                current = ModelRuntimeType.LLAMA_CPP
            )
        )
    }

    @Test
    fun `moe with prior sdengine stored but no override is reset to llama cpp`() {
        // Pre-change metadata may carry SD_ENGINE from the auto-assign era.
        assertEquals(
            ModelRuntimeType.LLAMA_CPP,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 8),
                isCustom = true,
                userOverride = false,
                current = ModelRuntimeType.SD_ENGINE
            )
        )
    }

    @Test
    fun `explicit user override to sdengine is never clobbered`() {
        assertEquals(
            ModelRuntimeType.SD_ENGINE,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 8),
                isCustom = true,
                userOverride = true,
                current = ModelRuntimeType.SD_ENGINE
            )
        )
    }

    @Test
    fun `litert containers stay on litert`() {
        assertEquals(
            ModelRuntimeType.LITE_RT,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 0, runtime = ModelRuntimeType.LITE_RT),
                isCustom = true,
                userOverride = false,
                current = ModelRuntimeType.LLAMA_CPP
            )
        )
    }

    @Test
    fun `catalog models keep their shipped runtime`() {
        assertEquals(
            ModelRuntimeType.LLAMA_CPP,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 0),
                isCustom = false,
                userOverride = false,
                current = ModelRuntimeType.LLAMA_CPP
            )
        )
    }
}
