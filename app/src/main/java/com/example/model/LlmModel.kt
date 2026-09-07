package com.example.model

enum class ModelRuntimeType(val label: String, val badge: String) {
    LLAMA_CPP("llama.cpp", "GGUF"),
    LITE_RT("LiteRT LM", "LiteRT")
}

data class LlmModel(
    val id: String,
    val name: String,
    val repoId: String,
    val fileName: String,
    val runtimeType: ModelRuntimeType,
    val sizeBytes: Long,
    val supportsMtp: Boolean = false, // Multi-Token Prediction / Speculative Decoding
    val supportsReasoning: Boolean = false, // DeepSeek-R1 / Qwen-R1 reasoning
    val hasMmproj: Boolean = false, // Vision support (mmproj)
    val mmprojFileName: String? = null,
    val isDownloaded: Boolean = false, // Default: NOT downloaded per user request
    val isVisionDownloaded: Boolean = false,
    val isMtpDownloaded: Boolean = false,
    val downloadProgress: Float = 0f, // 0.0 to 1.0
    val isDownloading: Boolean = false,
    val downloadSpeedText: String = "",
    val description: String = "",
    val quantization: String = "Q4_K_M",
    val localFilePath: String? = null,
    val localMmprojPath: String? = null,
    val localMtpDrafterPath: String? = null,
    
    // FDM (Free Download Manager) Direct Download Links & 3-in-1 Bundling
    val mainModelUrl: String = "",
    val visionTowerUrl: String = "",
    val mtpDrafterUrl: String = "",
    val mtpDrafterFileName: String? = null,
    val templateFileUrl: String = "",
    val templateFileName: String? = null,
    val localTemplatePath: String? = null,
    val isTemplateDownloaded: Boolean = false,
    val isBundledModel: Boolean = false,
    val downloadStatus: String = "IDLE", // IDLE, DOWNLOADING, PAUSED, COMPLETED, FAILED
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

    /**
     * Labels for bundled components currently mounted together
     */
    val bundleDescriptionBadge: String
        get() {
            val list = mutableListOf<String>()
            list.add("Main: ${fileName.take(16)}")
            if (hasMmproj) list.add("Vision: mmproj")
            if (supportsMtp) list.add("MTP Drafter: 2x")
            return list.joinToString(" + ")
        }
}

/**
 * Curated catalog of latest mobile-optimized models supporting llama.cpp (GGUF) and LiteRT.
 * Configured with Hugging Face direct download links for Main Model, Vision Tower, and MTP Drafter.
 * All models must be explicitly downloaded via the FDM bundle downloader.
 */
object ModelCatalog {
    val defaultModels = listOf(
        LlmModel(
            id = "qwen2.5-3b-bundle-gguf",
            name = "Qwen 2.5 3B (Vision + Drafter)",
            repoId = "Qwen/Qwen2.5-3B-Instruct-GGUF",
            fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 2_150_000_000L,
            supportsMtp = true,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-qwen2.5-3b-f16.gguf",
            mtpDrafterFileName = "qwen2.5-0.5b-instruct-draft-q4_k_m.gguf",
            mainModelUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            visionTowerUrl = "https://huggingface.co/Qwen/Qwen2.5-VL-3B-Instruct-GGUF/resolve/main/mmproj-qwen2.5-3b-f16.gguf",
            mtpDrafterUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            isBundledModel = true,
            isDownloaded = false,
            description = "Qwen 2.5 모델. 비전 타워와 드래프터를 포함하여 이미지 입력 및 가속을 지원합니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-1.5b",
            name = "DeepSeek-R1 Distill 1.5B (CoT + Drafter)",
            repoId = "deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 1_120_000_000L,
            supportsMtp = true,
            supportsReasoning = true,
            hasMmproj = false,
            mtpDrafterFileName = "DeepSeek-R1-DRAFT-0.5B-Q4_K_M.gguf",
            mainModelUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
            visionTowerUrl = "",
            mtpDrafterUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            isBundledModel = true,
            isDownloaded = false,
            description = "사고 과정(<think>)을 출력하는 추론 모델. 드래프터와 함께 구성됩니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "llama-3.2-3b-vision-bundle-gguf",
            name = "Llama 3.2 3B Vision (Multimodal + Drafter)",
            repoId = "meta-llama/Llama-3.2-3B-Instruct-GGUF",
            fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 2_050_000_000L,
            supportsMtp = true,
            supportsReasoning = false,
            hasMmproj = true,
            mmprojFileName = "mmproj-llama-3.2-3b-f16.gguf",
            mtpDrafterFileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            mainModelUrl = "https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            visionTowerUrl = "https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct-GGUF/resolve/main/mmproj-llama-3.2-3b-f16.gguf",
            mtpDrafterUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            isBundledModel = true,
            isDownloaded = false,
            description = "Llama 3.2 3B 모델. 본체와 비전 타워, 1B 드래프터가 함께 구성되어 이미지 분석을 지원합니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "exaone-3.5-2.4b-gguf",
            name = "EXAONE 3.5 2.4B Instruct",
            repoId = "LGAI-EXAONE/EXAONE-3.5-2.4B-Instruct-GGUF",
            fileName = "exaone-3.5-2.4b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 1_580_000_000L,
            supportsMtp = true,
            supportsReasoning = false,
            hasMmproj = false,
            mtpDrafterFileName = "exaone-3.5-draft-q4_k_m.gguf",
            mainModelUrl = "https://huggingface.co/LGAI-EXAONE/EXAONE-3.5-2.4B-Instruct-GGUF/resolve/main/exaone-3.5-2.4b-instruct-q4_k_m.gguf",
            visionTowerUrl = "",
            mtpDrafterUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            isBundledModel = true,
            isDownloaded = false,
            description = "한국어 지원 2.4B 모델. 드래프터와 함께 구성됩니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "phi-4-mini-litert",
            name = "Phi-4 Mini 3.8B (LiteRT)",
            repoId = "microsoft/Phi-4-mini-instruct-litert",
            fileName = "phi-4-mini-instruct-gpu-int4.bin",
            runtimeType = ModelRuntimeType.LITE_RT,
            sizeBytes = 2_250_000_000L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct-litert/resolve/main/phi-4-mini-instruct-gpu-int4.bin",
            templateFileName = "phi-4-mini-prompt-template.json",
            templateFileUrl = "https://huggingface.co/microsoft/Phi-4-mini-instruct-litert/resolve/main/tokenizer_config.json",
            isBundledModel = false,
            isDownloaded = false,
            description = "LiteRT 런타임 기반 3.8B 모델 (사고 과정 추론 및 템플릿 지원).",
            quantization = "INT4"
        ),
        LlmModel(
            id = "gemma-2-2b-litert",
            name = "Gemma 2 2B IT (LiteRT)",
            repoId = "google/gemma-2-2b-it-litert",
            fileName = "gemma-2-2b-it-gpu-int4.bin",
            runtimeType = ModelRuntimeType.LITE_RT,
            sizeBytes = 1_450_000_000L,
            supportsMtp = true,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/google/gemma-2-2b-it-litert/resolve/main/gemma-2-2b-it-gpu-int4.bin",
            templateFileName = "gemma-2-prompt-template.json",
            templateFileUrl = "https://huggingface.co/google/gemma-2-2b-it-litert/resolve/main/tokenizer_config.json",
            isBundledModel = false,
            isDownloaded = false,
            description = "LiteRT 런타임 기반 2B 소형 모델 (LiteRT 템플릿 규격 내장).",
            quantization = "INT4"
        ),
        LlmModel(
            id = "smollm2-1.7b-gguf",
            name = "SmolLM2 1.7B",
            repoId = "HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF",
            fileName = "smollm2-1.7b-instruct-q4_k_m.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 1_040_000_000L,
            supportsMtp = true,
            supportsReasoning = false,
            hasMmproj = false,
            mtpDrafterFileName = "smollm2-360m-draft-q4_k_m.gguf",
            mainModelUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            visionTowerUrl = "",
            mtpDrafterUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/resolve/main/smollm2-360m-instruct-q4_k_m.gguf",
            isBundledModel = true,
            isDownloaded = false,
            description = "경량 소형 모델. 360M 드래프터와 함께 구성됩니다.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "deepseek-r1-distill-qwen-7b",
            name = "DeepSeek-R1 Distill 7B",
            repoId = "deepseek-ai/DeepSeek-R1-Distill-Qwen-7B-GGUF",
            fileName = "DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 4_400_000_000L,
            supportsMtp = false,
            supportsReasoning = true,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/deepseek-ai/DeepSeek-R1-Distill-Qwen-7B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-7B-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "7B 추론 모델.",
            quantization = "Q4_K_M"
        ),
        LlmModel(
            id = "llama-3.2-1b-gguf",
            name = "Llama 3.2 1B Instruct",
            repoId = "meta-llama/Llama-3.2-1B-Instruct-GGUF",
            fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            runtimeType = ModelRuntimeType.LLAMA_CPP,
            sizeBytes = 780_000_000L,
            supportsMtp = true,
            supportsReasoning = false,
            hasMmproj = false,
            mainModelUrl = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            isBundledModel = false,
            isDownloaded = false,
            description = "1B 경량 모델.",
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
