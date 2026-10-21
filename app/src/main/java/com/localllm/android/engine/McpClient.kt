package com.localllm.android.engine

import com.localllm.android.BuildConfig
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class McpTool(
    val name: String,
    val description: String,
    val parameters: String
)

data class McpConnectionResult(
    val isSuccess: Boolean,
    val serverName: String,
    val tools: List<McpTool>,
    val latencyMs: Long,
    val message: String
)

data class McpToolCallResult(
    val isSuccess: Boolean,
    val text: String,
    val message: String
)

/**
 * Real MCP client speaking JSON-RPC 2.0.
 *
 * Transports tried in order:
 *  1. Streamable HTTP — POST initialize/tools/list/tools/call directly to the URL,
 *     accepting both plain-JSON and SSE (`data:`) response bodies.
 *  2. Legacy SSE transport — GET event stream, read the `endpoint` event, then
 *     POST JSON-RPC messages there.
 *
 * Deliberately reports failure instead of inventing tools: a previous version
 * returned hard-coded tools with `isSuccess = true` even when the server was
 * unreachable, which misled both the UI and the model prompt.
 */
class McpClient(client: OkHttpClient? = null) {

    private val http: OkHttpClient = client ?: OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        // Never downgrade https to http on redirect: the bearer token must not
        // travel in cleartext because a server answered with a Location header.
        .followSslRedirects(false)
        .build()

    private val sseHttp: OkHttpClient = http.newBuilder()
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val idSeq = AtomicLong(0)

    @Volatile private var sessionId: String? = null
    @Volatile private var messageEndpoint: String? = null
    @Volatile private var serverName: String = ""
    @Volatile private var cachedTools: List<McpTool> = emptyList()
    @Volatile private var bearerToken: String? = null

    fun currentTools(): List<McpTool> = cachedTools

    fun isConnected(): Boolean = messageEndpoint != null

    fun disconnect() {
        sessionId = null
        messageEndpoint = null
        serverName = ""
        cachedTools = emptyList()
        bearerToken = null
    }

    /**
     * @param bearerToken optional `Authorization: Bearer` credential. It is only ever sent
     *   to the configured origin (SSE endpoints on another origin are rejected, and OkHttp
     *   drops Authorization on cross-host redirects), and never over plain HTTP to a
     *   public host.
     */
    suspend fun connectServer(url: String, bearerToken: String? = null): McpConnectionResult = withContext(Dispatchers.IO) {
        disconnect()
        val clean = url.trim()
        if (clean.isBlank()) {
            return@withContext McpConnectionResult(false, "Unknown", emptyList(), 0, AppStrings.get(R.string.mcp_enter_url))
        }
        val parsed = try {
            clean.toHttpUrlOrNull()
        } catch (_: Throwable) {
            null
        }
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return@withContext McpConnectionResult(false, "Unknown", emptyList(), 0, AppStrings.get(R.string.mcp_bad_url))
        }
        val token = bearerToken?.trim()?.takeIf { it.isNotEmpty() }
        if (token != null && !isTokenSafeTransport(parsed)) {
            return@withContext McpConnectionResult(
                false, parsed.host, emptyList(), 0,
                "Refusing to send a bearer token over plain HTTP to a public host. Use an https:// URL."
            )
        }
        this@McpClient.bearerToken = token

        val start = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - start }
        val initReq = rpcRequest(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "LocalLLM-Android").put("version", BuildConfig.VERSION_NAME))
        )

        // 1) Streamable HTTP: initialize directly on the URL.
        val direct = postJson(clean, initReq.json, null, initReq.id)
        if (direct != null && direct.code in 200..299) {
            val name = parseServerName(direct.bodyJson) ?: parsed.host
            sessionId = direct.sessionId ?: sessionId
            messageEndpoint = clean
            postJson(clean, rpcNotification("notifications/initialized", JSONObject()), sessionId)
            return@withContext try {
                val tools = fetchTools(clean)
                cachedTools = tools
                serverName = name
                McpConnectionResult(true, name, tools, elapsed(), AppStrings.get(R.string.mcp_connected, tools.size))
            } catch (e: CancellationException) {
                disconnect()
                throw e
            } catch (e: Exception) {
                disconnect()
                McpConnectionResult(false, name, emptyList(), elapsed(), AppStrings.get(R.string.mcp_tools_fail, e.message))
            }
        }

        // 2) Legacy SSE transport handshake: same initialize/initialized/tools
        // sequence as the Streamable path, including the session id the server
        // returns. Skipping either step breaks spec-strict MCP servers.
        val sseEndpoint = openSseEndpoint(clean)
        if (sseEndpoint != null) {
            val init = postJson(sseEndpoint, initReq.json, null, initReq.id)
            if (init != null && init.code in 200..299) {
                val name = parseServerName(init.bodyJson) ?: parsed.host
                sessionId = init.sessionId ?: sessionId
                messageEndpoint = sseEndpoint
                postJson(sseEndpoint, rpcNotification("notifications/initialized", JSONObject()), sessionId)
                return@withContext try {
                    val tools = fetchTools(sseEndpoint)
                    cachedTools = tools
                    serverName = name
                    McpConnectionResult(true, name, tools, elapsed(), AppStrings.get(R.string.mcp_connected_sse, tools.size))
                } catch (e: CancellationException) {
                    disconnect()
                    throw e
                } catch (e: Exception) {
                    disconnect()
                    McpConnectionResult(false, name, emptyList(), elapsed(), AppStrings.get(R.string.mcp_tools_fail, e.message))
                }
            }
        }

        val hint = if (direct != null) "HTTP ${direct.code}" else AppStrings.get(R.string.mcp_conn_fail)
        McpConnectionResult(
            false, parsed.host, emptyList(), elapsed(),
            AppStrings.get(R.string.mcp_handshake, hint)
        )
    }

    suspend fun callTool(name: String, argumentsJson: String = "{}"): McpToolCallResult = withContext(Dispatchers.IO) {
        val endpoint = messageEndpoint
            ?: return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_not_connected))
        // Malformed arguments are reported, not silently replaced by {}: calling a tool
        // with arguments the model never wrote would be a wrong action, not a soft error.
        val args = try {
            JSONObject(argumentsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            return@withContext McpToolCallResult(false, "", "Tool arguments are not a valid JSON object.")
        }
        val call = rpcRequest("tools/call", JSONObject().put("name", name).put("arguments", args))
        val resp = postJson(endpoint, call.json, sessionId, call.id)
            ?: return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_call_net))
        if (resp.code !in 200..299) {
            return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_call_http, resp.code))
        }
        val body = resp.bodyJson
            ?: return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_call_parse))
        if (body.has("error") && !body.isNull("error")) {
            val msg = body.optJSONObject("error")?.optString("message") ?: AppStrings.get(R.string.eng_unknown_err)
            return@withContext McpToolCallResult(false, "", AppStrings.get(R.string.mcp_tool_error, msg))
        }
        val result = body.optJSONObject("result")
        val texts = mutableListOf<String>()
        val content = result?.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                val type = item.optString("type")
                if (type == "text") {
                    texts.add(item.optString("text"))
                } else if (type.isNotBlank()) {
                    // Say what was dropped so the model does not assume it saw everything.
                    texts.add("[$type content omitted: only text is supported]")
                }
            }
        }
        val failed = result?.optBoolean("isError", false) == true
        McpToolCallResult(
            !failed,
            texts.joinToString("\n"),
            if (texts.isEmpty()) AppStrings.get(R.string.mcp_empty) else AppStrings.get(R.string.mcp_exec_ok)
        )
    }

    companion object {
        internal const val MAX_TOOL_SCHEMA_CHARS = 2000
        internal const val MAX_TOOLS_CONTEXT_CHARS = 4000
        internal const val MAX_RESPONSE_BYTES = 8L * 1024 * 1024
        internal const val MAX_SSE_LINE_BYTES = 256L * 1024
    }

    // ---- JSON-RPC plumbing ----

    private data class JsonResponse(val code: Int, val sessionId: String?, val bodyJson: JSONObject?)

    private data class RpcPayload(val json: String, val id: Long)

    private fun rpcRequest(method: String, params: JSONObject): RpcPayload {
        val id = idSeq.incrementAndGet()
        val json = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()
        return RpcPayload(json, id)
    }

    private fun rpcNotification(method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()

    /**
     * POSTs JSON-RPC without ever blocking a thread: cancellation (the user hitting
     * Stop mid tool call) aborts the in-flight HTTP call instead of leaving an orphan
     * request running against the tool server for up to 30 seconds.
     */
    private suspend fun postJson(url: String, payload: String, session: String?, expectedId: Long? = null): JsonResponse? {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
        if (!session.isNullOrBlank()) builder.header("Mcp-Session-Id", session)
        authHeaderFor(url)?.let { builder.header("Authorization", it) }
        builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
        val call = http.newCall(builder.build())
        return try {
            call.await().use { resp ->
                val sid = resp.header("Mcp-Session-Id")
                val raw = try {
                    resp.body?.source()?.use { src ->
                        // Bound the buffer: a hostile server must not OOM the app.
                        if (src.request(MAX_RESPONSE_BYTES + 1)) {
                            throw IOException("MCP response exceeds $MAX_RESPONSE_BYTES bytes")
                        }
                        src.readUtf8()
                    }
                } catch (_: Exception) {
                    null
                }
                JsonResponse(resp.code, sid, extractJsonMessage(raw, expectedId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation {
            try {
                cancel()
            } catch (_: Throwable) {
            }
        }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCompleted) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                if (!cont.isCompleted) {
                    cont.resume(response) { _, _, _ ->
                        try {
                            response.close()
                        } catch (_: Throwable) {
                        }
                    }
                } else {
                    try {
                        response.close()
                    } catch (_: Throwable) {
                    }
                }
            }
        })
    }

    /** Handles both plain-JSON and SSE (`data: {...}`) response bodies. */
    internal fun extractJsonMessage(raw: String?, expectedId: Long? = null): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return try {
                JSONObject(trimmed).takeIf { matchesId(it, expectedId) }
            } catch (_: Exception) {
                null
            }
        }
        var last: JSONObject? = null
        for (line in trimmed.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("data:")) continue
            val payload = t.removePrefix("data:").trim()
            if (payload == "[DONE]") continue
            try {
                val obj = JSONObject(payload)
                if (!matchesId(obj, expectedId)) continue
                if (obj.has("result") || obj.has("error")) return obj
                last = obj
            } catch (_: Exception) {
                // Heartbeat / comment lines carry no JSON.
            }
        }
        // A response to THIS request always carries result/error and its id;
        // notifications, server requests and stale replies must never be
        // mistaken for one.
        return if (expectedId != null) null else last
    }

    private fun matchesId(obj: JSONObject, expectedId: Long?): Boolean {
        if (expectedId == null) return true
        val id = obj.opt("id") ?: return false
        return id.toString() == expectedId.toString()
    }

    /** readUtf8Line() with a hard bound: a newline-free stream must not OOM us. */
    private fun readBoundedLine(source: BufferedSource): String? {
        var want = 64L
        while (true) {
            if (!source.request(want)) {
                if (source.buffer.size == 0L) return null
                return source.readUtf8().trimEnd('\n', '\r')
            }
            val nl = source.buffer.indexOf('\n'.code.toByte())
            if (nl >= 0) {
                // The whole line may already be buffered in one socket read:
                // enforce the cap on the line length, not just on `want`.
                if (nl > MAX_SSE_LINE_BYTES) {
                    throw IOException("SSE line exceeds $MAX_SSE_LINE_BYTES bytes")
                }
                return source.readUtf8(nl + 1).trimEnd('\n', '\r')
            }
            if (source.buffer.size > MAX_SSE_LINE_BYTES) {
                throw IOException("SSE line exceeds $MAX_SSE_LINE_BYTES bytes")
            }
            want = minOf(want * 2, MAX_SSE_LINE_BYTES + 1)
        }
    }

    private fun parseServerName(envelope: JSONObject?): String? {
        val info = envelope?.optJSONObject("result")?.optJSONObject("serverInfo") ?: return null
        val name = info.optString("name").ifBlank { return null }
        val version = info.optString("version")
        return if (version.isBlank()) name else "$name $version"
    }

    private suspend fun fetchTools(endpoint: String): List<McpTool> {
        val req = rpcRequest("tools/list", JSONObject())
        val first = postJson(endpoint, req.json, sessionId, req.id)
            ?: throw IllegalStateException(AppStrings.get(R.string.mcp_list_net))
        if (first.code !in 200..299) throw IllegalStateException(AppStrings.get(R.string.mcp_list_http, first.code))
        val body = first.bodyJson ?: throw IllegalStateException(AppStrings.get(R.string.mcp_list_parse))
        if (body.has("error") && !body.isNull("error")) {
            val msg = body.optJSONObject("error")?.optString("message") ?: AppStrings.get(R.string.eng_unknown_err)
            throw IllegalStateException(AppStrings.get(R.string.mcp_server_error, msg))
        }
        val arr = body.optJSONObject("result")?.optJSONArray("tools") ?: JSONArray()
        val out = mutableListOf<McpTool>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val toolName = t.optString("name")
            if (toolName.isBlank()) continue
            out.add(McpTool(toolName, t.optString("description"), t.opt("inputSchema")?.toString() ?: "{}"))
        }
        return out
    }

    /** Legacy SSE transport handshake: returns the POST message endpoint, or null. */
    private suspend fun openSseEndpoint(baseUrl: String): String? {
        return try {
            val reqBuilder = Request.Builder().url(baseUrl).header("Accept", "text/event-stream").get()
            authHeaderFor(baseUrl)?.let { reqBuilder.header("Authorization", it) }
            val req = reqBuilder.build()
            sseHttp.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return null
                val contentType = resp.header("Content-Type") ?: ""
                if (!contentType.contains("text/event-stream", ignoreCase = true)) return null
                val source = resp.body?.source() ?: return null
                var eventName: String? = null
                repeat(200) {
                    val line = try {
                        readBoundedLine(source)
                    } catch (_: Exception) {
                        return null
                    } ?: return null
                    val t = line.trim()
                    when {
                        t.startsWith("event:") -> eventName = t.removePrefix("event:").trim()
                        t.startsWith("data:") -> {
                            val data = t.removePrefix("data:").trim()
                            if (eventName == "endpoint" && data.isNotBlank()) {
                                return resolveEndpoint(baseUrl, data)
                            }
                        }
                        t.isEmpty() -> eventName = null
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the `endpoint` event of the legacy SSE transport. The result must be on
     * the same origin (scheme, host, port) as the URL the user configured: the server
     * controls this value, so following an absolute URL anywhere would let a hostile or
     * compromised MCP server point this client (and its bearer token) at an arbitrary
     * host, including loopback/LAN services. Cross-origin endpoints are rejected.
     */
    internal fun resolveEndpoint(baseUrl: String, data: String): String? {
        return try {
            val base = baseUrl.toHttpUrlOrNull() ?: return null
            val resolved = base.resolve(data.trim()) ?: return null
            if (!sameOrigin(base, resolved)) null else resolved.toString()
        } catch (_: Exception) {
            null
        }
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean {
        if (a.scheme != b.scheme || a.port != b.port) return false
        // 127.0.0.1 and localhost are the same machine talking to itself; a local
        // server may advertise either spelling in its endpoint event.
        if (isLoopbackHost(a.host) && isLoopbackHost(b.host)) return true
        return a.host.equals(b.host, ignoreCase = true)
    }

    internal fun isLoopbackHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1") return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        return parts.size == 4 && parts.all { it in 0..255 } && parts[0] == 127
    }

    /**
     * The `Authorization` value for one request URL, or null when no token may go
     * there. Checked per request — not only when connecting — so a redirect or a
     * changed endpoint can never coax the stored token onto an unsafe transport.
     *
     * A bearer token may go over https anywhere, or over plain http only to loopback
     * or a private/LAN address (an on-device or home-network server). Plain http to a
     * public host would expose the credential on the wire. mDNS `.local` names are
     * deliberately NOT exempted: they resolve over broadcast and are spoofable on
     * untrusted networks, so they need https like everything else.
     */
    internal fun authHeaderFor(requestUrl: String): String? {
        val token = bearerToken ?: return null
        val httpUrl = try {
            requestUrl.toHttpUrlOrNull()
        } catch (_: Throwable) {
            null
        } ?: return null
        return if (isTokenSafeTransport(httpUrl)) "Bearer $token" else null
    }

    internal fun isTokenSafeTransport(url: HttpUrl): Boolean {
        if (url.isHttps) return true
        val host = url.host.lowercase()
        if (host == "localhost") return true
        val parts = host.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size == 4 && parts.all { it in 0..255 }) {
            val (a, b) = parts[0] to parts[1]
            return a == 127 || a == 10 ||
                (a == 192 && b == 168) ||
                (a == 172 && b in 16..31) ||
                (a == 169 && b == 254)
        }
        return host == "::1" || host == "[::1]"
    }
}
