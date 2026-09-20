package com.localllm.android.memory

import com.localllm.android.model.GenerationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Android kills an OOM process without a callback, so the guard has to detect the
 * death on the *next* start from the in-flight marker and lower the settings for
 * that run. These tests cover that contract, not just the ladder math.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MemoryGuardTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val memory = { MemorySnapshot(availMb = 1_000, totalMb = 4_000, lowMemory = false) }

    private fun store(clock: () -> Long = { 1_700_000_000_000L }) =
        MemoryGuardStore(rootDir = temporaryFolder.newFolder(), clock = clock, memory = memory)

    @Test
    fun `a clean run leaves the level alone`() {
        val guard = store()
        guard.markInferenceStart("m", "LLAMA_CPP", 4096, 99, true)
        guard.markInferenceEnd()

        val report = guard.onAppStart()
        assertFalse(report.abnormalPreviousRun)
        assertEquals(0, report.level)
        assertEquals(GenerationSettings(), guard.applyLevel(GenerationSettings()))
    }

    @Test
    fun `a kill during inference is detected on the next start`() {
        val guard = store()
        guard.markInferenceStart("MoE 27B", "SD_ENGINE", 4096, 0, false)
        // process dies here: no markInferenceEnd()

        val report = guard.onAppStart()
        assertTrue(report.abnormalPreviousRun)
        assertEquals(1, report.level)
        assertNotNull(report.lastIncident)
        assertTrue(report.lastIncident!!.detail.contains("SD_ENGINE"))
        assertEquals("MoE 27B", report.lastIncident!!.modelName)

        // The marker is consumed: a second boot without a new inference is clean.
        assertFalse(guard.onAppStart().abnormalPreviousRun)
        assertEquals(1, guard.level())
    }

    @Test
    fun `each abnormal run lowers the settings further and stops at the cap`() {
        val guard = store()
        repeat(6) {
            guard.markInferenceStart("m", "LLAMA_CPP", 16384, 99, true)
            guard.onAppStart()
        }
        assertEquals(MemoryGuardStore.MAX_LEVEL, guard.level())

        val base = GenerationSettings(contextWindow = 16384, enableMtp = true, enableGpuAcceleration = true, gpuLayers = 99)
        val reduced = guard.applyLevel(base)
        assertEquals(1024, reduced.contextWindow)
        assertFalse(reduced.enableMtp)
        assertFalse(reduced.enableIndexingAcceleration)
        assertFalse(reduced.enableGpuAcceleration)
        assertEquals(0, reduced.gpuLayers)
        assertEquals(16384, base.contextWindow)
    }

    @Test
    fun `the level is cumulative as it climbs`() {
        val guard = store()
        val base = GenerationSettings(contextWindow = 8192)

        guard.record(MemoryGuardStore.Cause.LOW_MEMORY_STOP, "first", raiseLevel = true)
        assertEquals(1, guard.level())
        assertFalse(guard.applyLevel(base).enableMtp)
        assertTrue(guard.applyLevel(base).enableIndexingAcceleration)

        guard.record(MemoryGuardStore.Cause.LOW_MEMORY_STOP, "second", raiseLevel = true)
        assertEquals(2, guard.level())
        assertFalse(guard.applyLevel(base).enableIndexingAcceleration)
        assertEquals(8192, guard.applyLevel(base).contextWindow)

        guard.record(MemoryGuardStore.Cause.LOW_MEMORY_STOP, "third", raiseLevel = true)
        assertEquals(2048, guard.applyLevel(base).contextWindow)
    }

    @Test
    fun `disabling the guard stops further reduction but keeps the history`() {
        val guard = store()
        guard.record(MemoryGuardStore.Cause.ABNORMAL_EXIT, "died", raiseLevel = true)
        guard.setEnabled(false)

        val base = GenerationSettings(contextWindow = 8192)
        assertEquals(base, guard.applyLevel(base))
        assertTrue(guard.incidents().isNotEmpty())

        guard.record(MemoryGuardStore.Cause.LOW_MEMORY_STOP, "still recorded", raiseLevel = true)
        assertEquals(1, guard.level())
    }

    @Test
    fun `reset clears the level incidents and marker`() {
        val guard = store()
        guard.markInferenceStart("m", "LITE_RT", 2048, 0, true)
        guard.record(MemoryGuardStore.Cause.OOM_ERROR, "boom", raiseLevel = true)

        guard.reset()
        assertEquals(0, guard.level())
        assertTrue(guard.incidents().isEmpty())
        assertFalse(guard.onAppStart().abnormalPreviousRun)
    }

    @Test
    fun `state and log survive a process restart`() {
        val dir = temporaryFolder.newFolder()
        val clock = { 1_700_000_000_000L }
        MemoryGuardStore(dir, clock, memory).apply {
            markInferenceStart("m", "LLAMA_CPP", 4096, 99, true)
            onAppStart()
        }

        val reopened = MemoryGuardStore(dir, clock, memory)
        assertEquals(1, reopened.level())
        assertTrue(reopened.logTail().contains("ABNORMAL_EXIT"))
        assertTrue(reopened.logTail().contains("[boot]"))
    }

    @Test
    fun `incidents keep the newest and cap the stored history`() {
        val guard = store()
        repeat(30) { index ->
            guard.record(MemoryGuardStore.Cause.MANUAL, "incident-$index")
        }
        val incidents = guard.incidents()
        assertEquals(20, incidents.size)
        assertEquals("incident-29", incidents.last().detail)
    }
}
