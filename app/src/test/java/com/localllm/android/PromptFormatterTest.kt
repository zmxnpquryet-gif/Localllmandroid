package com.localllm.android

import com.localllm.android.engine.PromptFormatter
import com.localllm.android.model.PromptTemplateType
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptFormatterTest {

    @Test
    fun `test ChatML formatting contains system and user markers`() {
        val prompt = PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.CHATML,
            templateJsonPath = null,
            systemPrompt = "You are a helpful assistant.",
            history = listOf("user" to "Hi", "assistant" to "Hello!"),
            userPrompt = "What is 2+2?",
            toolsContext = null,
            supportsReasoning = false
        )

        assertTrue(prompt.contains("<|im_start|>system"))
        assertTrue(prompt.contains("You are a helpful assistant."))
        assertTrue(prompt.contains("<|im_start|>user\nHi<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>assistant\nHello!<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nWhat is 2+2?<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `test Gemma formatting structure`() {
        val prompt = PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.GEMMA,
            templateJsonPath = null,
            systemPrompt = "",
            history = emptyList(),
            userPrompt = "Explain gravity",
            toolsContext = null,
            supportsReasoning = false
        )

        assertTrue(prompt.contains("<start_of_turn>user"))
        assertTrue(prompt.contains("Explain gravity"))
        assertTrue(prompt.contains("<end_of_turn>"))
        assertTrue(prompt.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `test Llama3 formatting structure`() {
        val prompt = PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.LLAMA3,
            templateJsonPath = null,
            systemPrompt = "You are a fast on-device assistant.",
            history = emptyList(),
            userPrompt = "Hello",
            toolsContext = null,
            supportsReasoning = false
        )

        assertTrue(prompt.contains("<|begin_of_text|>"))
        assertTrue(prompt.contains("<|start_header_id|>system<|end_header_id|>"))
        assertTrue(prompt.contains("<|start_header_id|>user<|end_header_id|>"))
        assertTrue(prompt.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }
}
