package com.localllm.android.engine

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

import android.util.Log
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.model.PromptTemplateType
import com.localllm.android.engine.gguf.GgufReader
import java.io.File
import java.io.FileInputStream

data class GgufFeatureDetection(
    val hasVisionTower: Boolean,
    val hasDrafter: Boolean,
    val details: String = "",
    val detectedRuntime: ModelRuntimeType = ModelRuntimeType.LLAMA_CPP,
    val isUnifiedBundle: Boolean = false,
    val expertCount: Int = 0,
    val architecture: String? = null,
    val detectedTemplate: PromptTemplateType? = null,
    val isReasoning: Boolean = false
) {
    val hasVision: Boolean get() = hasVisionTower

    val isMoe: Boolean get() = expertCount > 1

}

/**
 * Universal on-device model detector supporting both:
 * 1. Google LiteRT-LM (.litertlm, .bin, .task, .tflite):
 *    Native all-in-one unified model bundles containing language weights,
 *    vision encoders (PaliGemma, Gemma 3 Vision), tokenizers, and drafters.
 * 2. llama.cpp GGUF (.gguf):
 *    Single-file or split-bundle models with embedded or external vision towers / MTP heads.
 */
object GgufMetadataDetector {

    private const val TAG = "ModelMetadataDetector"

    /** Substrings matched against metadata KEYS and tensor NAMES only (never values). */
    private val VISION_STRUCTURED_HITS = listOf(
        "clip", "vision", "siglip", "llava", "mllama", "minicpm", "paligemma",
        "mm.", "mm_", "projector", "multimodal", "multi_modal", "image_encoder",
        "vision_encoder", "v.blk"
    )

    /** Substrings matched against metadata KEYS and tensor NAMES only (never values). */
    private val DRAFTER_STRUCTURED_HITS = listOf(
        "mtp", "draft", "drafter", "speculative", "multi_token_prediction", "target_draft"
    )

    fun isLiteRtModel(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".litertlm") ||
                lower.endsWith(".task") ||
                lower.endsWith(".tflite") ||
                lower.contains("litert") ||
                lower.contains("litertlm")
    }

    fun isVisionModel(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("-vl") ||
                lower.contains("_vl") ||
                lower.contains("qwen2-vl") ||
                lower.contains("qwen2vl") ||
                lower.contains("vision") ||
                lower.contains("llava") ||
                lower.contains("minicpm-v") ||
                lower.contains("paligemma") ||
                lower.contains("mmproj") ||
                lower.contains("multimodal") ||
                lower.contains("siglip")
    }

    fun isDrafterModel(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("mtp") ||
                lower.contains("draft") ||
                lower.contains("speculative") ||
                lower.contains("multi_token_prediction") ||
                lower.contains("target_draft")
    }

    fun detect(file: File): GgufFeatureDetection {
        if (!file.exists() || !file.isFile || file.length() < 128) {
            return GgufFeatureDetection(hasVisionTower = false, hasDrafter = false)
        }

        return try {
            val fileNameLower = file.name.lowercase()

            // Read up to 256 KB of model header to inspect magic bytes & metadata
            val readLen = minOf(file.length(), 256 * 1024L).toInt()
            val buffer = ByteArray(readLen)
            FileInputStream(file).use { fis ->
                fis.read(buffer, 0, readLen)
            }

            // 1. Check GGUF Magic Bytes: 0x47, 0x47, 0x55, 0x46 ("GGUF")
            val hasGgufMagic = buffer.size >= 4 &&
                    buffer[0] == 0x47.toByte() && buffer[1] == 0x47.toByte() &&
                    buffer[2] == 0x55.toByte() && buffer[3] == 0x46.toByte()

            // 2. Check LiteRT / TFLite FlatBuffer Magic (offset 4..7 == "TFL3" or "LITM" or "LRT1")
            val hasTfliteMagic = (buffer.size >= 8 &&
                    buffer[4] == 0x54.toByte() && buffer[5] == 0x46.toByte() &&
                    buffer[6] == 0x4C.toByte() && buffer[7] == 0x33.toByte()) ||
                    (buffer.size >= 8 &&
                    buffer[4] == 'L'.code.toByte() && buffer[5] == 'I'.code.toByte() &&
                    buffer[6] == 'T'.code.toByte() && buffer[7] == 'M'.code.toByte())

            // 3. Check MediaPipe / LiteRT .task Zip Container Magic (bytes 0..3 == "PK\x03\x04")
            val hasZipMagic = buffer.size >= 4 &&
                    buffer[0] == 0x50.toByte() && buffer[1] == 0x4B.toByte() &&
                    buffer[2] == 0x03.toByte() && buffer[3] == 0x04.toByte()

            val isLiteRtName = isLiteRtModel(fileNameLower)

            // Strict runtime classification: GGUF magic takes absolute priority
            val detectedRuntime = when {
                hasGgufMagic -> ModelRuntimeType.LLAMA_CPP
                hasTfliteMagic || hasZipMagic -> ModelRuntimeType.LITE_RT
                fileNameLower.endsWith(".gguf") -> ModelRuntimeType.LLAMA_CPP
                isLiteRtName -> ModelRuntimeType.LITE_RT
                else -> ModelRuntimeType.LLAMA_CPP // Safe fallback: default to LLAMA_CPP for standard weights
            }

            // Filename heuristics
            val nameHasVision = isVisionModel(fileNameLower)
            val nameHasDrafter = isDrafterModel(fileNameLower)

            // Vision/drafter evidence comes from structured metadata and tensor names —
            // KEYS and tensor NAMES. Scanning the raw 256 KB (which includes the vocab
            // token table) false-positives on any text model whose vocabulary happens
            // to contain words like "siglip" or "vision".
            var expertCount = 0
            var architecture: String? = null
            var generalName: String? = null
            var chatTemplate: String? = null
            var structuredHits = ""
            if (hasGgufMagic) {
                try {
                    val reader = GgufReader.open(file)
                    try {
                        architecture = reader.architecture()
                        generalName = reader.string("general.name")
                        chatTemplate = reader.string("tokenizer.chat_template")
                        expertCount = reader.archU32("expert_count")?.toInt() ?: 0
                        structuredHits = (reader.metadata.keys + reader.tensors.map { it.name })
                            .joinToString("\n").lowercase()
                    } finally {
                        reader.close()
                    }
                } catch (t: Throwable) {
                    Log.d(TAG, "GGUF metadata peek failed for ${file.name}: ${t.message}")
                }
            }

            val detectedTemplate = inferPromptTemplate(
                fileNameLower = fileNameLower,
                architecture = architecture,
                generalName = generalName,
                chatTemplate = chatTemplate
            )
            val isReasoning = inferReasoningSupport(
                fileNameLower = fileNameLower,
                architecture = architecture,
                generalName = generalName,
                chatTemplate = chatTemplate
            )

            // Vision projector / encoder indicators in metadata keys or tensor names.
            val metaHasVision = VISION_STRUCTURED_HITS.any { structuredHits.contains(it) }

            // Drafter / MTP indicators in metadata keys or tensor names.
            val metaHasDrafter = DRAFTER_STRUCTURED_HITS.any { structuredHits.contains(it) }

            val finalVision = nameHasVision || metaHasVision
            val finalDrafter = nameHasDrafter || metaHasDrafter

            // LiteRT models are inherently unified bundles
            val isUnified = detectedRuntime == ModelRuntimeType.LITE_RT || finalVision || finalDrafter

            val detailsList = mutableListOf<String>()
            if (detectedRuntime == ModelRuntimeType.LITE_RT) {
                detailsList.add(AppStrings.get(R.string.det_litert_all))
            }
            if (expertCount > 1) {
                detailsList.add(AppStrings.get(R.string.det_moe, expertCount))
            }
            if (finalVision) {
                detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) AppStrings.get(R.string.det_vision_integrated) else AppStrings.get(R.string.det_vision_detected))
            }
            if (finalDrafter) {
                detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) AppStrings.get(R.string.det_drafter_integrated) else AppStrings.get(R.string.det_drafter_detected))
            }

            Log.d(
                TAG,
                "Detection for ${file.name}: runtime=$detectedRuntime, vision=$finalVision, drafter=$finalDrafter, " +
                        "unified=$isUnified, arch=${architecture ?: "?"}, experts=$expertCount"
            )

            GgufFeatureDetection(
                hasVisionTower = finalVision,
                hasDrafter = finalDrafter,
                details = detailsList.joinToString(" • "),
                detectedRuntime = detectedRuntime,
                isUnifiedBundle = isUnified,
                expertCount = expertCount,
                architecture = architecture,
                detectedTemplate = detectedTemplate,
                isReasoning = isReasoning
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting model features: ${e.message}")
            GgufFeatureDetection(hasVisionTower = false, hasDrafter = false)
        }
    }

    private val R1_REGEX = Regex("""(^|[^a-z0-9])r1([^a-z0-9]|$)""")

    fun inferPromptTemplate(
        fileNameLower: String,
        architecture: String? = null,
        generalName: String? = null,
        chatTemplate: String? = null
    ): PromptTemplateType? {
        val hint = listOfNotNull(fileNameLower, architecture, generalName).joinToString(" ").lowercase()
        val tpl = chatTemplate.orEmpty()
        return when {
            tpl.contains("<|turn>") -> PromptTemplateType.GEMMA4
            tpl.contains("<start_of_turn>") -> PromptTemplateType.GEMMA
            tpl.contains("<|start_header_id|>") -> PromptTemplateType.LLAMA3
            tpl.contains("<|user|>") && (tpl.contains("<|end|>") || tpl.contains("<|assistant|>")) -> PromptTemplateType.PHI
            tpl.contains("<|im_start|>") -> when {
                hint.contains("lfm3") || hint.contains("lfm-3") || hint.contains("lfm 3") ||
                    (architecture?.lowercase()?.startsWith("lfm") == true && tpl.contains("<think>")) -> PromptTemplateType.LFM3
                hint.contains("minicpm5") || hint.contains("minicpm-5") -> PromptTemplateType.MINICPM5
                hint.contains("qwen3.5") || hint.contains("qwen3_5") || hint.contains("qwen 3.5") ||
                    (tpl.contains("<think>") && hint.contains("qwen")) -> PromptTemplateType.QWEN35
                else -> PromptTemplateType.CHATML
            }
            hint.contains("lfm3") || hint.contains("lfm-3") || hint.contains("lfm 3") -> PromptTemplateType.LFM3
            hint.contains("gemma4") || hint.contains("gemma-4") || hint.contains("gemma 4") -> PromptTemplateType.GEMMA4
            hint.contains("gemma") -> PromptTemplateType.GEMMA
            hint.contains("minicpm5") || hint.contains("minicpm-5") -> PromptTemplateType.MINICPM5
            hint.contains("qwen3.5") || hint.contains("qwen3_5") || hint.contains("qwen 3.5") -> PromptTemplateType.QWEN35
            hint.contains("llama-3") || hint.contains("llama3") || hint.contains("llama 3") ||
                hint.contains("llama-4") || hint.contains("llama4") || hint.contains("llama 4") -> PromptTemplateType.LLAMA3
            hint.contains("phi-3") || hint.contains("phi3") || hint.contains("phi-4") || hint.contains("phi4") -> PromptTemplateType.PHI
            else -> null
        }
    }

    fun inferReasoningSupport(
        fileNameLower: String,
        architecture: String? = null,
        generalName: String? = null,
        chatTemplate: String? = null
    ): Boolean {
        val hint = listOfNotNull(fileNameLower, architecture, generalName).joinToString(" ").lowercase()
        val tpl = chatTemplate.orEmpty()
        if (tpl.contains("<think>") || tpl.contains("<|think|>") || tpl.contains("enable_thinking")) {
            return true
        }
        return R1_REGEX.containsMatchIn(hint) ||
            hint.contains("reasoning") ||
            hint.contains("thinking") ||
            hint.contains("qwq") ||
            hint.contains("deepseek") ||
            hint.contains("lfm3") || hint.contains("lfm-3") || hint.contains("lfm 3") ||
            hint.contains("qwen3") || hint.contains("qwen 3") ||
            hint.contains("gemma4") || hint.contains("gemma-4") || hint.contains("gemma 4") ||
            hint.contains("minicpm5") || hint.contains("minicpm-5") ||
            hint.contains("agents-a1")
    }
}
