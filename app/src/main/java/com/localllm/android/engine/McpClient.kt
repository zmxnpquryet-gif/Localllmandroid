package com.localllm.android.engine

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
        .followSslRedirects(true)
        .build()

    private val sseHttp: OkHttpClient = http.newBuilder()
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    private val idSeq = AtomicLong(0)

    @Volatile private var sessionId: String? = null
    @Volatile private var messageEndpoint: String? = null
    @Volatile private var serverName: String = ""
    @Volatile private var cachedTools: List<McpTool> = emptyList()

    fun currentTools(): List<McpTool> = cachedTools

    fun disconnect() {
        sessionId = null
        messageEndpoint = null
        serverName = ""
        cachedTools = emptyList()
    }

    suspend fun connectServer(url: String): McpConnectionResult = withContext(Dispatchers.IO) {
        disconnect()
        val clean = url.trim()
        if (clean.isBlank()) {
            return@withContext McpConnectionResult(false, "Unknown", emptyList(), 0, "MCP URL을 입력해 주세요.")
        }
        val parsed = try {
            clean.toHttpUrlOrNull()
        } catch (_: Throwable) {
            null
        }
        if (parsed == null || (parsed.scheme != "http" && parsed.scheme != "https")) {
            return@withContext McpConnectionResult(false, "Unknown", emptyList(), 0, "http(s) 형식의 MCP URL이 아닙니다.")
        }

        val start = System.currentTimeMillis()
        val elapsed = { System.currentTimeMillis() - start }
        val initPayload = rpcRequest(
            "initialize",
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "LocalLLM-Android").put("version", "1.4.0"))
        )

        // 1) Streamable HTTP: initialize directly on the URL.
        val direct = postJson(clean, initPayload, null)
        if (direct != null && direct.code in 200..299) {
            val name = parseServerName(direct.bodyJson) ?: parsed.host
            sessionId = direct.sessionId ?: sessionId
            messageEndpoint = clean
            postJson(clean, rpcNotification("notifications/initialized", JSONObject()), sessionId)
            return@withContext try {
                val tools = fetchTools(clean)
                cachedTools = tools
                serverName = name
                McpConnectionResult(true, name, tools, elapsed(), "연결 성공: ${tools.size}개 도구")
            } catch (e: Exception) {
                disconnect()
                McpConnectionResult(false, name, emptyList(), elapsed(), "도구 목록 조회 실패: ${e.message}")
            }
        }

        // 2) Legacy SSE transport handshake.
        val sseEndpoint = openSseEndpoint(clean)
        if (sseEndpoint != null) {
            val init = postJson(sseEndpoint, initPayload, null)
            if (init != null && init.code in 200..299) {
                val name = parseServerName(init.bodyJson) ?: parsed.host
                messageEndpoint = sseEndpoint
                return@withContext try {
                    val tools = fetchTools(sseEndpoint)
                    cachedTools = tools
                    serverName = name
                    McpConnectionResult(true, name, tools, elapsed(), "연결 성공(SSE): ${tools.size}개 도구")
                } catch (e: Exception) {
                    disconnect()
                    McpConnectionResult(false, name, emptyList(), elapsed(), "도구 목록 조회 실패: ${e.message}")
                }
            }
        }

        val hint = if (direct != null) "HTTP ${direct.code}" else "연결 실패"
        McpConnectionResult(
            false, parsed.host, emptyList(), elapsed(),
            "MCP 핸드셰이크 실패 ($hint). Streamable HTTP 또는 SSE 방식의 MCP 서버 URL인지 확인하세요."
        )
    }

    suspend fun callTool(name: String, argumentsJson: String = "{}"): McpToolCallResult = withContext(Dispatchers.IO) {
        val endpoint = messageEndpoint
            ?: return@withContext McpToolCallResult(false, "", "MCP 서버에 연결되어 있지 않습니다. 먼저 연결하세요.")
        val args = try {
            JSONObject(argumentsJson.ifBlank { "{}" })
        } catch (_: Exception) {
            JSONObject()
        }
        val payload = rpcRequest("tools/call", JSONObject().put("name", name).put("arguments", args))
        val resp = postJson(endpoint, payload, sessionId)
            ?: return@withContext McpToolCallResult(false, "", "도구 호출 요청 실패 (네트워크 오류).")
        if (resp.code !in 200..299) {
            return@withContext McpToolCallResult(false, "", "도구 호출 실패 (HTTP ${resp.code}).")
        }
        val body = resp.bodyJson
            ?: return@withContext McpToolCallResult(false, "", "도구 호출 응답을 해석하지 못했습니다.")
        if (body.has("error") && !body.isNull("error")) {
            val msg = body.optJSONObject("error")?.optString("message") ?: "알 수 없는 오류"
            return@withContext McpToolCallResult(false, "", "도구 오류: $msg")
        }
        val result = body.optJSONObject("result")
        val texts = mutableListOf<String>()
        val content = result?.optJSONArray("content")
        if (content != null) {
            for (i in 0 until content.length()) {
                val item = content.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") texts.add(item.optString("text"))
            }
        }
        val failed = result?.optBoolean("isError", false) == true
        McpToolCallResult(
            !failed,
            texts.joinToString("\n"),
            if (texts.isEmpty()) "도구가 빈 결과를 반환했습니다." else "도구 실행 성공"
        )
    }

    companion object {
        internal const val MAX_TOOL_SCHEMA_CHARS = 2000
        internal const val MAX_TOOLS_CONTEXT_CHARS = 4000

        /** Renders the *actually connected* tools for prompt injection. Null when none. */
        fun buildToolsContext(server: String, tools: List<McpTool>): String? {
            if (tools.isEmpty()) return null
            val sb = StringBuilder("MCP 서버 \"").append(server).append("\" 제공 도구 (실시간 조회됨):")
            for (t in tools) {
                val schema = if (t.parameters.length > MAX_TOOL_SCHEMA_CHARS) {
                    t.parameters.take(MAX_TOOL_SCHEMA_CHARS) + "…(생략)"
                } else {
                    t.parameters
                }
                val line = "\n- ${t.name}: ${t.description} 입력: $schema"
                if (sb.length + line.length > MAX_TOOLS_CONTEXT_CHARS) {
                    sb.append("\n…(나머지 도구 생략)")
                    break
                }
                sb.append(line)
            }
            return sb.toString()
        }
    }

    // ---- JSON-RPC plumbing ----

    private data class JsonResponse(val code: Int, val sessionId: String?, val bodyJson: JSONObject?)

    private fun rpcRequest(method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", idSeq.incrementAndGet())
            .put("method", method)
            .put("params", params)
            .toString()

    private fun rpcNotification(method: String, params: JSONObject): String =
        JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("params", params)
            .toString()

    private fun postJson(url: String, payload: String, session: String?): JsonResponse? {
        return try {
            val builder = Request.Builder()
                .url(url)
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
            if (!session.isNullOrBlank()) builder.header("Mcp-Session-Id", session)
            builder.post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            http.newCall(builder.build()).execute().use { resp ->
                val sid = resp.header("Mcp-Session-Id")
                val raw = try {
                    resp.body?.string()
                } catch (_: Exception) {
                    null
                }
                JsonResponse(resp.code, sid, extractJsonMessage(raw))
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Handles both plain-JSON and SSE (`data: {...}`) response bodies. */
    internal fun extractJsonMessage(raw: String?): JSONObject? {
        if (raw.isNullOrBlank()) return null
        val trimmed = raw.trim()
        if (trimmed.startsWith("{")) {
            return try {
                JSONObject(trimmed)
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
                last = obj
                if (obj.has("result") || obj.has("error")) return obj
            } catch (_: Exception) {
                // Heartbeat / comment lines carry no JSON.
            }
        }
        return last
    }

    private fun parseServerName(envelope: JSONObject?): String? {
        val info = envelope?.optJSONObject("result")?.optJSONObject("serverInfo") ?: return null
        val name = info.optString("name").ifBlank { return null }
        val version = info.optString("version")
        return if (version.isBlank()) name else "$name $version"
    }

    private fun fetchTools(endpoint: String): List<McpTool> {
        val first = postJson(endpoint, rpcRequest("tools/list", JSONObject()), sessionId)
            ?: throw IllegalStateException("도구 목록 요청 실패 (네트워크 오류).")
        if (first.code !in 200..299) throw IllegalStateException("도구 목록 조회 실패 (HTTP ${first.code}).")
        val body = first.bodyJson ?: throw IllegalStateException("도구 목록 응답을 해석하지 못했습니다.")
        if (body.has("error") && !body.isNull("error")) {
            val msg = body.optJSONObject("error")?.optString("message") ?: "알 수 없는 오류"
            throw IllegalStateException("서버 오류: $msg")
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
    private fun openSseEndpoint(baseUrl: String): String? {
        return try {
            val req = Request.Builder().url(baseUrl).header("Accept", "text/event-stream").get().build()
            sseHttp.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return null
                val contentType = resp.header("Content-Type") ?: ""
                if (!contentType.contains("text/event-stream", ignoreCase = true)) return null
                val source = resp.body?.source() ?: return null
                var eventName: String? = null
                repeat(200) {
                    val line = try {
                        source.readUtf8Line()
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

    internal fun resolveEndpoint(baseUrl: String, data: String): String? {
        if (data.startsWith("http://") || data.startsWith("https://")) return data
        return try {
            val base = baseUrl.toHttpUrlOrNull() ?: return null
            base.resolve(data)?.toString()
        } catch (_: Exception) {
            null
        }
    }
}
