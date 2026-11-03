package com.localllm.android.audio

import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OmniAudioConversationTest {

    private val format = AudioFormat(16_000, 1, 16)

    private val model = NativeOmniModel(
        id = "omni-test",
        displayName = "Omni Test",
        family = OmniModelFamily.CUSTOM,
        protocol = "duplex-pcm-v1",
        capabilities = NativeOmniCapabilities(
            nativeAudioInput = true,
            nativeAudioOutput = true,
            streamingInput = true,
            streamingOutput = true,
            supportsInterruption = true,
            synchronizedTextOutput = true,
            modelProtocols = setOf("duplex-pcm-v1")
        )
    )

    @Test
    fun `incomplete native audio capabilities are reported honestly`() {
        val textOnly = model.copy(
            id = "text-only",
            capabilities = model.capabilities.copy(
                nativeAudioInput = false,
                nativeAudioOutput = false,
                streamingOutput = false
            )
        )
        val check = OmniCapabilityDetector.detect(textOnly) as OmniCapabilityCheck.Unsupported
        assertTrue(check.missing.contains("native audio input"))
        assertTrue(check.missing.contains("native audio output"))
        assertTrue(OmniCapabilityDetector.detect(model) is OmniCapabilityCheck.Supported)
    }

    @Test
    fun `duplex session orders text and audio and closes idempotently`() = runTest {
        val backend = FakeBackend()
        val conversation = OmniAudioConversation(
            backend = backend,
            model = model,
            config = OmniSessionConfig(format, format),
            scope = backgroundScope
        )
        conversation.open()
        assertEquals(OmniConversationState.LISTENING, conversation.state.value)

        conversation.sendAudio(NativeAudioFrame(format, byteArrayOf(1, 2), 1))
        conversation.finishInput()
        val emitted = async { conversation.events.take(3).toList() }
        testScheduler.runCurrent()
        backend.session.publishOutputs()
        testScheduler.advanceUntilIdle()

        val events = emitted.await()
        assertTrue(events[0] is OmniAudioEvent.TextDelta)
        assertTrue(events[1] is OmniAudioEvent.AudioOutput)
        assertTrue(events[2] is OmniAudioEvent.TurnFinished)
        assertTrue(events.zipWithNext().all { (a, b) -> a.sequence < b.sequence })

        conversation.interrupt()
        assertEquals(OmniConversationState.INTERRUPTED, conversation.state.value)
        conversation.close()
        conversation.close()
        assertTrue(backend.session.closed)
        assertEquals(OmniConversationState.CLOSED, conversation.state.value)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized audio frame is rejected before backend call`() = runTest {
        val backend = FakeBackend()
        val conversation = OmniAudioConversation(
            backend, model, OmniSessionConfig(format, format, maxFrameBytes = 2), backgroundScope
        )
        conversation.open()
        conversation.sendAudio(NativeAudioFrame(format, ByteArray(3), 1))
    }

    private class FakeBackend : NativeOmniModelBackend {
        override val backendId = "fake-native"
        val session = FakeSession()
        override suspend fun open(model: NativeOmniModel, config: OmniSessionConfig): NativeOmniBackendSession = session
    }

    private class FakeSession : NativeOmniBackendSession {
        private val output = MutableSharedFlow<OmniAudioEvent>(extraBufferCapacity = 8)
        override val events: Flow<OmniAudioEvent> = output
        var closed = false
        var interrupted = false
        val input = mutableListOf<NativeAudioFrame>()

        override suspend fun sendAudio(frame: NativeAudioFrame) { input += frame }
        override suspend fun finishInput() = Unit
        override suspend fun interrupt() { interrupted = true }
        override suspend fun close() { closed = true }

        suspend fun publishOutputs() {
            output.emit(OmniAudioEvent.TextDelta(1, "hello", 1))
            output.emit(OmniAudioEvent.AudioOutput(1, NativeAudioFrame(AudioFormat(16_000, 1, 16), byteArrayOf(3), 2), 2))
            output.emit(OmniAudioEvent.TurnFinished(1, 3))
        }
    }
}
