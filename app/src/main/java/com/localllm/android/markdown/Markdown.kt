package com.localllm.android.markdown

/**
 * A styled run of text inside a block.
 *
 * [linkUrl] is null for plain text; when set the run is rendered as a link.
 */
data class MarkdownSpan(
    val text: String,
    val styles: Set<MarkdownInlineStyle> = emptySet(),
    val linkUrl: String? = null
)

enum class MarkdownInlineStyle { BOLD, ITALIC, CODE, STRIKETHROUGH }

/**
 * Block-level markdown. Everything a streaming model can emit mid-token has a
 * representation here, so a half-written answer never loses characters:
 * an unclosed fence becomes [Code] with `closed = false`.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Heading(val level: Int, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Code(val language: String, val code: String, val closed: Boolean) : MarkdownBlock
    data class ListItem(val depth: Int, val marker: String, val spans: List<MarkdownSpan>) : MarkdownBlock
    data class Quote(val spans: List<MarkdownSpan>) : MarkdownBlock
    data object Rule : MarkdownBlock
}

/**
 * Small, dependency-free markdown parser tuned for streaming LLM output.
 *
 * Rules that make partial output safe to render:
 *  - a style is applied only when its closing marker is already in the text, so a
 *    trailing `**` shows up as literal characters instead of swallowing the answer;
 *  - an unterminated ``` fence turns the rest of the text into a code block;
 *  - parsing is pure, allocation-light and never throws.
 */
object MarkdownParser {

    fun parse(source: String): List<MarkdownBlock> {
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        val paragraph = StringBuilder()

        fun flushParagraph() {
            if (paragraph.isNotBlank()) {
                blocks += MarkdownBlock.Paragraph(parseInline(paragraph.toString().trim()))
            }
            paragraph.clear()
        }

        var i = 0
        while (i < lines.size) {
            val raw = lines[i]
            val trimmed = raw.trimStart()

            // Fenced code block (``` or ~~~). An unclosed fence keeps the rest as code.
            val fence = fenceOpen(trimmed)
            if (fence != null) {
                flushParagraph()
                val language = fence.info.substringBefore(' ').substringBefore('\t')
                val code = StringBuilder()
                var closed = false
                i++
                while (i < lines.size) {
                    val line = lines[i]
                    if (isFenceClose(line, fence)) {
                        closed = true
                        i++
                        break
                    }
                    if (code.isNotEmpty()) code.append('\n')
                    code.append(line)
                    i++
                }
                blocks += MarkdownBlock.Code(
                    language = language,
                    code = code.toString().trimEnd('\n'),
                    closed = closed
                )
                continue
            }

            if (trimmed.isBlank()) {
                flushParagraph()
                i++
                continue
            }

            // ATX heading: "#" .. "######" followed by a space.
            val heading = headingLevel(trimmed)
            if (heading > 0) {
                flushParagraph()
                val body = trimmed.substring(heading).trim().trimEnd('#').trimEnd()
                blocks += MarkdownBlock.Heading(heading, parseInline(body))
                i++
                continue
            }

            if (isRule(trimmed)) {
                flushParagraph()
                blocks += MarkdownBlock.Rule
                i++
                continue
            }

            if (trimmed.startsWith(">")) {
                flushParagraph()
                val quoted = StringBuilder()
                while (i < lines.size) {
                    val line = lines[i].trimStart()
                    if (!line.startsWith(">")) break
                    if (quoted.isNotEmpty()) quoted.append('\n')
                    quoted.append(line.removePrefix(">").removePrefix(" "))
                    i++
                }
                blocks += MarkdownBlock.Quote(parseInline(quoted.toString().trim()))
                continue
            }

            val item = listItem(raw)
            if (item != null) {
                flushParagraph()
                blocks += item
                i++
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(trimmed.trimEnd())
            i++
        }
        flushParagraph()
        return blocks
    }

    private class Fence(val char: Char, val count: Int, val info: String)

    private fun fenceOpen(trimmed: String): Fence? {
        if (trimmed.length < 3) return null
        val char = trimmed[0]
        if (char != '`' && char != '~') return null
        var count = 0
        while (count < trimmed.length && trimmed[count] == char) count++
        if (count < 3) return null
        val info = trimmed.substring(count).trim()
        // A backtick fence's info string cannot itself contain a backtick.
        if (char == '`' && info.contains('`')) return null
        return Fence(char, count, info)
    }

    private fun isFenceClose(line: String, fence: Fence): Boolean {
        val trimmed = line.trim()
        if (trimmed.length < fence.count) return false
        return trimmed.all { it == fence.char }
    }

    private fun headingLevel(trimmed: String): Int {
        var level = 0
        while (level < trimmed.length && trimmed[level] == '#') level++
        if (level !in 1..6) return 0
        val next = trimmed.getOrNull(level) ?: return 0
        if (next != ' ' && next != '\t') return 0
        return level
    }

    private fun isRule(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val char = trimmed[0]
        if (char != '-' && char != '*' && char != '_') return false
        var count = 0
        for (c in trimmed) {
            when {
                c == char -> count++
                c == ' ' || c == '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }

    private val bullet = Regex("^(\\s*)([-*+]|\\d{1,9}[.)])\\s+(.*)$")

    private fun listItem(raw: String): MarkdownBlock.ListItem? {
        val match = bullet.matchEntire(raw) ?: return null
        val indent = match.groupValues[1].replace("\t", "    ").length
        return MarkdownBlock.ListItem(
            depth = (indent / 2).coerceIn(0, 4),
            marker = match.groupValues[2],
            spans = parseInline(match.groupValues[3].trim())
        )
    }

    // ---------------------------------------------------------------- inline

    fun parseInline(text: String): List<MarkdownSpan> {
        val out = mutableListOf<MarkdownSpan>()
        val literal = StringBuilder()
        val length = text.length

        fun flushLiteral() {
            if (literal.isEmpty()) return
            out += MarkdownSpan(literal.toString())
            literal.clear()
        }

        fun emit(spans: List<MarkdownSpan>) {
            flushLiteral()
            for (span in spans) {
                if (span.text.isEmpty()) continue
                val last = out.lastOrNull()
                if (last != null && last.styles == span.styles && last.linkUrl == span.linkUrl) {
                    out[out.lastIndex] = last.copy(text = last.text + span.text)
                } else {
                    out += span
                }
            }
        }

        fun emit(text: String, styles: Set<MarkdownInlineStyle>, url: String? = null) {
            emit(listOf(MarkdownSpan(text, styles, url)))
        }

        var i = 0
        while (i < length) {
            val c = text[i]
            when {
                // Backslash escape keeps the escaped character verbatim.
                c == '\\' && i + 1 < length && !text[i + 1].isLetterOrDigit() && text[i + 1] != '\n' -> {
                    literal.append(text[i + 1])
                    i += 2
                }

                c == '`' -> {
                    var run = 0
                    while (i + run < length && text[i + run] == '`') run++
                    val close = findRun(text, i + run, run)
                    if (close >= 0) {
                        emit(text.substring(i + run, close), setOf(MarkdownInlineStyle.CODE))
                        i = close + run
                    } else {
                        // Unclosed inline code is emitted verbatim (streaming-safe).
                        literal.append(text, i, i + run)
                        i += run
                    }
                }

                c == '~' && i + 1 < length && text[i + 1] == '~' -> {
                    val close = text.indexOf("~~", i + 2)
                    if (close > i + 2) {
                        emit(addStyle(parseInline(text.substring(i + 2, close)), MarkdownInlineStyle.STRIKETHROUGH))
                        i = close + 2
                    } else {
                        literal.append("~~")
                        i += 2
                    }
                }

                c == '*' || c == '_' -> {
                    var run = 0
                    while (i + run < length && text[i + run] == c) run++
                    val consumed = consumeEmphasis(text, i, run) { inner, styles ->
                        emit(parseInline(inner).map { it.copy(styles = it.styles + styles) })
                    }
                    if (consumed > 0) {
                        i = consumed
                    } else {
                        literal.append(text, i, i + run.coerceAtMost(3))
                        i += run.coerceAtMost(3)
                    }
                }

                c == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    val isLink = closeBracket > i + 1 &&
                            text.getOrNull(closeBracket + 1) == '(' &&
                            text.indexOf(')', closeBracket + 2) > closeBracket + 1
                    if (isLink) {
                        val label = text.substring(i + 1, closeBracket)
                        val url = text.substring(closeBracket + 2, text.indexOf(')', closeBracket + 2)).trim()
                        val inner = parseInline(label)
                        if (inner.isEmpty()) {
                            emit(url, emptySet(), url.ifEmpty { null })
                        } else {
                            emit(inner.map { it.copy(linkUrl = url.ifEmpty { null }) })
                        }
                        i = text.indexOf(')', closeBracket + 2) + 1
                    } else {
                        literal.append(c)
                        i++
                    }
                }

                else -> {
                    literal.append(c)
                    i++
                }
            }
        }
        flushLiteral()
        return out
    }

    /**
     * Handles `*em*`, `**strong**`, `***both***`, `_em_`, `__strong__`.
     *
     * Returns the index just past the construct, or 0 when this marker run is not a
     * closed emphasis span (so the caller can treat it as literal text).
     */
    private inline fun consumeEmphasis(
        text: String,
        start: Int,
        run: Int,
        onStyled: (inner: String, styles: Set<MarkdownInlineStyle>) -> Unit
    ): Int {
        val marker = text[start]
        val after = text.getOrNull(start + run)
        // An opener must be followed by content, and `_` must not sit inside a word.
        if (after == null || after.isWhitespace()) return 0
        if (marker == '_' && start > 0 && text[start - 1].isLetterOrDigit()) return 0

        val combos = when {
            run >= 3 -> listOf(
                Triple(3, setOf(MarkdownInlineStyle.BOLD, MarkdownInlineStyle.ITALIC), 3),
                Triple(2, setOf(MarkdownInlineStyle.BOLD), 2),
                Triple(1, setOf(MarkdownInlineStyle.ITALIC), 1)
            )
            run == 2 -> listOf(Triple(2, setOf(MarkdownInlineStyle.BOLD), 2))
            else -> listOf(Triple(1, setOf(MarkdownInlineStyle.ITALIC), 1))
        }

        for ((markerLength, styles, consumedChars) in combos) {
            val delimiter = marker.toString().repeat(markerLength)
            val close = findEmphasisClose(text, start + markerLength, delimiter)
            if (close > start + markerLength) {
                onStyled(text.substring(start + markerLength, close), styles)
                return close + markerLength
            }
        }
        return 0
    }

    private fun findEmphasisClose(text: String, from: Int, delimiter: String): Int {
        var cursor = from
        while (cursor <= text.length - delimiter.length) {
            val index = text.indexOf(delimiter, cursor)
            if (index < 0) return -1
            val before = text.getOrNull(index - 1)
            val after = text.getOrNull(index + delimiter.length)
            val okBefore = before != null && !before.isWhitespace()
            val okAfter = delimiter[0] != '_' || after == null || !after.isLetterOrDigit()
            if (okBefore && okAfter) return index
            cursor = index + delimiter.length
        }
        return -1
    }

    private fun findRun(text: String, from: Int, count: Int): Int {
        var cursor = from
        while (cursor < text.length) {
            val index = text.indexOf('`', cursor)
            if (index < 0) return -1
            var run = 0
            while (index + run < text.length && text[index + run] == '`') run++
            if (run == count) return index
            cursor = index + run
        }
        return -1
    }

    private fun addStyle(spans: List<MarkdownSpan>, style: MarkdownInlineStyle): List<MarkdownSpan> =
        spans.map { it.copy(styles = it.styles + style) }
}
