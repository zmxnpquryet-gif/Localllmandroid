package com.localllm.android.audio

import com.localllm.android.model.ChatMessage
import com.localllm.android.model.LlmModel
import com.localllm.android.model.MessageRole
import com.localllm.android.model.PromptTemplateType

/**
 * Pure prompt-envelope builder and family classifier for native Omni audio sessions.
 */
internal object NativeOmniPromptBuilder {

    private const val SYSTEM_INSTRUCTION =
        "You are OffconAI, a helpful on-device voice assistant. " +
            "Understand the user's speech directly. Reply in the user's language, " +
            "naturally and concisely. Speak the answer."

    fun detectFamily(model: LlmModel): OmniModelFamily {
        val key = "${model.id} ${model.name}".lowercase()
        return when {
            "lfm3" in key || "liquid" in key -> OmniModelFamily.LFM3_OMNI
            "qwen" in key -> OmniModelFamily.QWEN_OMNI
            "gemma" in key -> OmniModelFamily.GEMMA_AUDIO
            "moshi" in key -> OmniModelFamily.MOSHI
            else -> OmniModelFamily.CUSTOM
        }
    }

    /** Returns `(promptPrefixWithMediaMarker, promptContinuation)` for `libmtmd` Omni turns. */
    fun buildEnvelope(model: LlmModel, messages: List<ChatMessage>): Pair<String, String> {
        val history = messages
            .filter { it.role == MessageRole.USER || it.role == MessageRole.ASSISTANT }
            .takeLast(8)

        if (model.promptTemplateType == PromptTemplateType.GEMMA4) {
            val prefix = StringBuilder("<|turn>system\n").append(SYSTEM_INSTRUCTION).append("<turn|>\n")
            history.forEach { message ->
                val role = if (message.role == MessageRole.USER) "user" else "model"
                prefix.append("<|turn>").append(role).append('\n')
                    .append(message.content).append("<turn|>\n")
            }
            prefix.append("<|turn>user\n<__media__>")
            return prefix.toString() to "<turn|>\n<|turn>model\n"
        }

        if (model.promptTemplateType == PromptTemplateType.QWEN35 ||
            model.promptTemplateType == PromptTemplateType.CHATML
        ) {
            val prefix = StringBuilder("<|im_start|>system\n").append(SYSTEM_INSTRUCTION).append("<|im_end|>\n")
            history.forEach { message ->
                val role = if (message.role == MessageRole.USER) "user" else "assistant"
                prefix.append("<|im_start|>").append(role).append('\n')
                    .append(message.content).append("<|im_end|>\n")
            }
            prefix.append("<|im_start|>user\n<__media__>")
            return prefix.toString() to "<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
        }

        val prefix = StringBuilder("<|im_start|>system\n").append(SYSTEM_INSTRUCTION).append("<|im_end|>\n")
        history.forEach { message ->
            val role = if (message.role == MessageRole.USER) "user" else "assistant"
            prefix.append("<|im_start|>").append(role).append('\n')
            if (role == "assistant") {
                prefix.append("<think></think>").append(message.content)
            } else {
                prefix.append(message.content)
            }
            prefix.append("<|im_end|>\n")
        }
        prefix.append("<|im_start|>user\n<__media__>")
        return prefix.toString() to "<|im_end|>\n<|im_start|>assistant\n<think></think>"
    }
}
