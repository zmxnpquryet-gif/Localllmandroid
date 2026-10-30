package com.localllm.android

import com.localllm.android.engine.JinjaTemplateEvaluator
import com.localllm.android.engine.PromptFormatter
import com.localllm.android.model.PromptTemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `LFM3 formatting adds thinking blocks to assistant turns`() {
        val prompt = PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.LFM3,
            templateJsonPath = null,
            systemPrompt = "You are a helpful assistant.",
            history = listOf("user" to "Hi", "assistant" to "Hello!"),
            userPrompt = "What is 2+2?",
            toolsContext = null,
            supportsReasoning = false
        )

        assertTrue(prompt.startsWith("<|im_start|>system\n"))
        assertTrue(prompt.contains("<|im_start|>assistant\n<think></think>Hello!<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\nWhat is 2+2?<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>"))
    }

    @Test
    fun `history cut mid-turn still opens with a user turn`() {
        // 7 turns: takeLast(6) would start on an assistant turn without normalization.
        val history = (0 until 7).map { i ->
            (if (i % 2 == 0) "user" else "assistant") to "turn$i"
        }
        val prompt = PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.CHATML,
            templateJsonPath = null,
            systemPrompt = "",
            history = history,
            userPrompt = "next?",
            toolsContext = null,
            supportsReasoning = false
        )
        val firstUser = prompt.indexOf("<|im_start|>user")
        val firstAssistant = prompt.indexOf("<|im_start|>assistant")
        assertTrue("first user turn must precede any assistant turn", firstUser in 0 until firstAssistant)
        assertFalse("the cut-off assistant turn must be gone", prompt.contains("turn1<|im_end|>"))
    }

    @Test
    fun `jinja role conditionals dispatch instead of merging branches`() {
        val template = "{% for message in messages %}" +
            "{% if message['role'] == 'user' %}U:{{ message['content'] }};" +
            "{% elif message['role'] == 'system' %}S:{{ message['content'] }};" +
            "{% else %}A:{{ message['content'] }};{% endif %}" +
            "{% endfor %}"
        val out = JinjaTemplateEvaluator.evaluate(
            templateText = template,
            systemPrompt = "",
            history = listOf("user" to "hi", "assistant" to "yo"),
            userPrompt = "?",
            toolsContext = null,
            supportsReasoning = false
        )!!
        assertTrue(out.contains("U:hi;"))
        assertTrue(out.contains("A:yo;"))
        assertFalse("assistant branch must not leak into the user turn", out.contains("A:hi;"))
        assertFalse("user branch must not leak into the assistant turn", out.contains("U:yo;"))
    }

    @Test
    fun `jinja unknown conditions fail closed`() {
        val template = "{% for message in messages %}" +
            "{% if frobnicate(message) %}X{% endif %}Y:{{ message['content'] }};" +
            "{% endfor %}"
        val out = JinjaTemplateEvaluator.evaluate(
            templateText = template,
            systemPrompt = "",
            history = listOf("user" to "hi"),
            userPrompt = "?",
            toolsContext = null,
            supportsReasoning = false
        )!!
        assertFalse(out.contains("X"))
        assertTrue(out.contains("Y:hi;"))
    }

    @Test
    fun `jinja plus inside string literals is preserved`() {
        val template = "{% for message in messages %}{{ 'a+b' }}{% endfor %}"
        val out = JinjaTemplateEvaluator.evaluate(
            templateText = template,
            systemPrompt = "",
            history = listOf("user" to "hi"),
            userPrompt = "?",
            toolsContext = null,
            supportsReasoning = false
        )!!
        assertTrue(out.contains("a+b"))
    }
}
