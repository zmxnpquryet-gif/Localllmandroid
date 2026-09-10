package com.localllm.android.engine

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
            PromptTemplateType.CHATML, PromptTemplateType.CUSTOM -> formatChatML(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
        }
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
                append("당신은 Android 기기에서 완전히 로컬로 실행되는 친절하고 유능한 AI 어시스턴트입니다. 외부 서버 없이 기기 내부에서 모든 답변을 생성합니다.")
            }
            if (!toolsContext.isNullOrBlank()) {
                append("\n\n[현재 기기 로컬 컨텍스트 및 도구 정보]:\n").append(toolsContext.trim())
            }
            if (supportsReasoning) {
                append("\n답변을 작성할 때 사고 및 추론 과정은 반드시 <think>...</think> 태그 안에 작성하세요.")
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
        for ((role, text) in history.takeLast(6)) {
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

        for ((role, text) in history.takeLast(6)) {
            val normalizedRole = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
            sb.append("<|im_start|>").append(normalizedRole).append("\n").append(text.trim()).append("<|im_end|>\n")
        }

        sb.append("<|im_start|>user\n").append(userPrompt.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
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

        for ((role, text) in history.takeLast(6)) {
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

        for ((role, text) in history.takeLast(6)) {
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
                    for ((role, text) in history.takeLast(6)) {
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
            for ((role, text) in history.takeLast(6)) {
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

                for (msg in messages) {
                    val renderedTurn = renderMessageTurn(loopBody, msg)
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

    private fun renderMessageTurn(loopBody: String, message: Map<String, String>): String {
        var body = loopBody.replace("{%-", "{%").replace("-%}", "%}").replace("{{-", "{{").replace("-}}", "}}")

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

    private fun renderExpression(expr: String, message: Map<String, String>): String {
        val role = message["role"] ?: ""
        val content = message["content"] ?: ""

        val sb = StringBuilder()
        val tokens = expr.split("+")
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
