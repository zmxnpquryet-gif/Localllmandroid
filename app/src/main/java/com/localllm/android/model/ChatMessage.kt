package com.localllm.android.model

import java.util.UUID

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}

data class ChatAttachment(
    val uriString: String,
    val mimeType: String,
    val fileName: String,
    val isImage: Boolean
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val reasoning: String? = null,
    val isReasoningStreaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val tps: Float = 0f, // Tokens per second
    val promptSpeed: Float = 0f, // Prompt processing speed (t/s)
    val contextTokens: Int = 0,
    val attachment: ChatAttachment? = null,
    val isStreaming: Boolean = false
)
