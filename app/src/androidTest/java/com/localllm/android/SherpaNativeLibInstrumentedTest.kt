package com.localllm.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the vendored sherpa-onnx AAR (fetched by the Gradle `fetchSherpaAar` task):
 * the packaged native libraries must load for the device ABI, otherwise on-device
 * speech recognition and speech synthesis die at runtime rather than at build time.
 */
@RunWith(AndroidJUnit4::class)
class SherpaNativeLibInstrumentedTest {

    @Test
    fun nativeLibrariesLoadForTheDeviceAbi() {
        // Dependency order: onnxruntime -> c-api -> cxx-api -> jni.
        System.loadLibrary("onnxruntime")
        System.loadLibrary("sherpa-onnx-c-api")
        System.loadLibrary("sherpa-onnx-cxx-api")
        System.loadLibrary("sherpa-onnx-jni")
    }

    @Test
    fun offlineRecognizerApiIsPackaged() {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineRecognizer")
        Class.forName("com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig")
    }

    @Test
    fun offlineTtsSupertonicApiIsPackaged() {
        Class.forName("com.k2fsa.sherpa.onnx.OfflineTts")
        Class.forName("com.k2fsa.sherpa.onnx.GenerationConfig")

        // The Korean voice the app downloads is a supertonic bundle: building its
        // config must not need anything beyond the seven model files.
        val supertonic = OfflineTtsSupertonicModelConfig(
            durationPredictor = "/tmp/duration_predictor.int8.onnx",
            textEncoder = "/tmp/text_encoder.int8.onnx",
            vectorEstimator = "/tmp/vector_estimator.int8.onnx",
            vocoder = "/tmp/vocoder.int8.onnx",
            ttsJson = "/tmp/tts.json",
            unicodeIndexer = "/tmp/unicode_indexer.bin",
            voiceStyle = "/tmp/voice.bin"
        )
        val config = OfflineTtsConfig(model = OfflineTtsModelConfig(supertonic = supertonic, numThreads = 2))
        assertEquals("/tmp/voice.bin", config.model.supertonic.voiceStyle)
    }
}
