package com.localllm.android.ui.markdown

/**
 * Small, dependency-free Markdown parser tuned for chat output.
 *
 * It deliberately keeps incomplete streaming constructs as data: an unclosed code
 * fence, math block, or table row is rendered without requiring a second parse pass
 * once generation finishes. Raw HTML is not executed.
 */
object MarkdownParser {

    private val heading = Regex("^\\s{0,3}(#{1,6})\\s+(.*)$")
    private val unorderedItem = Regex("^(\\s*)([-+*])\\s+(.*)$")
    private val orderedItem = Regex("^(\\s*)(\\d+)[.)]\\s+(.*)$")
    private val tableSeparator = Regex("^:?-{3,}:?$")
    private val horizontalRule = Regex("^\\s{0,3}((\\*\\s*){3,}|(-\\s*){3,}|(_\\s*){3,})$")

    fun parse(source: String): MarkdownDocument {
        if (source.isEmpty()) return MarkdownDocument(emptyList())
        val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MarkdownBlock>()
        var index = 0

        fun startsBlock(line: String, at: Int): Boolean {
            val t = line.trimStart()
            return t.isBlank() || t.startsWith("```") || t.startsWith("~~~") ||
                t == "$$" || t == "\\[" || heading.matches(line) ||
                t.startsWith(">") || horizontalRule.matches(line) ||
                unorderedItem.matches(line) || orderedItem.matches(line) ||
                (at + 1 < lines.size && isTable(lines, at))
        }

        while (index < lines.size) {
            val line = lines[index]
            if (line.isBlank()) {
                index++
                continue
            }

            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                val result = parseFence(lines, index)
                blocks += result.first
                index = result.second
                continue
            }

            if (trimmed == "$$" || trimmed == "\\[") {
                val result = parseMathBlock(lines, index, trimmed)
                blocks += result.first
                index = result.second
                continue
            }

            heading.matchEntire(line)?.let { match ->
                blocks += MarkdownBlock.Heading(
                    level = match.groupValues[1].length,
                    content = parseInline(match.groupValues[2].trim())
                )
                index++
                continue
            }

            if (horizontalRule.matches(line)) {
                blocks += MarkdownBlock.HorizontalRule
                index++
                continue
            }

            if (trimmed.startsWith(">")) {
                val quote = mutableListOf<String>()
                while (index < lines.size && lines[index].trimStart().startsWith(">")) {
                    quote += lines[index].trimStart().removePrefix(">").removePrefix(" ")
                    index++
                }
                blocks += MarkdownBlock.Blockquote(parse(quote.joinToString("\n")).blocks)
                continue
            }

            if (isTable(lines, index)) {
                val result = parseTable(lines, index)
                blocks += result.first
                index = result.second
                continue
            }

            if (unorderedItem.matches(line) || orderedItem.matches(line)) {
                val result = parseList(lines, index)
                blocks += result.first
                index = result.second
                continue
            }

            val paragraph = mutableListOf<String>()
            while (index < lines.size && !startsBlock(lines[index], index)) {
                paragraph += lines[index].trimEnd()
                index++
            }
            if (paragraph.isNotEmpty()) {
                blocks += MarkdownBlock.Paragraph(parseInline(paragraph.joinToString("\n")))
            }
        }
        return MarkdownDocument(blocks)
    }

    private fun parseFence(lines: List<String>, start: Int): Pair<MarkdownBlock.CodeBlock, Int> {
        val opening = lines[start].trimStart()
        val marker = if (opening.startsWith("~~~")) "~~~" else "```"
        val language = opening.removePrefix(marker).trim().ifBlank { null }
        val code = mutableListOf<String>()
        var index = start + 1
        var closed = false
        while (index < lines.size) {
            if (lines[index].trimStart().startsWith(marker)) {
                closed = true
                index++
                break
            }
            code += lines[index]
            index++
        }
        return MarkdownBlock.CodeBlock(language, code.joinToString("\n"), closed) to index
    }

    private fun parseMathBlock(lines: List<String>, start: Int, opening: String): Pair<MarkdownBlock.MathBlock, Int> {
        val closing = if (opening == "$$") "$$" else "\\]"
        val values = mutableListOf<String>()
        var index = start + 1
        var closed = false
        while (index < lines.size) {
            if (lines[index].trim() == closing) {
                closed = true
                index++
                break
            }
            values += lines[index]
            index++
        }
        return MarkdownBlock.MathBlock(values.joinToString("\n"), closed) to index
    }

    private fun parseList(lines: List<String>, start: Int): Pair<MarkdownBlock.ListBlock, Int> {
        val first = listMarker(lines[start]) ?: return MarkdownBlock.ListBlock(false, 1, emptyList()) to start + 1
        val baseIndent = first.indent
        val ordered = first.ordered
        val items = mutableListOf<MarkdownListItem>()
        var index = start

        while (index < lines.size) {
            val marker = listMarker(lines[index]) ?: break
            if (marker.indent != baseIndent || marker.ordered != ordered) break
            val contentLines = mutableListOf(marker.text)
            index++
            val childLines = mutableListOf<String>()
            while (index < lines.size) {
                val next = lines[index]
                val nextMarker = listMarker(next)
                if (nextMarker != null && nextMarker.indent == baseIndent) break
                if (next.isBlank()) {
                    if (index + 1 < lines.size && (listMarker(lines[index + 1])?.indent ?: Int.MAX_VALUE) >= baseIndent) {
                        index++
                        continue
                    }
                    break
                }
                val indent = next.takeWhile { it == ' ' || it == '\t' }.length
                if (indent <= baseIndent && nextMarker != null) break
                if (nextMarker != null && nextMarker.indent > baseIndent) {
                    childLines += next
                } else if (indent > baseIndent) {
                    contentLines += next.trim()
                } else {
                    break
                }
                index++
            }
            val children = if (childLines.isEmpty()) emptyList() else parse(childLines.joinToString("\n")).blocks
            items += MarkdownListItem(parseInline(contentLines.joinToString("\n")), children)
        }
        return MarkdownBlock.ListBlock(ordered, first.number, items) to index
    }

    private data class Marker(val indent: Int, val ordered: Boolean, val number: Int, val text: String)

    private fun listMarker(line: String): Marker? {
        unorderedItem.matchEntire(line)?.let {
            return Marker(it.groupValues[1].length, false, 1, it.groupValues[3])
        }
        orderedItem.matchEntire(line)?.let {
            val n = it.groupValues[2].toIntOrNull()?.coerceAtLeast(1) ?: 1
            return Marker(it.groupValues[1].length, true, n, it.groupValues[3])
        }
        return null
    }

    private fun isTable(lines: List<String>, index: Int): Boolean {
        if (index + 1 >= lines.size || '|' !in lines[index]) return false
        val separator = splitCells(lines[index + 1])
        return separator.isNotEmpty() && separator.all { tableSeparator.matches(it.trim()) }
    }

    private fun parseTable(lines: List<String>, start: Int): Pair<MarkdownBlock.Table, Int> {
        val headers = splitCells(lines[start]).map(::parseInline)
        val separators = splitCells(lines[start + 1])
        val alignments = List(headers.size) { col ->
            val marker = separators.getOrNull(col)?.trim().orEmpty()
            when {
                marker.startsWith(":") && marker.endsWith(":") -> TableAlignment.CENTER
                marker.endsWith(":") -> TableAlignment.END
                else -> TableAlignment.START
            }
        }
        val rows = mutableListOf<List<List<MarkdownInline>>>()
        var index = start + 2
        while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
            val cells = splitCells(lines[index])
            rows += List(headers.size) { col -> parseInline(cells.getOrNull(col).orEmpty()) }
            index++
        }
        return MarkdownBlock.Table(headers, rows, alignments) to index
    }

    /** Splits table rows without treating escaped pipes or inline code pipes as columns. */
    internal fun splitCells(line: String): List<String> {
        var value = line.trim()
        if (value.startsWith("|")) value = value.drop(1)
        if (value.endsWith("|") && !value.endsWith("\\|")) value = value.dropLast(1)
        val cells = mutableListOf<String>()
        val current = StringBuilder()
        var escaped = false
        var codeTicks = 0
        for (c in value) {
            when {
                escaped -> { current.append(c); escaped = false }
                c == '\\' -> { current.append(c); escaped = true }
                c == '`' -> { current.append(c); codeTicks = 1 - codeTicks }
                c == '|' && codeTicks == 0 -> { cells += current.toString().trim(); current.clear() }
                else -> current.append(c)
            }
        }
        cells += current.toString().trim()
        return cells
    }

    internal fun parseInline(text: String): List<MarkdownInline> {
        val output = mutableListOf<MarkdownInline>()

        fun parseSegment(value: String, styles: Set<InlineStyle>, href: String? = null) {
            var i = 0
            val literal = StringBuilder()
            fun emit(valueText: String, newStyles: Set<InlineStyle> = styles, link: String? = href) {
                if (valueText.isEmpty()) return
                appendInline(output, MarkdownInline(valueText, newStyles, link))
            }
            fun emitLiteral() { if (literal.isNotEmpty()) { emit(literal.toString()); literal.clear() } }
            while (i < value.length) {
                val c = value[i]
                if (c == '\\' && i + 1 < value.length) {
                    literal.append(value[i + 1]); i += 2; continue
                }
                if (c == '`') {
                    val end = value.indexOf('`', i + 1)
                    if (end > i) {
                        emitLiteral()
                        emit(value.substring(i + 1, end), styles + InlineStyle.CODE)
                        i = end + 1
                        continue
                    }
                }
                if (c == '[') {
                    val closeLabel = value.indexOf(']', i + 1)
                    if (closeLabel > i && closeLabel + 1 < value.length && value[closeLabel + 1] == '(') {
                        val closeUrl = value.indexOf(')', closeLabel + 2)
                        if (closeUrl > closeLabel) {
                            emitLiteral()
                            val label = value.substring(i + 1, closeLabel)
                            val url = value.substring(closeLabel + 2, closeUrl).trim()
                            if (url.isNotBlank()) parseSegment(label, styles, url) else emit(label)
                            i = closeUrl + 1
                            continue
                        }
                    }
                }
                if (c == '$') {
                    val marker = if (value.startsWith("$$", i)) "$$" else "$"
                    val end = value.indexOf(marker, i + marker.length)
                    if (end > i + marker.length) {
                        emitLiteral()
                        val expression = value.substring(i + marker.length, end).trim()
                        if (expression.isNotEmpty()) {
                            emit(expression, styles + InlineStyle.MATH, href)
                        }
                        i = end + marker.length
                        continue
                    }
                }
                val pair = when {
                    value.startsWith("**", i) -> "**"
                    value.startsWith("__", i) -> "__"
                    value.startsWith("~~", i) -> "~~"
                    c == '*' || c == '_' -> c.toString()
                    else -> null
                }
                if (pair != null) {
                    val end = value.indexOf(pair, i + pair.length)
                    if (end >= i + pair.length) {
                        emitLiteral()
                        val mark = when (pair) {
                            "**", "__" -> InlineStyle.BOLD
                            "~~" -> InlineStyle.STRIKETHROUGH
                            else -> InlineStyle.ITALIC
                        }
                        parseSegment(value.substring(i + pair.length, end), styles + mark, href)
                        i = end + pair.length
                        continue
                    }
                }
                literal.append(c)
                i++
            }
            emitLiteral()
        }
        parseSegment(text, emptySet())
        return output.ifEmpty { listOf(MarkdownInline("")) }
    }

    private fun appendInline(target: MutableList<MarkdownInline>, value: MarkdownInline) {
        val last = target.lastOrNull()
        if (last != null && last.styles == value.styles && last.href == value.href) {
            target[target.lastIndex] = last.copy(text = last.text + value.text)
        } else {
            target += value
        }
    }
}
