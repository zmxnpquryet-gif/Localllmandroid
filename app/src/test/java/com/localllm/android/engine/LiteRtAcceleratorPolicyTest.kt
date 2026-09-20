package com.localllm.android.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The GPU candidate is only offered when the device actually exposes an OpenCL
 * driver, because `Backend.GPU()` is OpenCL-based on Android and otherwise fails
 * inside the native library with an "OpenCL" error before falling back to CPU.
 */
class LiteRtAcceleratorPolicyTest {

    private fun labels(gpuAllowed: Boolean, hasOpenCl: Boolean, hasNpu: Boolean = false, hasVision: Boolean = false): List<String> =
        LiteRtAcceleratorPolicy.buildCandidates(
            gpuAllowed = gpuAllowed,
            hasOpenCl = hasOpenCl,
            hasNpu = hasNpu,
            hasVision = hasVision,
            maxTokens = 4096,
            threadCount = 4
        ).map { it.label }

    @Test
    fun `no opencl driver means no gpu candidate`() {
        val labels = labels(gpuAllowed = true, hasOpenCl = false)
        assertTrue(labels.none { it.contains("GPU") })
        assertTrue(labels.any { it.contains("CPU") })
    }

    @Test
    fun `opencl driver puts gpu first and vision gpu before text gpu`() {
        val labels = labels(gpuAllowed = true, hasOpenCl = true, hasVision = true)
        assertEquals("GPU 가속 (비전 연동)", labels.first())
        assertTrue(labels[1].startsWith("GPU 가속"))
        assertTrue(labels.count { it.contains("GPU") } == 2)
    }

    @Test
    fun `user disabled gpu removes gpu and npu but keeps cpu fallbacks`() {
        val candidates = LiteRtAcceleratorPolicy.buildCandidates(
            gpuAllowed = false,
            hasOpenCl = true,
            hasNpu = true,
            hasVision = false,
            maxTokens = 2048,
            threadCount = 4
        )
        assertTrue(candidates.none { it.kind != LiteRtAcceleratorPolicy.Kind.CPU })
        val last = candidates.last()
        assertEquals(null, last.maxNumTokens)
        assertFalse(last.useCacheDir)
    }

    @Test
    fun `npu candidate only appears when a vendor runtime is present`() {
        assertTrue(labels(gpuAllowed = true, hasOpenCl = true, hasNpu = false).none { it.contains("NPU") })
        assertTrue(labels(gpuAllowed = true, hasOpenCl = true, hasNpu = true).any { it.contains("NPU") })
    }

    @Test
    fun `driver related failures are the ones worth remembering`() {
        assertTrue(LiteRtAcceleratorPolicy.isDriverRelatedFailure("Failed to create OpenCL context"))
        assertTrue(LiteRtAcceleratorPolicy.isDriverRelatedFailure("clGetPlatformIDs returned -1"))
        assertTrue(LiteRtAcceleratorPolicy.isDriverRelatedFailure("GPU delegate initialization failed"))
        assertFalse(LiteRtAcceleratorPolicy.isDriverRelatedFailure("model file has an unsupported tensor layout"))
        assertFalse(LiteRtAcceleratorPolicy.isDriverRelatedFailure(null))
    }

    @Test
    fun `missing npu directory is not a crash`() {
        assertFalse(LiteRtAcceleratorPolicy.hasNpuSupport(null))
        assertFalse(LiteRtAcceleratorPolicy.hasNpuSupport(""))
        assertFalse(LiteRtAcceleratorPolicy.hasNpuSupport("Z:/definitely/missing/dir"))
    }
}
