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
import com.localllm.android.model.VoiceModelTemplate
import com.localllm.android.model.VoiceTemplates
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.sqrt

enum class InteractiveVoiceState {
    IDLE, LISTENING, PROCESSING, SPEAKING
}

/** Which recognizer actually listened. Shown in UI so the user can verify. */
enum class SttEngine {
    LOCAL_WHISPER, SYSTEM
}

class VoiceManager(private val context: Context) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val completion = SpeechCompletionTracker()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val localStt = LocalSttEngine(context)

    private val _voiceState = MutableStateFlow(InteractiveVoiceState.IDLE)
    val voiceState: StateFlow<InteractiveVoiceState> = _voiceState.asStateFlow()
    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()
    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()
    private val _installedVoiceTemplates = MutableStateFlow(VoiceTemplates.templates)
    val installedVoiceTemplates: StateFlow<List<VoiceModelTemplate>> = _installedVoiceTemplates.asStateFlow()

    private val _lastSttEngine = MutableStateFlow<SttEngine?>(null)
    val lastSttEngine: StateFlow<SttEngine?> = _lastSttEngine.asStateFlow()

    // ---- local recording state ----
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var recordingThread: Thread? = null
    @Volatile private var isRecording = false
    private var pendingResult: ((String) -> Unit)? = null
    private var pendingError: ((String) -> Unit)? = null

    init { initTts() }

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
                            override fun onError(utteranceId: String?) = finishSpeech(utteranceId, false)
                            override fun onStop(utteranceId: String?, interrupted: Boolean) = finishSpeech(utteranceId, false)
                        })
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("VoiceManager", "TTS listener setup warning", t)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.w("VoiceManager", "TextToSpeech init warning", t)
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
     * Local-first listening. When the on-device Whisper model is present, audio
     * is captured with AudioRecord and decoded locally — no system recognizer,
     * no keyboard STT, no network service. Otherwise falls back to the system
     * recognizer (with on-device pack preferred).
     */
    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (isRecording) return
        if (localStt.isModelDownloaded()) {
            startLocalRecording(onResult, onError)
            return
        }
        startSystemListening(onResult, onError)
    }

    fun isLocalRecording(): Boolean = isRecording

    private fun startLocalRecording(onResult: (String) -> Unit, onError: (String) -> Unit) {
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
            val maxSamples = sampleRate * 90 // 90s cap
            val buf = ShortArray(4096)
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
                    val level = ((20 * kotlin.math.log10(rms + 1e-6f) + 50f) / 50f).coerceIn(0.1f, 1f)
                    mainHandler.post { _audioAmplitude.value = level }
                }
            } catch (_: Throwable) {
            } finally {
                try { record.stop() } catch (_: Throwable) {}
                try { record.release() } catch (_: Throwable) {}
                if (recorder === record) recorder = null
            }
            val flat = FloatArray(total)
            var pos = 0
            for (c in chunks) {
                c.copyInto(flat, pos)
                pos += c.size
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
                android.util.Log.w("VoiceManager", "Local STT failed", e)
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
     * Stops listening. For a local recording this finalizes capture and kicks
     * off on-device transcription (the pending onResult fires when done).
     */
    fun stopListening() {
        if (isRecording) {
            isRecording = false
            try { recordingThread?.join(3000) } catch (_: Throwable) {}
            recordingThread = null
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
        if (!isTtsInitialized || textToSpeech == null) return
        val cleanSpeech = text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "코드 블록")
            .replace(Regex("[#*_\\[\\]()]"), "")
            .trim()
        // Failure or empty output must not start another microphone session.
        if (cleanSpeech.isBlank()) return
        val id = completion.begin(onComplete)
        _voiceState.value = InteractiveVoiceState.SPEAKING
        try {
            val result = textToSpeech?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, Bundle(), id)
            if (result != TextToSpeech.SUCCESS) finishSpeech(id, false)
        } catch (e: Exception) {
            finishSpeech(id, false)
            android.util.Log.w("VoiceManager", "TTS speak failed", e)
        }
    }

    fun stopSpeaking() {
        completion.cancel()
        textToSpeech?.stop()
        _voiceState.value = InteractiveVoiceState.IDLE
    }

    fun setVoiceState(state: InteractiveVoiceState) { _voiceState.value = state }

    fun toggleVoiceModelInstall(templateId: String) {
        _installedVoiceTemplates.value = _installedVoiceTemplates.value.map { item ->
            if (item.id == templateId) item.copy(isInstalled = !item.isInstalled) else item
        }
    }

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
        try { ioScope.cancel() } catch (_: Throwable) {}
    }
}
