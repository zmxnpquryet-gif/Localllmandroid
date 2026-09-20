package com.localllm.android.voice

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class InteractiveVoiceState {
    IDLE, LISTENING, PROCESSING, SPEAKING
}

/** Which recognizer actually listened. Shown in UI so the user can verify. */
enum class SttEngine {
    LOCAL_WHISPER, SYSTEM
}

/** Which synthesizer speaks. LOCAL_NEURAL needs the supertonic model installed. */
enum class TtsEngineMode {
    SYSTEM, LOCAL_NEURAL
}

class VoiceManager(private val context: Context) {
    companion object {
        private const val TAG = "VoiceManager"
        private const val PREFS = "voice_prefs"
        private const val KEY_TTS_MODE = "tts_mode"
        private const val KEY_TTS_SPEAKER = "tts_speaker"
        private const val KEY_TTS_SPEED = "tts_speed"

        /** Endpointing for hands-free conversation mode. */
        private const val CALIBRATION_CHUNKS = 3
        private const val MIN_SPEECH_MS = 250
        private const val SILENCE_MS = 900
        private const val HANDHELD_MAX_SECONDS = 30
        private const val MANUAL_MAX_SECONDS = 90
        private const val TRIM_HEAD_MS = 200
        private const val TRIM_TAIL_MS = 300
    }

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completion = SpeechCompletionTracker()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    val localStt = LocalSttEngine(context)
    val localTts = LocalTtsEngine(context)

    private val _voiceState = MutableStateFlow(InteractiveVoiceState.IDLE)
    val voiceState: StateFlow<InteractiveVoiceState> = _voiceState.asStateFlow()
    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()
    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _lastSttEngine = MutableStateFlow<SttEngine?>(null)
    val lastSttEngine: StateFlow<SttEngine?> = _lastSttEngine.asStateFlow()

    private val _ttsMode = MutableStateFlow(
        try {
            TtsEngineMode.valueOf(prefs.getString(KEY_TTS_MODE, TtsEngineMode.SYSTEM.name) ?: TtsEngineMode.SYSTEM.name)
        } catch (_: Throwable) {
            TtsEngineMode.SYSTEM
        }
    )
    val ttsMode: StateFlow<TtsEngineMode> = _ttsMode.asStateFlow()
    private val _ttsSpeakerId = MutableStateFlow(prefs.getInt(KEY_TTS_SPEAKER, 0).coerceIn(0, LocalTtsEngine.SPEAKER_COUNT - 1))
    val ttsSpeakerId: StateFlow<Int> = _ttsSpeakerId.asStateFlow()
    private val _ttsSpeed = MutableStateFlow(prefs.getFloat(KEY_TTS_SPEED, 1.0f).coerceIn(0.5f, 2.0f))
    val ttsSpeed: StateFlow<Float> = _ttsSpeed.asStateFlow()

    // ---- local recording state ----
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private var pendingResult: ((String) -> Unit)? = null
    private var pendingError: ((String) -> Unit)? = null

    // ---- local TTS playback state ----
    @Volatile private var localTtsJob: Job? = null
    @Volatile private var localTtsPlayer: TtsAudioPlayer? = null

    init {
        initTts()
    }

    private fun initTts() {
        try {
            textToSpeech = TextToSpeech(context) { status ->
                try {
                    if (status == TextToSpeech.SUCCESS) {
                        val result = textToSpeech?.setLanguage(Locale.KOREAN)
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            textToSpeech?.setLanguage(Locale.US)
                        }
                        isTtsInitialized = true
                        textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {
                                mainHandler.post {
                                    if (completion.isCurrent(utteranceId)) _voiceState.value = InteractiveVoiceState.SPEAKING
                                }
                            }

                            override fun onDone(utteranceId: String?) = finishSpeech(utteranceId, true)
                            override fun onError(utteranceId: String?) = onSystemSpeechFailed(utteranceId)
                            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeech(utteranceId, false)
                        })
                    }
                } catch (t: Throwable) {
                    android.util.Log.w(TAG, "TTS listener setup warning", t)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "TextToSpeech init warning", t)
        }
    }

    private fun finishSpeech(id: String?, successful: Boolean) {
        // TTS callbacks are not guaranteed to run on the main thread. Revalidate
        // after posting so a queued completion cannot restart a stopped session.
        mainHandler.post {
            if (completion.isCurrent(id)) {
                _voiceState.value = InteractiveVoiceState.IDLE
                completion.finish(id, successful)
            }
        }
    }

    /**
     * A failed system utterance must not end the hands-free loop: the answer is
     * already on screen, so the session continues listening.
     */
    private fun onSystemSpeechFailed(id: String?) {
        android.util.Log.w(TAG, "System TTS failed for $id")
        finishSpeech(id, true)
    }

    /**
     * Local-first listening. When the on-device Whisper model is present, audio
     * is captured with AudioRecord and decoded locally — no system recognizer,
     * no keyboard STT, no network service. Otherwise falls back to the system
     * recognizer (with on-device pack preferred).
     *
     * @param autoEndpoint stop on its own once the speaker goes quiet (conversation mode)
     */
    fun startListening(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        autoEndpoint: Boolean = false
    ) {
        if (isRecording) return
        if (localStt.isModelDownloaded()) {
            startLocalRecording(onResult, onError, autoEndpoint)
            return
        }
        startSystemListening(onResult, onError)
    }

    fun isLocalRecording(): Boolean = isRecording

    private fun startLocalRecording(
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
        autoEndpoint: Boolean
    ) {
        val sampleRate = LocalSttEngine.SAMPLE_RATE
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBuf = try {
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        } catch (_: Throwable) {
            -1
        }
        if (minBuf <= 0) {
            onError("이 기기에서 로컬 녹음을 시작할 수 없습니다.")
            return
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate, channelConfig, audioFormat, minBuf * 4
            )
        } catch (e: SecurityException) {
            onError("음성 입력을 위해 마이크 권한이 필요합니다.")
            return
        } catch (e: Exception) {
            onError("마이크 초기화 실패: ${e.localizedMessage}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            try { record.release() } catch (_: Throwable) {}
            onError("마이크 초기화 실패 (상태 오류).")
            return
        }
        pendingResult = onResult
        pendingError = onError
        _lastSttEngine.value = SttEngine.LOCAL_WHISPER
        _voiceState.value = InteractiveVoiceState.LISTENING
        isRecording = true
        recorder = record
        val thread = Thread({
            val chunks = ArrayList<FloatArray>(256)
            var total = 0
            val maxSeconds = if (autoEndpoint) HANDHELD_MAX_SECONDS else MANUAL_MAX_SECONDS
            val maxSamples = sampleRate * maxSeconds
            val buf = ShortArray(4096)
            val minSpeechSamples = sampleRate * MIN_SPEECH_MS / 1000
            val silenceLimit = sampleRate * SILENCE_MS / 1000
            var calibrationFrames = 0
            var noiseFloor = 1.0
            var speechSamples = 0
            var trailingSilence = 0
            var speechStartSample = -1
            var speechEndSample = -1
            try {
                record.startRecording()
                while (isRecording && total < maxSamples) {
                    val n = record.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (n == 0) continue
                    val floats = FloatArray(n)
                    var energy = 0.0
                    for (i in 0 until n) {
                        val f = buf[i] / 32768f
                        floats[i] = f
                        energy += f * f
                    }
                    chunks.add(floats)
                    total += n
                    val rms = sqrt(energy / n).toFloat()
                    val level = ((20 * log10(rms + 1e-6f) + 50f) / 50f).coerceIn(0.1f, 1f)
                    mainHandler.post { _audioAmplitude.value = level }

                    if (autoEndpoint) {
                        if (calibrationFrames < CALIBRATION_CHUNKS) {
                            noiseFloor = min(noiseFloor, rms.toDouble())
                            calibrationFrames++
                            continue
                        }
                        if (rms < noiseFloor) noiseFloor = rms.toDouble()
                        val speechThreshold = max(0.015f, (noiseFloor * 4).toFloat())
                        if (rms >= speechThreshold) {
                            if (speechStartSample < 0) speechStartSample = total
                            speechSamples += n
                            trailingSilence = 0
                            speechEndSample = total
                        } else if (speechStartSample >= 0) {
                            trailingSilence += n
                            if (speechSamples >= minSpeechSamples && trailingSilence >= silenceLimit) break
                        }
                    }
                }
            } catch (_: Throwable) {
            } finally {
                // The capture loop is over: never leave isRecording latched, or every
                // later startListening() would return early for the rest of the process.
                isRecording = false
                try { record.stop() } catch (_: Throwable) {}
                try { record.release() } catch (_: Throwable) {}
                if (recorder === record) recorder = null
                if (recordingThread === Thread.currentThread()) recordingThread = null
            }

            var flat = FloatArray(total)
            var pos = 0
            for (chunk in chunks) {
                chunk.copyInto(flat, pos)
                pos += chunk.size
            }
            if (autoEndpoint && speechStartSample > 0 && speechEndSample > speechStartSample) {
                val head = max(0, speechStartSample - sampleRate * TRIM_HEAD_MS / 1000)
                val tail = min(flat.size, speechEndSample + sampleRate * TRIM_TAIL_MS / 1000)
                if (tail > head) flat = flat.copyOfRange(head, tail)
            }
            finishLocalRecording(flat)
        }, "LocalSttRecord")
        recordingThread = thread
        thread.start()
    }

    private fun finishLocalRecording(samples: FloatArray) {
        val onResult = pendingResult
        val onError = pendingError
        pendingResult = null
        pendingError = null
        mainHandler.post {
            _audioAmplitude.value = 0f
            _voiceState.value = InteractiveVoiceState.PROCESSING
        }
        ioScope.launch {
            try {
                if (samples.size < LocalSttEngine.SAMPLE_RATE / 2) {
                    throw IllegalStateException("녹음된 음성이 너무 짧습니다.")
                }
                val text = localStt.transcribe(samples)
                withContext(Dispatchers.Main) {
                    _voiceState.value = InteractiveVoiceState.IDLE
                    _recognizedText.value = text
                    if (text.isNotBlank()) onResult?.invoke(text)
                    else onError?.invoke("음성을 인식하지 못했습니다.")
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Local STT failed", e)
                withContext(Dispatchers.Main) {
                    _voiceState.value = InteractiveVoiceState.IDLE
                    onError?.invoke(e.localizedMessage ?: "로컬 음성 인식 실패")
                }
            }
        }
    }

    private fun startSystemListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("음성 인식을 지원하지 않는 기기입니다.")
            return
        }
        destroySystemRecognizer()
        _lastSttEngine.value = SttEngine.SYSTEM
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) { _voiceState.value = InteractiveVoiceState.LISTENING }
                override fun onBeginningOfSpeech() { _voiceState.value = InteractiveVoiceState.LISTENING }
                override fun onRmsChanged(rmsdB: Float) { _audioAmplitude.value = ((rmsdB + 2f) / 12f).coerceIn(0.1f, 1f) }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() { _audioAmplitude.value = 0f }
                override fun onError(error: Int) {
                    _voiceState.value = InteractiveVoiceState.IDLE
                    val msg = when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH -> "음성을 인식하지 못했습니다."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "음성 입력 시간이 초과되었습니다."
                        SpeechRecognizer.ERROR_AUDIO -> "오디오 녹음 오류가 발생했습니다."
                        else -> "음성 인식 오류 ($error)"
                    }
                    onError(msg)
                }
                override fun onResults(results: Bundle?) {
                    _voiceState.value = InteractiveVoiceState.IDLE
                    val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    _recognizedText.value = text
                    if (text.isNotBlank()) onResult(text)
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let {
                        _recognizedText.value = it
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "ko-KR")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // Prefer the on-device pack so voice input stays offline when available.
            // Devices without an offline pack fall back to the system service.
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        speechRecognizer?.startListening(intent)
    }

    private fun destroySystemRecognizer() {
        try { speechRecognizer?.stopListening() } catch (_: Throwable) {}
        try { speechRecognizer?.destroy() } catch (_: Throwable) {}
        speechRecognizer = null
    }

    /**
     * Stops listening. For a local recording this only signals the capture thread,
     * which then finalizes and transcribes on its own thread (the pending onResult
     * fires when done) — the caller is never blocked.
     */
    fun stopListening() {
        if (isRecording) {
            isRecording = false
            return
        }
        pendingResult = null
        pendingError = null
        destroySystemRecognizer()
        _voiceState.value = InteractiveVoiceState.IDLE
        _audioAmplitude.value = 0f
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        stopSpeaking()
        val cleanSpeech = text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "코드 블록")
            .replace(Regex("[#*_\\[\\]()]"), "")
            .trim()
        // Nothing to say (or no synthesizer): the caller still gets its completion so
        // hands-free sessions keep going instead of silently dying here.
        if (cleanSpeech.isBlank()) {
            onComplete?.invoke()
            return
        }
        if (_ttsMode.value == TtsEngineMode.LOCAL_NEURAL && localTts.isModelDownloaded()) {
            speakLocal(cleanSpeech, onComplete)
            return
        }
        if (!isTtsInitialized || textToSpeech == null) {
            android.util.Log.w(TAG, "System TTS unavailable; skipping speech")
            onComplete?.invoke()
            return
        }
        val id = completion.begin(onComplete)
        _voiceState.value = InteractiveVoiceState.SPEAKING
        try {
            val result = textToSpeech?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) onSystemSpeechFailed(id)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "TTS speak failed", e)
            onSystemSpeechFailed(id)
        }
    }

    private fun speakLocal(text: String, onComplete: (() -> Unit)?) {
        val id = completion.begin(onComplete)
        _voiceState.value = InteractiveVoiceState.SPEAKING
        val speaker = _ttsSpeakerId.value
        val speed = _ttsSpeed.value
        localTtsJob = ioScope.launch {
            val player = TtsAudioPlayer(localTts.sampleRate())
            if (!player.start()) {
                android.util.Log.w(TAG, "Local TTS playback could not start")
                withContext(Dispatchers.Main) { finishSpeech(id, true) }
                return@launch
            }
            localTtsPlayer = player
            var spoken = false
            try {
                spoken = localTts.synthesize(text, speaker, speed) { chunk -> player.write(chunk) }
            } finally {
                player.finish()
                if (localTtsPlayer === player) localTtsPlayer = null
            }
            withContext(Dispatchers.Main) {
                if (!spoken) android.util.Log.w(TAG, "Local TTS synthesis failed")
                // The answer is already on screen: a failed synthesis continues the loop.
                finishSpeech(id, true)
            }
        }
    }

    fun stopSpeaking() {
        completion.cancel()
        localTtsJob?.cancel()
        localTtsJob = null
        localTtsPlayer?.stop()
        localTtsPlayer = null
        textToSpeech?.stop()
        _voiceState.value = InteractiveVoiceState.IDLE
    }

    fun setVoiceState(state: InteractiveVoiceState) { _voiceState.value = state }

    // ---- TTS engine selection ----

    fun setTtsMode(mode: TtsEngineMode) {
        _ttsMode.value = mode
        prefs.edit().putString(KEY_TTS_MODE, mode.name).apply()
    }

    fun setTtsSpeaker(speakerId: Int) {
        val clamped = speakerId.coerceIn(0, LocalTtsEngine.SPEAKER_COUNT - 1)
        _ttsSpeakerId.value = clamped
        prefs.edit().putInt(KEY_TTS_SPEAKER, clamped).apply()
    }

    fun setTtsSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        _ttsSpeed.value = clamped
        prefs.edit().putFloat(KEY_TTS_SPEED, clamped).apply()
    }

    /** True when the local neural voice is selected and installed. */
    fun isLocalTtsActive(): Boolean =
        _ttsMode.value == TtsEngineMode.LOCAL_NEURAL && localTts.isModelDownloaded()

    fun release() {
        try { isRecording = false } catch (_: Throwable) {}
        try { recordingThread?.join(1000) } catch (_: Throwable) {}
        recordingThread = null
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
        pendingResult = null
        pendingError = null
        destroySystemRecognizer()
        stopSpeaking()
        isTtsInitialized = false
        textToSpeech?.shutdown()
        textToSpeech = null
        try { localStt.release() } catch (_: Throwable) {}
        try { localTts.release() } catch (_: Throwable) {}
        try { ioScope.cancel() } catch (_: Throwable) {}
    }
}
