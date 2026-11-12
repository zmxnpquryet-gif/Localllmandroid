package com.localllm.android.engine

import com.localllm.android.model.ToolCallTemplate
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ToolCallDialectsTest {

    private val tools = listOf(
        McpTool("get_weather", "current weather lookup", """{"type":"object","properties":{"city":{"type":"string"}}}"""),
        McpTool("add", "addition", """{"type":"object"}""")
    )

    // ---------------- prompt injection ----------------

    @Test
    fun `each dialect is offered in the prompt it was selected for`() {
        val llama = McpToolProtocol.buildToolsPrompt("S", tools, ToolCallTemplate.LLAMA3_JSON)!!
        assertTrue(llama.contains(ToolCallProtocolTags.LLAMA))
        assertTrue(llama.contains("\"parameters\""))
        assertFalse(llama.contains("<tool_call>"))

        val mistral = McpToolProtocol.buildToolsPrompt("S", tools, ToolCallTemplate.MISTRAL)!!
        assertTrue(mistral.contains(ToolCallProtocolTags.MISTRAL))
        assertFalse(mistral.contains("<tool_call>"))

        val plain = McpToolProtocol.buildToolsPrompt("S", tools, ToolCallTemplate.PLAIN_JSON)!!
        assertFalse(plain.contains("<tool_call>"))
        assertFalse(plain.contains(ToolCallProtocolTags.MISTRAL))

        assertNull(McpToolProtocol.buildToolsPrompt("S", tools, ToolCallTemplate.NONE))

        // Every enabled dialect still lists the tools and the untrusted-data rule.
        for (template in listOf(
            ToolCallTemplate.AUTO,
            ToolCallTemplate.HERMES,
            ToolCallTemplate.MINICPM5,
            ToolCallTemplate.LLAMA3_JSON,
            ToolCallTemplate.MISTRAL,
            ToolCallTemplate.PLAIN_JSON
        )) {
            val prompt = McpToolProtocol.buildToolsPrompt("S", tools, template)!!
            assertTrue("$template lost the tool list", prompt.contains("get_weather"))
            assertTrue("$template lost the untrusted-data rule", prompt.contains("not instructions"))
        }
    }

    // ---------------- parsing ----------------

    @Test
    fun `llama 3 python tag call is parsed and hidden`() {
        val text = "Checking.\n<|python_tag|>{\"name\": \"get_weather\", \"parameters\": {\"city\": \"Seoul\"}}"
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.LLAMA3_JSON)
        assertTrue(parsed is ToolCallParse.Call)
        parsed as ToolCallParse.Call
        assertEquals("get_weather", parsed.call.name)
        assertEquals("Seoul", JSONObject(parsed.call.argumentsJson).optString("city"))
        assertEquals("Checking.", parsed.textBefore)
        assertEquals("Checking.", McpToolProtocol.visibleText(text, ToolCallTemplate.LLAMA3_JSON))
    }

    @Test
    fun `mistral array and function spellings are parsed`() {
        val array = """[TOOL_CALLS] [{"name": "get_weather", "arguments": {"city": "Busan"}}]"""
        val arrayParsed = McpToolProtocol.parse(array, tools, ToolCallTemplate.MISTRAL)
        assertTrue(arrayParsed is ToolCallParse.Call)
        arrayParsed as ToolCallParse.Call
        assertEquals("Busan", JSONObject(arrayParsed.call.argumentsJson).optString("city"))

        val fn = """[TOOL_CALLS]get_weather({"city": "Incheon"})"""
        val fnParsed = McpToolProtocol.parse(fn, tools, ToolCallTemplate.MISTRAL)
        assertTrue(fnParsed is ToolCallParse.Call)
        fnParsed as ToolCallParse.Call
        assertEquals("Incheon", JSONObject(fnParsed.call.argumentsJson).optString("city"))
        assertEquals("", McpToolProtocol.visibleText(fn, ToolCallTemplate.MISTRAL))
    }

    @Test
    fun `plain json is parsed only when it was selected`() {
        val text = """Here you go: {"name": "add", "arguments": {}}"""
        assertTrue(McpToolProtocol.parse(text, tools, ToolCallTemplate.HERMES) is ToolCallParse.None)

        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.PLAIN_JSON)
        assertTrue(parsed is ToolCallParse.Call)
        assertEquals("add", (parsed as ToolCallParse.Call).call.name)

        // JSON written as content is not a call: no tool name, so nothing happens.
        assertTrue(McpToolProtocol.parse("""{"temperature": 0.7}""", tools, ToolCallTemplate.PLAIN_JSON) is ToolCallParse.None)
        // A claimed name that the server does not offer is reported for correction.
        assertTrue(McpToolProtocol.parse("""{"name": "unknown_tool"}""", tools, ToolCallTemplate.PLAIN_JSON) is ToolCallParse.Invalid)
    }

    @Test
    fun `a model that drifts to another dialect is still understood`() {
        val hermes = """<tool_call>{"name": "add", "arguments": {"a": 1}}</tool_call>"""
        val parsed = McpToolProtocol.parse(hermes, tools, ToolCallTemplate.MISTRAL)
        assertTrue(parsed is ToolCallParse.Call)
        assertEquals("add", (parsed as ToolCallParse.Call).call.name)
        // The drifted block is still hidden from the user.
        assertEquals("", McpToolProtocol.visibleText(hermes, ToolCallTemplate.MISTRAL))
    }

    @Test
    fun `disabled tools never parse, prompt or hide anything`() {
        val hermes = """<tool_call>{"name": "add", "arguments": {}}</tool_call>"""
        assertTrue(McpToolProtocol.parse(hermes, tools, ToolCallTemplate.NONE) is ToolCallParse.None)
        assertEquals(hermes, McpToolProtocol.visibleText(hermes, ToolCallTemplate.NONE))
    }

    @Test
    fun `a marker inside a code fence is documentation`() {
        val text = "Example:\n```\n[TOOL_CALLS] [{\"name\": \"add\"}]\n```"
        assertTrue(McpToolProtocol.parse(text, tools, ToolCallTemplate.MISTRAL) is ToolCallParse.None)
    }

    @Test
    fun `truncated dialect blocks are rejected, not guessed`() {
        val llama = McpToolProtocol.parse("<|python_tag|>{\"name\": \"add\"", tools, ToolCallTemplate.LLAMA3_JSON)
        assertTrue(llama is ToolCallParse.Invalid)
        val mistral = McpToolProtocol.parse("[TOOL_CALLS] [{\"name\": \"add\"}", tools, ToolCallTemplate.MISTRAL)
        assertTrue(mistral is ToolCallParse.Invalid)
    }

    @Test
    fun `call echoes round trip through their own dialect`() {
        for (template in listOf(
            ToolCallTemplate.HERMES,
            ToolCallTemplate.MINICPM5,
            ToolCallTemplate.LLAMA3_JSON,
            ToolCallTemplate.MISTRAL,
            ToolCallTemplate.PLAIN_JSON
        )) {
            val echo = McpToolProtocol.formatCallEcho("get_weather", """{"city": "Seoul"}""", template)
            val parsed = McpToolProtocol.parse(echo, tools, template)
            assertTrue("echo for $template was not parsed: $echo", parsed is ToolCallParse.Call)
            parsed as ToolCallParse.Call
            assertEquals("get_weather", parsed.call.name)
            assertEquals("Seoul", JSONObject(parsed.call.argumentsJson).optString("city"))
        }
        assertEquals("", McpToolProtocol.formatCallEcho("add", "{}", ToolCallTemplate.NONE))
    }

    @Test
    fun `minicpm5 xml call is parsed with parameters and hidden`() {
        val text = "Checking.\n<function name=\"get_weather\"><param name=\"city\">Seoul</param></function>"
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.MINICPM5)
        assertTrue(parsed is ToolCallParse.Call)
        parsed as ToolCallParse.Call
        assertEquals("get_weather", parsed.call.name)
        assertEquals("Seoul", JSONObject(parsed.call.argumentsJson).optString("city"))
        assertEquals("Checking.", parsed.textBefore)
        assertEquals("Checking.", McpToolProtocol.visibleText(text, ToolCallTemplate.MINICPM5))
    }

    @Test
    fun `minicpm5 cdata values survive and the echo round trips`() {
        val text = "<function name=\"get_weather\"><param name=\"city\"><![CDATA[Seoul <b>]]></param></function>"
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.MINICPM5) as ToolCallParse.Call
        assertEquals("Seoul <b>", JSONObject(parsed.call.argumentsJson).optString("city"))

        val echo = McpToolProtocol.formatCallEcho("get_weather", """{"city":"New\nYork"}""", ToolCallTemplate.MINICPM5)
        assertTrue(echo.contains("<param name=\"city\"><![CDATA[New\nYork]]></param>"))
        val echoed = McpToolProtocol.parse(echo, tools, ToolCallTemplate.MINICPM5)
        assertTrue(echoed is ToolCallParse.Call)
        assertEquals("New\nYork", JSONObject((echoed as ToolCallParse.Call).call.argumentsJson).optString("city"))
    }

    @Test
    fun `minicpm5 rejects unknown tools, truncated blocks and fenced examples`() {
        assertTrue(
            McpToolProtocol.parse("<function name=\"nope\"></function>", tools, ToolCallTemplate.MINICPM5)
                is ToolCallParse.Invalid
        )
        assertTrue(
            McpToolProtocol.parse("<function name=\"add\"><param name=\"a\">1</param>", tools, ToolCallTemplate.MINICPM5)
                is ToolCallParse.Invalid
        )
        val fenced = "Example:\n```\n<function name=\"add\"></function>\n```"
        assertTrue(McpToolProtocol.parse(fenced, tools, ToolCallTemplate.MINICPM5) is ToolCallParse.None)
    }

    @Test
    fun `auto behaves like hermes when no detection ran`() {
        val text = """<tool_call>{"name": "add", "arguments": {}}</tool_call>"""
        assertTrue(McpToolProtocol.parse(text, tools, ToolCallTemplate.AUTO) is ToolCallParse.Call)
        assertTrue(McpToolProtocol.buildToolsPrompt("S", tools, ToolCallTemplate.AUTO)!!.contains("<tool_call>"))
    }
}

private object ToolCallProtocolTags {
    const val LLAMA = "<|python_tag|>"
    const val MISTRAL = "[TOOL_CALLS]"
}
