package com.localllm.android

import com.localllm.android.engine.ReasoningStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningStreamParserTest {

    @Test
    fun `test plain response without think tags`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("Hello, ")
        parser.processChunk("how can I help ")
        parser.processChunk("you today?")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("", parser.reasoningBuffer.toString())
        assertEquals("Hello, how can I help you today?", parser.contentBuffer.toString())
    }

    @Test
    fun `test standard reasoning with intact think tags`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>")
        assertTrue(parser.isInReasoningMode)
        parser.processChunk("Let me think about this step by step.")
        parser.processChunk("</think>")
        assertFalse(parser.isInReasoningMode)
        parser.processChunk("The answer is 42.")
        parser.finish()

        assertEquals("Let me think about this step by step.", parser.reasoningBuffer.toString())
        assertEquals("The answer is 42.", parser.contentBuffer.toString())
    }

    @Test
    fun `test split open think tag across chunks`() {
        val parser = ReasoningStreamParser()
        // Split "<think>" across two chunks: "<th" and "ink>"
        parser.processChunk("Prefix text. <th")
        assertFalse(parser.isInReasoningMode) // Still pending
        parser.processChunk("ink>Now in thinking mode.")
        assertTrue(parser.isInReasoningMode)
        parser.processChunk("</think>Final answer.")
        parser.finish()

        assertEquals("Now in thinking mode.", parser.reasoningBuffer.toString())
        assertEquals("Prefix text. Final answer.", parser.contentBuffer.toString())
    }

    @Test
    fun `test split close think tag across chunks`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>Reasoning content...</th")
        assertTrue(parser.isInReasoningMode)
        parser.processChunk("ink>Done thinking! Answer here.")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("Reasoning content...", parser.reasoningBuffer.toString())
        assertEquals("Done thinking! Answer here.", parser.contentBuffer.toString())
    }

    @Test
    fun `test single character streaming with split tags`() {
        val parser = ReasoningStreamParser()
        val fullText = "<think>1+1=2</think>Result: 2"
        for (char in fullText) {
            parser.processChunk(char.toString())
        }
        parser.finish()

        assertEquals("1+1=2", parser.reasoningBuffer.toString())
        assertEquals("Result: 2", parser.contentBuffer.toString())
    }
}
