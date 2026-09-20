package com.localllm.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.localllm.android.voice.LocalTtsEngine
import com.localllm.android.voice.TtsAudioPlayer
import com.localllm.android.voice.TtsEngineMode
import com.localllm.android.voice.VoiceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the real Korean neural voice when it is installed on the device
 * (skipped otherwise, so CI without the 145MB model stays green): synthesis must
 * produce audio at the model's rate, the streaming player must accept the chunks,
 * and the hands-free `speak()` callback must fire — that callback is what resumes
 * listening in a voice conversation.
 */
@RunWith(AndroidJUnit4::class)
class LocalTtsSynthesisInstrumentedTest {

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedKoreanVoiceSynthesizesAudio() {
        val engine = LocalTtsEngine(targetContext())
        assumeTrue("local TTS model is not installed on this device", engine.isModelDownloaded())
        try {
            assertEquals(LocalTtsEngine.DEFAULT_SAMPLE_RATE, engine.sampleRate())

            var sampleCount = 0
            var chunkCount = 0
            val ok = runBlocking {
                engine.synthesize("안녕하세요. 테스트입니다.", speakerId = 0, speed = 1.0f) { chunk ->
                    chunkCount++
                    sampleCount += chunk.size
                    true
                }
            }

            assertTrue("synthesis reported failure", ok)
            assertTrue("no chunks streamed", chunkCount > 0)
            assertTrue("no audio samples produced", sampleCount > 0)
        } finally {
            engine.release()
        }
    }

    @Test
    fun streamingPlayerAcceptsSynthesizedAudio() {
        val engine = LocalTtsEngine(targetContext())
        assumeTrue("local TTS model is not installed on this device", engine.isModelDownloaded())
        val player = TtsAudioPlayer(engine.sampleRate())
        try {
            assertTrue("AudioTrack could not start", player.start())
            val ok = runBlocking {
                engine.synthesize("짧은 테스트입니다.", speakerId = 0, speed = 1.0f) { chunk ->
                    player.write(chunk)
                }
            }
            assertTrue("synthesis aborted while playing", ok)
        } finally {
            player.finish()
            engine.release()
        }
    }

    @Test
    fun voiceManagerSpeaksAndReportsCompletion() {
        val manager = VoiceManager(targetContext())
        assumeTrue("local TTS model is not installed on this device", manager.localTts.isModelDownloaded())
        try {
            manager.setTtsMode(TtsEngineMode.LOCAL_NEURAL)
            assertTrue("local voice was not selected", manager.isLocalTtsActive())

            val spoken = CountDownLatch(1)
            manager.speak("대화 모드 테스트 음성입니다.") { spoken.countDown() }

            assertTrue("speak() never reported completion", spoken.await(60, TimeUnit.SECONDS))
        } finally {
            manager.release()
        }
    }
}
