package com.localllm.android.document

/**
 * Splits extracted document text into retrieval-sized chunks.
 *
 * Paragraph boundaries are preferred; a paragraph longer than [maxChars] is cut at a
 * sentence boundary first and a word boundary second, so a single huge cell or a
 * PDF page without blank lines cannot produce an unbounded chunk. Consecutive chunks
 * share up to [overlap] characters so an answer that straddles a boundary can still
 * be retrieved.
 */
object DocumentChunker {
    const val DEFAULT_MAX_CHARS = 900
    const val DEFAULT_OVERLAP = 120

    fun chunk(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        overlap: Int = DEFAULT_OVERLAP
    ): List<String> {
        require(maxChars > 0) { "maxChars must be positive" }
        require(overlap in 0 until maxChars) { "overlap must be smaller than maxChars" }

        val normalized = normalize(text)
        if (normalized.isEmpty()) return emptyList()

        val paragraphs = normalized
            .split(Regex("\\n\\s*\\n"))
            .flatMap { splitLongParagraph(it.trim(), maxChars) }
            .filter { it.isNotBlank() }

        val chunks = mutableListOf<String>()
        val current = StringBuilder()
        for (paragraph in paragraphs) {
            if (current.isEmpty()) {
                current.append(paragraph)
                continue
            }
            if (current.length + 2 + paragraph.length <= maxChars) {
                current.append("\n\n").append(paragraph)
                continue
            }

            chunks += current.toString().trim()
            val tail = current.takeLast(overlap).trimStart()
            current.setLength(0)
            if (tail.isNotEmpty() && tail.length + 2 + paragraph.length <= maxChars) {
                current.append(tail).append("\n\n").append(paragraph)
            } else {
                current.append(paragraph)
            }
        }
        if (current.isNotBlank()) chunks += current.toString().trim()

        return chunks.filter { it.isNotEmpty() }
    }

    private fun normalize(text: String): String = text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .replace(Regex("[\\t\\u000B\\f]+"), " ")
        .replace(Regex(" {2,}"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()

    private fun splitLongParagraph(paragraph: String, maxChars: Int): List<String> {
        if (paragraph.length <= maxChars) return listOf(paragraph)
        val pieces = mutableListOf<String>()
        var rest = paragraph
        while (rest.length > maxChars) {
            var cut = rest.lastIndexOf(". ", maxChars)
            if (cut < maxChars / 2) cut = rest.lastIndexOf("? ", maxChars)
            if (cut < maxChars / 2) cut = rest.lastIndexOf("! ", maxChars)
            if (cut < maxChars / 2) cut = rest.lastIndexOf(' ', maxChars)
            if (cut <= 0) cut = maxChars
            pieces += rest.substring(0, cut).trim()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotBlank()) pieces += rest
        return pieces.filter { it.isNotBlank() }
    }
}
