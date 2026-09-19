package com.localllm.android.voice

/** Main-thread confined; only a successful, current utterance may resume listening. */
internal class SpeechCompletionTracker {
    private var sequence = 0L
    private var currentId: String? = null
    private var callback: (() -> Unit)? = null

    fun begin(onComplete: (() -> Unit)?): String {
        cancel()
        val id = "LocalLlmSpeech_${++sequence}"
        currentId = id
        callback = onComplete
        return id
    }

    fun isCurrent(id: String?): Boolean = id != null && id == currentId

    fun finish(id: String?, successful: Boolean) {
        if (!isCurrent(id)) return
        val action = if (successful) callback else null
        cancel()
        action?.invoke()
    }

    fun cancel() {
        currentId = null
        callback = null
    }
}
