package com.localllm.android.engine

import android.util.Log

/**
 * Lightweight Jinja chat template interpreter designed for on-device mobile LLM runtimes (LiteRT-LM & llama.cpp).
 * Understands Hugging Face & Google standard chat_template.jinja formats.
 */
object JinjaTemplateEvaluator {

    private const val TAG = "JinjaTemplateEvaluator"

    fun evaluate(
        templateText: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String? = null,
        supportsReasoning: Boolean = false,
        effort: Float = 0.5f
    ): String? {
        val trimmedTemplate = templateText.trim()
        if (trimmedTemplate.isBlank()) return null

        if (trimmedTemplate.contains("<start_of_turn>")) {
            return PromptFormatter.formatGemma(systemPrompt, history, userPrompt, toolsContext, supportsReasoning, effort = effort)
        }
        if (trimmedTemplate.contains("<|im_start|>")) {
            return PromptFormatter.formatChatML(systemPrompt, history, userPrompt, toolsContext, supportsReasoning, effort = effort)
        }
        if (trimmedTemplate.contains("<|start_header_id|>")) {
            return PromptFormatter.formatLlama3(systemPrompt, history, userPrompt, toolsContext, supportsReasoning, effort = effort)
        }
        if (trimmedTemplate.contains("<|user|>") && (trimmedTemplate.contains("<|end|>") || trimmedTemplate.contains("<|assistant|>"))) {
            return PromptFormatter.formatPhi(systemPrompt, history, userPrompt, toolsContext, supportsReasoning, effort = effort)
        }

        return try {
            val messages = mutableListOf<Map<String, String>>()
            if (systemPrompt.isNotBlank() || !toolsContext.isNullOrBlank() || supportsReasoning) {
                val fullSystem = PromptFormatter.buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, effort = effort)
                messages.add(mapOf("role" to "system", "content" to fullSystem))
            }
            for ((role, text) in PromptFormatter.normalizedHistory(history)) {
                val r = when (role.lowercase()) {
                    "user" -> "user"
                    "assistant", "model" -> "assistant"
                    "system" -> "system"
                    else -> role
                }
                messages.add(mapOf("role" to r, "content" to text.trim()))
            }
            messages.add(mapOf("role" to "user", "content" to userPrompt.trim()))

            val forLoopRegex = Regex("""\{%[-\s]*for\s+message\s+in\s+messages\s*[-\s]*%\}(.*?)\{%[-\s]*endfor\s*[-\s]*%\}""", RegexOption.DOT_MATCHES_ALL)
            val forMatch = forLoopRegex.find(trimmedTemplate)

            val output = StringBuilder()

            if (forMatch != null) {
                val beforeLoop = trimmedTemplate.substring(0, forMatch.range.first).trim()
                if (beforeLoop.isNotBlank()) {
                    val cleanBefore = beforeLoop
                        .replace(Regex("""\{\{.*?bos_token.*?\}\}"""), "")
                        .replace(Regex("""\{%.*?%\}"""), "")
                        .trim()
                    if (cleanBefore.isNotBlank()) output.append(cleanBefore).append("\n")
                }

                val loopBody = forMatch.groupValues[1]

                for ((index, msg) in messages.withIndex()) {
                    val renderedTurn = renderMessageTurn(
                        loopBody,
                        msg,
                        loopIndex = index,
                        loopTotal = messages.size
                    )
                    if (renderedTurn.isNotBlank()) {
                        output.append(renderedTurn)
                    }
                }

                val afterLoop = trimmedTemplate.substring(forMatch.range.last + 1)
                val genPromptRegex = Regex("""\{%[-\s]*if\s+add_generation_prompt\s*[-\s]*%\}(.*?)\{%[-\s]*endif\s*[-\s]*%\}""", RegexOption.DOT_MATCHES_ALL)
                val genMatch = genPromptRegex.find(afterLoop)
                if (genMatch != null) {
                    val genBody = genMatch.groupValues[1]
                    val renderedGen = renderExpression(genBody, mapOf("role" to "assistant", "content" to ""))
                    output.append(renderedGen)
                }
                output.toString()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Dynamic Jinja evaluation error: ${e.message}")
            null
        }
    }

    private fun renderMessageTurn(
        loopBody: String,
        message: Map<String, String>,
        loopIndex: Int = 0,
        loopTotal: Int = 1
    ): String {
        var body = loopBody.replace("{%-", "{%").replace("-%}", "%}").replace("{{-", "{{").replace("-}}", "}}")
        body = evaluateConditionals(body, message, loopIndex, loopTotal)

        val exprRegex = Regex("""\{\{(.*?)\}\}""")
        val result = exprRegex.replace(body) { mr ->
            val expr = mr.groupValues[1].trim()
            renderExpression(expr, message)
        }

        return result
            .replace(Regex("""\{%.*?%\}"""), "")
            .replace(Regex("""\r\n|\r"""), "\n")
    }

    private val ifBlockRegex = Regex(
        """\{%\s*if\s+(.*?)\s*%\}((?:(?!\{%\s*if\b).)*?)\{%\s*endif\s*%\}""",
        RegexOption.DOT_MATCHES_ALL
    )
    private val elifSplitRegex = Regex("""\{%\s*elif\s+(.*?)\s*%\}""")
    private val elseSplitRegex = Regex("""\{%\s*else\s*%\}""")

    internal fun evaluateConditionals(
        body: String,
        message: Map<String, String>,
        loopIndex: Int,
        loopTotal: Int
    ): String {
        var out = body
        var guard = 0
        while (true) {
            val match = ifBlockRegex.find(out) ?: break
            if (++guard > 50) break
            val inner = match.groupValues[2]
            val branches = mutableListOf<Pair<String?, String>>()
            var rest = inner
            var firstCond = match.groupValues[1]
            while (true) {
                val elif = elifSplitRegex.find(rest)
                val els = elseSplitRegex.find(rest)
                val cut = listOfNotNull(
                    elif?.range?.first?.let { it to true },
                    els?.range?.first?.let { it to false }
                ).minByOrNull { it.first }
                if (cut == null) {
                    branches.add(firstCond to rest)
                    break
                }
                val isElif = cut.second
                val at = cut.first
                branches.add(firstCond to rest.substring(0, at))
                if (isElif) {
                    val m = elifSplitRegex.find(rest.substring(at))!!
                    firstCond = m.groupValues[1]
                    rest = rest.substring(at + m.range.last + 1)
                } else {
                    val m = elseSplitRegex.find(rest.substring(at))!!
                    branches.add(null to rest.substring(at + m.range.last + 1))
                    break
                }
            }
            var picked = ""
            for ((cond, text) in branches) {
                if (cond == null || evalCondition(cond, message, loopIndex, loopTotal)) {
                    picked = text
                    break
                }
            }
            out = out.substring(0, match.range.first) + picked +
                out.substring(match.range.last + 1)
        }
        return out
    }

    private fun evalCondition(
        expr: String,
        message: Map<String, String>,
        loopIndex: Int,
        loopTotal: Int
    ): Boolean {
        splitTopLevel(expr, "or").takeIf { it.size > 1 }?.let { parts ->
            return parts.any { evalCondition(it, message, loopIndex, loopTotal) }
        }
        splitTopLevel(expr, "and").takeIf { it.size > 1 }?.let { parts ->
            return parts.all { evalCondition(it, message, loopIndex, loopTotal) }
        }
        val e = expr.trim()
        if (e.startsWith("not ") || e.startsWith("not(")) {
            return !evalCondition(e.removePrefix("not").trim().removeSurrounding("(", ")"), message, loopIndex, loopTotal)
        }
        if (e.startsWith("(") && e.endsWith(")")) {
            return evalCondition(e.substring(1, e.length - 1), message, loopIndex, loopTotal)
        }
        val role = message["role"] ?: ""
        val eq = Regex("""^(message\[.role.\]|message\.role|role)\s*(==|!=)\s*['"](.*?)['"]$""").matchEntire(e)
        if (eq != null) {
            val same = role.equals(eq.groupValues[3], ignoreCase = true)
            return if (eq.groupValues[2] == "==") same else !same
        }
        if (e == "loop.first") return loopIndex == 0
        if (e == "loop.last") return loopIndex == loopTotal - 1
        if (e == "add_generation_prompt") return true
        if (e == "true" || e == "True") return true
        if (e == "false" || e == "False" || e.isEmpty()) return false
        return false
    }

    private fun splitConcatParts(expr: String): List<String> {
        val out = mutableListOf<String>()
        var quote: Char? = null
        var start = 0
        for (i in expr.indices) {
            val c = expr[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else if (c == '\'' || c == '"') {
                quote = c
            } else if (c == '+') {
                out.add(expr.substring(start, i))
                start = i + 1
            }
        }
        out.add(expr.substring(start))
        return out
    }

    private fun splitTopLevel(expr: String, keyword: String): List<String> {
        val out = mutableListOf<String>()
        var quote: Char? = null
        var depth = 0
        var start = 0
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (quote != null) {
                if (c == quote) quote = null
            } else when (c) {
                '\'', '"' -> quote = c
                '(' -> depth++
                ')' -> if (depth > 0) depth--
            }
            if (quote == null && depth == 0 &&
                expr.regionMatches(i, keyword, 0, keyword.length, ignoreCase = true)
            ) {
                val before = if (i == 0) ' ' else expr[i - 1]
                val afterIdx = i + keyword.length
                val after = if (afterIdx >= expr.length) ' ' else expr[afterIdx]
                if (!before.isLetterOrDigit() && before != '_' && !after.isLetterOrDigit() && after != '_') {
                    out.add(expr.substring(start, i))
                    i = afterIdx
                    start = i
                    continue
                }
            }
            i++
        }
        out.add(expr.substring(start))
        return out
    }

    private fun renderExpression(expr: String, message: Map<String, String>): String {
        val role = message["role"] ?: ""
        val content = message["content"] ?: ""

        val sb = StringBuilder()
        val tokens = splitConcatParts(expr)
        for (rawPart in tokens) {
            var part = rawPart.trim()
            var trimContent = false
            if (part.contains("|") && part.contains("trim")) {
                part = part.substringBefore("|").trim()
                trimContent = true
            }

            val text = when {
                part == "message['role']" || part == "message.role" || part == "role" -> role
                part == "message['content']" || part == "message.content" || part == "content" ->
                    if (trimContent) content.trim() else content
                (part.startsWith("'") && part.endsWith("'")) || (part.startsWith("\"") && part.endsWith("\"")) ->
                    part.substring(1, part.length - 1).replace("\\n", "\n").replace("\\t", "\t")
                else -> ""
            }
            sb.append(text)
        }
        return sb.toString()
    }
}
