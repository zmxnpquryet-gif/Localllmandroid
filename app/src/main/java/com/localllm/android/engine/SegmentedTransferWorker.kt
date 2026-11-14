package com.localllm.android.engine

import android.util.Log
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal data class ContentRange(val start: Long, val end: Long, val total: Long) {
    companion object {
        private val HEADER_REGEX = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)

        fun parse(value: String?): ContentRange? {
            val match = HEADER_REGEX.matchEntire(value?.trim() ?: return null) ?: return null
            val start = match.groupValues[1].toLongOrNull() ?: return null
            val end = match.groupValues[2].toLongOrNull() ?: return null
            val total = match.groupValues[3].toLongOrNull() ?: return null
            return if (start <= end && end < total) ContentRange(start, end, total) else null
        }
    }
}

/**
 * Executes HTTP Range segmented downloads (with `.partsmeta` revision guard and
 * zero-copy `FileChannel.transferTo` merge) and single-stream fallback transfers.
 */
internal class SegmentedTransferWorker(
    private val httpClient: OkHttpClient,
    private val userAgent: String,
    private val bufferSize: Int,
    private val defaultSegments: Int,
    private val minSegmentSize: Long
) {
    private val tag = "SegmentedTransferWorker"

    suspend fun fetch(
        taskTitle: String,
        finalUrl: String,
        totalSize: Long,
        supportsRange: Boolean,
        targetFile: File,
        modelsDir: File,
        token: String?,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        Log.i(tag, "[$taskTitle] Target size: $totalSize bytes (Range: $supportsRange, URL: $finalUrl)")
        if (supportsRange && totalSize >= minSegmentSize) {
            val ok = downloadSegmented(
                taskTitle = taskTitle,
                url = finalUrl,
                targetFile = targetFile,
                modelsDir = modelsDir,
                totalSize = totalSize,
                segmentCount = defaultSegments,
                token = token,
                onProgress = onProgress,
                onError = { err ->
                    Log.w(tag, "Segmented download failed: $err, falling back to single stream")
                    false
                }
            )
            if (ok) return true
        }
        return downloadSingleStream(taskTitle, finalUrl, targetFile, modelsDir, totalSize, token, onProgress, onError)
    }

    private suspend fun downloadSegmented(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        totalSize: Long,
        segmentCount: Int,
        token: String?,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Boolean
    ): Boolean {
        val partFiles = (0 until segmentCount).map { i -> File(modelsDir, "${targetFile.name}.part_$i") }
        val metaFile = File(modelsDir, "${targetFile.name}.partsmeta")
        val metaTotal = try {
            if (metaFile.isFile) metaFile.readText().trim().toLongOrNull() else null
        } catch (_: Exception) {
            null
        }
        when {
            metaTotal == null -> {
                if (partFiles.any { it.exists() }) {
                    Log.w(tag, "[$taskTitle] Parts sidecar missing/unreadable; discarding stale parts")
                    partFiles.forEach { it.delete() }
                }
            }
            metaTotal != totalSize -> {
                Log.w(tag, "[$taskTitle] Remote size changed ($metaTotal -> $totalSize); discarding stale parts")
                partFiles.forEach { it.delete() }
            }
        }
        try {
            metaFile.writeText(totalSize.toString())
        } catch (e: Exception) {
            Log.w(tag, "[$taskTitle] Could not write parts sidecar: ${e.message}")
            partFiles.forEach { it.delete() }
        }

        val segmentStarts = LongArray(segmentCount)
        val segmentEnds = LongArray(segmentCount)
        val segmentTotals = LongArray(segmentCount)
        val segmentDownloaded = Array(segmentCount) { AtomicLong(0L) }

        val chunkSize = totalSize / segmentCount
        for (i in 0 until segmentCount) {
            segmentStarts[i] = i * chunkSize
            segmentEnds[i] = if (i == segmentCount - 1) totalSize - 1L else (i + 1) * chunkSize - 1L
            segmentTotals[i] = segmentEnds[i] - segmentStarts[i] + 1L
            val existing = if (partFiles[i].exists()) partFiles[i].length().coerceAtMost(segmentTotals[i]) else 0L
            segmentDownloaded[i].set(existing)
        }

        var downloadFailed = false
        var failureReason = ""

        try {
            coroutineScope {
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = segmentDownloaded.sumOf { it.get() }

                val monitorJob = async(Dispatchers.Default) {
                    while (isActive) {
                        delay(200)
                        val now = System.currentTimeMillis()
                        val currentDownloaded = segmentDownloaded.sumOf { it.get() }
                        val (speedText, etaSec) = computeRateMetrics(
                            now - lastProgressTime,
                            currentDownloaded - lastProgressBytes,
                            totalSize - currentDownloaded
                        )
                        lastProgressTime = now
                        lastProgressBytes = currentDownloaded

                        val segmentBars = List(8) { slot ->
                            val segIndex = (slot * segmentCount / 8).coerceIn(0, segmentCount - 1)
                            ModelDownloader.safeFraction(segmentDownloaded[segIndex].get(), segmentTotals[segIndex])
                        }
                        onProgress(currentDownloaded, totalSize, speedText, etaSec, segmentBars)
                        if (currentDownloaded >= totalSize) break
                    }
                }

                val workers = (0 until segmentCount).map { i ->
                    async(Dispatchers.IO) {
                        downloadSingleSegment(
                            segmentIndex = i,
                            partFile = partFiles[i],
                            segStart = segmentStarts[i],
                            segEnd = segmentEnds[i],
                            segTotal = segmentTotals[i],
                            totalSize = totalSize,
                            url = url,
                            token = token,
                            counter = segmentDownloaded[i]
                        )
                    }
                }

                try {
                    workers.awaitAll()
                } finally {
                    monitorJob.cancel()
                }
            }

            val allComplete = (0 until segmentCount).all { i ->
                partFiles[i].exists() && partFiles[i].length() == segmentTotals[i]
            }
            if (allComplete) {
                mergeParts(partFiles, targetFile, modelsDir)
                metaFile.delete()
                return true
            }
            downloadFailed = true
            failureReason = AppStrings.get(R.string.dl_chunk_incomplete)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            downloadFailed = true
            failureReason = e.localizedMessage ?: AppStrings.get(R.string.dl_net_fail)
            Log.w(tag, "[$taskTitle] Segmented download error: $failureReason", e)
        }

        return if (downloadFailed) onError(failureReason) else true
    }

    private suspend fun downloadSingleSegment(
        segmentIndex: Int,
        partFile: File,
        segStart: Long,
        segEnd: Long,
        segTotal: Long,
        totalSize: Long,
        url: String,
        token: String?,
        counter: AtomicLong
    ) {
        var attempt = 0
        val maxAttempts = 3
        while (currentCoroutineContext().isActive && attempt < maxAttempts) {
            attempt++
            val currentPartLen = if (partFile.exists()) partFile.length() else 0L
            if (currentPartLen > segTotal) {
                throw IOException("Oversized part on segment $segmentIndex")
            }
            if (currentPartLen == segTotal) {
                counter.set(segTotal)
                return
            }

            val reqStart = segStart + currentPartLen
            try {
                val req = ModelDownloader.withHfAuth(
                    Request.Builder()
                        .url(HfSource.rewrite(url))
                        .header("User-Agent", userAgent)
                        .header("Range", "bytes=$reqStart-$segEnd"),
                    url,
                    token
                ).build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.code != 206) {
                        throw IOException("Expected HTTP 206 on segment $segmentIndex, got ${resp.code}")
                    }
                    val range = ContentRange.parse(resp.header("Content-Range"))
                    if (range != ContentRange(reqStart, segEnd, totalSize)) {
                        throw IOException("Invalid Content-Range on segment $segmentIndex")
                    }
                    val body = resp.body ?: throw IOException("Empty body on segment $segmentIndex")
                    val expectedLength = segEnd - reqStart + 1L
                    if (body.contentLength() != -1L && body.contentLength() != expectedLength) {
                        throw IOException("Invalid Content-Length on segment $segmentIndex")
                    }
                    try {
                        body.byteStream().use { input ->
                            BufferedOutputStream(FileOutputStream(partFile, currentPartLen > 0), bufferSize).use { output ->
                                val buf = ByteArray(bufferSize)
                                var remaining = expectedLength
                                while (remaining > 0L) {
                                    currentCoroutineContext().ensureActive()
                                    val readLen = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                    if (readLen == -1) throw IOException("Short body on segment $segmentIndex")
                                    output.write(buf, 0, readLen)
                                    remaining -= readLen
                                    counter.addAndGet(readLen.toLong())
                                }
                                currentCoroutineContext().ensureActive()
                                if (input.read() != -1) throw IOException("Oversized body on segment $segmentIndex")
                            }
                        }
                        if (partFile.length() != segTotal) {
                            throw IOException("Invalid part length on segment $segmentIndex")
                        }
                    } catch (e: Exception) {
                        RandomAccessFile(partFile, "rw").use { it.setLength(currentPartLen) }
                        counter.set(currentPartLen)
                        throw e
                    }
                }
                counter.set(segTotal)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(tag, "Segment $segmentIndex attempt $attempt failed: ${e.message}")
                if (attempt >= maxAttempts) throw e
                delay(500)
            }
        }
    }

    private fun mergeParts(partFiles: List<File>, targetFile: File, modelsDir: File) {
        val tempMerged = File(modelsDir, "${targetFile.name}.merging")
        if (tempMerged.exists()) tempMerged.delete()

        FileOutputStream(tempMerged).use { fos ->
            val outChannel = fos.channel
            for (part in partFiles) {
                FileInputStream(part).use { fis ->
                    val inChannel = fis.channel
                    var transferred = 0L
                    val size = inChannel.size()
                    while (transferred < size) {
                        val count = inChannel.transferTo(transferred, size - transferred, outChannel)
                        if (count <= 0) throw IOException("Incomplete merge of ${part.name}")
                        transferred += count
                    }
                }
            }
            outChannel.force(true)
        }

        if (targetFile.exists() && !targetFile.delete()) {
            tempMerged.delete()
            throw IOException("Could not replace previous file ${targetFile.name}")
        }
        if (!tempMerged.renameTo(targetFile)) {
            tempMerged.delete()
            throw IOException("Failed to publish merged download")
        }
        partFiles.forEach { it.delete() }
    }

    private suspend fun downloadSingleStream(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        totalSize: Long,
        token: String?,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        val tempFile = File(modelsDir, "${targetFile.name}.downloading")
        var published = false
        try {
            val req = ModelDownloader.withHfAuth(
                Request.Builder()
                    .url(HfSource.rewrite(url))
                    .header("User-Agent", userAgent),
                url,
                token
            ).build()

            return httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    onError(AppStrings.get(R.string.dl_http_err, resp.code, resp.message))
                    return false
                }
                val body = resp.body ?: run {
                    onError(AppStrings.get(R.string.dl_empty_body))
                    return false
                }

                val actualLength = body.contentLength().takeIf { it > 0 } ?: totalSize
                var downloaded = 0L
                var lastTime = System.currentTimeMillis()
                var lastBytes = 0L

                body.byteStream().use { input ->
                    BufferedOutputStream(FileOutputStream(tempFile, false), bufferSize).use { output ->
                        val buf = ByteArray(bufferSize)
                        var readLen: Int
                        while (input.read(buf).also { readLen = it } != -1) {
                            currentCoroutineContext().ensureActive()
                            output.write(buf, 0, readLen)
                            downloaded += readLen

                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 200L) {
                                val (speedText, etaSec) = computeRateMetrics(
                                    now - lastTime,
                                    downloaded - lastBytes,
                                    actualLength - downloaded
                                )
                                val frac = ModelDownloader.safeFraction(downloaded, actualLength)
                                onProgress(downloaded, actualLength, speedText, etaSec, List(8) { frac })
                                lastTime = now
                                lastBytes = downloaded
                            }
                        }
                        output.flush()
                    }
                }

                if (actualLength > 0 && downloaded != actualLength) {
                    tempFile.delete()
                    onError("다운로드가 중단되었습니다: $downloaded/$actualLength bytes")
                    return false
                }
                if (targetFile.exists()) targetFile.delete()
                if (!tempFile.renameTo(targetFile)) {
                    tempFile.delete()
                    onError("다운로드 파일 이동에 실패했습니다.")
                    return false
                }
                published = true
                Log.i(tag, "[$taskTitle] Single stream download completed: ${targetFile.name} (${targetFile.length()} bytes)")
                true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val err = AppStrings.get(R.string.dl_fail_generic, e.localizedMessage ?: e.message)
            Log.e(tag, "[$taskTitle] Single stream download failed", e)
            onError(err)
            return false
        } finally {
            if (!published) tempFile.delete()
        }
    }

    private fun computeRateMetrics(elapsedMs: Long, bytesDelta: Long, remainingBytes: Long): Pair<String, Int> {
        val timeDeltaSec = max(elapsedMs / 1000.0, 0.001)
        val clampedDelta = bytesDelta.coerceAtLeast(0L)
        val speedMbps = (clampedDelta.toDouble() / timeDeltaSec) / (1024.0 * 1024.0)
        val speedText = String.format(Locale.US, "%.2f MB/s", speedMbps)
        val bytesPerSec = (speedMbps * 1024.0 * 1024.0).toLong()
        val etaSec = if (bytesPerSec > 0) (remainingBytes.coerceAtLeast(0L) / bytesPerSec).toInt() else 0
        return speedText to etaSec
    }
}
