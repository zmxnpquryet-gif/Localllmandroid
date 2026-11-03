package com.localllm.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Platform PCM endpoints for native Omni sessions. They perform audio I/O only;
 * speech recognition and synthesis are deliberately not part of this layer.
 */
class AndroidPcmInputSource(
    private val context: Context,
    private val format: AudioFormat,
    private val bufferBytes: Int = 12_800
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var record: AudioRecord? = null

    @SuppressLint("MissingPermission") // Checked immediately below and reported explicitly.
    fun frames(): Flow<NativeAudioFrame> = flow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission is required for a native audio session")
        }
        val platformFormat = format.toPlatform()
        val min = AudioRecord.getMinBufferSize(format.sampleRateHz, format.channelMask(input = true), platformFormat)
        if (min <= 0) throw IOException("AudioRecord rejected ${format.sampleRateHz}Hz/${format.channels}ch format")
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            format.sampleRateHz,
            format.channelMask(input = true),
            platformFormat,
            maxOf(min * 2, bufferBytes)
        )
        this@AndroidPcmInputSource.record = record
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("AudioRecord could not initialize")
        }
        var sequence = 0L
        val buffer = ByteArray(maxOf(bufferBytes, min))
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IOException("AudioRecord did not enter recording state")
            }
            while (currentCoroutineContext().isActive && !closed.get()) {
                val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (count < 0) throw IOException("AudioRecord read failed: $count")
                if (count > 0) emit(NativeAudioFrame(format, buffer.copyOf(count), ++sequence))
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            if (this@AndroidPcmInputSource.record === record) this@AndroidPcmInputSource.record = null
        }
    }.flowOn(Dispatchers.IO)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        record?.runCatching {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        record = null
    }
}

class AndroidPcmOutputSink(
    private val format: AudioFormat,
    private val bufferBytes: Int = 12_800
) : AutoCloseable {
    private val closed = AtomicBoolean(false)
    private var track: AudioTrack? = null

    suspend fun open() = withContext(Dispatchers.IO) {
        check(track == null) { "AudioTrack is already open" }
        val platformFormat = format.toPlatform()
        val min = AudioTrack.getMinBufferSize(format.sampleRateHz, format.channelMask(input = false), platformFormat)
        if (min <= 0) throw IOException("AudioTrack rejected ${format.sampleRateHz}Hz/${format.channels}ch format")
        val created = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(PlatformAudioFormat.Builder()
                .setEncoding(platformFormat)
                .setSampleRate(format.sampleRateHz)
                .setChannelMask(format.channelMask(input = false))
                .build())
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(min * 2, bufferBytes))
            .build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            throw IOException("AudioTrack could not initialize")
        }
        created.play()
        track = created
    }

    suspend fun write(frame: NativeAudioFrame) = withContext(Dispatchers.IO) {
        check(!closed.get()) { "AudioTrack is closed" }
        require(frame.format == format) { "Output audio format changed without reopening" }
        val active = track ?: throw IllegalStateException("AudioTrack is not open")
        var offset = 0
        while (offset < frame.bytes.size) {
            val written = active.write(frame.bytes, offset, frame.bytes.size - offset, AudioTrack.WRITE_BLOCKING)
            if (written < 0) throw IOException("AudioTrack write failed: $written")
            offset += written
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        track?.runCatching {
            pause()
            flush()
            stop()
            release()
        }
        track = null
    }
}

private fun AudioFormat.channelMask(input: Boolean): Int = when {
    input && channels == 1 -> PlatformAudioFormat.CHANNEL_IN_MONO
    input -> PlatformAudioFormat.CHANNEL_IN_STEREO
    channels == 1 -> PlatformAudioFormat.CHANNEL_OUT_MONO
    else -> PlatformAudioFormat.CHANNEL_OUT_STEREO
}

private fun AudioFormat.toPlatform(): Int = when (bitsPerSample) {
    16 -> PlatformAudioFormat.ENCODING_PCM_16BIT
    32 -> PlatformAudioFormat.ENCODING_PCM_FLOAT
    else -> throw IllegalArgumentException("Android PCM I/O supports 16-bit or 32-bit float frames")
}
