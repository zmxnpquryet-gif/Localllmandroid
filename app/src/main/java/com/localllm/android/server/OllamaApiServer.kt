package com.localllm.android.server

import android.content.Context
import android.util.Log
import com.localllm.android.R
import com.localllm.android.engine.LlmEngine
import com.localllm.android.engine.McpToolProtocol
import com.localllm.android.i18n.AppStrings
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ToolCallTemplate
import com.localllm.android.model.ToolCallTemplates
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.Semaphore

/**
 * Hardened lightweight HTTP server running on port 11434 (Ollama & OpenAI protocols).
 * Delegates wire formatting to [HttpWireWriter] and payload mapping to [ApiPayloadMapper].
 */
class OllamaApiServer(
    private val context: Context,
    private val llmEngine: LlmEngine,
    private val getActiveModel: () -> LlmModel?,
    private val getAllModels: () -> List<LlmModel>,
    private val getSettings: () -> GenerationSettings,
    initialApiKey: String = generateSecureApiKey()
) {
    companion object {
        const val DEFAULT_PORT = 11434
        private const val TAG = "OllamaApiServer"
        private const val MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024
        private const val MAX_HEADER_COUNT = 100
        private const val MAX_HEADER_PHASE_MS = 20_000L
        private const val MAX_BODY_PHASE_MS = 60_000L
        private const val MAX_CONCURRENT_GENERATIONS = 1
        private const val MAX_CONNECTIONS = 32
        private const val MAX_PREAUTH_CONNECTIONS = 8

        internal fun isAllowedCorsOrigin(origin: String?): Boolean {
            if (origin.isNullOrBlank()) return false
            return try {
                val uri = java.net.URI(origin.trim())
                val scheme = uri.scheme?.lowercase() ?: return false
                if (scheme != "http" && scheme != "https") return false
                if (uri.rawUserInfo != null) return false
                val host = uri.host?.lowercase() ?: return false
                host == "localhost" || host == "127.0.0.1" || host == "::1"
            } catch (_: Exception) {
                false
            }
        }

        internal fun readHttpLine(
            input: InputStream,
            deadlineNanos: Long = Long.MAX_VALUE
        ): String? {
            val bytes = ByteArrayOutputStream()
            while (true) {
                if (System.nanoTime() > deadlineNanos) {
                    throw java.net.SocketTimeoutException("HTTP header phase timed out")
                }
                val next = input.read()
                if (next == -1) {
                    if (bytes.size() == 0) return null
                    throw EOFException("Incomplete HTTP header")
                }
                if (next == '\n'.code) {
                    return bytes.toString(StandardCharsets.US_ASCII.name()).removeSuffix("\r")
                }
                require(bytes.size() < 8192) { "HTTP header line too long" }
                bytes.write(next)
            }
        }

        internal fun readHttpBody(
            input: InputStream,
            contentLength: Int,
            deadlineNanos: Long = Long.MAX_VALUE
        ): String {
            require(contentLength in 0..MAX_REQUEST_BODY_BYTES) { "Invalid body length" }
            val bytes = ByteArray(contentLength)
            var offset = 0
            while (offset < contentLength) {
                if (System.nanoTime() > deadlineNanos) {
                    throw java.net.SocketTimeoutException("HTTP body phase timed out")
                }
                val read = input.read(bytes, offset, minOf(contentLength - offset, 64 * 1024))
                if (read < 0) throw EOFException("Incomplete HTTP body")
                offset += read
            }
            return String(bytes, StandardCharsets.UTF_8)
        }

        fun generateSecureApiKey(): String {
            val random = SecureRandom()
            val bytes = ByteArray(18)
            random.nextBytes(bytes)
            return "sk-local-" + android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
        }

        internal fun checkAuthorization(headers: Map<String, String>, expectedKey: String): Boolean {
            val authHeader = headers["authorization"]
            if (!authHeader.isNullOrBlank()) {
                val token = authHeader.replaceFirst(Regex("^bearer\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (constantTimeEquals(token, expectedKey)) return true
            }
            val xApiKey = headers["x-api-key"]
            if (!xApiKey.isNullOrBlank() && constantTimeEquals(xApiKey.trim(), expectedKey)) {
                return true
            }
            return false
        }

        internal fun constantTimeEquals(a: String, b: String): Boolean {
            if (a.isEmpty() || b.isEmpty()) return false
            return java.security.MessageDigest.isEqual(
                a.toByteArray(StandardCharsets.UTF_8),
                b.toByteArray(StandardCharsets.UTF_8)
            )
        }
    }

    private var serverSocket: ServerSocket? = null
    @Volatile
    private var isRunning = false
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)
    private val generationSlots = Semaphore(MAX_CONCURRENT_GENERATIONS)
    private val connectionSlots = Semaphore(MAX_CONNECTIONS)
    private val preAuthSlots = Semaphore(MAX_PREAUTH_CONNECTIONS)

    @Volatile
    var currentApiKey: String = initialApiKey
        private set

    fun setApiKey(key: String) {
        if (key.isNotBlank()) currentApiKey = key
    }

    var boundHost: String = "127.0.0.1"
        private set

    private val requestCounter = java.util.concurrent.atomic.AtomicInteger(0)
    val requestCount: Int get() = requestCounter.get()
    @Volatile
    var lastClientIp: String? = null
        private set
    @Volatile
    var lastEndpoint: String? = null
        private set
    var onRequestProcessed: ((Int) -> Unit)? = null

    fun isServerRunning(): Boolean = isRunning
    fun getPort(): Int = DEFAULT_PORT

    fun regenerateApiKey(): String {
        currentApiKey = generateSecureApiKey()
        return currentApiKey
    }

    fun start(onStatusChange: (Boolean, String?) -> Unit) {
        if (isRunning) {
            onStatusChange(true, AppStrings.get(R.string.aps_already, DEFAULT_PORT))
            return
        }

        try {
            val settings = getSettings()
            boundHost = settings.apiServerBindAddress.ifBlank { "127.0.0.1" }
            val bindAddress = InetAddress.getByName(boundHost)

            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(bindAddress, DEFAULT_PORT), 50)
            }
            isRunning = true

            val hostLabel = if (boundHost == "127.0.0.1") {
                AppStrings.get(R.string.aps_loopback)
            } else {
                AppStrings.get(R.string.aps_alliface, boundHost)
            }
            onStatusChange(true, AppStrings.get(R.string.aps_started, hostLabel, DEFAULT_PORT))

            serverJob = scope.launch {
                while (isRunning) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        if (!connectionSlots.tryAcquire()) {
                            runCatching { clientSocket.close() }
                            Log.w(TAG, "Connection cap ($MAX_CONNECTIONS) hit, socket closed")
                            continue
                        }
                        launch {
                            try {
                                handleClientSocket(clientSocket)
                            } finally {
                                connectionSlots.release()
                            }
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (!isRunning) break
                        Log.e(TAG, "Socket accept error", e)
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            isRunning = false
            Log.e(TAG, "Failed to start API server on $boundHost:$DEFAULT_PORT", e)
            onStatusChange(false, AppStrings.get(R.string.aps_start_fail, boundHost, DEFAULT_PORT, e.localizedMessage))
        }
    }

    fun stop(onStatusChange: (Boolean, String?) -> Unit) {
        isRunning = false
        try {
            serverSocket?.close()
            serverSocket = null
            serverJob?.cancel()
            serverJob = null
            onStatusChange(false, AppStrings.get(R.string.aps_stopped))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to stop server", e)
            onStatusChange(false, AppStrings.get(R.string.aps_stop_err, e.localizedMessage))
        }
    }

    private suspend fun handleClientSocket(socket: Socket) {
        if (!preAuthSlots.tryAcquire()) {
            runCatching { socket.close() }
            return
        }
        var preAuthReleased = false
        try {
            socket.soTimeout = 30000
            val input = socket.getInputStream().buffered()
            val output = socket.getOutputStream()
            val headerDeadline = System.nanoTime() + MAX_HEADER_PHASE_MS * 1_000_000L

            val requestLine = readHttpLine(input, headerDeadline) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val uri = parts[1]

            var contentLength = 0
            var contentLengthSeen = false
            var badContentLength = false
            val headers = mutableMapOf<String, String>()
            var headerCount = 0
            while (true) {
                val line = readHttpLine(input, headerDeadline) ?: break
                if (line.isEmpty()) break
                headerCount++
                if (headerCount > MAX_HEADER_COUNT) {
                    HttpWireWriter.sendResponse(output, 431, "Request Header Fields Too Large", "application/json", "{\"error\":\"Too many headers\"}", headers["origin"])
                    return
                }
                val colonIdx = line.indexOf(':')
                if (colonIdx > 0) {
                    val key = line.substring(0, colonIdx).trim().lowercase()
                    val value = line.substring(colonIdx + 1).trim()
                    headers[key] = value
                    if (key == "content-length") {
                        val parsed = value.toLongOrNull()
                        when {
                            parsed == null || parsed < 0 -> badContentLength = true
                            contentLengthSeen && parsed != contentLength.toLong() -> badContentLength = true
                            parsed > Int.MAX_VALUE -> {
                                contentLength = MAX_REQUEST_BODY_BYTES + 1
                                contentLengthSeen = true
                            }
                            else -> {
                                contentLength = parsed.toInt()
                                contentLengthSeen = true
                            }
                        }
                    }
                }
            }

            val clientOrigin = headers["origin"]
            if (badContentLength) {
                HttpWireWriter.sendResponse(output, 400, "Bad Request", "application/json", "{\"error\":\"Invalid Content-Length\"}", clientOrigin)
                return
            }
            if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
                HttpWireWriter.sendResponse(output, 411, "Length Required", "application/json", "{\"error\":\"Chunked request bodies are not supported\"}", clientOrigin)
                return
            }
            if (method == "OPTIONS") {
                HttpWireWriter.sendCorsPreflight(output, clientOrigin)
                return
            }

            val settings = getSettings()
            if (settings.apiServerRequireAuth && uri != "/") {
                if (!checkAuthorization(headers, currentApiKey)) {
                    val unauthorizedJson = JSONObject()
                        .put("error", "Authentication failed: a valid API key is required. Send 'Authorization: Bearer <api_key>' or the 'X-API-Key' header.")
                        .toString()
                    HttpWireWriter.sendResponse(output, 401, "Unauthorized", "application/json", unauthorizedJson, clientOrigin)
                    return
                }
            }

            lastClientIp = socket.inetAddress?.hostAddress
            lastEndpoint = "$method $uri"
            onRequestProcessed?.invoke(requestCounter.incrementAndGet())

            if (contentLength !in 0..MAX_REQUEST_BODY_BYTES) {
                HttpWireWriter.sendResponse(output, 413, "Content Too Large", "application/json", "{\"error\":\"Request body too large\"}", clientOrigin)
                return
            }
            val bodyDeadline = System.nanoTime() + MAX_BODY_PHASE_MS * 1_000_000L
            val body = readHttpBody(input, contentLength, bodyDeadline)
            preAuthSlots.release()
            preAuthReleased = true

            routeRequest(method, uri, body, output, clientOrigin)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error handling client request", e)
        } finally {
            if (!preAuthReleased) preAuthSlots.release()
            runCatching { socket.close() }
        }
    }

    private suspend fun routeRequest(
        method: String,
        uri: String,
        body: String,
        output: OutputStream,
        clientOrigin: String?
    ) {
        when {
            uri == "/" ->
                HttpWireWriter.sendResponse(output, 200, "OK", "text/plain", "Ollama is running on OffconAI\n", clientOrigin)
            uri.startsWith("/api/tags") ->
                HttpWireWriter.sendResponse(output, 200, "OK", "application/json", ApiPayloadMapper.buildOllamaTagsJson(getAllModels(), getActiveModel()), clientOrigin)
            uri.startsWith("/v1/models") ->
                HttpWireWriter.sendResponse(output, 200, "OK", "application/json", ApiPayloadMapper.buildOpenAiModelsJson(getAllModels(), getActiveModel()), clientOrigin)
            uri.startsWith("/api/show") ->
                HttpWireWriter.sendResponse(output, 200, "OK", "application/json", ApiPayloadMapper.buildOllamaShowJson(getActiveModel(), getSettings()), clientOrigin)
            uri.startsWith("/api/version") ->
                HttpWireWriter.sendResponse(output, 200, "OK", "application/json", JSONObject().put("version", "0.5.1").toString(), clientOrigin)
            uri.startsWith("/api/generate") && method == "POST" ->
                handleOllamaCompletion(output, body, clientOrigin, chatFormat = false)
            uri.startsWith("/api/chat") && method == "POST" ->
                handleOllamaCompletion(output, body, clientOrigin, chatFormat = true)
            uri.startsWith("/v1/chat/completions") && method == "POST" ->
                handleOpenAiChat(output, body, clientOrigin)
            else -> {
                val errorJson = JSONObject().put("error", "Endpoint not found: $uri").toString()
                HttpWireWriter.sendResponse(output, 404, "Not Found", "application/json", errorJson, clientOrigin)
            }
        }
    }

    private fun isBusyError(e: Throwable): Boolean =
        e.message?.contains("BUSY_INFERENCE") == true

    private suspend fun handleOllamaCompletion(
        output: OutputStream,
        body: String,
        origin: String?,
        chatFormat: Boolean
    ) {
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        val isStream = json.optBoolean("stream", true)
        val modelName = getActiveModel()?.name ?: "local-model"

        val (history, prompt) = if (chatFormat) {
            ApiPayloadMapper.parseMessages(json.optJSONArray("messages") ?: JSONArray())
        } else {
            emptyList<Pair<String, String>>() to json.optString("prompt", "")
        }

        if (prompt.isBlank()) {
            val errMsg = if (chatFormat) "No user message to respond to" else "Prompt cannot be empty"
            HttpWireWriter.sendResponse(output, 400, "Bad Request", "application/json", JSONObject().put("error", errMsg).toString(), origin)
            return
        }
        if (!llmEngine.isModelReady()) {
            val err = JSONObject().put("error", "No local model is selected or loaded. Load a model in the app first.").toString()
            HttpWireWriter.sendResponse(output, 503, "Service Unavailable", "application/json", err, origin)
            return
        }

        val request = ApiPayloadMapper.resolveRequestOptions(json, getSettings())
        if (!generationSlots.tryAcquire()) {
            val err = JSONObject().put("error", "Server is busy: another generation is already running.").toString()
            HttpWireWriter.sendResponse(output, 429, "Too Many Requests", "application/json", err, origin)
            return
        }

        fun buildOllamaLine(content: String, done: Boolean, error: String? = null, evalCount: Int? = null): String =
            JSONObject().apply {
                put("model", modelName)
                if (error == null) put("created_at", HttpWireWriter.isoTimestamp())
                if (chatFormat) {
                    put("message", JSONObject().put("role", "assistant").put("content", content))
                } else {
                    put("response", content)
                }
                put("done", done)
                if (evalCount != null) put("eval_count", evalCount)
                if (error != null) put("error", error)
            }.toString()

        try {
            if (isStream) {
                HttpWireWriter.sendStreamHeaders(output, "application/x-ndjson", origin)
                var failed = false
                try {
                    llmEngine.streamGenerate(
                        prompt = prompt,
                        history = history,
                        settings = request.settings,
                        attachment = null,
                        numPredictOverride = request.numPredict
                    ).collect { chunk ->
                        if (chunk.token.isNotEmpty()) {
                            HttpWireWriter.sendChunk(output, buildOllamaLine(chunk.token, done = false) + "\n")
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    failed = true
                    val errText = if (isBusyError(e)) {
                        "Server is busy: another generation is already running."
                    } else {
                        "Inference failed: ${e.localizedMessage}"
                    }
                    HttpWireWriter.sendChunk(output, buildOllamaLine("", done = true, error = errText) + "\n")
                }
                if (!failed) {
                    HttpWireWriter.sendChunk(output, buildOllamaLine("", done = true) + "\n")
                }
                HttpWireWriter.endChunk(output)
            } else {
                val answer = StringBuilder()
                var totalTokens = 0
                try {
                    llmEngine.streamGenerate(
                        prompt = prompt,
                        history = history,
                        settings = request.settings,
                        attachment = null,
                        numPredictOverride = request.numPredict
                    ).collect { chunk ->
                        if (chunk.token.isNotEmpty()) answer.append(chunk.token)
                        totalTokens = chunk.totalTokens
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val status = if (isBusyError(e)) 429 else 500
                    val statusLabel = if (status == 429) "Too Many Requests" else "Internal Server Error"
                    val errText = if (status == 429) {
                        "Server is busy: another generation is already running."
                    } else {
                        "Inference failed: ${e.localizedMessage}"
                    }
                    HttpWireWriter.sendResponse(output, status, statusLabel, "application/json", JSONObject().put("error", errText).toString(), origin)
                    return
                }
                HttpWireWriter.sendResponse(output, 200, "OK", "application/json", buildOllamaLine(answer.toString(), done = true, evalCount = totalTokens), origin)
            }
        } finally {
            generationSlots.release()
        }
    }

    private suspend fun handleOpenAiChat(output: OutputStream, body: String, origin: String?) {
        val json = runCatching { JSONObject(body) }.getOrElse { JSONObject() }
        val isStream = json.optBoolean("stream", false)
        val activeModel = getActiveModel()
        val modelName = activeModel?.name ?: "local-model"
        val chatId = "chatcmpl-${System.currentTimeMillis()}"

        if (!llmEngine.isModelReady()) {
            val err = JSONObject().put("error", JSONObject().put("message", "No local model is selected or loaded.")).toString()
            HttpWireWriter.sendResponse(output, 503, "Service Unavailable", "application/json", err, origin)
            return
        }

        val (historyList, lastUserPrompt) = ApiPayloadMapper.parseMessages(json.optJSONArray("messages") ?: JSONArray())
        if (lastUserPrompt.isBlank()) {
            val err = JSONObject().put("error", JSONObject().put("message", "No user message to respond to")).toString()
            HttpWireWriter.sendResponse(output, 400, "Bad Request", "application/json", err, origin)
            return
        }

        val request = ApiPayloadMapper.resolveRequestOptions(json, getSettings())
        val offeredTools = if (request.settings.toolCallsEnabled) {
            ApiPayloadMapper.parseOpenAiTools(json)
        } else {
            emptyList()
        }
        val toolDialect = activeModel?.let { ToolCallTemplates.resolve(it, null) } ?: ToolCallTemplate.HERMES
        val toolsPrompt = if (offeredTools.isNotEmpty() && toolDialect != ToolCallTemplate.NONE) {
            McpToolProtocol.buildToolsPrompt("OpenAI Client", offeredTools, toolDialect)
        } else {
            null
        }

        if (!generationSlots.tryAcquire()) {
            val err = JSONObject().put("error", JSONObject().put("message", "Server is busy: another generation is already running.")).toString()
            HttpWireWriter.sendResponse(output, 429, "Too Many Requests", "application/json", err, origin)
            return
        }

        try {
            if (isStream) {
                HttpWireWriter.sendStreamHeaders(output, "text/event-stream", origin)
                var finalContentText = ""
                var emittedVisibleLen = 0
                try {
                    llmEngine.streamGenerate(
                        prompt = lastUserPrompt,
                        history = historyList,
                        settings = request.settings,
                        attachment = null,
                        mcpToolsContext = toolsPrompt,
                        numPredictOverride = request.numPredict
                    ).collect { chunk ->
                        finalContentText = chunk.currentContentText
                        if (toolsPrompt == null) {
                            if (chunk.token.isNotEmpty()) {
                                HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiSseChunk(chatId, modelName, chunk.token, null))
                            }
                        } else {
                            val visible = McpToolProtocol.visibleText(finalContentText, toolDialect)
                            if (visible.length > emittedVisibleLen && visible.startsWith(finalContentText.take(emittedVisibleLen).let { McpToolProtocol.visibleText(it, toolDialect) })) {
                                val delta = visible.substring(emittedVisibleLen)
                                if (delta.isNotEmpty()) {
                                    HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiSseChunk(chatId, modelName, delta, null))
                                }
                                emittedVisibleLen = visible.length
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val errText = if (isBusyError(e)) {
                        "Server is busy: another generation is already running."
                    } else {
                        "Inference failed: ${e.localizedMessage}"
                    }
                    HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiSseChunk(chatId, modelName, null, "stop", errText))
                    HttpWireWriter.sendChunk(output, "data: [DONE]\n\n")
                    HttpWireWriter.endChunk(output)
                    return
                }
                val parsedBatch = if (toolsPrompt != null) {
                    McpToolProtocol.parseBatch(finalContentText, offeredTools, template = toolDialect)
                } else null
                if (parsedBatch != null && parsedBatch.calls.isNotEmpty()) {
                    HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiToolCallSseChunk(chatId, modelName, parsedBatch.calls))
                    HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiSseChunk(chatId, modelName, null, "tool_calls"))
                } else {
                    if (toolsPrompt != null && finalContentText.length > emittedVisibleLen) {
                        val rest = finalContentText.substring(emittedVisibleLen)
                        if (rest.isNotEmpty()) {
                            HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiSseChunk(chatId, modelName, rest, null))
                        }
                    }
                    HttpWireWriter.sendChunk(output, ApiPayloadMapper.buildOpenAiSseChunk(chatId, modelName, null, "stop"))
                }
                HttpWireWriter.sendChunk(output, "data: [DONE]\n\n")
                HttpWireWriter.endChunk(output)
            } else {
                var finalContentText = ""
                var totalTokens = 0
                try {
                    llmEngine.streamGenerate(
                        prompt = lastUserPrompt,
                        history = historyList,
                        settings = request.settings,
                        attachment = null,
                        mcpToolsContext = toolsPrompt,
                        numPredictOverride = request.numPredict
                    ).collect { chunk ->
                        finalContentText = chunk.currentContentText
                        totalTokens = chunk.totalTokens
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val status = if (isBusyError(e)) 429 else 500
                    val statusLabel = if (status == 429) "Too Many Requests" else "Internal Server Error"
                    val errText = if (status == 429) {
                        "Server is busy: another generation is already running."
                    } else {
                        "Inference failed: ${e.localizedMessage}"
                    }
                    val err = JSONObject().put("error", JSONObject().put("message", errText)).toString()
                    HttpWireWriter.sendResponse(output, status, statusLabel, "application/json", err, origin)
                    return
                }
                val parsedBatch = if (toolsPrompt != null) {
                    McpToolProtocol.parseBatch(finalContentText, offeredTools, template = toolDialect)
                } else null
                val responseBody = if (parsedBatch != null && parsedBatch.calls.isNotEmpty()) {
                    ApiPayloadMapper.buildOpenAiCompletionJson(chatId, modelName, parsedBatch.prose, totalTokens, parsedBatch.calls)
                } else {
                    ApiPayloadMapper.buildOpenAiCompletionJson(chatId, modelName, finalContentText, totalTokens)
                }
                HttpWireWriter.sendResponse(
                    output,
                    200,
                    "OK",
                    "application/json",
                    responseBody,
                    origin
                )
            }
        } finally {
            generationSlots.release()
        }
    }
}
