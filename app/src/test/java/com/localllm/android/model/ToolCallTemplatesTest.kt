package com.localllm.android.model

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCallTemplatesTest {

    private fun model(
        name: String,
        repo: String = "",
        declared: ToolCallTemplate = ToolCallTemplate.AUTO
    ) = LlmModel(
        id = "m",
        name = name,
        repoId = repo,
        fileName = "m.gguf",
        sizeBytes = 1L,
        toolCallTemplate = declared
    )

    @Test
    fun `detects the dialect from the model name or repo`() {
        assertEquals(ToolCallTemplate.MISTRAL, ToolCallTemplates.detect("Mistral-7B-Instruct", ""))
        assertEquals(ToolCallTemplate.MISTRAL, ToolCallTemplates.detect("Model", "org/mixtral-8x7b"))
        assertEquals(ToolCallTemplate.LLAMA3_JSON, ToolCallTemplates.detect("Llama-3.2-3B-Instruct", ""))
        assertEquals(ToolCallTemplate.LLAMA3_JSON, ToolCallTemplates.detect("Meta-Llama-3.1-8B", ""))
        assertEquals(ToolCallTemplate.HERMES, ToolCallTemplates.detect("Qwen3-4B-Instruct", ""))
        // Family detection covers the newer catalogs too.
        assertEquals(ToolCallTemplate.GEMMA4, ToolCallTemplates.detect("Gemma 4 E2B", ""))
        assertEquals(ToolCallTemplate.QWEN_XML, ToolCallTemplates.detect("LFM 3 1.2B", ""))
        assertEquals(ToolCallTemplate.QWEN_XML, ToolCallTemplates.detect("Qwen3.5-2B", ""))
        assertEquals(ToolCallTemplate.GLM4, ToolCallTemplates.detect("GLM-4.5-Flash", ""))
        assertEquals(ToolCallTemplate.DEEPSEEK, ToolCallTemplates.detect("DeepSeek-R1-0528-Qwen3-8B", ""))
        assertEquals(ToolCallTemplate.PHI4, ToolCallTemplates.detect("phi-4-mini", ""))
        assertEquals(ToolCallTemplate.COHERE, ToolCallTemplates.detect("Command-R-Plus", ""))
        assertEquals(ToolCallTemplate.XLAM, ToolCallTemplates.detect("Salesforce/xLAM-2-1b-fc-r", ""))
        // Unknown families keep the app's previous behaviour.
        assertEquals(ToolCallTemplate.HERMES, ToolCallTemplates.detect("StableLM-4B", ""))
    }

    @Test
    fun `the catalog declaration wins over detection`() {
        assertEquals(
            ToolCallTemplate.PLAIN_JSON,
            ToolCallTemplates.resolve(model("Qwen3-4B", declared = ToolCallTemplate.PLAIN_JSON), null)
        )
        assertEquals(
            ToolCallTemplate.NONE,
            ToolCallTemplates.resolve(model("Qwen3-4B", declared = ToolCallTemplate.NONE), null)
        )
    }

    @Test
    fun `the user choice wins over catalog and detection`() {
        assertEquals(
            ToolCallTemplate.MISTRAL,
            ToolCallTemplates.resolve(model("Llama-3.2-3B", declared = ToolCallTemplate.HERMES), "mistral")
        )
        assertEquals(
            ToolCallTemplate.HERMES,
            ToolCallTemplates.resolve(model("Qwen3-4B"), "hermes")
        )
        assertEquals(
            ToolCallTemplate.NONE,
            ToolCallTemplates.resolve(model("Qwen3-4B"), "none")
        )
    }

    @Test
    fun `auto and unknown override ids fall through to detection`() {
        assertEquals(ToolCallTemplate.LLAMA3_JSON, ToolCallTemplates.resolve(model("Llama-3.2-3B"), "auto"))
        assertEquals(ToolCallTemplate.MISTRAL, ToolCallTemplates.resolve(model("Mistral-7B"), "legacy-id"))
        assertEquals(ToolCallTemplate.LLAMA3_JSON, ToolCallTemplates.resolve(model("Llama-3.2-3B"), null))
    }
}
