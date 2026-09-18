package com.localllm.android.engine

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class McpClientTest {

    private fun fakeClient(handler: (Request) -> Response): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(object : Interceptor {
                override fun intercept(chain: Interceptor.Chain): Response = handler(chain.request())
            })
            .build()

    private fun jsonResponse(
        request: Request,
        json: String,
        code: Int = 200,
        contentType: String = "application/json",
        headers: Map<String, String> = emptyMap()
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("OK")
            .body(json.toResponseBody(contentType.toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun streamableServer(): (Request) -> Response = { request ->
        val body = try {
            request.body?.let { rb ->
                val buffer = okio.Buffer()
                rb.writeTo(buffer)
                buffer.readUtf8()
            } ?: ""
        } catch (_: Exception) {
            ""
        }
        val method = try {
            JSONObject(body).optString("method")
        } catch (_: Exception) {
            ""
        }
        when (method) {
            "initialize" -> jsonResponse(
                request,
                """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"TestServer","version":"9.9"}}}""",
                headers = mapOf("Mcp-Session-Id" to "sess-123")
            )
            "notifications/initialized" -> jsonResponse(request, "", 202)
            "tools/list" -> jsonResponse(
                request,
                """{"jsonrpc":"2.0","id":2,"result":{"tools":[
                    {"name":"get_weather","description":"현재 날씨 조회","inputSchema":{"type":"object","properties":{"city":{"type":"string"}}}},
                    {"name":"add","description":"덧셈","inputSchema":{"type":"object"}}
                ]}}"""
            )
            "tools/call" -> jsonResponse(
                request,
                """{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"맑음 21도"}]}}"""
            )
            else -> jsonResponse(request, """{"error":"unknown"}""", 400)
        }
    }

    @Test
    fun `streamable handshake lists real tools`() = runBlocking {
        val client = McpClient(fakeClient(streamableServer()))
        val result = client.connectServer("https://mcp.example.com/mcp")

        assertTrue(result.isSuccess)
        assertTrue(result.serverName.contains("TestServer"))
        assertEquals(listOf("get_weather", "add"), result.tools.map { it.name })
        assertEquals(listOf("get_weather", "add"), client.currentTools().map { it.name })
        assertTrue(result.latencyMs >= 0)

        val context = McpClient.buildToolsContext(result.serverName, result.tools)
        assertNotNull(context)
        assertTrue(context!!.contains("get_weather"))
        assertTrue(context.contains("city"))
    }

    @Test
    fun `tool call returns text content`() = runBlocking {
        val client = McpClient(fakeClient(streamableServer()))
        assertTrue(client.connectServer("https://mcp.example.com/mcp").isSuccess)

        val call = client.callTool("get_weather", """{"city":"서울"}""")
        assertTrue(call.isSuccess)
        assertEquals("맑음 21도", call.text)
    }

    @Test
    fun `unreachable server fails honestly without fake tools`() = runBlocking {
        val client = McpClient(fakeClient { request -> jsonResponse(request, "not found", 404) })
        val result = client.connectServer("https://mcp.example.com/mcp")

        assertFalse(result.isSuccess)
        assertTrue(result.tools.isEmpty())
        assertTrue(client.currentTools().isEmpty())
        assertTrue(result.message.contains("404"))
    }

    @Test
    fun `blank and non-http urls are rejected`() = runBlocking {
        val client = McpClient(fakeClient { request -> jsonResponse(request, "{}", 200) })

        assertFalse(client.connectServer("   ").isSuccess)
        assertFalse(client.connectServer("not a url").isSuccess)
        assertFalse(client.connectServer("ftp://files.example.com/mcp").isSuccess)
    }

    @Test
    fun `sse response bodies are parsed`() {
        val client = McpClient()
        val raw = "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":[]}}\n\ndata: [DONE]\n"
        val parsed = client.extractJsonMessage(raw)
        assertNotNull(parsed)
        assertTrue(parsed!!.has("result"))

        assertNull(client.extractJsonMessage(null))
        assertNull(client.extractJsonMessage("   "))
        assertNull(client.extractJsonMessage("event: ping\ndata: hello\n"))
    }

    @Test
    fun `endpoint resolution handles absolute and relative targets`() {
        val client = McpClient()
        assertEquals(
            "https://other.example/rpc",
            client.resolveEndpoint("https://mcp.example.com/sse", "https://other.example/rpc")
        )
        assertEquals(
            "https://mcp.example.com/messages/1",
            client.resolveEndpoint("https://mcp.example.com/sse", "/messages/1")
        )
        assertNull(client.resolveEndpoint("not a url", "/messages/1"))
    }

    @Test
    fun `tools context truncates long schemas and tool lists`() {
        val bigSchema = "{\"type\":\"object\",\"x\":\"" + "y".repeat(5000) + "\"}"
        val tools = listOf(McpTool("big", "큰 스키마", bigSchema)) +
                (1..50).map { McpTool("tool$it", "desc$it", "{}") }
        val context = McpClient.buildToolsContext("S", tools)!!
        assertTrue(context.length <= McpClient.MAX_TOOLS_CONTEXT_CHARS + 100)
        assertTrue(context.contains("…(생략)"))
        assertNull(McpClient.buildToolsContext("S", emptyList()))
    }
}
