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
    /** Native audio input (omni/audio models): hands-free conversation without a separate STT. */
    val supportsAudioInput: Boolean = false,
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
    /**
     * Set when the user picked the runtime by hand. Auto-detection never overwrites
     * an explicit choice, so nobody gets locked into a runtime.
     */
    val runtimeTypeOverrideByUser: Boolean = false
) {
    val displaySize: String
        get() {
            var total = sizeBytes
            if (hasMmproj && visionTowerUrl.isNotBlank()) total += 380_000_000L
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
    val defaultModels = listOf(
        LlmModel(
            id = "lfm3-1.7b-gguf",
            name = "LFM3 1.7B",
            repoId = "LiquidAI/LFM3-1.7B-GGUF",
            fileName = "LFM3-1.7B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LFM3,
            sizeBytes = 1_099_627_808L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/LiquidAI/LFM3-1.7B-GGUF/resolve/main/LFM3-1.7B-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Liquid AI LFM3 1.7B GGUF. Fast, multilingual on-device assistant with native llama.cpp support.",
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
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-LFM3-VL-2B-Q8_0.gguf",
            mainModelUrl = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/LFM3-VL-2B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/LiquidAI/LFM3-VL-2B-GGUF/resolve/main/mmproj-LFM3-VL-2B-Q8_0.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Liquid AI LFM3-VL 2B multimodal model with image understanding and a compact Q8 vision projector.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "functiongemma-mobile-actions-litert",
            name = "FunctionGemma Mobile (LiteRT)",
            repoId = "litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm",
            fileName = "mobile-actions_q8_ekv1024.litertlm",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 284_426_240L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            isBundledModel = true,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/litert-community/functiongemma-mobile-actions_q8_ekv1024.litertlm/resolve/main/mobile-actions_q8_ekv1024.litertlm",
            description = "Google official LiteRT Community ultra-light 284MB model for mobile actions and function calling.",
            quantization = "Q8"
        ),
        LlmModel(
            id = "gemma-4-e2b-it-litert",
            name = "Gemma 4 E2B IT (LiteRT LM, omni)",
            repoId = "litert-community/gemma-4-E2B-it-litert-lm",
            fileName = "gemma-4-E2B-it.litertlm",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 2_588_147_712L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            isBundledModel = false,
            isDownloaded = false,
            // Omni model: audio and vision encoders are loaded on demand, so a
            // hands-free turn can go straight in without a speech recognizer.
            supportsAudioInput = true,
            maxContextTokens = 32_768,
            minDeviceRamMb = 8_192,
            mainModelUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
            description = "Google official Gemma 4 E2B omni model for LiteRT-LM (2.6GB). Text, image and audio input in one file, with the audio encoder loaded on demand for hands-free conversation.",
            quantization = "INT2/4/8"
        ),
        LlmModel(
            id = "qwen3.5-0.8b-litert",
            name = "Qwen 3.5 0.8B (LiteRT)",
            repoId = "litert-community/Qwen3.5-0.8B",
            fileName = "Qwen3.5-0.8B_int8.litertlm",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 964_566_992L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            maxContextTokens = 4_096,
            minDeviceRamMb = 4_096,
            isBundledModel = false,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/litert-community/Qwen3.5-0.8B/resolve/main/Qwen3.5-0.8B_int8.litertlm",
            // Hybrid gated-delta attention: only six layers keep a KV cache (the
            // bundle budgets 4K tokens), so memory stays nearly flat as the chat grows.
            description = "Alibaba Qwen 3.5 0.8B hybrid model in LiteRT-LM form (963MB, text only). Its gated-delta attention keeps memory nearly flat with context, which suits low-RAM phones.",
            quantization = "INT8"
        ),
        LlmModel(
            id = "qwen3.5-2b-gguf",
            name = "Qwen 3.5 2B",
            repoId = "unsloth/Qwen3.5-2B-GGUF",
            fileName = "Qwen3.5-2B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_280_835_840L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            maxContextTokens = 32_768,
            minDeviceRamMb = 4_096,
            isBundledModel = false,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/unsloth/Qwen3.5-2B-GGUF/resolve/main/Qwen3.5-2B-Q4_K_M.gguf",
            // Hybrid gated-delta attention keeps the recurrent state constant-size, so
            // long chats cost far less memory than a same-size dense model.
            description = "Alibaba Qwen 3.5 2B GGUF (1.3GB). Hybrid attention keeps memory flat as context grows, with a thinking mode for harder prompts.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "gemma-4-e4b-it-litert",
            name = "Gemma 4 E4B IT (LiteRT LM, omni)",
            repoId = "litert-community/gemma-4-E4B-it-litert-lm",
            fileName = "gemma-4-E4B-it.litertlm",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 3_659_530_240L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            supportsAudioInput = true,
            maxContextTokens = 32_768,
            minDeviceRamMb = 12_288,
            isBundledModel = false,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
            description = "Google Gemma 4 E4B omni model for LiteRT-LM (3.7GB). Same text, image and audio input as E2B with more capacity for flagship phones.",
            quantization = "INT2/4/8"
        ),
        LlmModel(
            id = "lfm3-omni-2b-gguf",
            name = "LFM3 Omni 2B",
            repoId = "LiquidAI/LFM3-Omni-2B-GGUF",
            fileName = "LFM3-Omni-2B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LFM3,
            sizeBytes = 1_099_630_080L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-LFM3-Omni-2B-Q4_K_M.gguf",
            maxContextTokens = 32_768,
            minDeviceRamMb = 4_096,
            isBundledModel = false,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/LFM3-Omni-2B-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/LiquidAI/LFM3-Omni-2B-GGUF/resolve/main/mmproj-LFM3-Omni-2B-Q4_K_M.gguf",
            // The projector also carries the audio encoder, but this app only feeds
            // audio to LiteRT models today, so voice turns still use speech-to-text.
            description = "Liquid AI LFM3 Omni 2B (1.4GB with projector). Text, image, video and audio in one model; images use the projector and voice turns currently run through speech-to-text.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "minicpm-v-4.6-gguf",
            name = "MiniCPM-V 4.6 (vision)",
            repoId = "openbmb/MiniCPM-V-4.6-gguf",
            fileName = "MiniCPM-V-4_6-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 529_101_504L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-model-f16.gguf",
            maxContextTokens = 32_768,
            minDeviceRamMb = 4_096,
            isBundledModel = false,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/MiniCPM-V-4_6-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/openbmb/MiniCPM-V-4.6-gguf/resolve/main/mmproj-model-f16.gguf",
            // Built on Qwen 3.5 0.8B, so the Qwen/ChatML turn format applies as-is.
            description = "OpenBMB MiniCPM-V 4.6 (0.5GB + 1.1GB projector), the phone-class vision model with official Android demos. Images go through the projector; text uses ChatML.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "minicpm5-1b-gguf",
            name = "MiniCPM5 1B",
            repoId = "openbmb/MiniCPM5-1B-GGUF",
            fileName = "MiniCPM5-1B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.MINICPM5,
            sizeBytes = 688_065_920L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            maxContextTokens = 32_768,
            minDeviceRamMb = 4_096,
            isBundledModel = false,
            isDownloaded = false,
            toolCallTemplate = ToolCallTemplate.MINICPM5,
            mainModelUrl = "https://huggingface.co/openbmb/MiniCPM5-1B-GGUF/resolve/main/MiniCPM5-1B-Q4_K_M.gguf",
            description = "OpenBMB MiniCPM5 1B (0.7GB), an on-device model with native tool calling and a switchable thinking mode. Uses the MiniCPM5 turn format.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "minicpm5-2b-gguf",
            name = "MiniCPM5 2B",
            repoId = "openbmb/MiniCPM5-2B-GGUF",
            fileName = "MiniCPM5-2B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.MINICPM5,
            sizeBytes = 1_561_318_368L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            maxContextTokens = 32_768,
            minDeviceRamMb = 6_144,
            isBundledModel = false,
            isDownloaded = false,
            toolCallTemplate = ToolCallTemplate.MINICPM5,
            mainModelUrl = "https://huggingface.co/openbmb/MiniCPM5-2B-GGUF/resolve/main/MiniCPM5-2B-Q4_K_M.gguf",
            description = "OpenBMB MiniCPM5 2B (1.6GB), a 2B-class model tuned for tool use and long context on phones, with a switchable thinking mode.",
            quantization = "Q4_K_M"
        )
    )
}


