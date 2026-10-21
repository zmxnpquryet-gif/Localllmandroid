package com.localllm.android.model

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

data class GenerationSettings(
    val runtime: ModelRuntimeType = ModelRuntimeType.LLAMA_CPP,
    val contextWindow: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.1f,
    val enableMtp: Boolean = true, // Multi-token prediction
    val reasoningEffort: Float = 0.5f, // 0.2 (Low), 0.5 (Medium), 0.8 (High), 1.0 (Max)
    val showPerformanceMetrics: Boolean = true, // Display TPS and PP speed
    val enableIndexingAcceleration: Boolean = true, // KV-cache / Prompt cache
    val enableGpuAcceleration: Boolean = true, // Hardware GPU/NPU acceleration
    val gpuLayers: Int = 99, // Offloaded GPU layers for llama.cpp (0 = CPU only, 99 = full offload)
    val apiServerBindAddress: String = "127.0.0.1", // "127.0.0.1" (secure local) or "0.0.0.0" (LAN)
    val apiServerRequireAuth: Boolean = true, // Require Bearer API key for OllamaApiServer
    val mcpServerUrl: String = "",
    val isMcpEnabled: Boolean = false,
    val darkModePreference: String = "dark", // "system", "dark", "light"
    val themeColorName: String = "artistic", // "artistic", "liquid", "chatgpt", "cyber", "obsidian", "amber", "frost"
    val languagePreference: String = "system", // "system", "en", "ko", "zh"
    val hfSource: String = "official", // "official", "mirror" — 模型下载源
    val hfToken: String = "" // Optional Hugging Face Access Token for gated/private models
) {
    val isApiExternalAccessEnabled: Boolean
        get() = apiServerBindAddress == "0.0.0.0"

    val reasoningEffortLabel: String
        get() = when {
            reasoningEffort <= 0.25f -> AppStrings.get(R.string.gen_effort_low)
            reasoningEffort <= 0.6f -> AppStrings.get(R.string.gen_effort_medium)
            reasoningEffort <= 0.85f -> AppStrings.get(R.string.gen_effort_high)
            else -> AppStrings.get(R.string.gen_effort_max)
        }
}
