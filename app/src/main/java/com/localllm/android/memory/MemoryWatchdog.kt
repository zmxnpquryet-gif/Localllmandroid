package com.localllm.android.memory

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polls device memory while an inference runs and fires once when the process is
 * about to be killed. Stopping the generation ourselves keeps the session alive
 * with a recorded reason instead of letting the OS take the whole app down.
 */
class MemoryWatchdog(
    private val scope: CoroutineScope,
    private val memory: () -> MemorySnapshot,
    private val intervalMs: Long = 2_000L,
    private val onCritical: (MemorySnapshot) -> Unit
) {
    companion object {
        fun isCritical(snapshot: MemorySnapshot): Boolean {
            if (snapshot.unknown || snapshot.totalMb <= 0) return false
            val floor = maxOf(200L, (snapshot.totalMb * 0.06).toLong())
            return snapshot.lowMemory || snapshot.availMb < floor
        }
    }

    private val lock = Any()
    private var job: Job? = null
    private var fired = false

    val isRunning: Boolean get() = job != null

    fun start() {
        synchronized(lock) {
            if (job != null) return
            fired = false
            job = scope.launch {
                while (isActive) {
                    delay(intervalMs)
                    val snapshot = memory()
                    if (isCritical(snapshot)) {
                        if (!fired) {
                            fired = true
                            onCritical(snapshot)
                        }
                        return@launch
                    }
                }
            }
        }
    }

    fun stop() {
        synchronized(lock) {
            job?.cancel()
            job = null
        }
    }
}
