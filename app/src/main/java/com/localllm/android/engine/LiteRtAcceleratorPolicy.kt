package com.localllm.android.engine

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

import java.io.File

/**
 * Builds the LiteRT-LM backend candidate list in priority order.
 *
 * Split out of the engine so the accelerator gating is unit-testable: `Backend.GPU()`
 * is OpenCL-based on Android, so offering it on a device without an OpenCL driver only
 * produces a native "OpenCL" failure before falling back to CPU. The candidate list
 * therefore depends on what the device actually exposes.
 */
object LiteRtAcceleratorPolicy {

    private val OPENCL_PATHS = listOf(
        "/vendor/lib64/libOpenCL.so",
        "/vendor/lib/libOpenCL.so",
        "/system/lib64/libOpenCL.so",
        "/system/lib/libOpenCL.so",
        "/system/vendor/lib64/libOpenCL.so",
        "/system/vendor/lib/libOpenCL.so",
        "/odm/lib64/libOpenCL.so",
        "/odm/lib/libOpenCL.so"
    )

    private val NPU_LIB_PREFIXES = listOf("libQnn", "libneuron", "libNEURON", "libapuware")

    enum class Kind { GPU, NPU, CPU }

    data class Candidate(
        val label: String,
        val kind: Kind,
        val withVision: Boolean,
        val maxNumTokens: Int?,
        val useCacheDir: Boolean,
        val threadCount: Int
    )

    fun hasOpenClDriver(): Boolean = OPENCL_PATHS.any { path ->
        try {
            File(path).exists()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasNpuSupport(nativeLibraryDir: String?): Boolean {
        if (nativeLibraryDir.isNullOrBlank()) return false
        return try {
            val dir = File(nativeLibraryDir)
            dir.isDirectory && dir.listFiles()?.any { file ->
                NPU_LIB_PREFIXES.any { prefix -> file.name.startsWith(prefix) }
            } == true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * @param gpuAllowed user setting (and not already known broken on this device)
     * @param hasOpenCl OpenCL driver present
     * @param hasNpu vendor NPU runtime present in the app's native lib dir
     */
    fun buildCandidates(
        gpuAllowed: Boolean,
        hasOpenCl: Boolean,
        hasNpu: Boolean,
        hasVision: Boolean,
        maxTokens: Int,
        threadCount: Int
    ): List<Candidate> {
        val candidates = mutableListOf<Candidate>()
        val gpuUsable = gpuAllowed && hasOpenCl
        if (gpuUsable) {
            if (hasVision) {
                candidates.add(
                    Candidate(AppStrings.get(R.string.pol_gpu_vision), Kind.GPU, withVision = true, maxNumTokens = maxTokens, useCacheDir = true, threadCount = threadCount)
                )
            }
            candidates.add(
                Candidate(AppStrings.get(R.string.pol_gpu, maxTokens), Kind.GPU, withVision = false, maxNumTokens = maxTokens, useCacheDir = true, threadCount = threadCount)
            )
        }
        if (gpuAllowed && hasNpu) {
            candidates.add(
                Candidate(AppStrings.get(R.string.pol_npu, maxTokens), Kind.NPU, withVision = false, maxNumTokens = maxTokens, useCacheDir = true, threadCount = threadCount)
            )
        }
        if (hasVision) {
            candidates.add(
                Candidate(AppStrings.get(R.string.pol_cpu_mt_vision, threadCount), Kind.CPU, withVision = true, maxNumTokens = maxTokens, useCacheDir = true, threadCount = threadCount)
            )
        }
        candidates.add(
            Candidate(AppStrings.get(R.string.pol_cpu_mt, threadCount, maxTokens), Kind.CPU, withVision = false, maxNumTokens = maxTokens, useCacheDir = true, threadCount = threadCount)
        )
        candidates.add(
            Candidate(AppStrings.get(R.string.pol_cpu_ctx, threadCount), Kind.CPU, withVision = false, maxNumTokens = null, useCacheDir = false, threadCount = threadCount)
        )
        candidates.add(
            Candidate(AppStrings.get(R.string.pol_cpu_default), Kind.CPU, withVision = false, maxNumTokens = null, useCacheDir = false, threadCount = threadCount)
        )
        return candidates
    }

    /** OpenCL/driver faults are worth remembering; a one-off model problem is not. */
    fun isDriverRelatedFailure(message: String?): Boolean {
        val lower = message?.lowercase() ?: return false
        return lower.contains("opencl") ||
                lower.contains("clgetplatform") ||
                lower.contains("clcreate") ||
                lower.contains("egl") ||
                lower.contains("gpu") ||
                lower.contains("delegate")
    }
}
