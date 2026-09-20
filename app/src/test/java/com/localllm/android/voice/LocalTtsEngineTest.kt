package com.localllm.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** Model presence drives the TTS engine choice, so the file contract is pinned here. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalTtsEngineTest {

    private fun engine() = LocalTtsEngine(RuntimeEnvironment.getApplication())

    @Test
    fun `the korean voice is described by seven real files`() {
        assertEquals(7, LocalTtsEngine.REQUIRED_FILES.size)
        assertTrue(LocalTtsEngine.REQUIRED_FILES.containsKey("tts.json"))
        assertTrue(LocalTtsEngine.REQUIRED_FILES.containsKey("voice.bin"))
        assertTrue(LocalTtsEngine.REQUIRED_FILES.containsKey("unicode_indexer.bin"))
        assertTrue(LocalTtsEngine.REQUIRED_FILES.containsKey("text_encoder.int8.onnx"))
        LocalTtsEngine.REQUIRED_FILES.values.forEach { url ->
            assertTrue(
                "unexpected source: $url",
                url.startsWith("https://huggingface.co/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/main/")
            )
        }
    }

    @Test
    fun `an empty install reports missing`() {
        val engine = engine()
        assertFalse(engine.isModelDownloaded())
        assertEquals(0L, engine.downloadedBytes())
        assertTrue(engine.modelState.value is LocalTtsEngine.ModelState.Missing)
    }

    @Test
    fun `installing every file reports ready`() {
        val context = RuntimeEnvironment.getApplication()
        val dir = File(context.filesDir, LocalTtsEngine.MODEL_DIR_NAME).apply { mkdirs() }
        LocalTtsEngine.REQUIRED_FILES.keys.forEach { name ->
            File(dir, name).writeBytes(byteArrayOf(1))
        }

        val engine = LocalTtsEngine(context)
        assertTrue(engine.isModelDownloaded())
        assertEquals(LocalTtsEngine.REQUIRED_FILES.size.toLong(), engine.downloadedBytes())
        assertTrue(engine.modelState.value is LocalTtsEngine.ModelState.Ready)

        dir.deleteRecursively()
    }
}
