package com.localllm.android.audio

import com.localllm.android.model.ChatMessage
import com.localllm.android.model.LlmModel
import com.localllm.android.model.MessageRole
import com.localllm.android.model.PromptTemplateType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeOmniPromptBuilderTest {

    @Test
    fun `detects omni model families from id and display name`() {
        val lfm = LlmModel(id = "lfm3-omni-2b", name = "LFM3 Omni 2B", repoId = "r", fileName = "m.gguf", sizeBytes = 1L)
        val gemma = LlmModel(id = "gemma-4-e2b", name = "Gemma 4", repoId = "r", fileName = "m.gguf", sizeBytes = 1L)
        val qwen = LlmModel(id = "qwen3.5-omni", name = "Qwen Omni", repoId = "r", fileName = "m.gguf", sizeBytes = 1L)

        assertEquals(OmniModelFamily.LFM3_OMNI, NativeOmniPromptBuilder.detectFamily(lfm))
        assertEquals(OmniModelFamily.GEMMA_AUDIO, NativeOmniPromptBuilder.detectFamily(gemma))
        assertEquals(OmniModelFamily.QWEN_OMNI, NativeOmniPromptBuilder.detectFamily(qwen))
    }

    @Test
    fun `builds gemma4 and qwen35 omni envelopes with media marker`() {
        val history = listOf(
            ChatMessage(conversationId = "c1", role = MessageRole.USER, content = "Hello"),
            ChatMessage(conversationId = "c1", role = MessageRole.TOOL, content = "ignored tool row"),
            ChatMessage(conversationId = "c1", role = MessageRole.ASSISTANT, content = "Hi there")
        )

        val gemmaModel = LlmModel(
            id = "gemma4",
            name = "Gemma 4",
            repoId = "r",
            fileName = "gemma.gguf",
            sizeBytes = 1L,
            promptTemplateType = PromptTemplateType.GEMMA4
        )
        val (gemmaPrefix, gemmaCont) = NativeOmniPromptBuilder.buildEnvelope(gemmaModel, history)
        assertTrue(gemmaPrefix.contains("<|turn>user\nHello<turn|>"))
        assertTrue(gemmaPrefix.contains("<|turn>model\nHi there<turn|>"))
        assertTrue(!gemmaPrefix.contains("ignored tool row"))
        assertTrue(gemmaPrefix.endsWith("<|turn>user\n<__media__>"))
        assertEquals("<turn|>\n<|turn>model\n", gemmaCont)

        val qwenModel = gemmaModel.copy(id = "qwen35", promptTemplateType = PromptTemplateType.QWEN35)
        val (qwenPrefix, qwenCont) = NativeOmniPromptBuilder.buildEnvelope(qwenModel, history)
        assertTrue(qwenPrefix.endsWith("<|im_start|>user\n<__media__>"))
        assertTrue(qwenCont.contains("<|im_start|>assistant"))
    }
}
