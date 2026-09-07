package com.example.engine

import android.util.Log
import com.example.model.PromptTemplateType
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
        // 1. Try parsing local template JSON file if available
        if (!templateJsonPath.isNullOrBlank()) {
            val jsonFormatted = tryFormatWithJsonTemplate(
                templateJsonPath = templateJsonPath,
                systemPrompt = systemPrompt,
                history = history,
                userPrompt = userPrompt,
                toolsContext = toolsContext,
                supportsReasoning = supportsReasoning
            )
            if (jsonFormatted != null) {
                return jsonFormatted
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

    private fun buildSystemInstruction(
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
    private fun formatGemma(
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
    private fun formatChatML(
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
    private fun formatPhi(
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
    private fun formatLlama3(
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
     * Parses custom template configurations from downloaded template JSON
     */
    private fun tryFormatWithJsonTemplate(
        templateJsonPath: String,
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean
    ): String? {
        return try {
            val file = File(templateJsonPath)
            if (!file.exists() || file.length() == 0L) return null
            val content = file.readText()
            val json = JSONObject(content)

            // 1. Check if chat_template contains gemma / phi indicators
            val chatTemplateStr = json.optString("chat_template", "")
            if (chatTemplateStr.contains("<start_of_turn>")) {
                return formatGemma(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            } else if (chatTemplateStr.contains("<|im_start|>")) {
                return formatChatML(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            } else if (chatTemplateStr.contains("<|user|>")) {
                return formatPhi(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            } else if (chatTemplateStr.contains("<|start_header_id|>")) {
                return formatLlama3(systemPrompt, history, userPrompt, toolsContext, supportsReasoning)
            }

            // 2. Check for custom prefix/suffix mappings
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

            null
        } catch (e: Exception) {
            Log.w(TAG, "Error formatting with JSON template: ${e.message}")
            null
        }
    }
}
