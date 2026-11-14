package com.localllm.android.model

/**
 * Picks the tool-call dialect for a model.
 *
 * Precedence: a per-model choice saved by the user, then the dialect declared by the
 * catalog entry, then detection from the model name / repo. Detection is deliberately
 * small and conservative — every family it does not recognise keeps the app's
 * previous behaviour (Hermes), which every prompt template can still use.
 */
object ToolCallTemplates {

    fun detect(modelName: String, repoId: String): ToolCallTemplate {
        val haystack = "$modelName $repoId".lowercase()
        return when {
            // Mistral-family models announce calls with [TOOL_CALLS].
            listOf("mistral", "mixtral", "devstral", "magistral", "codestral")
                .any { it in haystack } -> ToolCallTemplate.MISTRAL

            // Llama 3.x+ uses <|python_tag|> followed by a JSON object.
            listOf("llama-3", "llama3", "llama 3", "llama-4", "llama4", "llama 4")
                .any { it in haystack } -> ToolCallTemplate.LLAMA3_JSON

            // MiniCPM5 emits XML <function name="..."><param ...>…</param></function>.
            listOf("minicpm5", "minicpm-5")
                .any { it in haystack } -> ToolCallTemplate.MINICPM5

            // Qwen 3.5 moved to the bare function-name XML form.
            listOf("qwen3.5", "qwen3_5", "qwen 3.5")
                .any { it in haystack } -> ToolCallTemplate.QWEN_XML

            // LFM 3 (LiquidAI) shares Qwen's XML dialect.
            listOf("lfm-3", "lfm3", "lfm 3")
                .any { it in haystack } -> ToolCallTemplate.QWEN_XML

            // GLM / ChatGLM / Zhipu models close their call with a reply tag.
            listOf("glm", "chatglm", "zhipu")
                .any { it in haystack } -> ToolCallTemplate.GLM4

            // DeepSeek V3/R1 use fullwidth-token call blocks.
            "deepseek" in haystack -> ToolCallTemplate.DEEPSEEK

            // Phi-3/4 emit the tool name, '>' and a JSON object.
            listOf("phi-3", "phi3", "phi-4", "phi4")
                .any { it in haystack } -> ToolCallTemplate.PHI4

            // Cohere Command models wrap calls in a tool_calls JSON array.
            listOf("cohere", "command-r", "commandr")
                .any { it in haystack } -> ToolCallTemplate.COHERE

            // xLAM replies with a bare JSON array of calls.
            "xlam" in haystack -> ToolCallTemplate.XLAM

            // Gemma 4 models are trained on the Gemma tool_call block.
            "gemma" in haystack -> ToolCallTemplate.GEMMA4

            // Qwen / Hermes / Nous models are trained on the <tool_call> block.
            listOf("qwen", "hermes", "nous", "functionary")
                .any { it in haystack } -> ToolCallTemplate.HERMES

            else -> ToolCallTemplate.HERMES
        }
    }

    /**
     * @param overrideId the user's choice for this model (`AUTO` id or null = follow
     *   the catalog and detection).
     */
    fun resolve(model: LlmModel, overrideId: String?): ToolCallTemplate {
        val override = overrideId?.let(ToolCallTemplate::fromId)
        if (override != null && override != ToolCallTemplate.AUTO) return override
        if (model.toolCallTemplate != ToolCallTemplate.AUTO) return model.toolCallTemplate
        return detect(model.name, model.repoId)
    }
}
