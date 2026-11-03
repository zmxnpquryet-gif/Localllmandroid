package com.localllm.android

import com.localllm.android.data.ModelStorageManager
import com.localllm.android.engine.GgufFeatureDetection
import com.localllm.android.model.ModelRuntimeType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins format-based runtime detection and keeps existing explicit LiteRT choices.
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
    fun `custom gguf with stale alternate runtime and no override resets to llama cpp`() {
        assertEquals(
            ModelRuntimeType.LLAMA_CPP,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 8),
                isCustom = true,
                userOverride = false,
                current = ModelRuntimeType.LITE_RT
            )
        )
    }

    @Test
    fun `explicit user override is never clobbered`() {
        assertEquals(
            ModelRuntimeType.LITE_RT,
            ModelStorageManager.resolveDefaultRuntime(
                detected = detection(expertCount = 8),
                isCustom = true,
                userOverride = true,
                current = ModelRuntimeType.LITE_RT
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
