package com.localllm.android.engine

import org.json.JSONArray
import org.json.JSONObject

/**
 * Encodes and decodes Gemma 4 (`<|tool_call>call:name{...}<tool_call|>`) and
 * Liquid AI LFM2/3 Pythonic (`<|tool_call_start|>[name(...)]<|tool_call_end|>`)
 * tool call blocks into standard [McpToolCall] JSON argument objects.
 */
internal object Gemma4ToolCodec {

    const val GEMMA4_OPEN = "<|tool_call>"
    const val GEMMA4_CLOSE = "<tool_call|>"
    const val GEMMA4_QUOTE = "<|\"|>"

    const val LFM_PY_OPEN = "<|tool_call_start|>"
    const val LFM_PY_CLOSE = "<|tool_call_end|>"

    private val IDENT_REGEX = Regex("^[A-Za-z0-9_.\\-]+$")

    fun formatGemma4Declarations(tools: List<McpTool>, maxChars: Int): String {
        val sb = StringBuilder()
        for (tool in tools) {
            val desc = tool.description.replace('\n', ' ').replace(GEMMA4_QUOTE, "\"")
            val schema = if (tool.parameters.length > McpClient.MAX_TOOL_SCHEMA_CHARS) {
                tool.parameters.take(McpClient.MAX_TOOL_SCHEMA_CHARS)
            } else {
                tool.parameters
            }
            val block = "\n<|tool>declaration:${tool.name}{description:${GEMMA4_QUOTE}${desc}${GEMMA4_QUOTE},parameters:$schema}<tool|>"
            if (sb.length + block.length > maxChars) break
            sb.append(block)
        }
        return sb.toString()
    }

    fun encodeGemma4Call(call: McpToolCall): String {
        val obj = runCatching { JSONObject(call.argumentsJson.ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        val sb = StringBuilder(GEMMA4_OPEN).append("call:").append(call.name).append('{')
        val keys = obj.keys().asSequence().sorted().toList()
        keys.forEachIndexed { idx, key ->
            if (idx > 0) sb.append(',')
            sb.append(key).append(':').append(encodeGemma4Value(obj.opt(key)))
        }
        sb.append('}').append(GEMMA4_CLOSE)
        return sb.toString()
    }

    private fun encodeGemma4Value(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is Boolean, is Number -> value.toString()
        is JSONObject -> {
            val keys = value.keys().asSequence().sorted().toList()
            keys.joinToString(",", prefix = "{", postfix = "}") { k ->
                "$k:${encodeGemma4Value(value.opt(k))}"
            }
        }
        is JSONArray -> (0 until value.length()).joinToString(",", prefix = "[", postfix = "]") { i ->
            encodeGemma4Value(value.opt(i))
        }
        else -> "$GEMMA4_QUOTE$value$GEMMA4_QUOTE"
    }

    /**
     * Decodes the body inside `<|tool_call>...<tool_call|>` (e.g. `call:fn{city:<|"|>Seoul<|"|>}`).
     * Returns `(toolName, argumentsJsonObject)` or an error message.
     */
    fun decodeGemma4Body(rawBody: String): Result<Pair<String, JSONObject>> {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{")) {
            return runCatching {
                val obj = JSONObject(trimmed)
                val fn = obj.optJSONObject("function")
                val name = (fn?.optString("name") ?: obj.optString("name")).trim()
                val args = (fn?.optJSONObject("arguments") ?: obj.optJSONObject("arguments") ?: JSONObject())
                name to args
            }
        }
        val withoutPrefix = trimmed.removePrefix("call:").trim()
        val braceStart = withoutPrefix.indexOf('{')
        val braceEnd = withoutPrefix.lastIndexOf('}')
        if (braceStart <= 0 || braceEnd < braceStart) {
            return Result.failure(IllegalArgumentException("Malformed Gemma 4 tool call syntax."))
        }
        val name = withoutPrefix.substring(0, braceStart).trim()
        if (!IDENT_REGEX.matches(name)) {
            return Result.failure(IllegalArgumentException("Invalid Gemma 4 tool name: $name"))
        }
        val inner = withoutPrefix.substring(braceStart, braceEnd + 1)
        val normalizedJson = gemma4StructToJson(inner)
        val args = runCatching { JSONObject(normalizedJson) }.getOrElse {
            return Result.failure(IllegalArgumentException("Gemma 4 arguments for $name could not be parsed as JSON."))
        }
        return Result.success(name to args)
    }

    /**
     * Converts Gemma 4's `{key:<|"|>val<|"|>,n:3}` object syntax into strict RFC 8259 JSON.
     */
    internal fun gemma4StructToJson(input: String): String {
        val step1 = StringBuilder()
        var i = 0
        while (i < input.length) {
            if (input.startsWith(GEMMA4_QUOTE, i)) {
                val start = i + GEMMA4_QUOTE.length
                val end = input.indexOf(GEMMA4_QUOTE, start)
                if (end < 0) {
                    step1.append(JSONObject.quote(input.substring(start)))
                    break
                }
                step1.append(JSONObject.quote(input.substring(start, end)))
                i = end + GEMMA4_QUOTE.length
            } else {
                step1.append(input[i])
                i++
            }
        }

        // Quote unquoted object keys: `{city:"Seoul", nested:{a:1}}` -> `{"city":"Seoul", "nested":{"a":1}}`
        val src = step1.toString()
        val out = StringBuilder(src.length + 16)
        var inString = false
        var escaped = false
        var idx = 0
        while (idx < src.length) {
            val c = src[idx]
            if (inString) {
                out.append(c)
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == '"' -> inString = false
                }
                idx++
                continue
            }
            if (c == '"') {
                inString = true
                out.append(c)
                idx++
                continue
            }
            if (c == '{' || c == ',') {
                out.append(c)
                idx++
                while (idx < src.length && src[idx].isWhitespace()) {
                    out.append(src[idx])
                    idx++
                }
                if (idx < src.length && (src[idx].isLetter() || src[idx] == '_')) {
                    val keyStart = idx
                    while (idx < src.length && (src[idx].isLetterOrDigit() || src[idx] == '_' || src[idx] == '-' || src[idx] == '.')) {
                        idx++
                    }
                    val candidateKey = src.substring(keyStart, idx)
                    var lookahead = idx
                    while (lookahead < src.length && src[lookahead].isWhitespace()) lookahead++
                    if (lookahead < src.length && src[lookahead] == ':') {
                        out.append(JSONObject.quote(candidateKey))
                    } else {
                        out.append(candidateKey)
                    }
                    continue
                }
                continue
            }
            out.append(c)
            idx++
        }
        return out.toString()
    }

    /**
     * Decodes Pythonic calls inside `<|tool_call_start|>[fn(a=1, b="x")]<|tool_call_end|>`.
     */
    fun decodePythonicCalls(rawBody: String): Result<List<Pair<String, JSONObject>>> {
        val trimmed = rawBody.trim()
        if (trimmed.startsWith("{")) {
            return decodeGemma4Body(trimmed).map { listOf(it) }
        }
        val unwrapped = if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            trimmed.substring(1, trimmed.length - 1).trim()
        } else {
            trimmed
        }
        if (unwrapped.isEmpty()) return Result.success(emptyList())

        val callStrings = splitTopLevelCommas(unwrapped)
        val out = mutableListOf<Pair<String, JSONObject>>()
        for (callStr in callStrings) {
            val openParen = callStr.indexOf('(')
            val closeParen = callStr.lastIndexOf(')')
            if (openParen <= 0 || closeParen < openParen) {
                return Result.failure(IllegalArgumentException("Malformed Pythonic tool call: $callStr"))
            }
            val name = callStr.substring(0, openParen).trim()
            if (!IDENT_REGEX.matches(name)) {
                return Result.failure(IllegalArgumentException("Invalid tool name: $name"))
            }
            val argsStr = callStr.substring(openParen + 1, closeParen).trim()
            val argsObj = JSONObject()
            if (argsStr.isNotEmpty()) {
                for (pair in splitTopLevelCommas(argsStr)) {
                    val eq = pair.indexOf('=')
                    if (eq <= 0) {
                        return Result.failure(IllegalArgumentException("Expected keyword argument in $name"))
                    }
                    val argName = pair.substring(0, eq).trim()
                    val rawVal = pair.substring(eq + 1).trim()
                    argsObj.put(argName, parsePythonLiteral(rawVal))
                }
            }
            out += name to argsObj
        }
        return Result.success(out)
    }

    private fun parsePythonLiteral(raw: String): Any {
        return when {
            raw == "True" || raw == "true" -> true
            raw == "False" || raw == "false" -> false
            raw == "None" || raw == "null" -> JSONObject.NULL
            raw.toLongOrNull() != null -> raw.toLong()
            raw.toDoubleOrNull() != null -> raw.toDouble()
            raw.startsWith("'") && raw.endsWith("'") && raw.length >= 2 ->
                raw.substring(1, raw.length - 1).replace("\\'", "'").replace("\\n", "\n").replace("\\t", "\t")
            else -> {
                val jsonNormalized = raw
                    .replace(Regex("\\bTrue\\b"), "true")
                    .replace(Regex("\\bFalse\\b"), "false")
                    .replace(Regex("\\bNone\\b"), "null")
                runCatching { JSONObject("{\"v\":$jsonNormalized}").opt("v") }.getOrNull()
                    ?: raw.removeSurrounding("\"")
            }
        }
    }

    private fun splitTopLevelCommas(text: String): List<String> {
        val result = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var depth = 0
        var start = 0
        for (i in text.indices) {
            val c = text[i]
            if (quote != null) {
                when {
                    escaped -> escaped = false
                    c == '\\' -> escaped = true
                    c == quote -> quote = null
                }
            } else {
                when (c) {
                    '"', '\'' -> quote = c
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> if (depth > 0) depth--
                    ',' -> if (depth == 0) {
                        result += text.substring(start, i).trim()
                        start = i + 1
                    }
                }
            }
        }
        val tail = text.substring(start).trim()
        if (tail.isNotEmpty()) result += tail
        return result
    }
}
