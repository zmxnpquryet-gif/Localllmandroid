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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true,
        documentContext: String? = null
    ): String {
        // RAG excerpts belong in the system instruction, next to the tools block, and
        // stay separate from the caller's system prompt. When a document block is
        // present the built-in prompt is materialized here, because
        // buildSystemInstruction only falls back to it for a blank prompt.
        val documentBlock = documentContext?.trim()?.takeIf { it.isNotEmpty() }
        val effectiveSystemPrompt = if (documentBlock == null) {
            systemPrompt
        } else {
            buildString {
                if (systemPrompt.isNotBlank()) {
                    append(systemPrompt.trim())
                } else if (includeBuiltInSystemPrompt) {
                    append(AppStrings.get(R.string.sys_prompt))
                }
                append("\n\n").append(AppStrings.get(R.string.sys_doc_header)).append("\n")
                append(documentBlock)
            }
        }

        // 1. Try parsing local template file (Jinja or JSON) if available
        if (!templateJsonPath.isNullOrBlank()) {
            val formatted = tryFormatWithTemplateFile(
                templateFilePath = templateJsonPath,
                systemPrompt = effectiveSystemPrompt,
                history = history,
                userPrompt = userPrompt,
                toolsContext = toolsContext,
                supportsReasoning = supportsReasoning,
                includeBuiltInSystemPrompt = includeBuiltInSystemPrompt
            )
            if (formatted != null) {
                return formatted
            }
        }

        // 2. Fall back to standard model prompt formats
        return when (templateType) {
            PromptTemplateType.GEMMA -> formatGemma(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
            PromptTemplateType.PHI -> formatPhi(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
            PromptTemplateType.LLAMA3 -> formatLlama3(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
            PromptTemplateType.LFM3 -> formatLfm3(effectiveSystemPrompt, history, userPrompt, toolsContext, includeBuiltInSystemPrompt)
            PromptTemplateType.GEMMA4 -> formatGemma4(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
            PromptTemplateType.QWEN35 -> formatQwen35(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
            PromptTemplateType.MINICPM5 -> formatMiniCPM5(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
            PromptTemplateType.CHATML, PromptTemplateType.CUSTOM -> formatChatML(effectiveSystemPrompt, history, userPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
        }
    }

    /**
     * Renders structured roles and metadata through the same model-specific token
     * templates as the legacy text API. This keeps tool/reasoning metadata available
     * without forcing every runtime into one JSON schema.
     */
    fun formatConversation(
        templateType: PromptTemplateType,
        templateJsonPath: String?,
        messages: List<TemplateMessage>,
        toolsContext: String?,
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true,
        documentContext: String? = null
    ): String {
        if (messages.isEmpty()) return ""
        val system = messages.filter { it.role == TemplateRole.SYSTEM }
            .joinToString("\n\n") { it.plainText() }
            .ifBlank { "" }
        val body = messages.filter { it.role != TemplateRole.SYSTEM }
        val lastUser = body.indexOfLast { it.role == TemplateRole.USER }
        if (lastUser < 0) {
            return formatPrompt(
                templateType,
                templateJsonPath,
                system,
                emptyList(),
                body.lastOrNull()?.plainText().orEmpty(),
                toolsContext,
                supportsReasoning,
                includeBuiltInSystemPrompt,
                documentContext
            )
        }
        fun asHistory(message: TemplateMessage): Pair<String, String> {
            val role = when (message.role) {
                TemplateRole.ASSISTANT -> "assistant"
                TemplateRole.TOOL -> "user"
                else -> "user"
            }
            val text = if (message.role == TemplateRole.TOOL) {
                buildString {
                    if (!message.name.isNullOrBlank()) append("tool:").append(message.name).append('\n')
                    append(message.plainText())
                }
            } else message.plainText()
            return role to text
        }
        val history = body.take(lastUser).map(::asHistory)
        val current = body[lastUser].plainText()
        return formatPrompt(
            templateType = templateType,
            templateJsonPath = templateJsonPath,
            systemPrompt = system,
            history = history,
            userPrompt = current,
            toolsContext = toolsContext,
            supportsReasoning = supportsReasoning,
            includeBuiltInSystemPrompt = includeBuiltInSystemPrompt,
            documentContext = documentContext
        )
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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        return buildString {
            if (systemPrompt.isNotBlank()) {
                append(systemPrompt.trim())
            } else if (includeBuiltInSystemPrompt) {
                // Low-end mode turns this off: every extra prefill token costs time on
                // weak hardware, and the tools/reasoning blocks still apply.
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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)

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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)

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
        toolsContext: String?,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning = false, includeBuiltInSystemPrompt)
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
     * Google Gemma 4 canonical turn format. The GGUF tokenizer supplies BOS.
     */
    fun formatGemma4(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
        if (systemText.isNotBlank() || supportsReasoning) {
            sb.append("<|turn>system\n")
            if (supportsReasoning) sb.append("<|think|>\n")
            sb.append(systemText).append("<turn|>\n")
        }
        for ((role, text) in normalizedHistory(history)) {
            val turnRole = if (role.equals("user", ignoreCase = true)) "user" else "model"
            sb.append("<|turn>").append(turnRole).append('\n')
                .append(text.trim()).append("<turn|>\n")
        }
        sb.append("<|turn>user\n").append(userPrompt.trim()).append("<turn|>\n")
        sb.append("<|turn>model\n")
        return sb.toString()
    }

    /**
     * Qwen 3.5 ChatML template with an explicit thinking channel. When thinking is
     * disabled the assistant opens with an empty think block, matching the official
     * tokenizer template rather than relying on hidden template defaults.
     */
    fun formatQwen35(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
        sb.append("<|im_start|>system\n").append(systemText).append("<|im_end|>\n")
        for ((role, text) in normalizedHistory(history)) {
            val normalizedRole = if (role.equals("user", ignoreCase = true)) "user" else "assistant"
            sb.append("<|im_start|>").append(normalizedRole).append('\n')
                .append(text.trim()).append("<|im_end|>\n")
        }
        sb.append("<|im_start|>user\n").append(userPrompt.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        if (supportsReasoning) sb.append("<think>\n") else sb.append("<think>\n\n</think>\n\n")
        return sb.toString()
    }

    /**
     * MiniCPM5 format: ChatML turns plus the model's own empty thinking block and
     * XML tool calls. The official template always opens an assistant turn with a
     * ` thinking` pair — an empty one when thinking is disabled — so history turns
     * must carry it too or the model sees a distribution it was never trained on.
     */
    fun formatMiniCPM5(
        systemPrompt: String,
        history: List<Pair<String, String>>,
        userPrompt: String,
        toolsContext: String?,
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
        if (systemText.isNotBlank()) {
            sb.append("<|im_start|>system\n").append(systemText).append("<|im_end|>\n")
        }

        for ((role, text) in normalizedHistory(history)) {
            val content = text.trim()
            if (role.equals("user", ignoreCase = true)) {
                sb.append("<|im_start|>user\n").append(content).append("<|im_end|>\n")
            } else {
                sb.append("<|im_start|>assistant\n")
                if (content.contains("<think>") || content.contains("</think>")) {
                    sb.append(content)
                } else {
                    sb.append("<think>\n\n</think>\n\n").append(content)
                }
                sb.append("<|im_end|>\n")
            }
        }

        sb.append("<|im_start|>user\n").append(userPrompt.trim()).append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n")
        // Thinking off keeps the empty think block the template emits for
        // enable_thinking=false; thinking on leaves the block open for the model.
        if (supportsReasoning) {
            sb.append("<think>")
        } else {
            sb.append("<think>\n\n</think>\n\n")
        }
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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)

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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean = true
    ): String {
        val sb = StringBuilder()
        sb.append("<|begin_of_text|>")
        val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)

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
        supportsReasoning: Boolean,
        includeBuiltInSystemPrompt: Boolean
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
                    val systemText = buildSystemInstruction(systemPrompt, toolsContext, supportsReasoning, includeBuiltInSystemPrompt)
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
