package com.localllm.android.memory

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.localllm.android.model.GenerationSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Snapshot of device memory. [unknown] means the platform refused to report values. */
data class MemorySnapshot(
    val availMb: Long,
    val totalMb: Long,
    val lowMemory: Boolean,
    val unknown: Boolean = false
) {
    companion object {
        const val MB = 1024L * 1024L

        fun unknown(): MemorySnapshot = MemorySnapshot(availMb = 0, totalMb = 0, lowMemory = false, unknown = true)

        fun read(context: Context): MemorySnapshot = try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                ?: return unknown()
            val info = ActivityManager.MemoryInfo()
            manager.getMemoryInfo(info)
            MemorySnapshot(
                availMb = info.availMem / MB,
                totalMb = info.totalMem / MB,
                lowMemory = info.lowMemory
            )
        } catch (t: Throwable) {
            Log.w("MemoryGuard", "Memory info unavailable: ${t.message}")
            unknown()
        }
    }
}

/**
 * Persistent memory-protection state.
 *
 * Android kills a process that runs out of memory without giving it a chance to
 * react, so "protection" cannot only be a pre-flight check: it has to survive the
 * kill. A marker file is written while an inference is in flight and removed when
 * it ends; a marker found at startup therefore means the previous process died
 * mid-inference (LMK kill, native crash, force stop). Each such death raises the
 * degradation level, which lowers the memory-hungry settings for the next run.
 */
class MemoryGuardStore(
    private val rootDir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val memory: () -> MemorySnapshot = { MemorySnapshot.unknown() }
) {
    companion object {
        const val MAX_LEVEL = 4
        private const val STATE_FILE = "memory_guard.json"
        private const val LOG_FILE = "memory_guard.log"
        private const val MARKER_FILE = "inference_guard.marker"
        private const val MAX_INCIDENTS = 20
        private const val MAX_LOG_BYTES = 256L * 1024L
    }

    enum class Cause {
        OOM_ERROR,
        ABNORMAL_EXIT,
        LOW_MEMORY_STOP,
        LOAD_REFUSED,
        TRIM_CRITICAL,
        MANUAL
    }

    data class Incident(
        val timeMs: Long,
        val cause: Cause,
        val detail: String,
        val modelName: String?,
        val level: Int,
        val availMb: Long,
        val totalMb: Long
    )

    data class StartupReport(
        val abnormalPreviousRun: Boolean,
        val level: Int,
        val enabled: Boolean,
        val lastIncident: Incident?
    )

    private val lock = Any()
    private var loaded = false
    private var level = 0
    private var enabled = true
    private val incidents = mutableListOf<Incident>()

    private val stateFile: File get() = File(rootDir, STATE_FILE)
    private val logFile: File get() = File(rootDir, LOG_FILE)
    private val markerFile: File get() = File(rootDir, MARKER_FILE)

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (stateFile.isFile) {
                val root = JSONObject(stateFile.readText())
                level = root.optInt("level", 0).coerceIn(0, MAX_LEVEL)
                enabled = root.optBoolean("enabled", true)
                val array = root.optJSONArray("incidents") ?: JSONArray()
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    incidents.add(
                        Incident(
                            timeMs = obj.optLong("timeMs", 0L),
                            cause = runCatching { Cause.valueOf(obj.optString("cause")) }.getOrDefault(Cause.MANUAL),
                            detail = obj.optString("detail", ""),
                            modelName = obj.optString("modelName", "").ifBlank { null },
                            level = obj.optInt("level", 0),
                            availMb = obj.optLong("availMb", 0L),
                            totalMb = obj.optLong("totalMb", 0L)
                        )
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w("MemoryGuard", "State load failed: ${t.message}")
        }
    }

    private fun persistLocked() {
        try {
            val root = JSONObject()
            root.put("level", level)
            root.put("enabled", enabled)
            val array = JSONArray()
            incidents.takeLast(MAX_INCIDENTS).forEach { incident ->
                array.put(
                    JSONObject()
                        .put("timeMs", incident.timeMs)
                        .put("cause", incident.cause.name)
                        .put("detail", incident.detail)
                        .put("modelName", incident.modelName ?: "")
                        .put("level", incident.level)
                        .put("availMb", incident.availMb)
                        .put("totalMb", incident.totalMb)
                )
            }
            root.put("incidents", array)
            if (!rootDir.exists()) rootDir.mkdirs()
            stateFile.writeText(root.toString(2))
        } catch (t: Throwable) {
            Log.w("MemoryGuard", "State save failed: ${t.message}")
        }
    }

    private fun stamp(timeMs: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(timeMs))

    private fun appendLogLocked(line: String) {
        try {
            if (!rootDir.exists()) rootDir.mkdirs()
            if (logFile.isFile && logFile.length() > MAX_LOG_BYTES) {
                val tail = logFile.readLines().takeLast(200)
                logFile.writeText(tail.joinToString("\n", postfix = "\n"))
            }
            logFile.appendText(line + "\n")
        } catch (t: Throwable) {
            Log.w("MemoryGuard", "Log append failed: ${t.message}")
        }
    }

    fun level(): Int {
        ensureLoaded()
        return level
    }

    fun isEnabled(): Boolean {
        ensureLoaded()
        return enabled
    }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            ensureLoaded()
            enabled = value
            appendLogLocked("${stamp(clock())} [cfg] enabled=$value level=$level")
            persistLocked()
        }
    }

    fun incidents(): List<Incident> {
        ensureLoaded()
        return incidents.toList()
    }

    /** Reads the in-flight marker; a leftover marker means the last run died during inference. */
    fun onAppStart(): StartupReport {
        synchronized(lock) {
            ensureLoaded()
            val snapshot = memory()
            val marker = markerFile.takeIf { it.isFile }
            var abnormal = false
            if (marker != null) {
                val payload = try {
                    JSONObject(marker.readText())
                } catch (_: Throwable) {
                    JSONObject()
                }
                val modelName = payload.optString("modelName", "").ifBlank { null }
                val detail = buildString {
                    append("previous process ended during inference")
                    val runtime = payload.optString("runtime", "")
                    if (runtime.isNotBlank()) append(" runtime=$runtime")
                    val ctx = payload.optInt("contextWindow", -1)
                    if (ctx > 0) append(" ctx=$ctx")
                    val gpuLayers = payload.optInt("gpuLayers", -1)
                    if (gpuLayers >= 0) append(" gpuLayers=$gpuLayers")
                    val started = payload.optLong("startedAtMs", 0L)
                    if (started > 0) append(" started=${stamp(started)}")
                }
                marker.delete()
                abnormal = true
                record(cause = Cause.ABNORMAL_EXIT, detail = detail, modelName = modelName, raiseLevel = true)
            }
            appendLogLocked(
                "${stamp(clock())} [boot] abnormal=$abnormal level=$level enabled=$enabled " +
                        "avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory}"
            )
            return StartupReport(abnormal, level, enabled, incidents.lastOrNull())
        }
    }

    fun markInferenceStart(
        modelName: String?,
        runtime: String,
        contextWindow: Int,
        gpuLayers: Int,
        gpuAcceleration: Boolean
    ) {
        synchronized(lock) {
            ensureLoaded()
            try {
                if (!rootDir.exists()) rootDir.mkdirs()
                val payload = JSONObject()
                    .put("modelName", modelName ?: "")
                    .put("runtime", runtime)
                    .put("contextWindow", contextWindow)
                    .put("gpuLayers", gpuLayers)
                    .put("gpuAcceleration", gpuAcceleration)
                    .put("startedAtMs", clock())
                markerFile.writeText(payload.toString())
                appendLogLocked(
                    "${stamp(clock())} [infer] start model=${modelName ?: "?"} runtime=$runtime " +
                            "ctx=$contextWindow gpuLayers=$gpuLayers gpu=$gpuAcceleration level=$level"
                )
            } catch (t: Throwable) {
                Log.w("MemoryGuard", "Marker write failed: ${t.message}")
            }
        }
    }

    /** Removes the in-flight marker: this run finished its inference normally. */
    fun markInferenceEnd() {
        synchronized(lock) {
            ensureLoaded()
            try {
                if (markerFile.exists()) markerFile.delete()
            } catch (t: Throwable) {
                Log.w("MemoryGuard", "Marker clear failed: ${t.message}")
            }
        }
    }

    fun record(
        cause: Cause,
        detail: String,
        modelName: String? = null,
        raiseLevel: Boolean = false
    ): Incident {
        synchronized(lock) {
            ensureLoaded()
            val snapshot = memory()
            if (raiseLevel && enabled) {
                level = (level + 1).coerceAtMost(MAX_LEVEL)
            }
            val incident = Incident(
                timeMs = clock(),
                cause = cause,
                detail = detail,
                modelName = modelName,
                level = level,
                availMb = snapshot.availMb,
                totalMb = snapshot.totalMb
            )
            incidents.add(incident)
            while (incidents.size > MAX_INCIDENTS) incidents.removeAt(0)
            appendLogLocked(
                "${stamp(incident.timeMs)} [${incident.cause}] level=$level model=${modelName ?: "?"} " +
                        "avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory} :: $detail"
            )
            persistLocked()
            return incident
        }
    }

    fun reset() {
        synchronized(lock) {
            ensureLoaded()
            level = 0
            incidents.clear()
            try {
                if (markerFile.exists()) markerFile.delete()
            } catch (_: Throwable) {
            }
            appendLogLocked("${stamp(clock())} [reset] level=0")
            persistLocked()
        }
    }

    fun logTail(maxLines: Int = 200): String {
        return try {
            if (!logFile.isFile) "" else logFile.readLines().takeLast(maxLines).joinToString("\n")
        } catch (t: Throwable) {
            Log.w("MemoryGuard", "Log read failed: ${t.message}")
            ""
        }
    }

    /** Cumulative degradation ladder: each level adds a reduction on top of the previous one. */
    fun applyLevel(settings: GenerationSettings): GenerationSettings {
        ensureLoaded()
        if (!enabled || level <= 0) return settings
        return when (level) {
            1 -> settings.copy(enableMtp = false)
            2 -> settings.copy(enableMtp = false, enableIndexingAcceleration = false)
            3 -> settings.copy(
                enableMtp = false,
                enableIndexingAcceleration = false,
                contextWindow = minOf(settings.contextWindow, 2048)
            )
            else -> settings.copy(
                enableMtp = false,
                enableIndexingAcceleration = false,
                contextWindow = minOf(settings.contextWindow, 1024),
                enableGpuAcceleration = false,
                gpuLayers = 0
            )
        }
    }

    /** Context window cap implied by the current level, used by the engines as a second gate. */
    fun contextCap(): Int = when {
        !isEnabled() -> Int.MAX_VALUE
        level >= MAX_LEVEL -> 1024
        level == 3 -> 2048
        else -> Int.MAX_VALUE
    }

    fun shouldUseGpu(): Boolean = isEnabled() && level < MAX_LEVEL
}

/** Process-wide handle. [init] must run before anything reads the guard. */
object MemoryGuard {
    private var store: MemoryGuardStore? = null

    fun init(context: Context): MemoryGuardStore {
        store?.let { return it }
        val appContext = context.applicationContext
        val created = MemoryGuardStore(
            rootDir = appContext.filesDir,
            memory = { MemorySnapshot.read(appContext) }
        )
        store = created
        return created
    }

    fun get(): MemoryGuardStore? = store

    fun require(): MemoryGuardStore = store
        ?: error("MemoryGuard.init() must be called from Application.onCreate()")
}
