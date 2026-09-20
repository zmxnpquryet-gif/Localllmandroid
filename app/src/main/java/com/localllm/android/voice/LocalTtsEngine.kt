package com.localllm.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Korean neural text-to-speech, fully on-device (sherpa-onnx + supertonic-3 int8).
 *
 * The model is the officially converted sherpa-onnx build (31 languages, 10 Korean
 * speakers, ~145MB across seven files) so no phonemizer data or archive extraction
 * is needed. Synthesis streams float samples straight into playback;
 * [DEFAULT_SAMPLE_RATE] is the rate the shipped model reports (measured on device,
 * 44.1kHz) and is only used when the engine is not loaded yet — playback always asks
 * the engine for its real rate.
 */
class LocalTtsEngine(private val context: Context) {

    companion object {
        const val TAG = "LocalTtsEngine"
        const val MODEL_DIR_NAME = "tts-supertonic-3-ko"
        const val LANGUAGE = "ko"
        const val SPEAKER_COUNT = 10
        const val DEFAULT_SAMPLE_RATE = 44_100
        const val TOTAL_BYTES = 152_000_000L

        private const val BASE_URL =
            "https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main"

        val REQUIRED_FILES = mapOf(
            "duration_predictor.int8.onnx" to "$BASE_URL/duration_predictor.int8.onnx",
            "text_encoder.int8.onnx" to "$BASE_URL/text_encoder.int8.onnx",
            "vector_estimator.int8.onnx" to "$BASE_URL/vector_estimator.int8.onnx",
            "vocoder.int8.onnx" to "$BASE_URL/vocoder.int8.onnx",
            "tts.json" to "$BASE_URL/tts.json",
            "unicode_indexer.bin" to "$BASE_URL/unicode_indexer.bin",
            "voice.bin" to "$BASE_URL/voice.bin"
        )
    }

    sealed interface ModelState {
        data object Missing : ModelState
        data class Downloading(val progress: Float) : ModelState
        data object Ready : ModelState
        data class Failed(val message: String) : ModelState
    }

    private val _modelState = MutableStateFlow<ModelState>(
        if (isModelDownloaded()) ModelState.Ready else ModelState.Missing
    )
    val modelState: StateFlow<ModelState> = _modelState.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var tts: OfflineTts? = null

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelDownloaded(): Boolean {
        val dir = modelDir()
        return REQUIRED_FILES.keys.all { name ->
            val file = File(dir, name)
            file.isFile && file.length() > 0
        }
    }

    fun downloadedBytes(): Long {
        val dir = modelDir()
        return REQUIRED_FILES.keys.sumOf { name ->
            File(dir, name).takeIf { it.isFile }?.length() ?: 0L
        }
    }

    suspend fun downloadModel(): Boolean = withContext(Dispatchers.IO) {
        try {
            val dir = modelDir().apply { if (!exists()) mkdirs() }
            val entries = REQUIRED_FILES.entries.toList()
            for ((index, entry) in entries.withIndex()) {
                val target = File(dir, entry.key)
                if (target.isFile && target.length() > 0) {
                    _modelState.value = ModelState.Downloading((index + 1).toFloat() / entries.size)
                    continue
                }
                val tmp = File(dir, entry.key + ".downloading")
                val request = Request.Builder().url(entry.value)
                    .header("User-Agent", "LocalLLM-Android-TTS/1.0").build()
                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val body = response.body ?: throw IllegalStateException("Empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L
                    var done = 0L
                    FileOutputStream(tmp).use { out ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            val read = body.byteStream().read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            done += read
                            if (total > 0) {
                                val fraction = (index + done.toFloat() / total) / entries.size
                                _modelState.value = ModelState.Downloading(fraction.coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (!tmp.renameTo(target)) throw IllegalStateException("Rename failed: ${entry.key}")
            }
            releaseEngine()
            _modelState.value = ModelState.Ready
            true
        } catch (e: Exception) {
            Log.w(TAG, "TTS model download failed", e)
            _modelState.value = ModelState.Failed(e.localizedMessage ?: "다운로드 실패")
            false
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        releaseEngine()
        modelDir().deleteRecursively()
        _modelState.value = ModelState.Missing
    }

    /** Sample rate of the loaded engine; falls back to the rate the shipped model reports. */
    fun sampleRate(): Int = try {
        ensureEngine()?.sampleRate() ?: DEFAULT_SAMPLE_RATE
    } catch (_: Throwable) {
        DEFAULT_SAMPLE_RATE
    }

    /**
     * The sherpa-onnx JNI resolves this callback reflectively by the exact signature
     * `invoke([F)Ljava/lang/Integer;`. A Kotlin lambda does not survive that lookup:
     * D8 desugars it into a `$$ExternalSyntheticLambda` class without the specialized
     * method, and the JNI then aborts the process ("JNI DETECTED ERROR ... NoSuchMethodError",
     * reproduced on a Galaxy S23 FE). An explicit class keeps the method.
     */
    private class SampleCallback(private val onChunk: (FloatArray) -> Boolean) : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (onChunk(samples)) 1 else 0
    }

    /**
     * Streams synthesized samples to [onChunk]; returning false from it aborts the
     * generation immediately (used when playback is stopped by the user).
     */
    suspend fun synthesize(
        text: String,
        speakerId: Int,
        speed: Float,
        onChunk: (FloatArray) -> Boolean
    ): Boolean = withContext(Dispatchers.IO) {
        val engine = ensureEngine() ?: return@withContext false
        try {
            val speakerCount = try {
                engine.numSpeakers()
            } catch (_: Throwable) {
                0
            }
            val config = GenerationConfig(
                sid = if (speakerCount > 0) speakerId.coerceIn(0, speakerCount - 1) else 0,
                numSteps = 8,
                speed = speed.coerceIn(0.5f, 2.0f),
                extra = mapOf("lang" to LANGUAGE)
            )
            engine.generateWithConfigAndCallback(text, config, SampleCallback(onChunk))
            true
        } catch (t: Throwable) {
            Log.w(TAG, "Synthesis failed", t)
            false
        }
    }

    @Synchronized
    private fun ensureEngine(): OfflineTts? {
        tts?.let { return it }
        if (!isModelDownloaded()) return null
        return try {
            val dir = modelDir()
            val supertonic = OfflineTtsSupertonicModelConfig(
                durationPredictor = File(dir, "duration_predictor.int8.onnx").absolutePath,
                textEncoder = File(dir, "text_encoder.int8.onnx").absolutePath,
                vectorEstimator = File(dir, "vector_estimator.int8.onnx").absolutePath,
                vocoder = File(dir, "vocoder.int8.onnx").absolutePath,
                ttsJson = File(dir, "tts.json").absolutePath,
                unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
                voiceStyle = File(dir, "voice.bin").absolutePath
            )
            val modelConfig = OfflineTtsModelConfig(
                supertonic = supertonic,
                // Measured ~1.8x realtime with 4 threads on a Galaxy S23 FE, so use the
                // device's cores (capped) rather than a conservative default.
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6),
                debug = false,
                provider = "cpu"
            )
            OfflineTts(null, OfflineTtsConfig(model = modelConfig)).also { tts = it }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to init sherpa-onnx TTS", t)
            null
        }
    }

    @Synchronized
    private fun releaseEngine() {
        try {
            tts?.release()
        } catch (_: Throwable) {
        }
        tts = null
    }

    fun release() {
        releaseEngine()
        try {
            http.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {
        }
    }
}
