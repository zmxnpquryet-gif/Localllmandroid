package com.localllm.android.engine

/**
 * Streaming parser for reasoning channels across model families:
 *  - `<think>...</think>` (DeepSeek, Qwen 3/3.5, LFM3, MiniCPM5)
 *  - `<|channel>thought...<channel|>` (Gemma 4)
 *
 * Also rescues tool-call blocks (`<tool_call>`, `<|tool_call>`, `<|tool_call_start|>`,
 * `<|python_tag|>`, `[TOOL_CALLS]`, `<function`) if a small model starts emitting a
 * tool call without first closing an open reasoning block.
 */
class ReasoningStreamParser(startInReasoning: Boolean = false) {

    companion object {
        private val OPEN_TAGS = listOf("<think>", "<|channel>thought\n", "<|channel>thought")
        private val CLOSE_TAGS = listOf("</think>", "<channel|>")
        private val TOOL_RESCUE_MARKERS = listOf(
            "<tool_call>",
            "<|tool_call>",
            "<|tool_call_start|>",
            "<|python_tag|>",
            "[TOOL_CALLS]",
            "<function=",
            "<function name=\""
        )
    }

    var isInReasoningMode: Boolean = startInReasoning
        private set

    val reasoningBuffer = StringBuilder()
    val contentBuffer = StringBuilder()

    private val pendingBuffer = StringBuilder()

    @Synchronized
    fun processChunk(chunk: String): Boolean {
        if (chunk.isEmpty()) return isInReasoningMode

        pendingBuffer.append(chunk)

        while (pendingBuffer.isNotEmpty()) {
            val current = pendingBuffer.toString()

            if (!isInReasoningMode && contentBuffer.isEmpty() && reasoningBuffer.isEmpty()) {
                val firstNonWhitespace = current.indexOfFirst { !it.isWhitespace() }
                if (firstNonWhitespace >= 0) {
                    val strayClose = CLOSE_TAGS.firstOrNull { current.startsWith(it, firstNonWhitespace) }
                    if (strayClose != null) {
                        pendingBuffer.delete(0, firstNonWhitespace + strayClose.length)
                        continue
                    }
                }
            }

            if (!isInReasoningMode) {
                // Wait if "<|channel>thought" matched at the very end without trailing '\n',
                // so "<|channel>thought\n" can consume the newline cleanly on the next chunk.
                if (current.endsWith("<|channel>thought")) {
                    val safeLen = current.length - "<|channel>thought".length
                    if (safeLen > 0) {
                        contentBuffer.append(current.substring(0, safeLen))
                        pendingBuffer.delete(0, safeLen)
                    }
                    break
                }

                val openMatch = findEarliestMatch(current, OPEN_TAGS)
                if (openMatch != null) {
                    val (openIdx, tag) = openMatch
                    if (openIdx > 0) {
                        contentBuffer.append(current.substring(0, openIdx))
                    }
                    pendingBuffer.delete(0, openIdx + tag.length)
                    isInReasoningMode = true
                } else {
                    val holdLen = maxPotentialPrefixLength(current, OPEN_TAGS)
                    if (holdLen > 0) {
                        val safeLen = current.length - holdLen
                        if (safeLen > 0) {
                            contentBuffer.append(current.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break
                    } else {
                        contentBuffer.append(current)
                        pendingBuffer.clear()
                    }
                }
            } else {
                val closeMatch = findEarliestMatch(current, CLOSE_TAGS)
                val rescueMatch = findEarliestMatch(current, TOOL_RESCUE_MARKERS)

                if (closeMatch != null && (rescueMatch == null || closeMatch.first <= rescueMatch.first)) {
                    val (closeIdx, tag) = closeMatch
                    if (closeIdx > 0) {
                        reasoningBuffer.append(current.substring(0, closeIdx))
                    }
                    pendingBuffer.delete(0, closeIdx + tag.length)
                    isInReasoningMode = false
                } else if (rescueMatch != null) {
                    // Model jumped straight from thinking into a tool call without emitting
                    // </think> or <channel|>. Flush preceding reasoning and switch to content
                    // WITHOUT consuming the tool marker itself.
                    val toolIdx = rescueMatch.first
                    if (toolIdx > 0) {
                        reasoningBuffer.append(current.substring(0, toolIdx))
                        pendingBuffer.delete(0, toolIdx)
                    }
                    isInReasoningMode = false
                } else {
                    val holdLen = maxOf(
                        maxPotentialPrefixLength(current, CLOSE_TAGS),
                        maxPotentialPrefixLength(current, TOOL_RESCUE_MARKERS)
                    )
                    if (holdLen > 0) {
                        val safeLen = current.length - holdLen
                        if (safeLen > 0) {
                            reasoningBuffer.append(current.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break
                    } else {
                        reasoningBuffer.append(current)
                        pendingBuffer.clear()
                    }
                }
            }
        }

        return isInReasoningMode
    }

    @Synchronized
    fun finish() {
        if (pendingBuffer.isNotEmpty()) {
            val remaining = pendingBuffer.toString()
            if (!isInReasoningMode && remaining == "<|channel>thought") {
                isInReasoningMode = true
            } else if (isInReasoningMode) {
                reasoningBuffer.append(remaining)
            } else {
                contentBuffer.append(remaining)
            }
            pendingBuffer.clear()
        }
    }

    private fun findEarliestMatch(text: String, tags: List<String>): Pair<Int, String>? {
        var bestIdx = Int.MAX_VALUE
        var bestTag: String? = null
        for (tag in tags) {
            val idx = text.indexOf(tag)
            if (idx >= 0 && (idx < bestIdx || (idx == bestIdx && tag.length > (bestTag?.length ?: 0)))) {
                bestIdx = idx
                bestTag = tag
            }
        }
        return bestTag?.let { bestIdx to it }
    }

    private fun maxPotentialPrefixLength(text: String, tags: List<String>): Int {
        var best = 0
        for (tag in tags) {
            val maxCheckLen = minOf(text.length, tag.length - 1)
            for (len in maxCheckLen downTo 1) {
                if (len <= best) break
                val suffix = text.substring(text.length - len)
                if (tag.startsWith(suffix)) {
                    best = len
                    break
                }
            }
        }
        return best
    }
}
