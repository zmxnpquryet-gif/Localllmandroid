package com.example.engine

import android.util.Log
import com.example.model.ModelRuntimeType
import java.io.File
import java.io.FileInputStream

data class GgufFeatureDetection(
    val hasVisionTower: Boolean,
    val hasDrafter: Boolean,
    val details: String = "",
    val detectedRuntime: ModelRuntimeType = ModelRuntimeType.LLAMA_CPP,
    val isUnifiedBundle: Boolean = false
) {
    val hasVision: Boolean get() = hasVisionTower
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

            val ascii = String(buffer, 0, readLen, Charsets.ISO_8859_1).lowercase()
            val utf8 = String(buffer, 0, readLen, Charsets.UTF_8).lowercase()
            val content = "$ascii $utf8"

            // Vision projector / encoder indicators in metadata or subgraphs (avoid generic noisy substrings like "mm." or "clip.")
            val metaHasVision = content.contains("qwen2vl") ||
                    content.contains("qwen2_vl") ||
                    content.contains("llava") ||
                    content.contains("mllama") ||
                    content.contains("minicpmv") ||
                    content.contains("paligemma") ||
                    content.contains("siglip") ||
                    content.contains("vision_encoder") ||
                    content.contains("image_encoder") ||
                    content.contains("clip.has_vision_encoder") ||
                    content.contains("mm.projector") ||
                    content.contains("mm_projector") ||
                    content.contains("v.blk.0")

            // Drafter / MTP indicators
            val metaHasDrafter = content.contains("mtp.") ||
                    content.contains("draft.head") ||
                    content.contains("speculative.draft") ||
                    content.contains("multi_token_prediction")

            val finalVision = nameHasVision || metaHasVision
            val finalDrafter = nameHasDrafter || metaHasDrafter

            // LiteRT models are inherently unified bundles
            val isUnified = detectedRuntime == ModelRuntimeType.LITE_RT || finalVision || finalDrafter

            val detailsList = mutableListOf<String>()
            if (detectedRuntime == ModelRuntimeType.LITE_RT) {
                detailsList.add("LiteRT 올인원 통합 모델")
            }
            if (finalVision) {
                detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) "통합 비전타워(Vision Encoder) 내장" else "비전타워 내장 감지")
            }
            if (finalDrafter) {
                detailsList.add(if (detectedRuntime == ModelRuntimeType.LITE_RT) "통합 추측 디코딩 드래프터 내장" else "드래프터(MTP) 내장 감지")
            }

            Log.d(TAG, "Detection for ${file.name}: runtime=$detectedRuntime, vision=$finalVision, drafter=$finalDrafter, unified=$isUnified")

            GgufFeatureDetection(
                hasVisionTower = finalVision,
                hasDrafter = finalDrafter,
                details = detailsList.joinToString(" • "),
                detectedRuntime = detectedRuntime,
                isUnifiedBundle = isUnified
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting model features: ${e.message}")
            GgufFeatureDetection(hasVisionTower = false, hasDrafter = false)
        }
    }
}
