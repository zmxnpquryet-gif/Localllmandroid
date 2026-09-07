package com.example.engine

import android.util.Log
import java.io.File
import java.io.FileInputStream

data class GgufFeatureDetection(
    val hasVisionTower: Boolean,
    val hasDrafter: Boolean,
    val details: String = ""
) {
    val hasVision: Boolean get() = hasVisionTower
}

/**
 * High-speed detector for embedded vision towers (Qwen2-VL, Llava, CLIP, MiniCPM-V, etc.)
 * and embedded speculative drafters (MTP heads, draft layers) directly within GGUF files.
 */
object GgufMetadataDetector {

    private const val TAG = "GgufMetadataDetector"

    fun isVisionModel(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("vl") ||
                lower.contains("vision") ||
                lower.contains("llava") ||
                lower.contains("minicpm-v") ||
                lower.contains("paligemma") ||
                lower.contains("gemma-3") ||
                lower.contains("mmproj") ||
                lower.contains("qwen2vl")
    }

    fun isDrafterModel(name: String): Boolean {
        val lower = name.lowercase()
        return lower.contains("mtp") ||
                lower.contains("draft") ||
                lower.contains("speculative") ||
                lower.contains("multi_token_prediction")
    }

    fun detect(file: File): GgufFeatureDetection {
        if (!file.exists() || !file.isFile || file.length() < 128) {
            return GgufFeatureDetection(hasVisionTower = false, hasDrafter = false)
        }

        return try {
            val fileNameLower = file.name.lowercase()

            // 1. Filename heuristics
            val nameHasVision = isVisionModel(fileNameLower)
            val nameHasDrafter = isDrafterModel(fileNameLower)

            // 2. Read up to 256 KB of GGUF header to inspect embedded metadata keys
            val readLen = minOf(file.length(), 256 * 1024L).toInt()
            val buffer = ByteArray(readLen)
            FileInputStream(file).use { fis ->
                fis.read(buffer, 0, readLen)
            }

            val ascii = String(buffer, 0, readLen, Charsets.ISO_8859_1).lowercase()
            val utf8 = String(buffer, 0, readLen, Charsets.UTF_8).lowercase()
            val content = "$ascii $utf8"

            // Vision projector indicators in GGUF metadata / tensor names
            val metaHasVision = content.contains("qwen2vl") ||
                    content.contains("llava") ||
                    content.contains("clip.") ||
                    content.contains("vision.") ||
                    content.contains("projector.") ||
                    content.contains("v.blk.") ||
                    content.contains("mm.") ||
                    content.contains("minicpmv") ||
                    content.contains("mllama") ||
                    content.contains("paligemma")

            // Drafter / MTP indicators in GGUF metadata / tensor names
            val metaHasDrafter = content.contains("mtp.") ||
                    content.contains("draft.") ||
                    content.contains("speculative") ||
                    content.contains("multi_token_prediction")

            val finalVision = nameHasVision || metaHasVision
            val finalDrafter = nameHasDrafter || metaHasDrafter

            val detailsList = mutableListOf<String>()
            if (finalVision) detailsList.add("비전타워 내장 감지")
            if (finalDrafter) detailsList.add("드래프터(MTP) 내장 감지")

            Log.d(TAG, "Detection for ${file.name}: vision=$finalVision, drafter=$finalDrafter (${detailsList.joinToString()})")
            GgufFeatureDetection(
                hasVisionTower = finalVision,
                hasDrafter = finalDrafter,
                details = detailsList.joinToString(" • ")
            )
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting GGUF features: ${e.message}")
            GgufFeatureDetection(hasVisionTower = false, hasDrafter = false)
        }
    }
}
