package com.localllm.android.document

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.TextRecognizerOptionsInterface
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OCR for scanned PDFs, fully on-device.
 *
 * Android's [PdfRenderer] rasterizes each page (it does not expose text), then ML Kit
 * Text Recognition reads the bitmap with a bundled model. No Play Services, no
 * network, no upload of the document.
 *
 * Only the first [MAX_OCR_PAGES] pages are read: at ~150 DPI a page takes a moment to
 * recognize, and a 300-page scan must not block the import for minutes. The extracted
 * text says so, so the model does not answer from a partial document unknowingly.
 */
class PdfOcrExtractor(private val context: Context) {

    suspend fun extract(
        uri: Uri,
        languagePreference: String,
        totalPages: Int
    ): String = withContext(Dispatchers.IO) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException(AppStrings.get(R.string.doc_error_unreadable))
        descriptor.use { fileDescriptor ->
            val renderer = PdfRenderer(fileDescriptor)
            try {
                val recognizer = TextRecognition.getClient(
                    optionsFor(
                        PdfOcrPolicy.scriptFor(languagePreference, Locale.getDefault().language)
                    )
                )
                try {
                    val pageCount = minOf(totalPages, MAX_OCR_PAGES)
                    val output = StringBuilder()
                    if (totalPages > pageCount) {
                        output.append(
                            AppStrings.get(R.string.doc_ocr_partial, pageCount, totalPages)
                        ).append("\n\n")
                    }
                    for (index in 0 until pageCount) {
                        val page = renderer.openPage(index)
                        try {
                            val bitmap = renderPage(page)
                            try {
                                val text = recognizer.recognize(bitmap)
                                if (text.isNotBlank()) {
                                    output.append("Page ").append(index + 1).append('\n')
                                        .append(text.trim()).append("\n\n")
                                }
                            } finally {
                                bitmap.recycle()
                            }
                        } finally {
                            page.close()
                        }
                    }
                    output.toString().trim()
                } finally {
                    recognizer.close()
                }
            } finally {
                renderer.close()
            }
        }
    }

    private fun renderPage(page: PdfRenderer.Page): Bitmap {
        val scale = minOf(
            MAX_SCALE,
            MAX_RENDER_WIDTH / page.width.toFloat().coerceAtLeast(1f)
        )
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Scanned pages with transparency would otherwise OCR as black-on-black.
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private suspend fun TextRecognizer.recognize(bitmap: Bitmap): String =
        suspendCancellableCoroutine { continuation ->
            process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { error -> continuation.resumeWithException(error) }
        }

    private fun optionsFor(script: PdfOcrPolicy.Script): TextRecognizerOptionsInterface =
        when (script) {
            PdfOcrPolicy.Script.LATIN -> TextRecognizerOptions.DEFAULT_OPTIONS
            PdfOcrPolicy.Script.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
            PdfOcrPolicy.Script.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
        }

    companion object {
        private const val MAX_OCR_PAGES = 20
        private const val MAX_SCALE = 2f
        private const val MAX_RENDER_WIDTH = 1600f
    }
}
