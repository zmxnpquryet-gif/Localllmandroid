package com.localllm.android.model

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
    val downloadEtaSeconds: Int = 0,
    val hfToken: String? = null
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
            description = "초경량 360M 고속 모델. 다운로드가 매우 빠르고 모든 기기에서 쾌속 구동됩니다.",
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
            description = "한국어 및 다국어 지원 0.5B 초경량 모델. 가볍고 빠른 응답 속도를 자랑합니다.",
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
            description = "Meta Llama 3.2 1B 경량 고성능 온디바이스 어시스턴트 모델.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-1.5b-gguf",
            name = "DeepSeek-R1 Distill 1.5B (사고 추론)",
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
            sizeBytes = 1_117_320_736L,
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
            description = "추론 및 지시어 준수 능력이 뛰어난 1.7B 모바일 최적화 모델.",
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
            description = "Google Gemma 2 2B 고성능 모델. Gemma 공식 템플릿 규격 완벽 지원.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "qwen2-vl-2b-instruct-gguf",
            name = "Qwen2-VL 2B (비전 멀티모달)",
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
            description = "이미지 입력 및 분석을 지원하는 공식 Qwen2-VL 멀티모달 비전 모델.",
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
            description = "Google LiteRT LM 엔진 구동용 Qwen 2.5 Coder 1.5B 모델. 공식 Jinja 프롬프트 템플릿(chat_template.jinja) 완벽 지원.",
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
            description = "Google 공식 LiteRT-LM 규격 Gemma 3 1B IT 온디바이스 모델. 584MB 초경량 INT4 양자화 탑재.",
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
            description = "Google 공식 LiteRT Community 모바일 액션 및 함수 호출 초경량 284MB 모델.",
            quantization = "Q8"
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

