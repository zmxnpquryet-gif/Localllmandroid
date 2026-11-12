package com.localllm.android.voice

import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType

/** How a hands-free turn reaches the model. */
enum class VoiceAudioRoute {
    /** The recorded audio is sent to the model as content; no recognizer runs. */
    DIRECT_AUDIO,

    /** The audio is transcribed first and the model receives text. */
    STT
}

enum class VoiceAudioReason {
    /** Omni model on a runtime that accepts audio content. */
    MODEL_ACCEPTS_AUDIO,

    /** Text-only model: a recognizer has to transcribe the turn. */
    MODEL_NEEDS_STT,

    /** Omni model, but this build's runtime cannot decode audio. */
    RUNTIME_CANNOT_DECODE_AUDIO
}

data class VoiceAudioDecision(
    val route: VoiceAudioRoute,
    val reason: VoiceAudioReason
)

/**
 * Picks the audio path for a hands-free turn.
 *
 * The app has two runtimes and only one of them can carry audio: LiteRT LM exposes
 * audio content, while the llama.cpp bridge in this build only handles images. An
 * omni model on llama.cpp therefore still needs a recognizer — the UI says so
 * instead of silently swallowing the microphone.
 */
object AudioInputRouter {

    fun route(model: LlmModel?): VoiceAudioDecision {
        if (model == null || !model.supportsAudioInput) {
            return VoiceAudioDecision(VoiceAudioRoute.STT, VoiceAudioReason.MODEL_NEEDS_STT)
        }
        return if (supportsDirectAudio(model.runtimeType)) {
            VoiceAudioDecision(VoiceAudioRoute.DIRECT_AUDIO, VoiceAudioReason.MODEL_ACCEPTS_AUDIO)
        } else {
            VoiceAudioDecision(VoiceAudioRoute.STT, VoiceAudioReason.RUNTIME_CANNOT_DECODE_AUDIO)
        }
    }

    /** Only the LiteRT LM runtime has an audio content path in this build. */
    fun supportsDirectAudio(runtime: ModelRuntimeType): Boolean =
        runtime == ModelRuntimeType.LITE_RT
}
