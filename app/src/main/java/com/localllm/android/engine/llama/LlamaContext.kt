package com.localllm.android.engine.llama

import android.net.Uri
import androidx.annotation.Keep

/**
 * Minimal bridge to the upstream llama.cpp runtime.
 *
 * The app historically used a third-party `llamacpp-kotlin` wrapper. This class
 * keeps the small part of that API the engine actually consumed, while the
 * native side is the pinned upstream `ggml-org/llama.cpp` source tree in
 * `app/src/main/cpp/llama.cpp`.
 *
 * Native sessions live in a registry keyed by an opaque id, so a stop/free from
 * another thread can never dereference a session that is being destroyed.
 */
@Keep
class LlamaContext(params: Map<String, Any>) {

    companion object {
        const val STATUS_OK = 0
        const val STATUS_NOT_READY = 1
        const val STATUS_BUSY = 2
        const val STATUS_PROMPT_FAILED = 3
        const val STATUS_DECODE_FAILED = 4
        const val STATUS_CONTEXT_OVERFLOW = 5
        const val STATUS_MULTIMODAL_FAILED = 6
        const val STATUS_OUT_OF_MEMORY = 7
    }

    @Volatile
    private var handle: Long = 0L

    @Volatile
    private var tokenCallback: ((String) -> Unit)? = null

    val context: Long
        get() = handle

    init {
        val modelPath = resolvePath(params["model_path"] as? String ?: params["model"] as? String)
        val mmprojPath = resolvePath(params["mmproj_path"] as? String)

        if (modelPath != null) {
            handle = nativeCreate(
                modelPath,
                mmprojPath,
                (params["n_ctx"] as? Number)?.toInt() ?: 2048,
                (params["n_batch"] as? Number)?.toInt() ?: 512,
                (params["n_threads"] as? Number)?.toInt() ?: 4,
                (params["n_gpu_layers"] as? Number)?.toInt() ?: 0,
                params["use_mmap"] as? Boolean ?: true,
                params["use_mlock"] as? Boolean ?: false,
                params["vocab_only"] as? Boolean ?: false
            )
        }
    }

    /**
     * Runs one blocking completion. [onToken] receives complete UTF-8 chunks;
     * the native loop clears the KV cache before every prompt, so prompts are
     * self-contained.
     *
     * @throws CompletionException when the native loop cannot start or fails.
     */
    fun completion(params: Map<String, Any>, onToken: (String) -> Unit) {
        val activeHandle = handle
        check(activeHandle != 0L) { "llama.cpp context is not loaded" }
        val prompt = params["prompt"] as? String
            ?: throw IllegalArgumentException("prompt is required")

        tokenCallback = onToken
        try {
            val status = nativeCompletion(
                activeHandle,
                prompt,
                params["image_path"] as? String,
                (params["temperature"] as? Number)?.toFloat() ?: 0.8f,
                (params["top_k"] as? Number)?.toInt() ?: 40,
                (params["top_p"] as? Number)?.toFloat() ?: 0.95f,
                (params["repetition_penalty"] as? Number)?.toFloat() ?: 1.0f,
                (params["n_predict"] as? Number)?.toInt() ?: -1,
                this
            )
            if (status != STATUS_OK) throw CompletionException(status)
        } finally {
            tokenCallback = null
        }
    }

    fun tokenize(text: String): List<Int> {
        val activeHandle = handle
        if (activeHandle == 0L) return emptyList()
        return nativeTokenize(activeHandle, text).toList()
    }

    fun stopCompletion() {
        val activeHandle = handle
        if (activeHandle != 0L) nativeStop(activeHandle)
    }

    fun isPredicting(): Boolean {
        val activeHandle = handle
        return activeHandle != 0L && nativeIsPredicting(activeHandle)
    }

    @Synchronized
    fun release() {
        val activeHandle = handle
        handle = 0L
        if (activeHandle != 0L) nativeFree(activeHandle)
        tokenCallback = null
    }

    /**
     * Called from JNI with a complete UTF-8 byte sequence. Decoding here (rather
     * than in native code) keeps 4-byte code points such as emoji intact.
     */
    @Keep
    fun emitTokenBytes(bytes: ByteArray) {
        tokenCallback?.invoke(String(bytes, Charsets.UTF_8))
    }

    private fun resolvePath(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val uri = Uri.parse(value)
            if (uri.scheme == "file") uri.path else value
        } catch (_: Throwable) {
            value
        }
    }

    private external fun nativeCreate(
        modelPath: String,
        mmprojPath: String?,
        nCtx: Int,
        nBatch: Int,
        nThreads: Int,
        nGpuLayers: Int,
        useMmap: Boolean,
        useMlock: Boolean,
        vocabOnly: Boolean
    ): Long

    private external fun nativeFree(handle: Long)
    private external fun nativeTokenize(handle: Long, text: String): IntArray
    private external fun nativeCompletion(
        handle: Long,
        prompt: String,
        imagePath: String?,
        temperature: Float,
        topK: Int,
        topP: Float,
        repetitionPenalty: Float,
        nPredict: Int,
        callbackTarget: LlamaContext
    ): Int
    private external fun nativeStop(handle: Long)
    private external fun nativeIsPredicting(handle: Long): Boolean
}

@Keep
class CompletionException(val status: Int) : IllegalStateException(
    when (status) {
        LlamaContext.STATUS_NOT_READY -> "llama.cpp context is not ready"
        LlamaContext.STATUS_BUSY -> "llama.cpp context is busy"
        LlamaContext.STATUS_PROMPT_FAILED -> "llama.cpp prompt evaluation failed"
        LlamaContext.STATUS_DECODE_FAILED -> "llama.cpp decode failed"
        LlamaContext.STATUS_CONTEXT_OVERFLOW -> "llama.cpp context window is full"
        LlamaContext.STATUS_MULTIMODAL_FAILED -> "llama.cpp multimodal evaluation failed"
        LlamaContext.STATUS_OUT_OF_MEMORY -> "llama.cpp ran out of memory"
        else -> "llama.cpp completion failed (status=$status)"
    }
)

object LlamaRuntime {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        System.loadLibrary("llama_jni")
        loaded = true
    }
}
