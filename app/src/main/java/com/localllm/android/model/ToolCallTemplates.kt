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
