package com.localllm.android.audio

import com.localllm.android.engine.llama.LlamaContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Generic llama.cpp Omni bridge. The caller supplies the family-specific prompt
 * envelope; libmtmd owns the model-specific audio encoder and speech decoder.
 *
 * This backend is deliberately separate from the Whisper STT -> text LLM -> TTS
 * path. The model itself performs understanding and speech generation.
 */
class LlamaOmniBackend(
    private val modelPath: String,
    private val mmprojPath: String,
    private val promptPrefix: String,
    private val promptContinuation: String,
    private val scope: CoroutineScope,
    private val contextTokens: Int = 4096,
    private val threadCount: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
) : NativeOmniModelBackend {
    override val backendId: String = "llama.cpp-lfm3-omni"

    override suspend fun open(
        model: NativeOmniModel,
        config: OmniSessionConfig
    ): NativeOmniBackendSession {
        check(config.inputFormat.sampleRateHz == 16_000) {
            "LFM3 Omni requires 16 kHz mono PCM input"
        }
        check(config.inputFormat.channels == 1 && config.inputFormat.bitsPerSample == 16) {
            "LFM3 Omni requires mono PCM16 input"
        }
        val context = withContext(Dispatchers.IO) {
            LlamaContext(
                mapOf(
                    "model_path" to modelPath,
                    "mmproj_path" to mmprojPath,
                    "n_ctx" to contextTokens,
                    "n_batch" to 512,
                    "n_threads" to threadCount,
                    "n_gpu_layers" to 0,
                    "use_mmap" to true,
                    "use_mlock" to false
                )
            )
        }
        check(context.context != 0L) { "LFM3 Omni native context failed to load" }
        return Lfm3OmniSession(
            context = context,
            model = model,
            config = config,
            promptPrefix = promptPrefix,
            promptContinuation = promptContinuation,
            scope = scope
        )
    }
}

private class Lfm3OmniSession(
    private val context: LlamaContext,
    private val model: NativeOmniModel,
    private val config: OmniSessionConfig,
    private val promptPrefix: String,
    private val promptContinuation: String,
    private val scope: CoroutineScope
) : NativeOmniBackendSession {
    private val channel = Channel<OmniAudioEvent>(Channel.UNLIMITED)
    private val sequence = AtomicLong(0L)
    private val input = ByteArrayOutputStream()
    private val lock = Mutex()
    private var completionJob: Job? = null
    private var finished = false
    private var closed = false

    override val events: Flow<OmniAudioEvent> = channel.receiveAsFlow()

    override suspend fun sendAudio(frame: NativeAudioFrame) {
        lock.withLock {
            check(!finished && !closed) { "Audio input is already finished" }
            require(frame.format == config.inputFormat) { "Unexpected input audio format" }
            require(input.size() + frame.bytes.size <= config.maxTurnAudioBytes) {
                "Native Omni turn exceeds the audio limit"
            }
            input.write(frame.bytes)
        }
    }

    override suspend fun finishInput() {
        val bytes = lock.withLock {
            check(!finished && !closed) { "Audio input is already finished" }
            finished = true
            input.toByteArray()
        }
        require(bytes.isNotEmpty()) { "No audio was recorded" }
        require(bytes.size % 2 == 0) { "PCM16 input has an odd byte count" }

        completionJob = scope.launch(Dispatchers.IO) {
            try {
                val pcm = ShortArray(bytes.size / 2) { i ->
                    val lo = bytes[i * 2].toInt() and 0xff
                    val hi = bytes[i * 2 + 1].toInt() and 0xff
                    ((hi shl 8) or lo).toShort()
                }
                context.omniCompletion(
                    promptPrefix = promptPrefix,
                    promptContinuation = promptContinuation,
                    pcm16 = pcm,
                    temperature = 0f,
                    maxSteps = 4096,
                    expectedOutputSampleRate = config.outputFormat.sampleRateHz,
                    onToken = { text ->
                        if (text.isNotEmpty()) {
                            channel.trySend(
                                OmniAudioEvent.TextDelta(
                                    turnId = 0L,
                                    text = text,
                                    sequence = sequence.incrementAndGet()
                                )
                            )
                        }
                    },
                    onAudioPcm16 = { pcm16 ->
                        channel.trySend(
                            OmniAudioEvent.AudioOutput(
                                turnId = 0L,
                                frame = NativeAudioFrame(
                                    format = config.outputFormat,
                                    bytes = pcm16,
                                    sequence = sequence.incrementAndGet()
                                ),
                                sequence = sequence.get()
                            )
                        )
                    }
                )
                channel.trySend(
                    OmniAudioEvent.TurnFinished(
                        turnId = 0L,
                        sequence = sequence.incrementAndGet()
                    )
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (t: Throwable) {
                channel.trySend(
                    OmniAudioEvent.Failure(
                        turnId = 0L,
                        message = model.displayName + ": " +
                            (t.localizedMessage ?: t.message ?: "native audio failed"),
                        sequence = sequence.incrementAndGet()
                    )
                )
            }
        }
    }

    override suspend fun interrupt() {
        context.stopCompletion()
        completionJob?.cancel()
        channel.trySend(
            OmniAudioEvent.Interrupted(0L, sequence.incrementAndGet())
        )
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        context.stopCompletion()
        completionJob?.cancel()
        completionJob = null
        withContext(Dispatchers.IO) { context.release() }
        channel.close()
    }
}
