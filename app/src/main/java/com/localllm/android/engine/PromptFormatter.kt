package com.localllm.android.engine

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

import android.util.Log
import com.localllm.android.model.PromptTemplateType
import org.json.JSONObject
import java.io.File

/**
 * Formats prompt turns based on model specifications (Gemma, Phi, ChatML, Llama 3, Custom JSON).
 */
object PromptFormatter {

    private const val TAG = "PromptFormatter"

    fun formatPrompt(
        templateType: PromptTemplateType,
        templateJsonPath: String?,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String {
        // 1. Try parsing local template file (Jinja or JSON) if available
        if (!templateJsonPath.isNullOrBlank()) {
            val formatted = tryFormatWithTemplateFile(
                templateFilePath = templateJsonPath,
                systemPrompt = systemPrompt,
                history = history,
                userPrompt = userPrompt,
                toolsContext = toolsContext,
                supportsReasoning = supportsReasoning
            )
            if (formatted != null) {
                return formatted
            }
        }

        // 2. Fall back to standard model prompt formats
        return when (templateType) {
            PromptTemplateType.GEMMA -> formatGemma(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            PromptTemplateType.PHI -> formatPhi(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            PromptTemplateType.LLAMA3 -> formatLlama3(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            PromptTemplateType.LFM3 -> formatLfm3(systemPrompt, history, userPrompt, toolsContext)
            PromptTemplateType.CHATML, PromptTemplateType.CUSTOM -> formatChatML(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
        }
    }

    /**
     * History as the model must see it: at most 6 turns, roles folded to
     * user/assistant, and opening with a user turn. A raw takeLast(6) cut can start
     * on an assistant turn (or a stray system/tool row), which makes some models
     * refuse the prompt or misattribute the first turn. At least one turn is kept.
     */
    fun normalizedHistory(history: List<Pair<String, String>>): List<Pair<String, String>> {
        val tail = history.takeLast(6).map { (role, text) ->
            (if (role.equals("user", ignoreCase = true)) "user" else "assistant") to text
        }.toMutableList()
        while (tail.size > 1 && tail.first().first != "user") tail.removeAt(0)
        return tail
    }

    fun buildSystemInstruction(
        systemPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String {
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
            } else {
                append(AppStrings.get(R.string.sys_prompt))
            }
            if (!toolsContext.isNullOrBlank()) {
                append("\n\n" + AppStrings.get(R.string.sys_ctx_header) + "\n").append(toolsContext.trim())
            }
            if (supportsReasoning) {
                append("\n" + AppStrings.get(R.string.sys_think))
            }
        }
    }

    /**
     * Gemma format (<start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n)
     */
    /**
     * Gemma format (<start_of_turn>user\n...<end_of_turn>\n<start_of_turn>model\n)
     */
    fun formatGemma(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning)

        // For Gemma, system prompt is typically placed inside the first user turn or before it
        var isFirstUserTurn = true
        for ((role, text) in normalizedHistory(history)) {
            val turnRole = if (role.equals("user", ignoreCase = true)) "user" else "model"
            sb.append("<start_of_turn>").append(turnRole).append("\n")
            if (turnRole == "user" && isFirstUserTurn && systemText.isNotBlank()) {
                sb.append(systemText).append("\n\n")
                isFirstUserTurn = false
            }
            sb.append(text.trim()).append("<end_of_turn>\n")
        }

        sb.append("<start_of_turn>user\n")
        if (isFirstUserTurn && systemText.isNotBlank()) {
            sb.append(systemText).append("\n\n")
        }
        sb.append(userPrompt.trim()).append("<end_of_turn>\n")
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }

    /**
     * ChatML format (<|im_start|>user\n...<|im_end|>\n<|im_start|>assistant\n)
     */
    fun formatChatML(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning)

        sb.append("<|im_start|>system\n").append(systemText).append("<|im_end|>\n")

        for ((role, text) in normalizedHistory(history)) {
            val normalizedRole = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
            sb.append("<|im_start|>").append(normalizedRole).append("\n").append(text.trim()).append("<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n").append(userPrompt.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }

    /**
     * LFM3 format: ChatML-style turns with explicit thinking blocks and no BOS.
     * The native tokenizer adds BOS, matching the official template's leading
     * `bos_token`. Previous assistant turns get an empty thinking block because
     * the app persists only user-visible content.
     */
    fun formatLfm3(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning = false)
        if (systemText.isNotBlank()) {
            sb.append("<|im_start|>system\n").append(systemText).append("<|im_end|>\n")
        }

        for ((role, text) in normalizedHistory(history)) {
            if (role.equals("user", ignoreCase = true)) {
                sb.append("<|im_start|>user\n").append(text.trim()).append("<|im_end|>\n")
            } else {
                val content = text.trim()
                sb.append("<|im_start|>assistant\n")
                if (content.contains("<think>")) {
                    sb.append(content)
                } else {
                    sb.append("<think></think>").append(content)
                }
                sb.append("<|im_end|>\n")
            }
        }

        sb.append("<|im_start|>user\n").append(userPrompt.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n<think>")
        return sb.toString()
    }

    /**
     * Phi format (<|system|>\n...<|end|>\n<|user|>\n...<|end|>\n<|assistant|>\n)
     */
    fun formatPhi(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning)

        sb.append("<|system|>\n").append(systemText).append("<|end|>\n")

        for ((role, text) in normalizedHistory(history)) {
            val normalizedRole = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
            sb.append("<|").append(normalizedRole).append("|>\n").append(text.trim()).append("<|end|>\n")
        }

        sb.append("<|user|>\n").append(userPrompt.trim()).append("<|end|>\n")
        sb.append("<|assistant|>\n")
        return sb.toString()
    }

    /**
     * Llama 3 format (<|start_header_id|>system<|end_header_id|>\n\n...<|eot_id|>)
     */
    fun formatLlama3(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning)

        sb.append("<|start_header_id|>system<|end_header_id|>\n\n").append(systemText).append("<|eot_id|>")

        for ((role, text) in normalizedHistory(history)) {
            val normalizedRole = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
            sb.append("<|start_header_id|>").append(normalizedRole).append("<|end_header_id|>\n\n")
                .append(text.trim()).append("<|eot_id|>")
        }

        sb.append("<|start_header_id|>user<|end_header_id|>\n\n").append(userPrompt.trim()).append("<|eot_id|>")
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }

    /**
     * Parses custom template configurations from downloaded template file (Jinja or JSON).
     */
    private fun tryFormatWithTemplateFile(
        templateFilePath: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String? {
        return try {
            val file = File(templateFilePath)
            if (!file.exists() || file.length() == 0L) return null
            val content = file.readText().trim()

            // 1. Check if the file is directly a Jinja chat template (.jinja or contains Jinja tags)
            if (file.name.endsWith(".jinja", ignoreCase = true) ||
                content.contains("{% for") ||
                content.contains("{{") ||
                content.startsWith("{%")
            ) {
                val jinjaRendered = JinjaTemplateEvaluator.evaluate(
                    templateText = content,
                    systemPrompt = systemPrompt,
                    history = history,
                    userPrompt = userPrompt,
                    toolsContext = toolsContext,
                    supportsReasoning = supportsReasoning
                )
                if (!jinjaRendered.isNullOrBlank()) return jinjaRendered
            }

            // 2. Check if the file is a JSON file (e.g., tokenizer_config.json)
            if (file.name.endsWith(".json", ignoreCase = true) || content.startsWith("{")) {
                val json = JSONObject(content)

                // 2-a. Check for chat_template inside JSON (which is a Jinja string)
                val chatTemplateStr = json.optString("chat_template", "")
                if (chatTemplateStr.isNotBlank()) {
                    val rendered = JinjaTemplateEvaluator.evaluate(
                        templateText = chatTemplateStr,
                        systemPrompt = systemPrompt,
                        history = history,
                        userPrompt = userPrompt,
                        toolsContext = toolsContext,
                        supportsReasoning = supportsReasoning
                    )
                    if (!rendered.isNullOrBlank()) return rendered
                }

                // 2-b. Check for custom prefix/suffix mappings
                val userPrefix = json.optString("user_prefix", "")
                val assistantPrefix = json.optString("assistant_prefix", "")
                if (userPrefix.isNotBlank() && assistantPrefix.isNotBlank()) {
                    val userSuffix = json.optString("user_suffix", "")
                    val assistantSuffix = json.optString("assistant_suffix", "")
                    val systemPrefix = json.optString("system_prefix", "")
                    val systemSuffix = json.optString("system_suffix", "")

                    val sb = StringBuilder()
                    val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning)
                    if (systemText.isNotBlank() && systemPrefix.isNotBlank()) {
                        sb.append(systemPrefix).append(systemText).append(systemSuffix).append("\n")
                    }
                    for ((role, text) in normalizedHistory(history)) {
                        if (role.equals("user", ignoreCase = true)) {
                            sb.append(userPrefix).append(text.trim()).append(userSuffix).append("\n")
                        } else {
                            sb.append(assistantPrefix).append(text.trim()).append(assistantSuffix).append("\n")
                        }
                    }
                    sb.append(userPrefix).append(userPrompt.trim()).append(userSuffix).append("\n")
                    sb.append(assistantPrefix)
                    return sb.toString()
                }
            }

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error formatting with template file: ${e.message}")
            null
        }
    }
}

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
        supportsReasoning: Boolean = false
    ): String? {
        val trimmedTemplate = templateText.trim()
        if (trimmedTemplate.isBlank()) return null

        // 1. Signature detection for known control tokens
        if (trimmedTemplate.contains("<start_of_turn>")) {
            return PromptFormatter.formatGemma(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
        }
        if (trimmedTemplate.contains("<|im_start|>")) {
            return PromptFormatter.formatChatML(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
        }
        if (trimmedTemplate.contains("<|start_header_id|>")) {
            return PromptFormatter.formatLlama3(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
        }
        if (trimmedTemplate.contains("<|user|>") && (trimmedTemplate.contains("<|end|>") || trimmedTemplate.contains("<|assistant|>"))) {
            return PromptFormatter.formatPhi(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
        }

        // 2. Generic Jinja Chat Template interpreter
        return try {
            val messages = mutableListOf<Map<String, String>>()
            if (systemPrompt.isNotBlank() || !toolsContext.isNullOrBlank() || supportsReasoning) {
                val fullSystem = PromptFormatter.buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning)
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

            // Extract the body inside `{% for message in messages %}` ... `{% endfor %}`
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
                        loopBody, msg,
                        loopIndex = index, loopTotal = messages.size
                    )
                    if (renderedTurn.isNotBlank()) {
                        output.append(renderedTurn)
                    }
                }

                // After loop: check for `{% if add_generation_prompt %}`
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

        // Evaluate {% if %} / {% elif %} / {% else %} / {% endif %} first: stripping
        // the tags (the old behavior) pasted every branch into every message.
        body = evaluateConditionals(body, message, loopIndex, loopTotal)

        // Replace {{ ... }} expressions
        val exprRegex = Regex("""\{\{(.*?)\}\}""")
        val result = exprRegex.replace(body) { mr ->
            val expr = mr.groupValues[1].trim()
            renderExpression(expr, message)
        }

        // Clean remaining control tags
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

    /**
     * Minimal conditional: innermost {% if %} blocks with elif/else chains, where
     * conditions are role comparisons (`message['role'] == 'user'`), loop position
     * (`loop.first`, `loop.last`), `add_generation_prompt` (true here), booleans,
     * and not/and/or over those. Anything unrecognized evaluates to false rather
     * than pasting all branches. No nested {% if %} support (inner blocks are left
     * for a later pass to strip, as before).
     */
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
            // Split the branches: [if-cond] text (elif-cond text)* (else text)?.
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
            // One level per pass: a picked branch may itself contain an {% if %},
            // which the next loop iteration handles.
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
        // or
        splitTopLevel(expr, "or").takeIf { it.size > 1 }?.let { parts ->
            return parts.any { evalCondition(it, message, loopIndex, loopTotal) }
        }
        // and
        splitTopLevel(expr, "and").takeIf { it.size > 1 }?.let { parts ->
            return parts.all { evalCondition(it, message, loopIndex, loopTotal) }
        }
        var e = expr.trim()
        if (e.startsWith("not ") || e.startsWith("not(")) {
            return !evalCondition(e.removePrefix("not").trim().removeSurrounding("(", ")"), message, loopIndex, loopTotal)
        }
        if (e.startsWith("(") && e.endsWith(")")) {
            return evalCondition(e.substring(1, e.length - 1), message, loopIndex, loopTotal)
        }
        val role = message["role"] ?: ""
        // message['role'] == 'user' / message.role != "system"
        val eq = Regex("""^(message\[.role.\]|message\.role|role)\s*(==|!=)\s*['"](.*?)['"]$""").matchEntire(e)
        if (eq != null) {
            val left = when (eq.groupValues[1]) {
                "role" -> role
                else -> role
            }
            val same = left.equals(eq.groupValues[3], ignoreCase = true)
            return if (eq.groupValues[2] == "==") same else !same
        }
        if (e == "loop.first") return loopIndex == 0
        if (e == "loop.last") return loopIndex == loopTotal - 1
        if (e == "add_generation_prompt") return true
        if (e == "true" || e == "True") return true
        if (e == "false" || e == "False" || e.isEmpty()) return false
        // Unknown predicate: fail closed (drop the branch) rather than pasting it.
        return false
    }

    /** Splits a {{ a + 'b+c' }} concatenation, ignoring '+' inside quotes. */
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

    /** Splits on a bare keyword operator, ignoring occurrences inside quotes. */
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
        // A '+' inside a quoted literal ('a+b') is text, not concatenation.
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
                part == "message['content']" || part == "message.content" || part == "content" -> {
                    if (trimContent) content.trim() else content
                }
                (part.startsWith("'") && part.endsWith("'")) || (part.startsWith("\"") && part.endsWith("\"")) -> {
                    val unquoted = part.substring(1, part.length - 1)
                    unquoted.replace("\\n", "\n").replace("\\t", "\t")
                }
                else -> ""
            }
            sb.append(text)
        }
        return sb.toString()
    }
}
