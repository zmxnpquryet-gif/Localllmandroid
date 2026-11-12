package com.localllm.android.document

import java.util.Locale

/**
 * Decides when a PDF needs OCR and merges the two extraction paths.
 *
 * PDFBox returns an empty (or near-empty) string for a scanned page because the page
 * is just an image. Rather than OCR every PDF (slow, and worse than the embedded
 * text layer), OCR only runs when the digital text looks too thin for the page count.
 */
object PdfOcrPolicy {

    /** Script model to use; ML Kit ships one bundled model per script. */
    enum class Script { LATIN, KOREAN, CHINESE }

    fun needsOcr(digitalText: String, pageCount: Int): Boolean {
        val alphanumeric = digitalText.count { it.isLetterOrDigit() }
        return alphanumeric < maxOf(MIN_DIGITAL_CHARACTERS, pageCount * MIN_CHARACTERS_PER_PAGE)
    }

    /**
     * Prefers whichever result carries more actual text. A PDF with a broken text
     * layer can still produce a few characters; a successful OCR pass is longer.
     */
    fun selectText(digitalText: String, ocrText: String): String {
        if (ocrText.isBlank()) return digitalText
        if (digitalText.isBlank()) return ocrText
        val digital = digitalText.count { it.isLetterOrDigit() }
        val ocr = ocrText.count { it.isLetterOrDigit() }
        return if (ocr > digital) ocrText else digitalText
    }

    /**
     * The Korean model also recognizes Latin, and the Chinese model also recognizes
     * Latin, so one model covers the app's UI languages plus English.
     */
    fun scriptFor(languagePreference: String, deviceLanguage: String): Script {
        val preference = languagePreference.lowercase(Locale.ROOT)
        val language = when (preference) {
            "ko", "zh", "en" -> preference
            else -> deviceLanguage.lowercase(Locale.ROOT)
        }
        return when {
            language.startsWith("ko") -> Script.KOREAN
            language.startsWith("zh") -> Script.CHINESE
            else -> Script.LATIN
        }
    }

    private const val MIN_DIGITAL_CHARACTERS = 60
    private const val MIN_CHARACTERS_PER_PAGE = 15
}
