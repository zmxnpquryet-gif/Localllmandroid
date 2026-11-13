package com.localllm.android.document

import android.content.Context
import android.net.Uri
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/**
 * Converts a picked document into plain text.
 *
 * PDF text comes from PDFBox-Android; XLSX/PPTX from [OfficeTextParser]. Anything
 * else is treated as UTF-8 text (source code, Markdown, CSV, JSON). Legacy binary
 * Office formats (`.xls`, `.ppt`) are rejected explicitly instead of being decoded
 * as mojibake.
 */
class DocumentTextExtractor(private val context: Context) {

    private val pdfOcr = PdfOcrExtractor(context.applicationContext)

    suspend fun extract(uri: Uri, displayName: String, languagePreference: String = "system"): String {
        val extension = displayName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()

        return when {
            extension == "pdf" || mime == "application/pdf" -> extractPdf(uri, languagePreference)
            extension == "xlsx" || mime.contains("spreadsheetml") -> readBytes(uri).let(OfficeTextParser::parseXlsx)
            extension == "pptx" || mime.contains("presentationml") -> readBytes(uri).let(OfficeTextParser::parsePptx)
            extension in LEGACY_OFFICE -> throw IOException(
                AppStrings.get(R.string.doc_error_unsupported, ".$extension")
            )
            else -> readText(uri)
        }
    }

    private suspend fun extractPdf(uri: Uri, languagePreference: String): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        var pageCount = 0
        val digitalText = open(uri).use { stream ->
            PDDocument.load(stream).use { document ->
                if (document.numberOfPages > MAX_PDF_PAGES) {
                    throw IOException(AppStrings.get(R.string.doc_error_pages, MAX_PDF_PAGES))
                }
                pageCount = document.numberOfPages
                PDFTextStripper().apply {
                    sortByPosition = true
                    startPage = 1
                    endPage = document.numberOfPages
                }.getText(document).replace('\u000C', '\n')
            }
        }

        if (!PdfOcrPolicy.needsOcr(digitalText, pageCount)) return digitalText

        val ocrText = try {
            pdfOcr.extract(uri, languagePreference, pageCount)
        } catch (t: Throwable) {
            // A device where ML Kit cannot run should still index a PDF that has a
            // usable text layer instead of failing the whole import.
            if (digitalText.isNotBlank()) return digitalText
            throw IOException(AppStrings.get(R.string.doc_error_no_text), t)
        }

        val selected = PdfOcrPolicy.selectText(digitalText, ocrText)
        if (selected.isBlank()) throw IOException(AppStrings.get(R.string.doc_error_no_text))
        return selected
    }

    private fun readText(uri: Uri): String {
        val bytes = readBytes(uri)
        val text = String(bytes, Charsets.UTF_8)
        // A binary file that slipped through the picker is mostly NULs; indexing it
        // would only add noise to every later retrieval.
        if (text.isNotEmpty() && text.count { it == '\u0000' } > text.length / 10) {
            throw IOException(AppStrings.get(R.string.doc_error_binary))
        }
        return text
    }

    private fun readBytes(uri: Uri): ByteArray {
        val input = open(uri)
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (out.size() <= MAX_TEXT_BYTES) {
                val read = stream.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            if (out.size() > MAX_TEXT_BYTES) {
                throw IOException(AppStrings.get(R.string.doc_error_too_large, MAX_TEXT_BYTES / (1024 * 1024)))
            }
            out.toByteArray()
        }
    }

    private fun open(uri: Uri): InputStream =
        context.contentResolver.openInputStream(uri)
            ?: throw IOException(AppStrings.get(R.string.doc_error_unreadable))

    companion object {
        private const val MAX_TEXT_BYTES = 12 * 1024 * 1024
        private const val MAX_PDF_PAGES = 400
        private val LEGACY_OFFICE = setOf("xls", "ppt", "doc")
    }
}
