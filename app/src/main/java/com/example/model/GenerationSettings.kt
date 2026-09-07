package com.example.model

data class GenerationSettings(
    val runtime: ModelRuntimeType = ModelRuntimeType.LLAMA_CPP,
    val contextWindow: Int = 4096,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val repetitionPenalty: Float = 1.1f,
    val systemPrompt: String = "",
    val enableMtp: Boolean = true, // Multi-token prediction
    val enableVision: Boolean = true, // Load mmproj vision tower
    val reasoningEffort: Float = 0.5f, // 0.2 (Low), 0.5 (Medium), 0.8 (High), 1.0 (Max)
    val showPerformanceMetrics: Boolean = true, // Display TPS and PP speed
    val enableIndexingAcceleration: Boolean = true, // KV-cache / Prompt cache
    val mcpServerUrl: String = "https://mcp.weather.dev/sse",
    val isMcpEnabled: Boolean = false,
    val darkModePreference: String = "dark", // "system", "dark", "light"
    val themeColorName: String = "artistic", // "artistic", "chatgpt", "cyber", "obsidian", "amber", "frost"
    val hfToken: String = "" // Optional Hugging Face Access Token for gated/private models
) {
    val reasoningEffortLabel: String
        get() = when {
            reasoningEffort <= 0.25f -> "낮음 (Low)"
            reasoningEffort <= 0.6f -> "보통 (Medium)"
            reasoningEffort <= 0.85f -> "높음 (High)"
            else -> "최대 (Max)"
        }
}
