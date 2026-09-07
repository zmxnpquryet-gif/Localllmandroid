package com.example.server

import android.content.Context
import android.util.Log
import com.example.engine.LlmEngine
import com.example.model.GenerationSettings
import com.example.model.LlmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.lastOrNull
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

/**
 * Lightweight HTTP server running on port 11434 (compatible with standard Ollama / LLM API protocols).
 * Provides /api/tags, /api/generate, /api/chat, /v1/models, and /v1/chat/completions endpoints.
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
    }

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Request statistics for UI
    var requestCount: Int = 0
        private set
    var lastClientIp: String? = null
        private set
    var lastEndpoint: String? = null
        private set

    fun isServerRunning(): Boolean = isRunning

    fun getPort(): Int = DEFAULT_PORT

    fun start(onStatusChange: (Boolean, String?) -> Unit) {
        if (isRunning) {
            onStatusChange(true, "서버가 이미 포트 $DEFAULT_PORT 에서 실행 중입니다.")
            return
        }

        try {
            // Bind to all interfaces (0.0.0.0) on port 11434
            serverSocket = ServerSocket(DEFAULT_PORT, 50, InetAddress.getByName("0.0.0.0"))
            isRunning = true
            onStatusChange(true, "API 서버가 포트 $DEFAULT_PORT 에서 시작되었습니다.")

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
            Log.e(TAG, "Failed to start API server on port $DEFAULT_PORT", e)
            onStatusChange(false, "서버 시작 실패 (포트 $DEFAULT_PORT): ${e.localizedMessage}")
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
            socket.soTimeout = 60000
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
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                val lower = line!!.lowercase()
                if (lower.startsWith("content-length:")) {
                    contentLength = line!!.substringAfter(":").trim().toIntOrNull() ?: 0
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

            // Handle CORS Preflight
            if (method == "OPTIONS") {
                sendResponse(output, 204, "No Content", "text/plain", "")
                return
            }

            // Routing
            when {
                // Root status check
                uri == "/" -> {
                    sendResponse(output, 200, "OK", "text/plain", "Ollama is running on Android\n")
                }

                // Ollama /api/tags (model list)
                uri.startsWith("/api/tags") || uri.startsWith("/v1/models") -> {
                    handleModelsList(output)
                }

                // Ollama /api/version
                uri.startsWith("/api/version") -> {
                    val json = JSONObject().put("version", "0.5.1").toString()
                    sendResponse(output, 200, "OK", "application/json", json)
                }

                // Ollama /api/generate
                uri.startsWith("/api/generate") && method == "POST" -> {
                    handleGenerate(output, body)
                }

                // Ollama /api/chat or OpenAI /v1/chat/completions
                (uri.startsWith("/api/chat") || uri.startsWith("/v1/chat/completions")) && method == "POST" -> {
                    handleChat(output, body, isOpenAi = uri.startsWith("/v1/chat/completions"))
                }

                else -> {
                    val errorJson = JSONObject().put("error", "Endpoint not found: $uri").toString()
                    sendResponse(output, 404, "Not Found", "application/json", errorJson)
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

    private fun handleModelsList(output: OutputStream) {
        val models = getAllModels()
        val active = getActiveModel()
        val modelsArray = JSONArray()

        models.forEach { m ->
            val obj = JSONObject()
            obj.put("name", m.name)
            obj.put("model", m.name)
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

        // If no model, provide active model placeholder
        if (modelsArray.length() == 0 && active != null) {
            modelsArray.put(JSONObject().apply {
                put("name", active.name)
                put("model", active.name)
                put("size", active.sizeBytes)
            })
        }

        val resJson = JSONObject().put("models", modelsArray).toString()
        sendResponse(output, 200, "OK", "application/json", resJson)
    }

    private suspend fun handleGenerate(output: OutputStream, body: String) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val prompt = json.optString("prompt", "")
        val stream = json.optBoolean("stream", false)
        val currentModel = getActiveModel()

        if (prompt.isBlank()) {
            val err = JSONObject().put("error", "Prompt cannot be empty").toString()
            sendResponse(output, 400, "Bad Request", "application/json", err)
            return
        }

        val settings = getSettings()
        var fullResponse = ""

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
            }
        } catch (e: Exception) {
            fullResponse = "추론 중 오류가 발생했습니다: ${e.localizedMessage}"
        }

        val responseJson = JSONObject().apply {
            put("model", currentModel?.name ?: "default")
            put("response", fullResponse)
            put("done", true)
            put("total_duration", 1200000000L)
            put("load_duration", 50000000L)
            put("prompt_eval_count", prompt.length / 3)
            put("eval_count", fullResponse.length / 3)
        }.toString()

        sendResponse(output, 200, "OK", "application/json", responseJson)
    }

    private suspend fun handleChat(output: OutputStream, body: String, isOpenAi: Boolean) {
        val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
        val messagesArray = json.optJSONArray("messages") ?: JSONArray()
        val currentModel = getActiveModel()

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

        val settings = getSettings()
        var fullAnswer = ""

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
            }
        } catch (e: Exception) {
            fullAnswer = "오류: ${e.localizedMessage}"
        }

        val resultJson = if (isOpenAi) {
            JSONObject().apply {
                put("id", "chatcmpl-${System.currentTimeMillis()}")
                put("object", "chat.completion")
                put("created", System.currentTimeMillis() / 1000)
                put("model", currentModel?.name ?: "local-model")
                put("choices", JSONArray().put(JSONObject().apply {
                    put("index", 0)
                    put("message", JSONObject().apply {
                        put("role", "assistant")
                        put("content", fullAnswer)
                    })
                    put("finish_reason", "stop")
                }))
                put("usage", JSONObject().apply {
                    put("prompt_tokens", (lastUserPrompt.length / 3) + 10)
                    put("completion_tokens", fullAnswer.length / 3)
                    put("total_tokens", ((lastUserPrompt.length + fullAnswer.length) / 3) + 10)
                })
            }.toString()
        } else {
            JSONObject().apply {
                put("model", currentModel?.name ?: "default")
                put("message", JSONObject().apply {
                    put("role", "assistant")
                    put("content", fullAnswer)
                })
                put("done", true)
                put("total_duration", 1500000000L)
            }.toString()
        }

        sendResponse(output, 200, "OK", "application/json", resultJson)
    }

    private fun sendResponse(
        output: OutputStream,
        statusCode: Int,
        statusText: String,
        contentType: String,
        content: String
    ) {
        val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
        val header = buildString {
            append("HTTP/1.1 $statusCode $statusText\r\n")
            append("Content-Type: $contentType; charset=utf-8\r\n")
            append("Content-Length: ${contentBytes.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n")
            append("\r\n")
        }

        output.write(header.toByteArray(StandardCharsets.UTF_8))
        if (contentBytes.isNotEmpty()) {
            output.write(contentBytes)
        }
        output.flush()
    }
}
