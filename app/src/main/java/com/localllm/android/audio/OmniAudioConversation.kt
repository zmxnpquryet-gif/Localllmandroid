package com.localllm.android.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * Duplex native-audio conversation lifecycle. The backend owns model-specific audio
 * codecs; this layer owns turn ids, interruption, ordered text/audio events, limits,
 * and idempotent cleanup.
 */
class OmniAudioConversation(
    private val backend: NativeOmniModelBackend,
    private val model: NativeOmniModel,
    private val config: OmniSessionConfig,
    private val scope: CoroutineScope
) {
    private val lifecycle = Mutex()
    private val eventSequence = AtomicLong(0)
    private val turnSequence = AtomicLong(0)
    private val queuedEventFlow = MutableSharedFlow<OmniAudioEvent>(extraBufferCapacity = 64)
    private val mutableState = MutableStateFlow(OmniConversationState.IDLE)
    private var backendSession: NativeOmniBackendSession? = null
    private var eventJob: Job? = null
    private var activeTurn = 0L
    private var turnAudioBytes = 0

    val state: StateFlow<OmniConversationState> = mutableState.asStateFlow()
    val events: Flow<OmniAudioEvent> = queuedEventFlow.asSharedFlow()

    suspend fun open() {
        lifecycle.withLock {
            check(mutableState.value == OmniConversationState.IDLE) { "Session was already opened" }
            if (OmniCapabilityDetector.detect(model) is OmniCapabilityCheck.Unsupported) {
                mutableState.value = OmniConversationState.FAILED
                error("Native Omni audio capabilities are incomplete for ${model.displayName}")
            }
            mutableState.value = OmniConversationState.CONNECTING
            try {
                val session = backend.open(model, config)
                backendSession = session
                activeTurn = turnSequence.incrementAndGet()
                eventJob = scope.launch {
                    session.events.collect { event ->
                        val normalized = normalize(event)
                        when (normalized) {
                            is OmniAudioEvent.TextDelta -> mutableState.value = OmniConversationState.SPEAKING
                            is OmniAudioEvent.AudioOutput -> mutableState.value = OmniConversationState.SPEAKING
                            is OmniAudioEvent.TurnFinished -> {
                                mutableState.value = OmniConversationState.LISTENING
                                activeTurn = turnSequence.incrementAndGet()
                                turnAudioBytes = 0
                            }
                            is OmniAudioEvent.Interrupted -> mutableState.value = OmniConversationState.INTERRUPTED
                            is OmniAudioEvent.Failure -> mutableState.value = OmniConversationState.FAILED
                            is OmniAudioEvent.Ready -> Unit
                        }
                        queuedEventFlow.emit(normalized)
                    }
                }
                mutableState.value = OmniConversationState.LISTENING
                queuedEventFlow.emit(OmniAudioEvent.Ready(activeTurn))
            } catch (t: Throwable) {
                mutableState.value = OmniConversationState.FAILED
                throw t
            }
        }
    }

    suspend fun sendAudio(frame: NativeAudioFrame) {
        val session = backendSession ?: error("Session is not open")
        check(mutableState.value == OmniConversationState.LISTENING) {
            "Audio input is not accepted while ${mutableState.value}"
        }
        require(frame.format == config.inputFormat) { "Input audio format does not match the negotiated session" }
        require(frame.bytes.isNotEmpty() && frame.bytes.size <= config.maxFrameBytes) { "Invalid native audio frame size" }
        turnAudioBytes += frame.bytes.size
        require(turnAudioBytes <= config.maxTurnAudioBytes) { "Turn audio limit exceeded; finish or interrupt the turn" }
        try {
            session.sendAudio(frame)
        } catch (t: Throwable) {
            mutableState.value = OmniConversationState.FAILED
            throw t
        }
    }

    suspend fun finishInput() {
        val session = backendSession ?: error("Session is not open")
        check(mutableState.value == OmniConversationState.LISTENING) { "No listening turn to finish" }
        mutableState.value = OmniConversationState.PROCESSING
        try {
            session.finishInput()
        } catch (t: Throwable) {
            mutableState.value = OmniConversationState.FAILED
            throw t
        }
    }

    suspend fun startNextTurn() {
        check(mutableState.value in setOf(OmniConversationState.INTERRUPTED, OmniConversationState.SPEAKING)) {
            "Cannot start a turn while ${mutableState.value}"
        }
        activeTurn = turnSequence.incrementAndGet()
        turnAudioBytes = 0
        mutableState.value = OmniConversationState.LISTENING
    }

    suspend fun interrupt() {
        val session = backendSession ?: return
        session.interrupt()
        activeTurn = turnSequence.incrementAndGet()
        turnAudioBytes = 0
        mutableState.value = OmniConversationState.INTERRUPTED
        queuedEventFlow.emit(OmniAudioEvent.Interrupted(activeTurn, eventSequence.incrementAndGet()))
    }

    suspend fun close() {
        lifecycle.withLock {
            if (mutableState.value == OmniConversationState.CLOSED) return
            val session = backendSession
            backendSession = null
            eventJob?.cancel()
            eventJob = null
            try {
                session?.close()
            } catch (e: CancellationException) {
                throw e
            } finally {
                mutableState.value = OmniConversationState.CLOSED
            }
        }
    }

    private fun normalize(event: OmniAudioEvent): OmniAudioEvent {
        val turn = if (event.turnId == 0L) activeTurn else event.turnId
        val sequence = if (event.sequence == 0L) eventSequence.incrementAndGet() else event.sequence
        return when (event) {
            is OmniAudioEvent.Ready -> OmniAudioEvent.Ready(turn)
            is OmniAudioEvent.TextDelta -> event.copy(turnId = turn, sequence = sequence)
            is OmniAudioEvent.AudioOutput -> event.copy(turnId = turn, sequence = sequence)
            is OmniAudioEvent.TurnFinished -> event.copy(turnId = turn, sequence = sequence)
            is OmniAudioEvent.Interrupted -> event.copy(turnId = turn, sequence = sequence)
            is OmniAudioEvent.Failure -> event.copy(turnId = turn, sequence = sequence)
        }
    }
}
