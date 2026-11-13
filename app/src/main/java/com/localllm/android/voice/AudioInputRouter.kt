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
 * The llama.cpp bridge carries native Omni audio through the LFM3 turn path; every
 * other model — and LiteRT in this build, which is text/image only — needs the
 * on-device recognizer. The UI says which path a turn takes instead of silently
 * swallowing the microphone.
 */
object AudioInputRouter {

    fun route(model: LlmModel?): VoiceAudioDecision {
        if (model == null || !model.supportsNativeAudioInput) {
            return VoiceAudioDecision(VoiceAudioRoute.STT, VoiceAudioReason.MODEL_NEEDS_STT)
        }
        return if (supportsDirectAudio(model)) {
            VoiceAudioDecision(VoiceAudioRoute.DIRECT_AUDIO, VoiceAudioReason.MODEL_ACCEPTS_AUDIO)
        } else {
            VoiceAudioDecision(VoiceAudioRoute.STT, VoiceAudioReason.RUNTIME_CANNOT_DECODE_AUDIO)
        }
    }

    /** The native Omni path currently exists only for llama.cpp GGUF models. */
    fun supportsDirectAudio(model: LlmModel): Boolean =
        model.supportsNativeAudioInput && model.runtimeType == ModelRuntimeType.LLAMA_CPP
}
