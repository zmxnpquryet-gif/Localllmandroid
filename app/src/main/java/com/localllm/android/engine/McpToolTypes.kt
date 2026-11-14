package com.localllm.android.engine

/** A tool invocation the model asked for. Never executed without user confirmation. */
data class McpToolCall(
    val name: String,
    /** Arguments as a JSON object string. */
    val argumentsJson: String
)

data class ToolCallBatch(
    val calls: List<McpToolCall>,
    val prose: String,
    val errors: List<String> = emptyList(),
    val incomplete: Boolean = false
) {
    val hasWork: Boolean get() = calls.isNotEmpty() || errors.isNotEmpty() || incomplete
}

data class ToolExecution(
    val call: McpToolCall,
    val approved: Boolean,
    val result: McpToolCallResult? = null,
    val declined: Boolean = false
)

fun interface ToolApprovalPolicy {
    suspend fun approve(call: McpToolCall): Boolean
}

fun interface ToolInvoker {
    suspend fun invoke(call: McpToolCall): McpToolCallResult
}

/** What the parser made of a finished assistant turn. */
sealed class ToolCallParse {
    /** No tool call in the text. */
    data object None : ToolCallParse()

    /** A well-formed call to a tool that exists on the connected server. */
    data class Call(val call: McpToolCall, val textBefore: String) : ToolCallParse()

    /** The model tried to call a tool but got it wrong; [reason] is fed back to it. */
    data class Invalid(val reason: String, val textBefore: String) : ToolCallParse()
}
