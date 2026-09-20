package com.localllm.android

import android.app.Application
import android.util.Log
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot

/**
 * Registers the memory guard before any UI exists: the previous run's abnormal
 * end is detected here, and a Java-level OOM is recorded with the memory state
 * at the moment of the crash (Android's own LMK kill leaves no callback at all,
 * which is why the on-disk marker in [MemoryGuardStore] exists as well).
 */
class LocalLlmApp : Application() {

    companion object {
        private const val TAG = "LocalLlmApp"

        /** Values of ComponentCallbacks2's (deprecated) running-trim levels. */
        private const val TRIM_MEMORY_RUNNING_LOW = 10
        private const val TRIM_MEMORY_RUNNING_CRITICAL = 15

        @Volatile
        private var startupReport: MemoryGuardStore.StartupReport? = null

        /** Returns the startup report once, so a single banner is shown per launch. */
        fun consumeStartupReport(): MemoryGuardStore.StartupReport? {
            val report = startupReport
            startupReport = null
            return report
        }
    }

    override fun onCreate() {
        super.onCreate()
        val guard = MemoryGuard.init(this)
        startupReport = guard.onAppStart()
        installCrashRecorder()
    }

    /**
     * Only the "running" trim levels describe memory pressure (5 = moderate,
     * 10 = low, 15 = critical). The higher values (20 = UI hidden, 40 = background,
     * 60/80 = cached/empty) are visibility signals that every app receives when the
     * user leaves it — recording those would fill the incident history with noise,
     * which the first real-device run showed immediately.
     */
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
