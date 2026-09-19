package com.localllm.android.voice

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineStream
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
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
 * Fully on-device speech recognition (sherpa-onnx + Whisper tiny multilingual).
 *
 * No system recognizer, no keyboard STT, no Google service involved: 16kHz PCM
 * captured via AudioRecord is decoded locally. The ~75MB model downloads once
 * into the app's private files dir on explicit user request.
 */
class LocalSttEngine(private val context: Context) {

    companion object {
        const val TAG = "LocalSttEngine"
        const val SAMPLE_RATE = 16000
        const val MODEL_DIR_NAME = "stt-whisper-tiny"
        private const val BASE_URL =
            "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny/resolve/main"

        val REQUIRED_FILES = mapOf(
            "tiny-encoder.int8.onnx" to "$BASE_URL/tiny-encoder.int8.onnx",
            "tiny-decoder.int8.onnx" to "$BASE_URL/tiny-decoder.int8.onnx",
            "tiny-tokens.txt" to "$BASE_URL/tiny-tokens.txt"
        )

        /** Korean-first language hint; whisper tiny is multilingual regardless. */
        const val DEFAULT_LANGUAGE = "ko"
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
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isModelDownloaded(): Boolean {
        val dir = modelDir()
        return REQUIRED_FILES.keys.all { name ->
            val f = File(dir, name)
            f.isFile && f.length() > 1024
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
                if (target.isFile && target.length() > 1024) {
                    _modelState.value = ModelState.Downloading((index + 1).toFloat() / entries.size)
                    continue
                }
                val tmp = File(dir, entry.key + ".downloading")
                val request = Request.Builder().url(entry.value)
                    .header("User-Agent", "LocalLLM-Android-STT/1.0").build()
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val body = resp.body ?: throw IllegalStateException("Empty body")
                    val total = body.contentLength().takeIf { it > 0 } ?: -1L
                    var done = 0L
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(256 * 1024)
                        while (true) {
                            val n = body.byteStream().read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val frac = (index + done.toFloat() / total) / entries.size
                                _modelState.value = ModelState.Downloading(frac.coerceIn(0f, 1f))
                            }
                        }
                    }
                }
                if (!tmp.renameTo(target)) throw IllegalStateException("Rename failed")
            }
            releaseRecognizer()
            _modelState.value = ModelState.Ready
            true
        } catch (e: Exception) {
            Log.w(TAG, "STT model download failed", e)
            _modelState.value = ModelState.Failed(e.localizedMessage ?: "다운로드 실패")
            false
        }
    }

    suspend fun deleteModel() = withContext(Dispatchers.IO) {
        releaseRecognizer()
        modelDir().deleteRecursively()
        _modelState.value = ModelState.Missing
    }

    @Synchronized
    private fun ensureRecognizer(): OfflineRecognizer? {
        recognizer?.let { return it }
        if (!isModelDownloaded()) return null
        return try {
            val dir = modelDir()
            val whisper = OfflineWhisperModelConfig(
                encoder = File(dir, "tiny-encoder.int8.onnx").absolutePath,
                decoder = File(dir, "tiny-decoder.int8.onnx").absolutePath,
                language = DEFAULT_LANGUAGE,
                task = "transcribe"
            )
            val model = OfflineModelConfig(
                whisper = whisper,
                tokens = File(dir, "tiny-tokens.txt").absolutePath,
                numThreads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            )
            val config = OfflineRecognizerConfig()
            config.modelConfig = model
            OfflineRecognizer(null, config).also { recognizer = it }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to init sherpa-onnx recognizer", e)
            null
        }
    }

    /** Transcribes 16kHz mono float samples. Empty string = nothing recognized. */
    suspend fun transcribe(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): String =
        withContext(Dispatchers.IO) {
            val rec = ensureRecognizer() ?: throw IllegalStateException("로컬 STT 모델이 없습니다.")
            var stream: OfflineStream? = null
            try {
                stream = rec.createStream()
                val resampled = if (sampleRate == SAMPLE_RATE) samples
                else resample(samples, sampleRate, SAMPLE_RATE)
                // Feed in chunks so a multi-minute capture never spikes memory.
                var offset = 0
                while (offset < resampled.size) {
                    val end = minOf(offset + SAMPLE_RATE * 15, resampled.size)
                    stream.acceptWaveform(resampled.copyOfRange(offset, end), SAMPLE_RATE)
                    offset = end
                }
                rec.decode(stream)
                rec.getResult(stream).text.trim()
            } finally {
                try {
                    stream?.release()
                } catch (_: Throwable) {
                }
            }
        }

    private fun resample(input: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        if (input.isEmpty() || fromHz <= 0) return input
        val ratio = toHz.toDouble() / fromHz
        val outSize = (input.size * ratio).toInt().coerceAtLeast(1)
        return FloatArray(outSize) { i ->
            val pos = i / ratio
            val p0 = pos.toInt().coerceIn(0, input.size - 1)
            val p1 = (p0 + 1).coerceIn(0, input.size - 1)
            val frac = (pos - p0).toFloat()
            input[p0] * (1 - frac) + input[p1] * frac
        }
    }

    @Synchronized
    private fun releaseRecognizer() {
        try {
            recognizer?.release()
        } catch (_: Throwable) {
        }
        recognizer = null
    }

    fun release() {
        releaseRecognizer()
        try {
            http.dispatcher.executorService.shutdown()
        } catch (_: Throwable) {
        }
    }
}
