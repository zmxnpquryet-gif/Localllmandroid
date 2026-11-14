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
            ToolCallTemplate.QWEN_XML,
            ToolCallTemplate.GEMMA4,
            ToolCallTemplate.MINICPM5,
            ToolCallTemplate.LLAMA3_JSON,
            ToolCallTemplate.MISTRAL,
            ToolCallTemplate.GLM4,
            ToolCallTemplate.DEEPSEEK,
            ToolCallTemplate.PHI4,
            ToolCallTemplate.COHERE,
            ToolCallTemplate.XLAM,
            ToolCallTemplate.REACT,
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
            ToolCallTemplate.QWEN_XML,
            ToolCallTemplate.GEMMA4,
            ToolCallTemplate.MINICPM5,
            ToolCallTemplate.LLAMA3_JSON,
            ToolCallTemplate.MISTRAL,
            ToolCallTemplate.GLM4,
            ToolCallTemplate.DEEPSEEK,
            ToolCallTemplate.PHI4,
            ToolCallTemplate.COHERE,
            ToolCallTemplate.XLAM,
            ToolCallTemplate.REACT,
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
    fun `qwen35 and lfm3 xml calls parse with or without outer tool_call tags`() {
        val wrapped = "Let me check.\n<tool_call>\n<function=get_weather>\n<parameter=city>\nSeoul\n</parameter>\n</function>\n</tool_call>"
        val parsedWrapped = McpToolProtocol.parse(wrapped, tools, ToolCallTemplate.QWEN_XML) as ToolCallParse.Call
        assertEquals("get_weather", parsedWrapped.call.name)
        assertEquals("Seoul", JSONObject(parsedWrapped.call.argumentsJson).optString("city"))
        assertEquals("Let me check.", parsedWrapped.textBefore)

        val unwrapped = "Checking.\n<function=get_weather>\n<parameter=city>\nBusan\n</parameter>\n</function>"
        val parsedUnwrapped = McpToolProtocol.parse(unwrapped, tools, ToolCallTemplate.QWEN_XML) as ToolCallParse.Call
        assertEquals("get_weather", parsedUnwrapped.call.name)
        assertEquals("Busan", JSONObject(parsedUnwrapped.call.argumentsJson).optString("city"))
        assertEquals("Checking.", McpToolProtocol.visibleText(unwrapped, ToolCallTemplate.QWEN_XML))
    }

    @Test
    fun `gemma4 and lfm pythonic tool calls are parsed and hidden`() {
        val gemma = "One moment.<|tool_call>call:get_weather{city:<|\"|>Seoul<|\"|>}<tool_call|>"
        val parsedGemma = McpToolProtocol.parse(gemma, tools, ToolCallTemplate.GEMMA4) as ToolCallParse.Call
        assertEquals("get_weather", parsedGemma.call.name)
        assertEquals("Seoul", JSONObject(parsedGemma.call.argumentsJson).optString("city"))
        assertEquals("One moment.", McpToolProtocol.visibleText(gemma, ToolCallTemplate.GEMMA4))

        val lfmPy = "Sure.<|tool_call_start|>[get_weather(city=\"Incheon\")]<|tool_call_end|>"
        val parsedPy = McpToolProtocol.parse(lfmPy, tools, ToolCallTemplate.QWEN_XML) as ToolCallParse.Call
        assertEquals("get_weather", parsedPy.call.name)
        assertEquals("Incheon", JSONObject(parsedPy.call.argumentsJson).optString("city"))
        assertEquals("Sure.", McpToolProtocol.visibleText(lfmPy, ToolCallTemplate.QWEN_XML))
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
    fun `glm4 reply-tag call is parsed and hidden`() {
        val text = "Checking.\n" + McpToolProtocol.OPEN +
            """{"name": "get_weather", "arguments": {"city": "Seoul"}}""" + McpToolProtocol.GLM4_CLOSE
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.GLM4)
        assertTrue(parsed is ToolCallParse.Call)
        assertEquals(
            "Seoul",
            JSONObject((parsed as ToolCallParse.Call).call.argumentsJson).optString("city")
        )
        assertEquals("Checking.", McpToolProtocol.visibleText(text, ToolCallTemplate.GLM4))

        // GLM-4 also accepts the plain Hermes-style closer.
        val hermesClosed = McpToolProtocol.OPEN +
            """{"name": "get_weather", "arguments": {"city": "Busan"}}""" + McpToolProtocol.CLOSE
        assertTrue(McpToolProtocol.parse(hermesClosed, tools, ToolCallTemplate.GLM4) is ToolCallParse.Call)

        // A Hermes-selected model that emits the reply-tag form still completes.
        assertTrue(McpToolProtocol.parse(text, tools, ToolCallTemplate.HERMES) is ToolCallParse.Call)
    }

    @Test
    fun `deepseek wrapped call is parsed and hidden`() {
        val text = "Sure.\n" + McpToolProtocol.DEEPSEEK_CALLS_BEGIN + McpToolProtocol.DEEPSEEK_CALL_BEGIN +
            McpToolProtocol.DEEPSEEK_NAME + "get_weather" + McpToolProtocol.DEEPSEEK_SEP +
            """{"city": "Seoul"}""" + McpToolProtocol.DEEPSEEK_CALL_END + McpToolProtocol.DEEPSEEK_CALLS_END
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.DEEPSEEK)
        assertTrue(parsed is ToolCallParse.Call)
        assertEquals(
            "Seoul",
            JSONObject((parsed as ToolCallParse.Call).call.argumentsJson).optString("city")
        )
        assertEquals("Sure.", McpToolProtocol.visibleText(text, ToolCallTemplate.DEEPSEEK))

        // An unclosed block is rejected so no partial operation runs.
        assertTrue(
            McpToolProtocol.parse(
                McpToolProtocol.DEEPSEEK_CALL_BEGIN + McpToolProtocol.DEEPSEEK_NAME + "add",
                tools,
                ToolCallTemplate.DEEPSEEK
            ) is ToolCallParse.Invalid
        )
    }

    @Test
    fun `phi4 name-and-json call is parsed and hidden`() {
        val text = "Checking.\n" + McpToolProtocol.OPEN + "get_weather>" + """{"city": "Seoul"}"""
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.PHI4)
        assertTrue(parsed is ToolCallParse.Call)
        assertEquals(
            "Seoul",
            JSONObject((parsed as ToolCallParse.Call).call.argumentsJson).optString("city")
        )
        assertEquals("Checking.", McpToolProtocol.visibleText(text, ToolCallTemplate.PHI4))

        // A Phi-4 body inside a Hermes block still decodes (drift recovery).
        val drifted = McpToolProtocol.OPEN + "get_weather>" +
            """{"city": "Busan"}""" + McpToolProtocol.CLOSE
        assertTrue(McpToolProtocol.parse(drifted, tools, ToolCallTemplate.HERMES) is ToolCallParse.Call)
    }

    @Test
    fun `cohere and xlam json calls are parsed and hidden`() {
        val cohere = """Here: {"tool_calls": [{"name": "get_weather", "arguments": {"city": "Seoul"}}]}"""
        val parsedCohere = McpToolProtocol.parse(cohere, tools, ToolCallTemplate.COHERE) as ToolCallParse.Call
        assertEquals("Seoul", JSONObject(parsedCohere.call.argumentsJson).optString("city"))
        assertEquals("Here:", McpToolProtocol.visibleText(cohere, ToolCallTemplate.COHERE))

        val xlam = """[{"name": "get_weather", "arguments": {"city": "Busan"}}]"""
        val parsedXlam = McpToolProtocol.parse(xlam, tools, ToolCallTemplate.XLAM) as ToolCallParse.Call
        assertEquals("Busan", JSONObject(parsedXlam.call.argumentsJson).optString("city"))
        assertEquals("", McpToolProtocol.visibleText(xlam, ToolCallTemplate.XLAM))

        // Plain JSON without the wrapper shape is never a call in these dialects.
        assertTrue(
            McpToolProtocol.parse("""{"name": "add", "arguments": {}}""", tools, ToolCallTemplate.COHERE)
                is ToolCallParse.None
        )
        assertTrue(
            McpToolProtocol.parse("""{"name": "add", "arguments": {}}""", tools, ToolCallTemplate.XLAM)
                is ToolCallParse.None
        )
    }

    @Test
    fun `react action call is parsed and hidden`() {
        val text = "Thought: check weather\nAction: get_weather\nAction Input: {\"city\": \"Seoul\"}"
        val parsed = McpToolProtocol.parse(text, tools, ToolCallTemplate.REACT) as ToolCallParse.Call
        assertEquals("get_weather", parsed.call.name)
        assertEquals("Seoul", JSONObject(parsed.call.argumentsJson).optString("city"))
        assertEquals("Thought: check weather", McpToolProtocol.visibleText(text, ToolCallTemplate.REACT))

        // Documented ReAct examples inside a code fence are not calls.
        val fenced = "Example:\n```\nAction: get_weather\nAction Input: {\"city\": \"Busan\"}\n```"
        assertTrue(McpToolProtocol.parse(fenced, tools, ToolCallTemplate.REACT) is ToolCallParse.None)
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
