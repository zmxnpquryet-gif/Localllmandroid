package com.localllm.android

import android.app.Application
import android.util.Log
import com.localllm.android.engine.LlmEngine
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot

/**
 * Owns process-wide services and records memory failures before the Compose UI exists.
 * The package name remains stable so existing OffconAI installations can upgrade in place.
 */
class OffconAIApplication : Application() {

    companion object {
        private const val TAG = "OffconAIApplication"
        private const val TRIM_MEMORY_RUNNING_LOW = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL = 15

        @Volatile
        private var startupReport: MemoryGuardStore.StartupReport? = null

        fun consumeStartupReport(): MemoryGuardStore.StartupReport? {
            val report = startupReport
            startupReport = null
            return report
        }
    }

    /**
     * The single on-device inference engine. The API foreground service borrows this
     * instance after the UI closes; a second engine would allocate another native model.
     */
    val llmEngine: LlmEngine by lazy { LlmEngine(this) }

    override fun onCreate() {
        super.onCreate()
        val guard = MemoryGuard.init(this)
        startupReport = guard.onAppStart()
        installCrashRecorder()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level !in TRIM_MEMORY_RUNNING_LOW..TRIM_MEMORY_RUNNING_CRITICAL) return
        try {
            MemoryGuard.get()?.record(
                cause = MemoryGuardStore.Cause.TRIM_CRITICAL,
                detail = "onTrimMemory level=$level"
            )
        } catch (t: Throwable) {
            Log.w(TAG, "trim record failed: ${t.message}")
        }
    }

    private fun installCrashRecorder() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val guard = MemoryGuard.get()
                val isOom = throwable is OutOfMemoryError || generateSequence(throwable.cause) { it.cause }
                    .any { it is OutOfMemoryError }
                if (guard != null && isOom) {
                    val snapshot = MemorySnapshot.read(this)
                    guard.record(
                        cause = MemoryGuardStore.Cause.OOM_ERROR,
                        detail = "${throwable.javaClass.simpleName}: ${throwable.message} " +
                            "avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory}",
                        modelName = null,
                        raiseLevel = true
                    )
                    guard.markInferenceEnd()
                } else if (guard != null) {
                    guard.record(
                        cause = MemoryGuardStore.Cause.MANUAL,
                        detail = "uncaught ${throwable.javaClass.simpleName}: ${throwable.message}"
                    )
                }
            } catch (t: Throwable) {
                Log.w(TAG, "crash record failed: ${t.message}")
            }
            previous?.uncaughtException(thread, throwable)
        }
    }
}
