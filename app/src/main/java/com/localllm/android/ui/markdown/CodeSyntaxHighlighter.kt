package com.localllm.android.ui.markdown

data class HighlightSpan(val start: Int, val end: Int, val kind: HighlightKind)

enum class HighlightKind { KEYWORD, STRING, NUMBER, COMMENT, TYPE, BOOLEAN }

/**
 * Deterministic, low-cost highlighter for chat snippets. It is lexical rather than
 * a language parser and intentionally leaves unknown languages unhighlighted.
 */
object CodeSyntaxHighlighter {

    private val keywords = setOf(
        "as", "async", "await", "break", "case", "catch", "class", "const", "continue",
        "def", "do", "else", "enum", "export", "extends", "false", "finally", "for",
        "from", "fun", "function", "if", "import", "in", "interface", "internal", "is",
        "let", "new", "null", "object", "override", "package", "private", "protected",
        "public", "return", "sealed", "static", "super", "switch", "throw", "true", "try",
        "type", "val", "var", "void", "when", "while", "with", "yield", "None", "True",
        "False", "SELECT", "FROM", "WHERE", "JOIN", "GROUP", "ORDER", "BY", "LIMIT"
    )

    private val supported = setOf(
        "kt", "kts", "kotlin", "java", "js", "jsx", "ts", "tsx", "python", "py",
        "json", "bash", "sh", "shell", "powershell", "ps1", "xml", "html", "css",
        "sql", "c", "cpp", "c++", "rust", "rs", "go", "swift", "yaml", "yml", "toml"
    )

    fun highlight(code: String, language: String?): List<HighlightSpan> {
        val lang = language?.lowercase()?.trim()
        if (lang.isNullOrBlank() || lang !in supported) return emptyList()
        val out = mutableListOf<HighlightSpan>()
        var i = 0
        while (i < code.length) {
            val lineComment = lineCommentAt(code, i, lang)
            if (lineComment >= 0) {
                val end = code.indexOf('\n', lineComment).let { if (it < 0) code.length else it }
                out += HighlightSpan(lineComment, end, HighlightKind.COMMENT)
                i = end
                continue
            }
            if (code.startsWith("/*", i) && lang !in setOf("python", "py", "bash", "sh", "shell", "ps1", "powershell")) {
                val end = code.indexOf("*/", i + 2).let { if (it < 0) code.length else it + 2 }
                out += HighlightSpan(i, end, HighlightKind.COMMENT)
                i = end
                continue
            }
            val c = code[i]
            if (c == '"' || c == '\'' || (c == '`' && lang in setOf("js", "jsx", "ts", "tsx"))) {
                val end = findQuotedEnd(code, i, c)
                out += HighlightSpan(i, end, HighlightKind.STRING)
                i = end
                continue
            }
            if (c.isDigit()) {
                var end = i + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] in ".xX_")) end++
                out += HighlightSpan(i, end, HighlightKind.NUMBER)
                i = end
                continue
            }
            if (c.isLetter() || c == '_' || c == '$') {
                var end = i + 1
                while (end < code.length && (code[end].isLetterOrDigit() || code[end] == '_' || code[end] == '$')) end++
                val word = code.substring(i, end)
                val kind = when {
                    word in setOf("true", "false", "null", "None", "True", "False") -> HighlightKind.BOOLEAN
                    word in keywords -> HighlightKind.KEYWORD
                    word.firstOrNull()?.isUpperCase() == true -> HighlightKind.TYPE
                    else -> null
                }
                if (kind != null) out += HighlightSpan(i, end, kind)
                i = end
                continue
            }
            i++
        }
        return out
    }

    private fun lineCommentAt(code: String, index: Int, lang: String): Int {
        if (code.startsWith("//", index) && lang !in setOf("python", "py", "powershell", "ps1", "sql")) return index
        if (code[index] == '#' && lang in setOf("python", "py", "bash", "sh", "shell", "powershell", "ps1", "yaml", "yml", "toml")) return index
        if (code.startsWith("--", index) && lang == "sql") return index
        return -1
    }

    private fun findQuotedEnd(code: String, start: Int, quote: Char): Int {
        var i = start + 1
        var escaped = false
        while (i < code.length) {
            val c = code[i]
            if (escaped) {
                escaped = false
            } else if (c == '\\') {
                escaped = true
            } else if (c == quote) {
                return i + 1
            } else if (c == '\n' && quote != '`') {
                return i
            }
            i++
        }
        return code.length
    }
}
