package com.localllm.android.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The footprint estimate exists because comparing the model file size with free RAM
 * ignored the KV cache, which is exactly what pushes a large-context load into an
 * out-of-memory kill.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryEstimatorTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun estimate(
        fileMb: Long,
        nLayers: Int = 128,
        kvHeads: Int = 8,
        headDim: Int = 128,
        contextWindow: Int = 4096
    ) = MemoryEstimator.Estimate(
        fileBytes = fileMb * MemorySnapshot.MB,
        kvBytes = MemoryEstimator.kvBytes(nLayers, kvHeads, headDim, contextWindow),
        overheadBytes = 96L * MemorySnapshot.MB,
        contextWindow = contextWindow,
        nLayers = nLayers,
        kvHeads = kvHeads,
        headDim = headDim
    )

    private fun snapshot(availMb: Long, totalMb: Long = 8_000) =
        MemorySnapshot(availMb = availMb, totalMb = totalMb, lowMemory = false)

    @Test
    fun `kv cache size follows layers times kv heads times context`() {
        val expected = 2L * 32 * 8 * 128 * 4096 * 2
        assertEquals(expected, MemoryEstimator.kvBytes(32, 8, 128, 4096))
        assertEquals(expected / 2, MemoryEstimator.kvBytes(32, 8, 128, 2048))
        assertEquals(0L, MemoryEstimator.kvBytes(32, 8, 128, 0))
    }

    @Test
    fun `a model that fits needs no reduction`() {
        // 3GB weights + 2GB KV + 96MB buffers on a 6GB budget.
        val decision = MemoryEstimator.decide(estimate(fileMb = 3_000), snapshot(availMb = 8_000), 4096)
        assertEquals(MemoryEstimator.Status.OK, decision.status)
        assertEquals(4096, decision.effectiveContextWindow)
    }

    @Test
    fun `a tight model has its context reduced instead of being refused`() {
        // Same weights, 6GB available: only the KV cache has to give.
        val decision = MemoryEstimator.decide(estimate(fileMb = 3_000), snapshot(availMb = 6_000), 4096)
        assertEquals(MemoryEstimator.Status.REDUCED, decision.status)
        assertTrue(decision.effectiveContextWindow < 4096)
        val reduced = decision.estimate!!
        assertTrue(reduced.kvBytes < MemoryEstimator.kvBytes(128, 8, 128, 4096))
        assertTrue(reduced.totalMb() <= 6_000 * 0.80)
    }

    @Test
    fun `a model that cannot fit at the smallest context is refused`() {
        // 3GB of weights against 3.2GB available: the buffers alone do not fit.
        val decision = MemoryEstimator.decide(estimate(fileMb = 3_000), snapshot(availMb = 3_200), 4096)
        assertEquals(MemoryEstimator.Status.REFUSED, decision.status)
        assertTrue(decision.reason.contains("available"))
    }

    @Test
    fun `an unreadable memory report never blocks a load`() {
        val decision = MemoryEstimator.decide(estimate(fileMb = 3_000), MemorySnapshot.unknown(), 4096)
        assertEquals(MemoryEstimator.Status.UNKNOWN, decision.status)
        assertEquals(4096, decision.effectiveContextWindow)
    }

    @Test
    fun `non gguf files produce no estimate`() {
        val file = temporaryFolder.newFile("not-a-model.gguf")
        file.writeText("this is definitely not a gguf container")
        assertNull(MemoryEstimator.estimate(file, 4096))
    }
}
