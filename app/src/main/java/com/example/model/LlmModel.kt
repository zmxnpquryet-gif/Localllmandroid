package com.example.model

enum class ModelRuntimeType(val label: String, val badge: String) {
    LLAMA_CPP("llama.cpp", "GGUF"),
    LITE_RT("LiteRT LM", "LiteRT")
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
    val downloadEtaSeconds: Int = 0
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
            repoId = "HuggingFaceTB/SmolLM2-360M-Instruct-GGUF",
            fileName = "smollm2-360m-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 229_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "초경량 360M 모델. 다운로드가 빠르고 저사양 스마트폰에서도 쾌적하게 구동됩니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "gemma-2-2b-it-litert",
            name = "Gemma 2 2B Instruct (LiteRT)",
            repoId = "google/gemma-2-2b-it",
            fileName = "gemma-2-2b-it.bin",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 1_450_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            templateFileUrl = "https://huggingface.co/google/gemma-2-2b-it/raw/main/tokenizer_config.json",
            templateFileName = "gemma-2-2b-template.json",
            mainModelUrl = "https://huggingface.co/google/gemma-2-2b-it/resolve/main/gemma-2-2b-it.bin",
            isBundledModel = false,
            isDownloaded = false,
            description = "Google Gemma 2 2B 공식 모바일 모델. LiteRT 런타임 및 Gemma 전용 대화 템플릿 지원.",
            quantization = "INT4"
        ),
        LlmModel(
            id = "phi-4-mini-instruct-litert",
            name = "Phi-4 Mini Instruct (LiteRT)",
            repoId = "microsoft/Phi-4-mini-instruct",
            fileName = "phi-4-mini-instruct.bin",
            runtimeType = ModelRuntimeType.LITE_RT,
            promptTemplateType = PromptTemplateType.PHI,
            sizeBytes = 1_850_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            templateFileUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct/raw/main/tokenizer_config.json",
            templateFileName = "phi-4-mini-template.json",
            mainModelUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct/resolve/main/phi-4-mini-instruct.bin",
            isBundledModel = false,
            isDownloaded = false,
            description = "Microsoft Phi-4 Mini 3.8B 경량화 모델. LiteRT 런타임 및 Phi 프롬프트 규격 지원.",
            quantization = "INT4"
        ),
        LlmModel(
            id = "gemma-2-2b-it-gguf",
            name = "Gemma 2 2B Instruct (GGUF)",
            repoId = "bartowski/gemma-2-2b-it-GGUF",
            fileName = "gemma-2-2b-it-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.GEMMA,
            sizeBytes = 1_580_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/gemma-2-2b-it-GGUF/resolve/main/gemma-2-2b-it-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Google Gemma 2 2B GGUF 모델. Gemma 규격 템플릿(<start_of_turn>)을 완벽 지원합니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2.5-0.5b-instruct-gguf",
            name = "Qwen 2.5 0.5B Instruct",
            repoId = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            fileName = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 398_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "한국어 및 다국어 지원 0.5B 모델. 가볍고 빠른 응답 속도를 자랑합니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-1.5b-gguf",
            name = "DeepSeek-R1 Distill 1.5B (사고 추론)",
            repoId = "bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_120_000_000L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "사고 과정(<think>)을 온디바이스에서 생성하는 심층 추론(CoT) 특화 모델.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2.5-1.5b-instruct-gguf",
            name = "Qwen 2.5 1.5B Instruct",
            repoId = "Qwen/Qwen2.5-1.5B-Instruct-GGUF",
            fileName = "qwen2.5-1.5b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 986_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/Qwen/Qwen2.5-1.5B-Instruct-GGUF/resolve/main/qwen2.5-1.5b-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "고품질 한국어 대화 및 코딩, 요약 능력을 갖춘 1.5B 모델.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "llama-3.2-1b-instruct-gguf",
            name = "Llama 3.2 1B Instruct",
            repoId = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.LLAMA3,
            sizeBytes = 800_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "Meta Llama 3.2 1B 경량 고성능 온디바이스 어시스턴트 모델.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "smollm2-1.7b-instruct-gguf",
            name = "SmolLM2 1.7B Instruct",
            repoId = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            promptTemplateType = PromptTemplateType.CHATML,
            sizeBytes = 1_060_000_000L,
            supportsMtp = false,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "추론 및 지시어 준수 능력이 균형 잡힌 1.7B 모바일 최적화 모델.",
            quantization = "Q4_K_M"
        )
    )
}

/**
 * Voice model templates (Korean-capable speech STT/TTS)
 */
data class VoiceModelTemplate(
    val id: String,
    val name: String,
    val type: String, // "STT" or "TTS"
    val repoId: String,
    val fileName: String,
    val sizeText: String,
    val koreanSupport: Boolean = true,
    val isInstalled: Boolean = false
)

object VoiceTemplates {
    val templates = listOf(
        VoiceModelTemplate(
            id = "whisper-tiny-ko",
            name = "Whisper-Tiny (Korean STT)",
            type = "STT",
            repoId = "openai/whisper-tiny",
            fileName = "ggml-tiny.bin",
            sizeText = "75 MB",
            koreanSupport = true,
            isInstalled = false
        ),
        VoiceModelTemplate(
            id = "whisper-base-ko",
            name = "Whisper-Base Korean-Tuned",
            type = "STT",
            repoId = "whisper-korean/whisper-base-ko",
            fileName = "ggml-base-ko.bin",
            sizeText = "142 MB",
            koreanSupport = true,
            isInstalled = false
        ),
        VoiceModelTemplate(
            id = "melo-tts-ko",
            name = "MeloTTS Korean Neural (TTS)",
            type = "TTS",
            repoId = "myshell-ai/MeloTTS-Korean",
            fileName = "melo_tts_ko_fast.onnx",
            sizeText = "110 MB",
            koreanSupport = true,
            isInstalled = false
        ),
        VoiceModelTemplate(
            id = "piper-ko-medium",
            name = "Piper-TTS Korean Voice (TTS)",
            type = "TTS",
            repoId = "rhasspy/piper-voices",
            fileName = "ko_KR-hyunjun-medium.onnx",
            sizeText = "63 MB",
            koreanSupport = true,
            isInstalled = false
        )
    )
}

