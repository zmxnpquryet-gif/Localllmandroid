package com.localllm.android.ui.controllers

import android.app.Application
import android.util.Log
import com.localllm.android.R
import com.localllm.android.audio.AndroidPcmInputSource
import com.localllm.android.audio.AndroidPcmOutputSink
import com.localllm.android.audio.AudioFormat
import com.localllm.android.audio.LlamaOmniBackend
import com.localllm.android.audio.NativeOmniCapabilities
import com.localllm.android.audio.NativeOmniModel
import com.localllm.android.audio.NativeOmniPromptBuilder
import com.localllm.android.audio.OmniAudioConversation
import com.localllm.android.audio.OmniAudioEvent
import com.localllm.android.audio.OmniSessionConfig
import com.localllm.android.engine.LlmEngine
import com.localllm.android.i18n.LocalizedStringProvider
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.MessageRole
import com.localllm.android.voice.InteractiveVoiceState
import com.localllm.android.voice.TtsEngineMode
import com.localllm.android.voice.VoiceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coordinates both the Whisper STT -> LLM -> TTS hands-free voice loop and the
 * native `libmtmd` Omni audio turn lifecycle.
 */
internal class VoiceSessionController(
    private val application: Application,
    private val scope: CoroutineScope,
    val voiceManager: VoiceManager,
    private val llmEngine: LlmEngine,
    private val conversations: ConversationController,
    private val strings: LocalizedStringProvider,
    private val activeModel: () -> LlmModel?,
    private val currentSettings: () -> GenerationSettings,
    private val beginInferenceGuard: () -> Unit,
    private val endInferenceGuard: () -> Unit,
    private val postStatus: (String) -> Unit
) {
    private var voiceSessionJob: Job? = null
    private var nativeOmniJob: Job? = null
    private var nativeOmniConversation: OmniAudioConversation? = null
    private var nativeOmniInput: AndroidPcmInputSource? = null
    private var nativeOmniOutput: AndroidPcmOutputSink? = null
    private var lastVoiceInput: String? = null
    private var duplicateVoiceTurns = 0

    private val _nativeOmniActive = MutableStateFlow(false)
    val nativeOmniActive: StateFlow<Boolean> = _nativeOmniActive.asStateFlow()

    private val _nativeOmniTranscript = MutableStateFlow("")
    val nativeOmniTranscript: StateFlow<String> = _nativeOmniTranscript.asStateFlow()

    fun downloadLocalStt() {
        scope.launch { voiceManager.localStt.downloadModel() }
    }

    fun deleteLocalStt() {
        scope.launch { voiceManager.localStt.deleteModel() }
    }

    fun downloadLocalTts() {
        scope.launch {
            if (voiceManager.localTts.downloadModel()) {
                voiceManager.setTtsMode(TtsEngineMode.LOCAL_NEURAL)
            }
        }
    }

    fun deleteLocalTts() {
        scope.launch {
            voiceManager.localTts.deleteModel()
            voiceManager.setTtsMode(TtsEngineMode.SYSTEM)
        }
    }

    fun selectTtsMode(mode: TtsEngineMode) = voiceManager.setTtsMode(mode)
    fun setTtsSpeaker(speakerId: Int) = voiceManager.setTtsSpeaker(speakerId)
    fun setTtsSpeed(speed: Float) = voiceManager.setTtsSpeed(speed)
    fun previewTts() = voiceManager.speak(strings.get(R.string.voice_tts_preview_text))

    fun toggleNativeOmniTurn() {
        if (_nativeOmniActive.value) stopNativeOmniTurn() else startNativeOmniTurn()
    }

    fun startNativeOmniTurn() {
        val target = activeModel()
        if (target == null || !target.isDownloaded ||
            !target.supportsNativeAudioInput || !target.supportsNativeAudioOutput
        ) {
            postStatus("Download a native audio model such as LFM3 Omni 2B.")
            voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
            return
        }
        val modelPath = target.localFilePath
        val mmprojPath = target.localMmprojPath
        if (modelPath.isNullOrBlank() || mmprojPath.isNullOrBlank()) {
            postStatus("The Omni model or its audio projector is missing.")
            voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
            return
        }

        _nativeOmniActive.value = true
        nativeOmniJob?.cancel()
        nativeOmniJob = scope.launch {
            var input: AndroidPcmInputSource? = null
            var output: AndroidPcmOutputSink? = null
            var conversation: OmniAudioConversation? = null
            try {
                voiceManager.stopListening()
                voiceManager.stopSpeaking()
                if (llmEngine.isModelReady()) llmEngine.loadModel(null, currentSettings())

                postStatus("Preparing ${target.name} native audio…")
                val inputFormat = AudioFormat(16_000, 1, 16)
                val outputFormat = AudioFormat(24_000, 1, 16)
                val prompt = NativeOmniPromptBuilder.buildEnvelope(target, conversations.messages.value)
                val nativeModel = NativeOmniModel(
                    id = target.id,
                    displayName = target.name,
                    family = NativeOmniPromptBuilder.detectFamily(target),
                    protocol = target.nativeAudioProtocol,
                    capabilities = NativeOmniCapabilities(
                        nativeAudioInput = true,
                        nativeAudioOutput = true,
                        streamingInput = false,
                        streamingOutput = true,
                        supportsInterruption = true,
                        synchronizedTextOutput = true,
                        modelProtocols = setOf("lfm3-omni-pcm")
                    )
                )
                val backend = LlamaOmniBackend(
                    modelPath = modelPath,
                    mmprojPath = mmprojPath,
                    promptPrefix = prompt.first,
                    promptContinuation = prompt.second,
                    scope = scope
                )
                conversation = OmniAudioConversation(
                    backend = backend,
                    model = nativeModel,
                    config = OmniSessionConfig(inputFormat, outputFormat),
                    scope = scope
                )
                output = AndroidPcmOutputSink(outputFormat)
                output.open()
                nativeOmniOutput = output
                conversation.open()
                nativeOmniConversation = conversation
                _nativeOmniTranscript.value = ""
                postStatus("LFM3 Omni · native audio · one buffered turn")

                val finished = CompletableDeferred<Unit>()
                val eventJob = launch(Dispatchers.IO) {
                    conversation.events.collect { event ->
                        when (event) {
                            is OmniAudioEvent.TextDelta -> {
                                _nativeOmniTranscript.value += event.text
                                voiceManager.setVoiceState(InteractiveVoiceState.SPEAKING)
                            }
                            is OmniAudioEvent.AudioOutput -> {
                                voiceManager.setVoiceState(InteractiveVoiceState.SPEAKING)
                                output.write(event.frame)
                            }
                            is OmniAudioEvent.TurnFinished -> finished.complete(Unit)
                            is OmniAudioEvent.Failure ->
                                finished.completeExceptionally(IllegalStateException(event.message))
                            is OmniAudioEvent.Interrupted -> finished.cancel()
                            is OmniAudioEvent.Ready ->
                                voiceManager.setVoiceState(InteractiveVoiceState.LISTENING)
                        }
                    }
                }

                input = AndroidPcmInputSource(application, inputFormat)
                nativeOmniInput = input
                voiceManager.setVoiceState(InteractiveVoiceState.LISTENING)
                input.frames().collect { frame -> conversation.sendAudio(frame) }
                if (_nativeOmniActive.value) {
                    voiceManager.setVoiceState(InteractiveVoiceState.PROCESSING)
                    conversation.finishInput()
                    finished.await()
                }
                eventJob.cancel()
            } catch (cancel: CancellationException) {
                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                throw cancel
            } catch (t: Throwable) {
                postStatus(t.localizedMessage ?: t.message ?: "Native Omni session failed")
                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
            } finally {
                input?.close()
                output?.close()
                withContext(Dispatchers.IO) { runCatching { conversation?.close() } }
                nativeOmniInput = null
                nativeOmniOutput = null
                nativeOmniConversation = null
                _nativeOmniActive.value = false
                if (voiceManager.voiceState.value != InteractiveVoiceState.IDLE) {
                    voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                }
            }
        }
    }

    fun stopNativeOmniTurn() {
        nativeOmniInput?.close()
        nativeOmniInput = null
        if (voiceManager.voiceState.value != InteractiveVoiceState.LISTENING) {
            nativeOmniConversation?.let { conv -> scope.launch { runCatching { conv.interrupt() } } }
            nativeOmniJob?.cancel()
            nativeOmniJob = null
            _nativeOmniActive.value = false
            voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
        }
    }

    fun stopNativeOmniSession() {
        nativeOmniInput?.close()
        nativeOmniInput = null
        nativeOmniConversation?.let { conv -> scope.launch { runCatching { conv.interrupt() } } }
        nativeOmniJob?.cancel()
        nativeOmniJob = null
        _nativeOmniActive.value = false
        voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
    }

    fun startInteractiveVoiceSession() {
        val current = activeModel()
        if (current == null || !current.isDownloaded) {
            val noModelMsg = strings.get(R.string.vm_voice_no_model)
            postStatus(noModelMsg)
            voiceManager.speak(noModelMsg)
            return
        }
        voiceManager.startListening(
            onResult = { userSpokenText ->
                if (userSpokenText.isBlank() || userSpokenText == lastVoiceInput) {
                    duplicateVoiceTurns++
                    if (duplicateVoiceTurns >= 2) {
                        voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                        return@startListening
                    }
                    startInteractiveVoiceSession()
                    return@startListening
                }
                duplicateVoiceTurns = 0
                lastVoiceInput = userSpokenText
                voiceManager.setVoiceState(InteractiveVoiceState.PROCESSING)
                voiceSessionJob?.cancel()
                voiceSessionJob = scope.launch {
                    try {
                        if (!llmEngine.isModelReady()) {
                            val target = activeModel()
                            if (target == null || !target.isDownloaded) {
                                voiceManager.speak(strings.get(R.string.vm_voice_no_model))
                                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                                return@launch
                            }
                            postStatus(strings.get(R.string.vm_status_loading_memory, target.name))
                            val loadResult = try {
                                llmEngine.loadModel(target, currentSettings())
                            } catch (t: Throwable) {
                                strings.get(R.string.vm_status_model_load_exception, t.localizedMessage ?: t.message)
                            }
                            if (!llmEngine.isModelReady()) {
                                voiceManager.speak(strings.get(R.string.vm_voice_load_failed, loadResult))
                                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                                return@launch
                            }
                            postStatus(loadResult)
                        }
                        val convId = conversations.currentConversationId.value ?: conversations.ensureConversationId()
                        val userMsg = ChatMessage(conversationId = convId, role = MessageRole.USER, content = userSpokenText)
                        val voiceDraft = conversations.capturePendingDraft(convId)
                        conversations.repository.saveConversationAndMessage(voiceDraft, userMsg)
                        if (voiceDraft != null) conversations.clearPendingDraft(convId)

                        val voiceHistory = conversations.messages.value.takeLast(6).map(ChatTurnRunner::toHistoryPair)
                        beginInferenceGuard()
                        try {
                            llmEngine.streamGenerate(
                                prompt = userSpokenText,
                                history = voiceHistory,
                                settings = currentSettings(),
                                attachment = null
                            ).collect { chunk ->
                                if (chunk.isComplete) {
                                    val assistantMsg = ChatMessage(
                                        conversationId = convId,
                                        role = MessageRole.ASSISTANT,
                                        content = chunk.currentContentText,
                                        reasoning = chunk.currentReasoningText.ifBlank { null },
                                        tps = chunk.tps,
                                        promptSpeed = chunk.promptSpeed
                                    )
                                    conversations.repository.saveMessage(assistantMsg)
                                    voiceManager.speak(chunk.currentContentText) {
                                        startInteractiveVoiceSession()
                                    }
                                }
                            }
                        } finally {
                            endInferenceGuard()
                        }
                    } catch (e: CancellationException) {
                        voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                        throw e
                    } catch (e: Exception) {
                        Log.w("VoiceSessionController", "Voice session error", e)
                        voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                        voiceManager.speak(strings.get(R.string.vm_voice_error))
                    }
                }
            },
            onError = { _ -> voiceManager.setVoiceState(InteractiveVoiceState.IDLE) },
            autoEndpoint = true
        )
    }

    fun stopAllVoiceActivity() {
        voiceSessionJob?.cancel()
        voiceSessionJob = null
        nativeOmniInput?.close()
        nativeOmniInput = null
        nativeOmniJob?.cancel()
        nativeOmniJob = null
        _nativeOmniActive.value = false
        runCatching { voiceManager.stopListening() }
        runCatching { voiceManager.stopSpeaking() }
    }

    fun release() {
        nativeOmniInput?.close()
        nativeOmniJob?.cancel()
        nativeOmniJob = null
        voiceManager.release()
    }
}
