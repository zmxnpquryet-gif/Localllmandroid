package com.localllm.android.server

import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Pure JSON request/response mappers for Ollama (`/api/`) and OpenAI (`/v1/`) protocols.
 */
internal object ApiPayloadMapper {

    data class EffectiveRequestOptions(
        val settings: GenerationSettings,
        val numPredict: Int?
    )

    fun resolveRequestOptions(json: JSONObject, base: GenerationSettings): EffectiveRequestOptions {
        val options = json.optJSONObject("options")
        fun optDouble(vararg keys: String, fallback: Double): Double {
            for (key in keys) {
                if (json.has(key) && !json.isNull(key)) {
                    val v = json.optDouble(key, Double.NaN)
                    if (!v.isNaN()) return v
                }
                if (options != null && options.has(key) && !options.isNull(key)) {
                    val v = options.optDouble(key, Double.NaN)
                    if (!v.isNaN()) return v
                }
            }
            return fallback
        }
        fun optInt(vararg keys: String, fallback: Int): Int {
            for (key in keys) {
                if (json.has(key) && !json.isNull(key)) {
                    val v = json.optInt(key, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) return v
                }
                if (options != null && options.has(key) && !options.isNull(key)) {
                    val v = options.optInt(key, Int.MIN_VALUE)
                    if (v != Int.MIN_VALUE) return v
                }
            }
            return fallback
        }

        val temperature = optDouble("temperature", fallback = base.temperature.toDouble()).coerceIn(0.0, 2.0).toFloat()
        val topP = optDouble("top_p", fallback = base.topP.toDouble()).coerceIn(0.01, 1.0).toFloat()
        val topK = optInt("top_k", fallback = base.topK).coerceIn(1, 200)
        val numPredictRaw = optInt("num_predict", "max_tokens", fallback = Int.MIN_VALUE)
        val numPredict = if (numPredictRaw == Int.MIN_VALUE) null else numPredictRaw.coerceIn(1, 16384)

        return EffectiveRequestOptions(
            settings = base.copy(temperature = temperature, topP = topP, topK = topK),
            numPredict = numPredict
        )
    }

    fun parseMessages(messagesArray: JSONArray): Pair<List<Pair<String, String>>, String> {
        val historyList = mutableListOf<Pair<String, String>>()
        var lastUserPrompt = ""

        for (i in 0 until messagesArray.length()) {
            val msgObj = messagesArray.optJSONObject(i) ?: continue
            val role = msgObj.optString("role", "user")
            val content = extractTextContent(msgObj.opt("content"))
            if (i == messagesArray.length() - 1 && role == "user") {
                lastUserPrompt = content
            } else {
                historyList.add(role to content)
            }
        }

        if (lastUserPrompt.isBlank() && historyList.isNotEmpty()) {
            val idx = historyList.indexOfLast { it.first == "user" && it.second.isNotBlank() }
            if (idx >= 0) {
                lastUserPrompt = historyList[idx].second
                historyList.removeAt(idx)
            }
        }

        return historyList to lastUserPrompt
    }

    fun extractTextContent(raw: Any?): String = when (raw) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until raw.length()).joinToString("") { i ->
            val part = raw.optJSONObject(i)
            if (part != null && part.optString("type", "text") == "text") part.optString("text", "") else ""
        }
        else -> raw.toString()
    }

    fun buildOllamaTagsJson(models: List<LlmModel>, active: LlmModel?): String {
        val modelsArray = JSONArray()
        models.forEach { m ->
            modelsArray.put(
                JSONObject().apply {
                    put("name", m.name)
                    put("model", m.name)
                    put("modified_at", HttpWireWriter.isoTimestamp())
                    put("size", m.sizeBytes)
                    put("digest", m.id)
                    put("details", JSONObject().apply {
                        put("format", m.runtimeType.badge)
                        put("family", m.runtimeType.label)
                        put("parameter_size", "Local")
                        put("quantization_level", m.quantization)
                    })
                }
            )
        }
        if (modelsArray.length() == 0 && active != null) {
            modelsArray.put(
                JSONObject().apply {
                    put("name", active.name)
                    put("model", active.name)
                    put("modified_at", HttpWireWriter.isoTimestamp())
                    put("size", active.sizeBytes)
                    put("digest", active.id)
                }
            )
        }
        return JSONObject().put("models", modelsArray).toString()
    }

    fun buildOpenAiModelsJson(models: List<LlmModel>, active: LlmModel?): String {
        val dataArray = JSONArray()
        val nowEpoch = System.currentTimeMillis() / 1000
        models.forEach { m ->
            dataArray.put(
                JSONObject().apply {
                    put("id", m.name)
                    put("object", "model")
                    put("created", nowEpoch)
                    put("owned_by", "local-device")
                }
            )
        }
        if (dataArray.length() == 0 && active != null) {
            dataArray.put(
                JSONObject().apply {
                    put("id", active.name)
                    put("object", "model")
                    put("created", nowEpoch)
                    put("owned_by", "local-device")
                }
            )
        }
        return JSONObject().apply {
            put("object", "list")
            put("data", dataArray)
        }.toString()
    }

    fun buildOllamaShowJson(currentModel: LlmModel?, serving: GenerationSettings): String =
        JSONObject().apply {
            put("license", "Open")
            put("modelfile", "# Modelfile generated by OffconAI\nFROM ${currentModel?.fileName ?: "model.gguf"}")
            put("parameters", "temperature ${serving.temperature}\ntop_p ${serving.topP}")
            put("template", "{{ .Prompt }}")
            put("details", JSONObject().apply {
                put("parent_model", "")
                put("format", currentModel?.runtimeType?.badge ?: "GGUF")
                put("family", currentModel?.runtimeType?.label ?: "llama.cpp")
                put("parameter_size", "Local")
                put("quantization_level", currentModel?.quantization ?: "Q4_K_M")
            })
        }.toString()

    fun buildOpenAiSseChunk(chatId: String, modelName: String, token: String?, finishReason: String?, errorMessage: String? = null): String {
        val obj = JSONObject().apply {
            put("id", chatId)
            put("object", "chat.completion.chunk")
            put("created", System.currentTimeMillis() / 1000)
            put("model", modelName)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("delta", if (token != null) JSONObject().put("content", token) else JSONObject())
                put("finish_reason", finishReason ?: JSONObject.NULL)
            }))
            if (errorMessage != null) {
                put("error", JSONObject().put("message", errorMessage))
            }
        }
        return "data: $obj\n\n"
    }

    fun buildOpenAiCompletionJson(chatId: String, modelName: String, fullAnswer: String, totalTokens: Int): String =
        JSONObject().apply {
            put("id", chatId)
            put("object", "chat.completion")
            put("created", System.currentTimeMillis() / 1000)
            put("model", modelName)
            put("choices", JSONArray().put(JSONObject().apply {
                put("index", 0)
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", fullAnswer)
                })
                put("finish_reason", "stop")
            }))
            put("usage", JSONObject().apply {
                put("completion_tokens", totalTokens)
                put("total_tokens", totalTokens)
            })
        }.toString()
}
