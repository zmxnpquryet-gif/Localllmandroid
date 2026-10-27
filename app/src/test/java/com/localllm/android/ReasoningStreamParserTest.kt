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

    // ------------------------------------------------------------------
    // Edge cases
    // ------------------------------------------------------------------

    @Test
    fun `unclosed think tag at end of stream flushes pending text as reasoning`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>")
        assertTrue(parser.isInReasoningMode)
        parser.processChunk("thinking, but the stream ends mid-s")
        // No close tag ever arrives; everything stays in the reasoning buffer.
        parser.finish()

        assertTrue(parser.isInReasoningMode)
        assertEquals("thinking, but the stream ends mid-s", parser.reasoningBuffer.toString())
        assertEquals("", parser.contentBuffer.toString())
    }

    @Test
    fun `unclosed think tag with a partial close tag pending flushes it as reasoning`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>reasoning</thi")
        assertTrue(parser.isInReasoningMode)
        parser.finish()

        assertEquals("reasoning</thi", parser.reasoningBuffer.toString())
        assertEquals("", parser.contentBuffer.toString())
    }

    @Test
    fun `partial lookalike tag with no think tag flushes as content`() {
        val parser = ReasoningStreamParser()
        // "</thi" is not a prefix of the OPEN tag "<think>", so it goes straight
        // to content instead of waiting for more chunks.
        parser.processChunk("</thi")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("</thi", parser.contentBuffer.toString())
        assertEquals("", parser.reasoningBuffer.toString())
    }

    @Test
    fun `lookalike text similar to think tag is emitted as content`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<thin ice>hello")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("<thin ice>hello", parser.contentBuffer.toString())
        assertEquals("", parser.reasoningBuffer.toString())
    }

    @Test
    fun `lookalike tag split across chunks is emitted as content`() {
        val parser = ReasoningStreamParser()
        // "<thi" is a prefix of "<think>" and gets held; "n ice>" breaks the match.
        parser.processChunk("wait <thi")
        parser.processChunk("n ice> ok")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("wait <thin ice> ok", parser.contentBuffer.toString())
        assertEquals("", parser.reasoningBuffer.toString())
    }

    @Test
    fun `consecutive think blocks are all routed`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>first</think>A<think>second</think>B")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("firstsecond", parser.reasoningBuffer.toString())
        assertEquals("AB", parser.contentBuffer.toString())
    }

    @Test
    fun `consecutive think blocks with no content between them`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>a</think><think>b</think>")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("ab", parser.reasoningBuffer.toString())
        assertEquals("", parser.contentBuffer.toString())
    }

    @Test
    fun `consecutive think blocks split across chunks`() {
        val parser = ReasoningStreamParser()
        parser.processChunk("<think>one")
        parser.processChunk("</think>mid<think>two</think>")
        parser.finish()

        assertFalse(parser.isInReasoningMode)
        assertEquals("onetwo", parser.reasoningBuffer.toString())
        assertEquals("mid", parser.contentBuffer.toString())
    }
}
