package com.localllm.android.engine

/**
 * Fence-aware string and JSON delimiter scanning helpers shared by tool-call dialect parsers.
 */
internal object ToolTextSpans {

    fun stripRanges(text: String, ranges: List<IntRange>): String {
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(text.length)
        var cursor = 0
        for (range in ranges.sortedBy { it.first }) {
            if (range.first > cursor) sb.append(text, cursor, range.first)
            cursor = maxOf(cursor, range.last + 1)
        }
        if (cursor < text.length) sb.append(text, cursor, text.length)
        return sb.toString()
            .replace(Regex("\\n{3,}"), "\n\n")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }

    /**
     * Index of the delimiter that closes the one at [start], ignoring braces inside
     * JSON strings. -1 when the text ends first (a truncated reply).
     */
    fun matchDelimited(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        var inString = false
        var escaped = false
        while (i < text.length) {
            val c = text[i]
            if (inString) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
            } else {
                when (c) {
                    '"' -> inString = true
                    open -> depth++
                    close -> {
                        depth--
                        if (depth == 0) return i
                    }
                }
            }
            i++
        }
        return -1
    }

    /**
     * Ranges of triple-backtick fenced spans. An unclosed fence runs to the end of
     * the text (a code block still being streamed is still a code block).
     */
    fun fenceSpans(text: String): List<IntRange> {
        val out = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val start = text.indexOf("```", from)
            if (start < 0) break
            val end = text.indexOf("```", start + 3)
            if (end < 0) {
                out.add(start..text.length)
                break
            }
            out.add(start..end + 2)
            from = end + 3
        }
        return out
    }

    fun indexOfOutsideFences(text: String, needle: String, fromIndex: Int): Int {
        val fences = fenceSpans(text)
        var at = text.indexOf(needle, fromIndex)
        while (at >= 0 && fences.any { at in it }) {
            at = text.indexOf(needle, at + 1)
        }
        return at
    }

    fun stripCodeFence(s: String): String {
        val t = s.trim()
        if (!t.startsWith("```")) return t
        return t.removePrefix("```json").removePrefix("```JSON").removePrefix("```")
            .removeSuffix("```").trim()
    }
}
