package com.localllm.android.engine

import android.content.Context
import android.net.Uri
import android.util.Log
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import com.localllm.android.model.ChatAttachment
import java.io.File
import java.io.FileInputStream

/**
 * Shared model container inspection and vision attachment staging.
 * Eliminates duplicate GGUF/LiteRT header checks and content-URI staging across
 * [LlmEngine], [LiteRtSessionRunner], [LlamaSessionRunner], and [ModelDownloader].
 */
internal object ModelFileValidator {
    private const val TAG = "ModelFileValidator"
    private const val MIN_LITERT_BYTES = 1024 * 1024L

    fun isGgufFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4L) return false
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(4)
                fis.read(header) == 4 &&
                    header[0] == 'G'.code.toByte() &&
                    header[1] == 'G'.code.toByte() &&
                    header[2] == 'U'.code.toByte() &&
                    header[3] == 'F'.code.toByte()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to read GGUF header from ${file.name}: ${t.message}")
            false
        }
    }

    fun isLiteRtModelFile(file: File): Boolean {
        if (!file.exists() || file.length() < MIN_LITERT_BYTES) return false
        val preview = readTextPreview(file)
        return !(
            preview.contains("Unauthorized", ignoreCase = true) ||
                preview.contains("401", ignoreCase = true) ||
                preview.startsWith("<!DOCTYPE", ignoreCase = true) ||
                preview.startsWith("<html", ignoreCase = true) ||
                preview.contains("{\"error\"", ignoreCase = true)
            )
    }

    fun readTextPreview(file: File, maxBytes: Int = 256): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(maxBytes)
                val len = fis.read(buf)
                if (len > 0) String(buf, 0, len, Charsets.UTF_8).trim() else ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    /** Returns `(stageLabel, errorMessage)` if a LiteRT file fails pre-flight checks, or null if valid. */
    fun diagnoseInvalidLiteRtForLoad(file: File): Pair<String, String>? {
        val preview = readTextPreview(file)
        if (preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true)) {
            return AppStrings.get(R.string.eng_stage_401) to AppStrings.get(R.string.eng_err_401)
        }
        if (preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true)) {
            return AppStrings.get(R.string.eng_stage_html) to AppStrings.get(R.string.eng_err_html)
        }
        if (file.length() < MIN_LITERT_BYTES) {
            return AppStrings.get(R.string.eng_stage_size) to AppStrings.get(R.string.eng_err_size, file.length())
        }
        return null
    }

    fun diagnoseInvalidGgufForLoad(file: File): String {
        val preview = readTextPreview(file)
        return when {
            preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true) ->
                AppStrings.get(R.string.eng_err_401)
            preview.contains("404", ignoreCase = true) || preview.contains("Not Found", ignoreCase = true) ->
                AppStrings.get(R.string.eng_err_404)
            preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                AppStrings.get(R.string.eng_err_html2)
            else ->
                AppStrings.get(R.string.eng_err_magic)
        }
    }

    fun diagnoseInvalidDownload(file: File, isGguf: Boolean): String {
        val preview = readTextPreview(file)
        return when {
            preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true) ->
                AppStrings.get(R.string.dl_fail_401)
            preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                AppStrings.get(R.string.dl_fail_html)
            !isGguf && file.length() < MIN_LITERT_BYTES ->
                AppStrings.get(R.string.dl_fail_small, file.length())
            isGguf ->
                AppStrings.get(R.string.dl_verify_gguf)
            else ->
                AppStrings.get(R.string.dl_verify_litert)
        }
    }

    /**
     * Native vision backends (`libmtmd` and LiteRT `Content.ImageFile`) read filesystem
     * paths, while user attachments often arrive as `content://` URIs. Copies `content://`
     * streams into `cacheDir` for the lifetime of one inference call.
     */
    fun materializeVisionAttachment(
        context: Context,
        attachment: ChatAttachment?,
        tempPrefix: String
    ): Pair<String?, File?> {
        if (attachment?.isImage != true) return null to null
        val raw = attachment.uriString
        val uri = try {
            Uri.parse(raw)
        } catch (_: Throwable) {
            return null to null
        }
        return when (uri.scheme) {
            "content" -> try {
                val temp = File(context.cacheDir, "${tempPrefix}_${System.currentTimeMillis()}.jpg")
                val opened = context.contentResolver.openInputStream(uri) ?: return null to null
                opened.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                temp.absolutePath to temp
            } catch (e: Exception) {
                Log.w(TAG, "Failed to materialize content URI for vision ($tempPrefix): ${e.message}")
                null to null
            }
            "file" -> uri.path?.takeIf { File(it).isFile } to null
            else -> {
                val file = File(raw)
                file.absolutePath.takeIf { file.isFile } to null
            }
        }
    }
}
