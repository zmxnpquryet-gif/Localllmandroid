package com.example.engine

/**
 * Robust streaming parser for reasoning models (e.g. DeepSeek-R1, Qwen-Thinking).
 * Correctly handles `<think>` and `</think>` tags even when tokens are split
 * across chunk boundaries (e.g. "<th" in chunk N, "ink>" in chunk N+1).
 */
class ReasoningStreamParser {

    companion object {
        private const val OPEN_TAG = "<think>"
        private const val CLOSE_TAG = "</think>"
    }

    var isInReasoningMode: Boolean = false
        private set

    val reasoningBuffer = StringBuilder()
    val contentBuffer = StringBuilder()

    private val pendingBuffer = StringBuilder()

    /**
     * Processes an incoming raw token chunk and updates the reasoning and content buffers.
     *
     * @param chunk The raw token chunk received from the model stream.
     * @return Pair of (isReasoningMode, deltaContentText)
     */
    @Synchronized
    fun processChunk(chunk: String): Boolean {
        if (chunk.isEmpty()) return isInReasoningMode

        pendingBuffer.append(chunk)

        while (pendingBuffer.isNotEmpty()) {
            if (!isInReasoningMode) {
                val openIdx = pendingBuffer.indexOf(OPEN_TAG)
                if (openIdx >= 0) {
                    // Everything before <think> belongs to content
                    if (openIdx > 0) {
                        contentBuffer.append(pendingBuffer.substring(0, openIdx))
                    }
                    pendingBuffer.delete(0, openIdx + OPEN_TAG.length)
                    isInReasoningMode = true
                } else {
                    // Check if pendingBuffer ends with a prefix of "<think>"
                    val potentialPrefixLen = findPotentialPrefixLength(pendingBuffer.toString(), OPEN_TAG)
                    if (potentialPrefixLen > 0) {
                        // Safe to emit everything before the potential prefix
                        val safeLen = pendingBuffer.length - potentialPrefixLen
                        if (safeLen > 0) {
                            contentBuffer.append(pendingBuffer.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break // Wait for next chunk to determine if it's really "<think>"
                    } else {
                        contentBuffer.append(pendingBuffer.toString())
                        pendingBuffer.clear()
                    }
                }
            } else {
                val closeIdx = pendingBuffer.indexOf(CLOSE_TAG)
                if (closeIdx >= 0) {
                    // Everything before </think> belongs to reasoning
                    if (closeIdx > 0) {
                        reasoningBuffer.append(pendingBuffer.substring(0, closeIdx))
                    }
                    pendingBuffer.delete(0, closeIdx + CLOSE_TAG.length)
                    isInReasoningMode = false
                } else {
                    // Check if pendingBuffer ends with a prefix of "</think>"
                    val potentialPrefixLen = findPotentialPrefixLength(pendingBuffer.toString(), CLOSE_TAG)
                    if (potentialPrefixLen > 0) {
                        val safeLen = pendingBuffer.length - potentialPrefixLen
                        if (safeLen > 0) {
                            reasoningBuffer.append(pendingBuffer.substring(0, safeLen))
                            pendingBuffer.delete(0, safeLen)
                        }
                        break // Wait for next chunk to determine if it's really "</think>"
                    } else {
                        reasoningBuffer.append(pendingBuffer.toString())
                        pendingBuffer.clear()
                    }
                }
            }
        }

        return isInReasoningMode
    }

    /**
     * Called when stream generation is complete to flush any buffered potential tag characters.
     */
    @Synchronized
    fun finish() {
        if (pendingBuffer.isNotEmpty()) {
            if (isInReasoningMode) {
                reasoningBuffer.append(pendingBuffer.toString())
            } else {
                contentBuffer.append(pendingBuffer.toString())
            }
            pendingBuffer.clear()
        }
    }

    /**
     * Checks if [text] ends with a non-empty prefix of [targetTag] (e.g. "<", "</", "</th").
     * Returns the length of the matching prefix, or 0 if none matches.
     */
    private fun findPotentialPrefixLength(text: String, targetTag: String): Int {
        val maxCheckLen = minOf(text.length, targetTag.length - 1)
        for (len in maxCheckLen downTo 1) {
            val suffix = text.substring(text.length - len)
            if (targetTag.startsWith(suffix)) {
                return len
            }
        }
        return 0
    }
}
