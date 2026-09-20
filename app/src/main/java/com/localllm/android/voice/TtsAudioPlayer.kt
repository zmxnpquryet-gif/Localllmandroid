package com.localllm.android.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

/**
 * Streaming PCM player for synthesized speech (mono, 16-bit). Write calls are
 * blocking so generation and playback stay in lockstep, and [stop] sets a flag
 * that makes the next write fail — the synthesis callback uses that to abort.
 */
class TtsAudioPlayer(private val sampleRate: Int) {

    companion object {
        private const val TAG = "TtsAudioPlayer"

        /**
         * Playback starts only after this much audio is buffered: synthesis produces the
         * first samples slower than real time, and starting the track on an empty buffer
         * makes the device log an underrun and restart the track (observed on a
         * Galaxy S23 FE) which can clip the first syllable.
         */
        private const val PRE_BUFFER_MS = 300
    }

    private var track: AudioTrack? = null

    @Volatile
    private var stopped = false

    @Volatile
    private var writtenFrames = 0L

    private val pending = ArrayList<FloatArray>()
    private var pendingSamples = 0

    fun start(): Boolean {
        stopped = false
        pending.clear()
        pendingSamples = 0
        return openTrack()
    }

    private fun openTrack(): Boolean {
        return try {
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(sampleRate / 2 * 2)
            val created = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        // USAGE_MEDIA, not USAGE_ASSISTANT: on Samsung (measured on a
                        // Galaxy S23 FE) the assistant usage routes to the separate
                        // STREAM_ASSISTANT volume group, which sat at 2/15 while media was
                        // at 14/15 — playback was reported as "no sound" even though the
                        // frames were delivered. Media is also what the system TTS uses.
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                // ~1s of slack: synthesis can momentarily fall behind realtime, and a
                // larger stream buffer absorbs those stalls instead of draining.
                .setBufferSizeInBytes(minBuffer * 8)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                false
            } else {
                created.play()
                track = created
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack start failed: ${t.message}")
            false
        }
    }

    /** @return false when playback was stopped or the track failed. */
    fun write(samples: FloatArray): Boolean {
        if (stopped) return false
        if (samples.isEmpty()) return true
        val active = track
        if (active == null) {
            pending.add(samples)
            pendingSamples += samples.size
            if (pendingSamples < sampleRate * PRE_BUFFER_MS / 1000) return true
            if (!openTrack()) return false
            val buffered = pending.toList()
            pending.clear()
            pendingSamples = 0
            buffered.forEach { chunk -> if (!writePcm(chunk)) return false }
            return !stopped
        }
        return writePcm(samples)
    }

    private fun writePcm(samples: FloatArray): Boolean {
        val active = track ?: return false
        val pcm = ShortArray(samples.size)
        for (i in samples.indices) {
            val clamped = samples[i].coerceIn(-1f, 1f)
            pcm[i] = (clamped * 32767f).toInt().toShort()
        }
        return try {
            val written = active.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written > 0) writtenFrames += written
            written >= 0 && !stopped
        } catch (t: Throwable) {
            Log.w(TAG, "AudioTrack write failed: ${t.message}")
            false
        }
    }

    /** Waits for the buffered audio to drain, then releases the track. */
    fun finish(timeoutMs: Long = 4000L) {
        val active = track
        if (active == null) {
            pending.clear()
            pendingSamples = 0
            return
        }
        track = null
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!stopped &&
                active.playbackHeadPosition.toLong() < writtenFrames &&
                System.currentTimeMillis() < deadline
            ) {
                Thread.sleep(20L)
            }
        } catch (_: Throwable) {
        }
        try {
            active.stop()
        } catch (_: Throwable) {
        }
        try {
            active.release()
        } catch (_: Throwable) {
        }
    }

    fun stop() {
        stopped = true
        val active = track
        track = null
        if (active == null) return
        try {
            active.pause()
        } catch (_: Throwable) {
        }
        try {
            active.flush()
        } catch (_: Throwable) {
        }
        try {
            active.stop()
        } catch (_: Throwable) {
        }
        try {
            active.release()
        } catch (_: Throwable) {
        }
    }
}
