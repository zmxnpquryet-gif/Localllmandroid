package com.example.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.model.VoiceModelTemplate
import com.example.model.VoiceTemplates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class InteractiveVoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING
}

class VoiceManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null
    private var isTtsInitialized = false

    private val _voiceState = MutableStateFlow(InteractiveVoiceState.IDLE)
    val voiceState: StateFlow<InteractiveVoiceState> = _voiceState.asStateFlow()

    private val _recognizedText = MutableStateFlow("")
    val recognizedText: StateFlow<String> = _recognizedText.asStateFlow()

    private val _audioAmplitude = MutableStateFlow(0f)
    val audioAmplitude: StateFlow<Float> = _audioAmplitude.asStateFlow()

    private val _installedVoiceTemplates = MutableStateFlow(VoiceTemplates.templates)
    val installedVoiceTemplates: StateFlow<List<VoiceModelTemplate>> = _installedVoiceTemplates.asStateFlow()

    init {
        initTts()
    }

    private fun initTts() {
        textToSpeech = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = textToSpeech?.setLanguage(Locale.KOREAN)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    textToSpeech?.setLanguage(Locale.US)
                }
                isTtsInitialized = true
                textToSpeech?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        _voiceState.value = InteractiveVoiceState.SPEAKING
                    }

                    override fun onDone(utteranceId: String?) {
                        _voiceState.value = InteractiveVoiceState.IDLE
                    }

                    override fun onError(utteranceId: String?) {
                        _voiceState.value = InteractiveVoiceState.IDLE
                    }
                })
            }
        }
    }

    fun startListening(onResult: (String) -> Unit, onError: (String) -> Unit) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onError("음성 인식을 지원하지 않는 기기입니다.")
            return
        }

        stopListening()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _voiceState.value = InteractiveVoiceState.LISTENING
                }

                override fun onBeginningOfSpeech() {
                    _voiceState.value = InteractiveVoiceState.LISTENING
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // Amplitude between 0.0 and 1.0
                    val normalized = ((rmsdB + 2f) / 12f).coerceIn(0.1f, 1f)
                    _audioAmplitude.value = normalized
                }

                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _audioAmplitude.value = 0f
                }

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
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""
                    _recognizedText.value = text
                    if (text.isNotBlank()) {
                        onResult(text)
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    matches?.firstOrNull()?.let {
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
        }
        speechRecognizer?.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        speechRecognizer?.destroy()
        speechRecognizer = null
        _voiceState.value = InteractiveVoiceState.IDLE
        _audioAmplitude.value = 0f
    }

    fun speak(text: String, onComplete: (() -> Unit)? = null) {
        if (!isTtsInitialized || textToSpeech == null) {
            onComplete?.invoke()
            return
        }

        stopSpeaking()
        _voiceState.value = InteractiveVoiceState.SPEAKING

        // Strip markdown and thinking tags for TTS speech
        val cleanSpeech = text
            .replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .replace(Regex("`{1,3}[^`]*`{1,3}"), "코드 블록")
            .replace(Regex("[#*_\\[\\]()]"), "")
            .trim()

        val params = Bundle()
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LocalLlmSpeech_${System.currentTimeMillis()}")

        textToSpeech?.speak(cleanSpeech, TextToSpeech.QUEUE_FLUSH, params, "LocalLlmSpeech")
    }

    fun stopSpeaking() {
        textToSpeech?.stop()
        _voiceState.value = InteractiveVoiceState.IDLE
    }

    fun setVoiceState(state: InteractiveVoiceState) {
        _voiceState.value = state
    }

    fun toggleVoiceModelInstall(templateId: String) {
        _installedVoiceTemplates.value = _installedVoiceTemplates.value.map { item ->
            if (item.id == templateId) {
                item.copy(isInstalled = !item.isInstalled)
            } else item
        }
    }

    fun release() {
        stopListening()
        stopSpeaking()
        textToSpeech?.shutdown()
        textToSpeech = null
    }
}
