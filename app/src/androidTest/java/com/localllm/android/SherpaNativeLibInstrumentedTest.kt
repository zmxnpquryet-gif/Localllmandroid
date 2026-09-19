package com.localllm.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the vendored sherpa-onnx AAR (fetched by the Gradle `fetchSherpaAar` task):
 * the packaged native libraries must load for the device ABI, otherwise on-device
 * speech recognition dies at runtime rather than at build time.
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
}
