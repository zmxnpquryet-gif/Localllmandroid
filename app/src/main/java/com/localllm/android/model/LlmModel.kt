package com.localllm.android.model

import java.util.Locale

enum class ModelRuntimeType(val label: String, val badge: String) {
    LLAMA_CPP("llama.cpp", "GGUF"),
    LITE_RT("LiteRT LM", "LiteRT")
}

enum class PromptTemplateType(val id: String, val displayName: String) {
    CHATML("chatml", "ChatML (<|im_start|>)"),
    GEMMA("gemma", "Gemma 2 (<start_of_turn>)"),
    PHI("phi", "Phi-4 (<|user|>)"),
    LLAMA3("llama3", "Llama 3 (<|start_header_id|>)"),
    LFM3("lfm3", "LFM3 (<|im_start|>, <think>)"),
    GEMMA4("gemma4", "Gemma 4 (<|turn>model)"),
    QWEN35("qwen35", "Qwen 3.5 (<|im_start|>, think)"),
    MINICPM5("minicpm5", "MiniCPM5 (<|im_start|> + <function>)"),
    CUSTOM("custom", "Custom / JSON")
}

/**
 * Tool-call dialect a model was trained on. The runtime speaks plain text, so the
 * dialect only decides the block syntax offered in the prompt and then parsed back
 * — every on-device template can still fall back to [HERMES].
 */
enum class ToolCallTemplate(val id: String, val displayName: String, val shortLabel: String) {
    AUTO("auto", "Auto-detect", "Auto"),
    HERMES("hermes", "Hermes <tool_call>", "Hermes"),
    LLAMA3_JSON("llama3_json", "Llama 3 JSON", "Llama 3"),
    MISTRAL("mistral", "Mistral [TOOL_CALLS]", "Mistral"),
    PLAIN_JSON("plain_json", "Plain JSON object", "JSON"),
    MINICPM5("minicpm5", "MiniCPM5 XML <function>", "MiniCPM5"),
    NONE("none", "Disabled", "Off");

    companion object {
        /**
         * Unknown or legacy ids fall back to auto-detection rather than pinning a
         * dialect the model may never have been trained on.
         */
        fun fromId(id: String?): ToolCallTemplate =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

data class LlmModel(
    val id: String,
    val name: String,
    val repoId: String,
    val fileName: String,
    val runtimeType: ModelRuntimeType = ModelRuntimeType.LLAMA_CPP,
    val promptTemplateType: PromptTemplateType = PromptTemplateType.CHATML,
    val sizeBytes: Long,
    val supportsMtp: Boolean = false,
    val supportsReasoning: Boolean = false,
    val hasMmproj: Boolean = false,
    val mmprojSizeBytes: Long = 0L,
    val supportsNativeAudioInput: Boolean = false,
    val supportsNativeAudioOutput: Boolean = false,
    val nativeAudioProtocol: String = "",
    val mmprojFileName: String? = null,
    val isDownloaded: Boolean = false,
    val isVisionDownloaded: Boolean = false,
    val isMtpDownloaded: Boolean = false,
    val downloadProgress: Float = 0f,
    val isDownloading: Boolean = false,
    val downloadSpeedText: String = "",
    val description: String = "",
    val quantization: String = "Q4_K_M",
    /** Context length the model is served with at most; 0 = not stated by the catalog. */
    val maxContextTokens: Int = 0,
    /** Practical minimum device RAM for a usable load; 0 = not stated by the catalog. */
    val minDeviceRamMb: Int = 0,
    /** Tool-call dialect this model was trained on; AUTO detects it from the name. */
    val toolCallTemplate: ToolCallTemplate = ToolCallTemplate.AUTO,
    val localFilePath: String? = null,
    val localMmprojPath: String? = null,
    val localMtpDrafterPath: String? = null,
    
    // Direct Download Links
    val mainModelUrl: String = "",
    val visionTowerUrl: String = "",
    val mtpDrafterUrl: String = "",
    val mtpDrafterFileName: String? = null,
    val templateFileUrl: String = "",
    val templateFileName: String? = null,
    val localTemplatePath: String? = null,
    val isTemplateDownloaded: Boolean = false,
    val isBundledModel: Boolean = false,
    val downloadStatus: String = "IDLE", // IDLE, DOWNLOADING, COMPLETED, FAILED
    val mainDownloadProgress: Float = 0f,
    val visionDownloadProgress: Float = 0f,
    val mtpDownloadProgress: Float = 0f,
    val templateDownloadProgress: Float = 0f,
    val downloadEtaSeconds: Int = 0,
    val hfToken: String? = null,
    /** Preserves an explicit per-model choice when metadata is reconciled. */
    val runtimeTypeOverrideByUser: Boolean = false
) {
    val displaySize: String
        get() {
            var total = sizeBytes
            if (hasMmproj && visionTowerUrl.isNotBlank()) total += if (mmprojSizeBytes > 0L) mmprojSizeBytes else 380_000_000L
            if (supportsMtp && mtpDrafterUrl.isNotBlank()) total += 420_000_000L
            val gb = total / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
            else String.format(Locale.US, "%.0f MB", total / (1024.0 * 1024.0))
        }

    val runtimeBadge: String
        get() = when (runtimeType) {
            ModelRuntimeType.LLAMA_CPP -> "llama.cpp (GGUF)"
            ModelRuntimeType.LITE_RT -> "LiteRT"
        }
}

/**
 * Curated catalog of real, verified, public Hugging Face GGUF models.
 * Accessible without login or gated tokens.
 */
object ModelCatalog {
    /**
     * Curated 2026 small-model catalog. Sizes and filenames are taken from the
     * official or verified public GGUF repositories. Audio/video capabilities
     * describe the model contract; the runtime still checks the downloaded
     * projector and native backend before enabling them.
     */
    val defaultModels = listOf(
        LlmModel(
            id = "lfm3-omni-2b-native-audio",
            name = "LFM3 Omni 2B · Native Voice",
            repoId = "LiquidAI/LFM3-Omni-2B-GGUF",
            fileName = "LFM3-Omni-2B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LFM3,
            sizeBytes = 1_099_630_080L,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-LFM3-Omni-2B-Q8_0.gguf",
            mmprojSizeBytes = 506_842_528L,
            supportsNativeAudioInput = true,
            supportsNativeAudioOutput = true,
            nativeAudioProtocol = "LFM3-Omni / PCM16 16kHz input / 24kHz output",
            mainModelUrl = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/LFM3-Omni-2B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/mmproj-LFM3-Omni-2B-Q8_0.gguf",
            description = "Liquid AI의 2.17B end-to-end Omni 모델. 텍스트·이미지와 16kHz 음성을 직접 이해하고 텍스트와 24kHz 음성으로 응답합니다. Q8_0 프로젝터는 공식 llama.cpp 가이드가 사용하는 구성입니다. LFM Open License v1.0.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "lfm3-1.7b-gguf",
            name = "LFM3 1.7B",
            repoId = "LiquidAI/LFM3-1.7B-GGUF",
            fileName = "LFM3-1.7B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LFM3,
            sizeBytes = 1_099_627_808L,
            supportsReasoning = true,
            mainModelUrl = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/main/LFM3-1.7B-Q4_K_M.gguf",
            description = "Liquid AI LFM3 1.7B text model. Fast multilingual on-device assistant with Korean support and modern tool-capable ChatML templates. LFM Open License v1.0.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "lfm3-vl-2b-gguf",
            name = "LFM3-VL 2B",
            repoId = "LiquidAI/LFM3-VL-2B-GGUF",
            fileName = "LFM3-VL-2B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LFM3,
            sizeBytes = 1_099_627_808L,
            supportsReasoning = true,
            hasMmproj = true,
            mmprojFileName = "mmproj-LFM3-VL-2B-Q8_0.gguf",
            mmprojSizeBytes = 203_031_872L,
            mainModelUrl = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/LFM3-VL-2B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/mmproj-LFM3-VL-2B-Q8_0.gguf",
            description = "Liquid AI LFM3-VL 2B vision-language model with native image input and a compact Q8 vision projector. LFM Open License v1.0.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen3.5-0.8b-gguf",
            name = "Qwen 3.5 0.8B",
            repoId = "unsloth/Qwen3.5-0.8B-GGUF",
            fileName = "Qwen3.5-0.8B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.QWEN35,
            sizeBytes = 532_517_120L,
            supportsReasoning = true,
            hasMmproj = true,
            mmprojFileName = "mmproj-F16.gguf",
            mmprojSizeBytes = 204_987_232L,
            mainModelUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/mmproj-F16.gguf",
            description = "Qwen 3.5 0.8B Q4_K_M. 2026 compact hybrid-reasoning model with image input and Korean support. Verified Unsloth GGUF; Apache-2.0.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen3.5-2b-gguf",
            name = "Qwen 3.5 2B",
            repoId = "unsloth/Qwen3.5-2B-GGUF",
            fileName = "Qwen3.5-2B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.QWEN35,
            sizeBytes = 1_280_835_840L,
            supportsReasoning = true,
            hasMmproj = true,
            mmprojFileName = "mmproj-F16.gguf",
            mmprojSizeBytes = 668_227_264L,
            mainModelUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/mmproj-F16.gguf",
            description = "Qwen 3.5 2B Q4_K_M. Balanced phone-class model with adaptive reasoning, image understanding, tools and multilingual output. Apache-2.0.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen3.5-4b-gguf",
            name = "Qwen 3.5 4B",
            repoId = "unsloth/Qwen3.5-4B-GGUF",
            fileName = "Qwen3.5-4B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.QWEN35,
            sizeBytes = 2_740_937_888L,
            supportsReasoning = true,
            hasMmproj = true,
            mmprojFileName = "mmproj-F16.gguf",
            mmprojSizeBytes = 672_423_616L,
            mainModelUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/Qwen3.5-4B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/unsloth/Qwen3.5-4B-GGUF/resolve/main/mmproj-F16.gguf",
            description = "Qwen 3.5 4B Q4_K_M. Higher-quality phone/tablet model with reasoning, vision and tools. Needs more RAM than the 0.8B/2B variants. Apache-2.0.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "gemma-4-e2b-it-qat-gguf",
            name = "Gemma 4 E2B IT · QAT",
            repoId = "google/gemma-4-E2B-it-qat-q4_0-gguf",
            fileName = "gemma-4-E2B_q4_0-it.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.GEMMA4,
            sizeBytes = 3_349_516_256L,
            supportsReasoning = true,
            hasMmproj = true,
            mmprojFileName = "gemma-4-E2B-it-mmproj.gguf",
            mmprojSizeBytes = 986_833_664L,
            mainModelUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B_q4_0-it.gguf",
            visionTowerUrl = "https://huggingface.co/google/gemma-4-E2B-it-qat-q4_0-gguf/resolve/main/gemma-4-E2B-it-mmproj.gguf",
            description = "Google Gemma 4 E2B instruction model, official QAT q4_0 GGUF. Efficient on-device agent with reasoning and vision input. Voice can use the separate STT pipeline. Apache-2.0.",
            quantization = "Q4_0"
        ),
        LlmModel(
            id = "gemma-4-e4b-it-qat-gguf",
            name = "Gemma 4 E4B IT · QAT",
            repoId = "google/gemma-4-E4B-it-qat-q4_0-gguf",
            fileName = "gemma-4-E4B_q4_0-it.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.GEMMA4,
            sizeBytes = 5_154_941_280L,
            supportsReasoning = true,
            hasMmproj = true,
            mmprojFileName = "gemma-4-E4B-it-mmproj.gguf",
            mmprojSizeBytes = 991_552_256L,
            mainModelUrl = "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B_q4_0-it.gguf",
            visionTowerUrl = "https://huggingface.co/google/gemma-4-E4B-it-qat-q4_0-gguf/resolve/main/gemma-4-E4B-it-mmproj.gguf",
            description = "Google Gemma 4 E4B instruction model, official QAT q4_0 GGUF. Larger reasoning and vision capable variant for tablets and high-memory phones. Voice can use the separate STT pipeline. Apache-2.0.",
            quantization = "Q4_0"
        )
    )
}
