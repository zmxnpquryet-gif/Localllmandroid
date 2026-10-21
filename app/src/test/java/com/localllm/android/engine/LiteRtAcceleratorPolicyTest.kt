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

    /** 断言按 kind 进行：label 是本地化文案，JVM 单测无资源。 */
    private fun kinds(gpuAllowed: Boolean, hasOpenCl: Boolean, hasNpu: Boolean = false, hasVision: Boolean = false): List<LiteRtAcceleratorPolicy.Kind> =
        LiteRtAcceleratorPolicy.buildCandidates(
            gpuAllowed = gpuAllowed,
            hasOpenCl = hasOpenCl,
            hasNpu = hasNpu,
            hasVision = hasVision,
            maxTokens = 4096,
            threadCount = 4
        ).map { it.kind }

    @Test
    fun `no opencl driver means no gpu candidate`() {
        val kinds = kinds(gpuAllowed = true, hasOpenCl = false)
        assertTrue(kinds.none { it == LiteRtAcceleratorPolicy.Kind.GPU })
        assertTrue(kinds.any { it == LiteRtAcceleratorPolicy.Kind.CPU })
    }

    @Test
    fun `opencl driver puts gpu first and vision gpu before text gpu`() {
        val candidates = LiteRtAcceleratorPolicy.buildCandidates(
            gpuAllowed = true,
            hasOpenCl = true,
            hasNpu = false,
            hasVision = true,
            maxTokens = 4096,
            threadCount = 4
        )
        // 视觉 GPU 候选必须排在纯文本 GPU 之前
        assertEquals(LiteRtAcceleratorPolicy.Kind.GPU, candidates.first().kind)
        assertTrue(candidates.first().withVision)
        assertEquals(LiteRtAcceleratorPolicy.Kind.GPU, candidates[1].kind)
        assertFalse(candidates[1].withVision)
        assertEquals(2, candidates.count { it.kind == LiteRtAcceleratorPolicy.Kind.GPU })
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
        assertTrue(kinds(gpuAllowed = true, hasOpenCl = true, hasNpu = false).none { it == LiteRtAcceleratorPolicy.Kind.NPU })
        assertTrue(kinds(gpuAllowed = true, hasOpenCl = true, hasNpu = true).any { it == LiteRtAcceleratorPolicy.Kind.NPU })
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
