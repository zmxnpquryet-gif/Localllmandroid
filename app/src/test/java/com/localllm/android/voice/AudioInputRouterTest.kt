package com.localllm.android.voice

import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputRouterTest {

    private fun model(
        audioInput: Boolean,
        runtime: ModelRuntimeType = ModelRuntimeType.LITE_RT
    ) = LlmModel(
        id = "m",
        name = "m",
        repoId = "r",
        fileName = "m.litertlm",
        sizeBytes = 1L,
        runtimeType = runtime,
        supportsAudioInput = audioInput
    )

    @Test
    fun `omni model on litert takes the audio directly`() {
        val decision = AudioInputRouter.route(model(audioInput = true))
        assertEquals(VoiceAudioRoute.DIRECT_AUDIO, decision.route)
        assertEquals(VoiceAudioReason.MODEL_ACCEPTS_AUDIO, decision.reason)
    }

    @Test
    fun `omni model on llama cpp still needs a recognizer`() {
        val decision = AudioInputRouter.route(
            model(audioInput = true, runtime = ModelRuntimeType.LLAMA_CPP)
        )
        assertEquals(VoiceAudioRoute.STT, decision.route)
        assertEquals(VoiceAudioReason.RUNTIME_CANNOT_DECODE_AUDIO, decision.reason)
    }

    @Test
    fun `text model always goes through stt`() {
        for (runtime in ModelRuntimeType.entries) {
            val decision = AudioInputRouter.route(model(audioInput = false, runtime = runtime))
            assertEquals(VoiceAudioRoute.STT, decision.route)
            assertEquals(VoiceAudioReason.MODEL_NEEDS_STT, decision.reason)
        }
    }

    @Test
    fun `no model falls back to stt`() {
        val decision = AudioInputRouter.route(null)
        assertEquals(VoiceAudioRoute.STT, decision.route)
        assertEquals(VoiceAudioReason.MODEL_NEEDS_STT, decision.reason)
    }

    @Test
    fun `only litert advertises direct audio`() {
        assertTrue(AudioInputRouter.supportsDirectAudio(ModelRuntimeType.LITE_RT))
        assertFalse(AudioInputRouter.supportsDirectAudio(ModelRuntimeType.LLAMA_CPP))
    }
}
