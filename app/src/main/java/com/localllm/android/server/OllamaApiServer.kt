package com.localllm.android.server

import android.content.Context
import android.util.Log
import com.localllm.android.engine.LlmEngine
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Hardened lightweight HTTP server running on port 11434 (compatible with standard Ollama & OpenAI protocols).
 * Connects directly to the real on-device inference engine (llmEngine.streamGenerate).
 * Features:
 *  - Secure localhost binding by default (127.0.0.1) with optional LAN mode.
 *  - Token-based authentication (Authorization: Bearer <api_key> / X-API-Key).
 *  - Strict origin validation instead of wildcard CORS (Access-Control-Allow-Origin: * removed).
 *  - Supports /api/tags, /api/show, /api/version, /api/generate, /api/chat, /v1/models, /v1/chat/completions.
 */
class OllamaApiServer(
    private val context: Context,
    private val llmEngine: LlmEngine,
    private val getActiveModel: () -> LlmModel?,
    private val getAllModels: () -> List<LlmModel>,
    private val getSettings: () -> GenerationSettings
) {
    companion object {
        const val DEFAULT_PORT = 11434
        private const val TAG = "OllamaApiServer"

        fun generateSecureApiKey(): String {
            val random = SecureRandom()
            val bytes = ByteArray(18)
            random.nextBytes(bytes)
            return "sk-local-" + android.util.Base64.encodeToString(bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
        }
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    var currentApiKey: String = generateSecureApiKey()
        private set

    var boundHost: String = "127.0.0.1"
        private set

    // Request statistics for UI
    var requestCount: Int = 0
        private set
    var lastClientIp: String? = null
        private set
    var lastEndpoint: String? = null
        private set

    fun isServerRunning(): Boolean = isRunning

    fun getPort(): Int = DEFAULT_PORT

    fun regenerateApiKey(): String {
        currentApiKey = generateSecureApiKey()
        return currentApiKey
    }

    fun start(onStatusChange: (Boolean, String?) -> Unit) {
        if (isRunning) {
            onStatusChange(true, "서버가 이미 포트 $DEFAULT_PORT 에서 실행 중입니다.")
            return
        }

        try {
            val settings = getSettings()
            boundHost = if (settings.apiServerBindAddress.isNotBlank()) settings.apiServerBindAddress else "127.0.0.1"
            val bindAddress = InetAddress.getByName(boundHost)

            serverSocket = ServerSocket(DEFAULT_PORT, 50, bindAddress)
            isRunning = true

            val hostLabel = if (boundHost == "127.0.0.1") "로컬 루프백(127.0.0.1)" else "전체 인터페이스($boundHost)"
            onStatusChange(true, "보안 API 서버가 $hostLabel 포트 $DEFAULT_PORT 에서 시작되었습니다.")

            serverJob = scope.launch {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        launch {
                            handleClientSocket(clientSocket)
                        }
                    } catch (e: Exception) {
                        if (!isRunning) break
                        Log.e(TAG, "Socket accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            isRunning = false
            Log.e(TAG, "Failed to start API server on $boundHost:$DEFAULT_PORT", e)
            onStatusChange(false, "서버 시작 실패 ($boundHost:$DEFAULT_PORT): ${e.localizedMessage}")
        }
    }

    fun stop(onStatusChange: (Boolean, String?) -> Unit) {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            onStatusChange(false, "API 서버가 중지되었습니다.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop server", e)
            onStatusChange(false, "서버 중지 중 오류: ${e.localizedMessage}")
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        try {
            socket.soTimeout = 120000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val output = socket.getOutputStream()

            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1]

            lastClientIp = socket.inetAddress?.hostAddress
            lastEndpoint = "$method $uri"
            requestCount++

            // Read HTTP headers
            var contentLength = 0
            val headers = mutableMapOf<String, String>()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line.isNullOrEmpty()) break
                val colonIdx = line!!.indexOf(':')
                if (colonIdx > 0) {
                    val key = line!!.substring(0, colonIdx).trim().lowercase()
                    val value = line!!.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        contentLength = value.toIntOrNull() ?: 0
                    }
                }
            }

            val clientOrigin = headers["origin"]

            // Handle CORS Preflight
            if (method == "OPTIONS") {
                sendCorsPreflight(output, clientOrigin)
                return
            }

            // Authentication Check
            val settings = getSettings()
            if (settings.apiServerRequireAuth && uri != "/") {
                val isAuthorized = checkAuthorization(headers)
                if (!isAuthorized) {
                    val unauthorizedJson = JSONObject().apply {
                        put("error", "인증 실패: 유효한 API 키가 필요합니다. 'Authorization: Bearer <api_key>' 또는 'X-API-Key' 헤더를 전달하세요.")
                    }.toString()
                    sendResponse(output, 401, "Unauthorized", "application/json", unauthorizedJson, clientOrigin)
                    return
                }
            }

            // Read Body if any
            val body = if (contentLength > 0) {
                val chars = CharArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val read = reader.read(chars, readTotal, contentLength - readTotal)
                    if (read == -1) break
                    readTotal += read
                }
                String(chars, 0, readTotal)
            } else {
                ""
            }

            // Routing
            when {
                // Root status check
                uri == "/" -> {
                    sendResponse(output, 200, "OK", "text/plain", "Ollama is running on Android (LocalLLM)\n", clientOrigin)
                }

                // Ollama /api/tags
                uri.startsWith("/api/tags") -> {
                    handleOllamaTags(output, clientOrigin)
                }

                // OpenAI /v1/models
                uri.startsWith("/v1/models") -> {
                    handleOpenAiModels(output, clientOrigin)
                }

                // Ollama /api/show
                uri.startsWith("/api/show") -> {
                    handleOllamaShow(output, body, clientOrigin)
                }

                // Ollama /api/version
                uri.startsWith("/api/version") -> {
                    val json = JSONObject().put("version", "0.5.1").toString()
                    sendResponse(output, 200, "OK", "application/json", json, clientOrigin)
                }

                // Ollama /api/generate
                uri.startsWith("/api/generate") && method == "POST" -> {
                    handleGenerate(output, body, clientOrigin)
                }

                // Ollama /api/chat
                uri.startsWith("/api/chat") && method == "POST" -> {
                    handleOllamaChat(output, body, clientOrigin)
                }

                // OpenAI /v1/chat/completions
                uri.startsWith("/v1/chat/completions") && method == "POST" -> {
                    handleOpenAiChat(output, body, clientOrigin)
                }

                else -> {
                    val errorJson = JSONObject().put("error", "Endpoint not found: $uri").toString()
                    sendResponse(output, 404, "Not Found", "application/json", errorJson, clientOrigin)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client request", e)
        } finally {
            try {
                socket.close()
            } catch (_: Exception) {}
        }
    }

    private fun checkAuthorization(headers: Map<String, String>): Boolean {
        val authHeader = headers["authorization"]
        if (!authHeader.isNullOrBlank()) {
            val token = authHeader.removePrefix("Bearer ").removePrefix("bearer ").trim()
            if (token == currentApiKey) return true
        }
        val xApiKey = headers["x-api-key"]
        if (!xApiKey.isNullOrBlank() && xApiKey.trim() == currentApiKey) {
            return true
        }
        return false
    }

    private fun handleOllamaTags(output: OutputStream, origin: String?) {
        val models = getAllModels()
        val active = getActiveModel()
        val modelsArray = JSONArray()

        models.forEach { m ->
            val obj = JSONObject()
            obj.put("name", m.name)
            obj.put("model", m.name)
            obj.put("modified_at", getIsoTimestamp())
            obj.put("size", m.sizeBytes)
            obj.put("digest", m.id)
            obj.put("details", JSONObject().apply {
                put("format", m.runtimeType.badge)
                put("family", m.runtimeType.label)
                put("parameter_size", "Local")
                put("quantization_level", m.quantization)
            })
            modelsArray.put(obj)
        }

        if (modelsArray.length() == 0 && active != null) {
            modelsArray.put(JSONObject().apply {
                put("name", active.name)
                put("model", active.name)
                put("modified_at", getIsoTimestamp())
                put("size", active.sizeBytes)
                put("digest", active.id)
            })
        }

        val resJson = JSONObject().put("models", modelsArray).toString()
        sendResponse(output, 200, "OK", "application/json", resJson, origin)
    }

    private fun handleOpenAiModels(output: OutputStream, origin: String?) {
        val models = getAllModels()
        val active = getActiveModel()
        val dataArray = JSONArray()

        models.forEach { m ->
            dataArray.put(JSONObject().apply {
                put("id", m.name)
                put("object", "model")
                put("created", System.currentTimeMillis() / 1000)
                put("owned_by", "local-device")
            })
        }

        if (dataArray.length() == 0 && active != null) {
            dataArray.put(JSONObject().apply {
                put("id", active.name)
                put("object", "model")
                put("created", System.currentTimeMillis() / 1000)
                put("owned_by", "local-device")
            })
        }

        val resJson = JSONObject().apply {
            put("object", "list")
            put("data", dataArray)
        }.toString()

        sendResponse(output, 200, "OK", "application/json", resJson, origin)
    }

    private fun handleOllamaShow(output: OutputStream, body: String, origin: String?) {
        val currentModel = getActiveModel()
        val detailsJson = JSONObject().apply {
            put("license", "Open")
            put("modelfile", "# Modelfile generated by LocalLLM Android\nFROM ${currentModel?.fileName ?: "model.gguf"}")
            put("parameters", "temperature 0.7\ntop_p 0.9")
            put("template", "{{ .Prompt }}")
            put("details", JSONObject().apply {
                put("parent_model", "")
                put("format", currentModel?.runtimeType?.badge ?: "GGUF")
                put("family", currentModel?.runtimeType?.label ?: "llama.cpp")
                put("parameter_size", "Local")
                put("quantization_level", currentModel?.quantization ?: "Q4_K_M")
            })
        }.toString()

        sendResponse(output, 200, "OK", "application/json", detailsJson, origin)
    }

    private suspend fun handleGenerate(output: OutputStream, body: String, origin: String?) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val prompt = json.optString("prompt", "")
        val isStream = json.optBoolean("stream", true)
        val currentModel = getActiveModel()

        if (prompt.isBlank()) {
            val err = JSONObject().put("error", "Prompt cannot be empty").toString()
            sendResponse(output, 400, "Bad Request", "application/json", err, origin)
            return
        }

        if (!llmEngine.isModelReady()) {
            val err = JSONObject().put("error", "선택되거나 로드된 로컬 모델이 없습니다. 앱에서 모델을 먼저 로드하세요.").toString()
            sendResponse(output, 503, "Service Unavailable", "application/json", err, origin)
            return
        }

        val settings = getSettings()
        val modelName = currentModel?.name ?: "local-model"

        if (isStream) {
            sendStreamHeaders(output, "application/x-ndjson", origin)
            try {
                llmEngine.streamGenerate(
                    prompt = prompt,
                    history = emptyList(),
                    settings = settings,
                    attachment = null
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        val chunkObj = JSONObject().apply {
                            put("model", modelName)
                            put("created_at", getIsoTimestamp())
                            put("response", chunk.token)
                            put("done", false)
                        }
                        sendChunk(output, chunkObj.toString() + "\n")
                    }
                }
            } catch (e: Exception) {
                val errObj = JSONObject().apply {
                    put("model", modelName)
                    put("response", "\n[추론 오류: ${e.localizedMessage}]")
                    put("done", false)
                }
                sendChunk(output, errObj.toString() + "\n")
            }

            val finalObj = JSONObject().apply {
                put("model", modelName)
                put("created_at", getIsoTimestamp())
                put("response", "")
                put("done", true)
            }
            sendChunk(output, finalObj.toString() + "\n")
            endChunk(output)

        } else {
            var fullResponse = ""
            var totalTokens = 0
            try {
                llmEngine.streamGenerate(
                    prompt = prompt,
                    history = emptyList(),
                    settings = settings,
                    attachment = null
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        fullResponse += chunk.token
                    }
                    totalTokens = chunk.totalTokens
                }
            } catch (e: Exception) {
                fullResponse = "추론 중 오류: ${e.localizedMessage}"
            }

            val responseJson = JSONObject().apply {
                put("model", modelName)
                put("created_at", getIsoTimestamp())
                put("response", fullResponse)
                put("done", true)
                put("eval_count", totalTokens)
            }.toString()

            sendResponse(output, 200, "OK", "application/json", responseJson, origin)
        }
    }

    private suspend fun handleOllamaChat(output: OutputStream, body: String, origin: String?) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val messagesArray = json.optJSONArray("messages") ?: JSONArray()
        val isStream = json.optBoolean("stream", true)
        val currentModel = getActiveModel()
        val modelName = currentModel?.name ?: "local-model"

        if (!llmEngine.isModelReady()) {
            val err = JSONObject().put("error", "선택되거나 로드된 로컬 모델이 없습니다. 앱에서 모델을 먼저 로드하세요.").toString()
            sendResponse(output, 503, "Service Unavailable", "application/json", err, origin)
            return
        }

        val (historyList, lastUserPrompt) = parseMessages(messagesArray)
        val settings = getSettings()

        if (isStream) {
            sendStreamHeaders(output, "application/x-ndjson", origin)
            try {
                llmEngine.streamGenerate(
                    prompt = lastUserPrompt,
                    history = historyList,
                    settings = settings,
                    attachment = null
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        val chunkObj = JSONObject().apply {
                            put("model", modelName)
                            put("created_at", getIsoTimestamp())
                            put("message", JSONObject().apply {
                                put("role", "assistant")
                                put("content", chunk.token)
                            })
                            put("done", false)
                        }
                        sendChunk(output, chunkObj.toString() + "\n")
                    }
                }
            } catch (e: Exception) {
                val errObj = JSONObject().apply {
                    put("model", modelName)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", "\n[추론 오류: ${e.localizedMessage}]")
                    })
                    put("done", false)
                }
                sendChunk(output, errObj.toString() + "\n")
            }

            val finalObj = JSONObject().apply {
                put("model", modelName)
                put("created_at", getIsoTimestamp())
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", "")
                })
                put("done", true)
            }
            sendChunk(output, finalObj.toString() + "\n")
            endChunk(output)

        } else {
            var fullAnswer = ""
            var totalTokens = 0
            try {
                llmEngine.streamGenerate(
                    prompt = lastUserPrompt,
                    history = historyList,
                    settings = settings,
                    attachment = null
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        fullAnswer += chunk.token
                    }
                    totalTokens = chunk.totalTokens
                }
            } catch (e: Exception) {
                fullAnswer = "오류: ${e.localizedMessage}"
            }

            val resultJson = JSONObject().apply {
                put("model", modelName)
                put("created_at", getIsoTimestamp())
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", fullAnswer)
                })
                put("done", true)
                put("eval_count", totalTokens)
            }.toString()

            sendResponse(output, 200, "OK", "application/json", resultJson, origin)
        }
    }

    private suspend fun handleOpenAiChat(output: OutputStream, body: String, origin: String?) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val messagesArray = json.optJSONArray("messages") ?: JSONArray()
        val isStream = json.optBoolean("stream", false)
        val currentModel = getActiveModel()
        val modelName = currentModel?.name ?: "local-model"
        val chatId = "chatcmpl-${System.currentTimeMillis()}"

        if (!llmEngine.isModelReady()) {
            val err = JSONObject().put("error", JSONObject().put("message", "선택되거나 로드된 로컬 모델이 없습니다.")).toString()
            sendResponse(output, 503, "Service Unavailable", "application/json", err, origin)
            return
        }

        val (historyList, lastUserPrompt) = parseMessages(messagesArray)
        val settings = getSettings()

        if (isStream) {
            sendStreamHeaders(output, "text/event-stream", origin)
            try {
                llmEngine.streamGenerate(
                    prompt = lastUserPrompt,
                    history = historyList,
                    settings = settings,
                    attachment = null
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        val sseData = JSONObject().apply {
                            put("id", chatId)
                            put("object", "chat.completion.chunk")
                            put("created", System.currentTimeMillis() / 1000)
                            put("model", modelName)
                            put("choices", JSONArray().put(JSONObject().apply {
                                put("index", 0)
                                put("delta", JSONObject().put("content", chunk.token))
                                put("finish_reason", JSONObject.NULL)
                            }))
                        }
                        sendChunk(output, "data: $sseData\n\n")
                    }
                }
            } catch (e: Exception) {
                val errData = JSONObject().apply {
                    put("id", chatId)
                    put("object", "chat.completion.chunk")
                    put("choices", JSONArray().put(JSONObject().apply {
                        put("index", 0)
                        put("delta", JSONObject().put("content", "\n[오류: ${e.localizedMessage}]"))
                    }))
                }
                sendChunk(output, "data: $errData\n\n")
            }

            val finalSse = JSONObject().apply {
                put("id", chatId)
                put("object", "chat.completion.chunk")
                put("created", System.currentTimeMillis() / 1000)
                put("model", modelName)
                put("choices", JSONArray().put(JSONObject().apply {
                    put("index", 0)
                    put("delta", JSONObject())
                    put("finish_reason", "stop")
                }))
            }
            sendChunk(output, "data: $finalSse\n\n")
            sendChunk(output, "data: [DONE]\n\n")
            endChunk(output)

        } else {
            var fullAnswer = ""
            var totalTokens = 0
            try {
                llmEngine.streamGenerate(
                    prompt = lastUserPrompt,
                    history = historyList,
                    settings = settings,
                    attachment = null
                ).collect { chunk ->
                    if (chunk.token.isNotEmpty()) {
                        fullAnswer += chunk.token
                    }
                    totalTokens = chunk.totalTokens
                }
            } catch (e: Exception) {
                fullAnswer = "오류: ${e.localizedMessage}"
            }

            val resultJson = JSONObject().apply {
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

            sendResponse(output, 200, "OK", "application/json", resultJson, origin)
        }
    }

    private fun parseMessages(messagesArray: JSONArray): Pair<List<Pair<String, String>>, String> {
        val historyList = mutableListOf<Pair<String, String>>()
        var lastUserPrompt = ""

        for (i in 0 until messagesArray.length()) {
            val msgObj = messagesArray.optJSONObject(i) ?: continue
            val role = msgObj.optString("role", "user")
            val content = msgObj.optString("content", "")
            if (i == messagesArray.length() - 1 && role == "user") {
                lastUserPrompt = content
            } else {
                historyList.add(Pair(role, content))
            }
        }

        if (lastUserPrompt.isBlank() && historyList.isNotEmpty()) {
            val last = historyList.removeAt(historyList.size - 1)
            lastUserPrompt = last.second
        }

        return Pair(historyList, lastUserPrompt)
    }

    private fun getValidatedCorsOrigin(origin: String?): String? {
        if (origin.isNullOrBlank()) return null
        // Restrict CORS to localhost/loopback origins only to prevent drive-by attacks from arbitrary websites
        return if (origin.startsWith("http://localhost") ||
            origin.startsWith("http://127.0.0.1") ||
            origin.startsWith("https://localhost")
        ) {
            origin
        } else null
    }

    private fun sendCorsPreflight(output: OutputStream, origin: String?) {
        val allowedOrigin = getValidatedCorsOrigin(origin)
        val header = buildString {
            append("HTTP/1.1 204 No Content\r\n")
            if (allowedOrigin != null) {
                append("Access-Control-Allow-Origin: $allowedOrigin\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key\r\n")
            }
            append("Connection: close\r\n\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun sendStreamHeaders(output: OutputStream, contentType: String, origin: String?) {
        val allowedOrigin = getValidatedCorsOrigin(origin)
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: keep-alive\r\n")
            if (allowedOrigin != null) {
                append("Access-Control-Allow-Origin: $allowedOrigin\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key\r\n")
            }
            append("\r\n")
        }
        output.write(header.toByteArray(StandardCharsets.UTF_8))
        output.flush()
    }

    private fun sendChunk(output: OutputStream, text: String) {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isEmpty()) return
        output.write(Integer.toHexString(bytes.size).toByteArray(StandardCharsets.US_ASCII))
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.write(bytes)
        output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun endChunk(output: OutputStream) {
        output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
        output.flush()
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        content: String,
        origin: String?
    ) {
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        val allowedOrigin = getValidatedCorsOrigin(origin)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${contentBytes.size}\r\n")
            append("Connection: close\r\n")
            if (allowedOrigin != null) {
                append("Access-Control-Allow-Origin: $allowedOrigin\r\n")
                append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
                append("Access-Control-Allow-Headers: Content-Type, Authorization, X-API-Key\r\n")
            }
            append("\r\n")
        }

        output.write(header.toByteArray(StandardCharsets.UTF_8))
        if (contentBytes.isNotEmpty()) {
            output.write(contentBytes)
        }
        output.flush()
    }

    private fun getIsoTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }
}
