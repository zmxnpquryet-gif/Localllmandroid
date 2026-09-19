package com.localllm.android.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCompletionTrackerTest {
    @Test fun successfulCompletionRunsExactlyOnce() {
        val tracker = SpeechCompletionTracker()
        var calls = 0
        val id = tracker.begin { calls++ }
        tracker.finish(id, true)
        tracker.finish(id, true)
        assertEquals(1, calls)
    }
    @Test fun cancelledAndFailedSpeechDoNotResumeListening() {
        val tracker = SpeechCompletionTracker()
        var calls = 0
        val cancelled = tracker.begin { calls++ }
        tracker.cancel()
        tracker.finish(cancelled, true)
        val failed = tracker.begin { calls++ }
        tracker.finish(failed, false)
        tracker.finish(failed, true)
        assertEquals(0, calls)
    }
    @Test fun staleEventsDoNotFinishNewSpeech() {
        val tracker = SpeechCompletionTracker()
        var calls = 0
        val old = tracker.begin { calls += 100 }
        val current = tracker.begin { calls++ }
        tracker.finish(old, false)
        tracker.finish(old, true)
        tracker.finish(null, true)
        assertTrue(tracker.isCurrent(current))
        tracker.finish(current, true)
        assertEquals(1, calls)
    }
    @Test fun callbackCanStartNewSpeech() {
        val tracker = SpeechCompletionTracker()
        var next: String? = null
        val first = tracker.begin { next = tracker.begin(null) }
        tracker.finish(first, true)
        assertTrue(tracker.isCurrent(next))
    }
}
