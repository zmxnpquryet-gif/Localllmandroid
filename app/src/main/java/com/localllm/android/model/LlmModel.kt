package com.localllm.android.model

enum class ModelRuntimeType(val label: String, val badge: String) {
    LLAMA_CPP("llama.cpp", "GGUF"),
    LITE_RT("LiteRT LM", "LiteRT"),
    /** SDengine: first-party experimental engine (TEST — refuses on-device inference). */
    SD_ENGINE("SDengine (TEST)", "SDengine (TEST)")
}

enum class PromptTemplateType(val id: String, val displayName: String) {
    CHATML("chatml", "ChatML (<|im_start|>)"),
    GEMMA("gemma", "Gemma 2 (<start_of_turn>)"),
    PHI("phi", "Phi-4 (<|user|>)"),
    LLAMA3("llama3", "Llama 3 (<|start_header_id|>)"),
    CUSTOM("custom", "Custom / JSON")
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
     * Set when the user picked the runtime by hand. Auto-detection (MoE GGUF → SDengine)
     * never overwrites an explicit choice, so nobody gets locked into a runtime.
     */
    val runtimeTypeOverrideByUser: Boolean = false
) {
    val displaySize: String
        get() {
            var total = sizeBytes
            if (hasMmproj && visionTowerUrl.isNotBlank()) total += 380_000_000L
            if (supportsMtp && mtpDrafterUrl.isNotBlank()) total += 420_000_000L
            val gb = total / (1024.0 * 1024.0 * 1024.0)
            return if (gb >= 1.0) String.format("%.2f GB", gb)
            else String.format("%.0f MB", total / (1024.0 * 1024.0))
        }

    val runtimeBadge: String
        get() = when (runtimeType) {
            ModelRuntimeType.LLAMA_CPP -> "llama.cpp (GGUF)"
            ModelRuntimeType.LITE_RT -> "LiteRT"
            ModelRuntimeType.SD_ENGINE -> "SDengine (TEST)"
        }
}

/**
 * Curated catalog of real, verified, public Hugging Face GGUF models.
 * Accessible without login or gated tokens.
 */
object ModelCatalog {
    val defaultModels = listOf(
        LlmModel(
            id = "smollm2-360m-instruct-gguf",
            name = "SmolLM2 360M Instruct",
            repoId = "bartowski/SmolLM2-360M-Instruct-GGUF",
            fileName = "SmolLM2-360M-Instruct-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 270_590_880L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/SmolLM2-360M-Instruct-GGUF/resolve/main/SmolLM2-360M-Instruct-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Ultra-light 360M model. Downloads very fast and runs snappily on any device.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2.5-0.5b-instruct-gguf",
            name = "Qwen 2.5 0.5B Instruct",
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 491_400_032L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "0.5B ultra-light model with Korean and multilingual support. Lightweight with fast responses.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "llama-3.2-1b-instruct-gguf",
            name = "Llama 3.2 1B Instruct",
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LLAMA3,
            sizeBytes = 807_694_464L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Meta Llama 3.2 1B lightweight high-performance on-device assistant model.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-1.5b-gguf",
            name = "DeepSeek-R1 Distill 1.5B",
            repoId = "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_117_320_800L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Deep reasoning (CoT) model that generates its own thinking process (<think>) on-device.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2.5-1.5b-instruct-gguf",
            name = "Qwen 2.5 1.5B Instruct",
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_117_320_736L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "1.5B model with high-quality dialogue, coding, and summarization capabilities.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "smollm2-1.7b-instruct-gguf",
            name = "SmolLM2 1.7B Instruct",
            repoId = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_055_609_536L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Mobile-optimized 1.7B model with strong reasoning and instruction-following abilities.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "gemma-2-2b-it-gguf",
            name = "Gemma 2 2B Instruct (GGUF)",
            repoId = "bartowski/gemma-2-2b-it-GGUF",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 1_708_582_752L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "High-performance Google Gemma 2 2B model with full support for the official Gemma template.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2-vl-2b-instruct-gguf",
            name = "Qwen2-VL 2B",
            repoId = "bartowski/Qwen2-VL-2B-Instruct-GGUF",
            fileName = "Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 986_047_232L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-Qwen2-VL-2B-Instruct-f16.gguf",
            mainModelUrl = "https://huggingface.co/bartowski/Qwen2-VL-2B-Instruct-GGUF/resolve/main/Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/bartowski/Qwen2-VL-2B-Instruct-GGUF/resolve/main/mmproj-Qwen2-VL-2B-Instruct-f16.gguf",
            isBundledModel = true,
            isDownloaded = false,
            description = "Official Qwen2-VL multimodal vision model with image input and analysis support.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2.5-coder-1.5b-litert",
            name = "Qwen 2.5 Coder 1.5B (LiteRT)",
            repoId = "4ntoine/Qwen2.5-Coder-1.5B-Instruct-LiteRTLM",
            fileName = "model.litertlm",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_567_489_440L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            templateFileUrl = "https://huggingface.co/itme-brain/Qwen-chat_template.jinja/raw/main/chat_template.jinja",
            templateFileName = "chat_template.jinja",
            mainModelUrl = "https://huggingface.co/4ntoine/Qwen2.5-Coder-1.5B-Instruct-LiteRTLM/resolve/main/model.litertlm",
            isBundledModel = false,
            isDownloaded = false,
            description = "Qwen 2.5 Coder 1.5B for the Google LiteRT LM engine, with full official Jinja prompt template (chat_template.jinja) support.",
            quantization = "INT4"
        ),
        LlmModel(
            id = "gemma-3-1b-it-litert",
            name = "Gemma 3 1B IT (LiteRT LM)",
            repoId = "lotapa/gemma3-1b-it-int4.litertlm",
            fileName = "gemma3-1b-it-int4.litertlm",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 584_417_280L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            isBundledModel = false,
            isDownloaded = false,
            mainModelUrl = "https://huggingface.co/lotapa/gemma3-1b-it-int4.litertlm/resolve/main/gemma3-1b-it-int4.litertlm",
            description = "Google official LiteRT-LM Gemma 3 1B IT on-device model with ultra-light 584MB INT4 quantization.",
            quantization = "INT4"
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
        )
    )
}


