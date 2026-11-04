package com.localllm.android.audio

import kotlinx.coroutines.flow.Flow

enum class OmniModelFamily { QWEN_OMNI, GEMMA_AUDIO, LFM3_OMNI, MOSHI, CUSTOM }

data class AudioFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val signedPcmLittleEndian: Boolean = true
) {
    init {
        require(sampleRateHz > 0)
        require(channels in 1..2)
        require(bitsPerSample in setOf(16, 24, 32))
    }
}

/** Native model audio payload. It is never produced by wrapping STT or TTS. */
data class NativeAudioFrame(
    val format: AudioFormat,
    val bytes: ByteArray,
    val sequence: Long,
    val isFinal: Boolean = false
)

data class NativeOmniCapabilities(
    val nativeAudioInput: Boolean,
    val nativeAudioOutput: Boolean,
    val streamingInput: Boolean,
    val streamingOutput: Boolean,
    val supportsInterruption: Boolean,
    val synchronizedTextOutput: Boolean,
    val modelProtocols: Set<String>,
    val limitation: String? = null
)

data class NativeOmniModel(
    val id: String,
    val displayName: String,
    val family: OmniModelFamily,
    val protocol: String,
    val capabilities: NativeOmniCapabilities
)

sealed interface OmniCapabilityCheck {
    data class Supported(val model: NativeOmniModel) : OmniCapabilityCheck
    data class Unsupported(val missing: List<String>) : OmniCapabilityCheck
}

object OmniCapabilityDetector {
    /**
     * Capability comes from the model adapter, never from a model's name alone.
     * A conventional STT -> text LLM -> TTS setup remains a separate voice mode.
     */
    fun detect(model: NativeOmniModel): OmniCapabilityCheck {
        val capabilities = model.capabilities
        val missing = buildList {
            if (!capabilities.nativeAudioInput) add("native audio input")
            if (!capabilities.nativeAudioOutput) add("native audio output")
            if (!capabilities.streamingOutput) add("streaming audio output")
            if (model.protocol.isBlank()) add("model audio protocol")
        }
        return if (missing.isEmpty()) OmniCapabilityCheck.Supported(model)
        else OmniCapabilityCheck.Unsupported(missing)
    }
}

data class OmniSessionConfig(
    val inputFormat: AudioFormat,
    val outputFormat: AudioFormat,
    val maxFrameBytes: Int = 64 * 1024,
    val maxTurnAudioBytes: Int = 16 * 1024 * 1024,
    val protocolOptions: Map<String, String> = emptyMap()
)

enum class OmniConversationState {
    IDLE,
    CONNECTING,
    LISTENING,
    PROCESSING,
    SPEAKING,
    INTERRUPTED,
    FAILED,
    CLOSED
}

sealed interface OmniAudioEvent {
    val turnId: Long
    val sequence: Long

    data class Ready(override val turnId: Long) : OmniAudioEvent { override val sequence = 0L }
    data class TextDelta(override val turnId: Long, val text: String, override val sequence: Long) : OmniAudioEvent
    data class AudioOutput(override val turnId: Long, val frame: NativeAudioFrame, override val sequence: Long) : OmniAudioEvent
    data class TurnFinished(override val turnId: Long, override val sequence: Long) : OmniAudioEvent
    data class Interrupted(override val turnId: Long, override val sequence: Long) : OmniAudioEvent
    data class Failure(override val turnId: Long, val message: String, override val sequence: Long) : OmniAudioEvent
}

interface NativeOmniModelBackend {
    val backendId: String
    suspend fun open(model: NativeOmniModel, config: OmniSessionConfig): NativeOmniBackendSession
}

interface NativeOmniBackendSession {
    val events: Flow<OmniAudioEvent>
    suspend fun sendAudio(frame: NativeAudioFrame)
    suspend fun finishInput()
    suspend fun interrupt()
    suspend fun close()
}
