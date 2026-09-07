package com.example.engine

import android.util.Log
import com.example.model.LlmModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class FdmComponentType(val label: String, val badge: String) {
    MAIN_MODEL("메인 가중치 모델", "MAIN"),
    VISION_TOWER("비전 타워 (mmproj)", "VISION"),
    MTP_DRAFTER("MTP 투기적 드래프터", "MTP"),
    TEMPLATE("LiteRT 프롬프트 템플릿", "TEMPLATE")
}

data class FdmComponentStatus(
    val type: FdmComponentType,
    val title: String,
    val fileName: String,
    val url: String,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedText: String = "",
    val isCompleted: Boolean = false
)

data class DownloadStatus(
    val modelId: String,
    val progress: Float,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedText: String,
    val etaSeconds: Int = 0,
    val activeComponentName: String = "",
    val mainProgress: Float = 0f,
    val visionProgress: Float = 0f,
    val mtpProgress: Float = 0f,
    val templateProgress: Float = 0f,
    val segments: List<Float> = List(8) { 0f },
    val components: List<FdmComponentStatus> = emptyList(),
    val isCompleted: Boolean = false,
    val errorMessage: String? = null,
    val localMainPath: String? = null,
    val localVisionPath: String? = null,
    val localMtpPath: String? = null,
    val localTemplatePath: String? = null
)

/**
 * High-performance Multi-thread Segmented Range Downloader (FDM engine).
 * Uses HTTP Range requests and RandomAccessFile to write segments in parallel.
 * Supports resume, cancellation, ETA, and per-segment progress monitoring.
 */
class ModelDownloader {

    private val tag = "ModelDownloader"

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) LocalLLM/1.1.0 FDM"
        private const val DEFAULT_SEGMENTS = 6
        private const val MIN_SEGMENT_SIZE = 10 * 1024 * 1024L // 10MB
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun downloadUnifiedBundle(
        model: LlmModel,
        modelsDir: File
    ): Flow<DownloadStatus> = flow {
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val mainFile = File(modelsDir, model.fileName)
        val visionFile = if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
            File(modelsDir, model.mmprojFileName ?: "mmproj-${model.fileName}")
        } else null
        val mtpFile = if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
            File(modelsDir, model.mtpDrafterFileName ?: "draft-${model.fileName}")
        } else null
        val templateFile = if (model.templateFileUrl.isNotBlank() || model.templateFileName != null) {
            File(modelsDir, model.templateFileName ?: "${model.fileName.substringBeforeLast('.')}-template.json")
        } else null

        data class DownloadTask(
            val type: FdmComponentType,
            val title: String,
            val targetFile: File,
            val url: String,
            val fallbackEstimatedSize: Long
        )

        val tasks = mutableListOf<DownloadTask>()
        tasks.add(
            DownloadTask(
                type = FdmComponentType.MAIN_MODEL,
                title = "메인 가중치",
                targetFile = mainFile,
                url = model.mainModelUrl.ifBlank {
                    if (model.repoId.isNotBlank()) "https://huggingface.co/${model.repoId}/resolve/main/${model.fileName}" else ""
                },
                fallbackEstimatedSize = if (model.sizeBytes > 0) model.sizeBytes else 800_000_000L
            )
        )

        if (visionFile != null && model.visionTowerUrl.isNotBlank()) {
            tasks.add(
                DownloadTask(
                    type = FdmComponentType.VISION_TOWER,
                    title = "비전 타워 (mmproj)",
                    targetFile = visionFile,
                    url = model.visionTowerUrl,
                    fallbackEstimatedSize = 350_000_000L
                )
            )
        }

        if (mtpFile != null && model.mtpDrafterUrl.isNotBlank()) {
            tasks.add(
                DownloadTask(
                    type = FdmComponentType.MTP_DRAFTER,
                    title = "MTP 2x 투기적 드래프터",
                    targetFile = mtpFile,
                    url = model.mtpDrafterUrl,
                    fallbackEstimatedSize = 400_000_000L
                )
            )
        }

        if (templateFile != null && model.templateFileUrl.isNotBlank()) {
            tasks.add(
                DownloadTask(
                    type = FdmComponentType.TEMPLATE,
                    title = "프롬프트 템플릿",
                    targetFile = templateFile,
                    url = model.templateFileUrl,
                    fallbackEstimatedSize = 50_000L
                )
            )
        }

        val componentStatusMap = tasks.associate { task ->
            task.type to FdmComponentStatus(
                type = task.type,
                title = task.title,
                fileName = task.targetFile.name,
                url = task.url,
                totalBytes = task.fallbackEstimatedSize
            )
        }.toMutableMap()

        var totalBundleBytes = tasks.sumOf { it.fallbackEstimatedSize }
        var bundleDownloadedBytes = 0L

        for (task in tasks) {
            val componentType = task.type
            val targetFile = task.targetFile
            val url = task.url

            if (url.isBlank()) continue

            // If already fully downloaded and valid
            if (targetFile.exists() && targetFile.length() > 0 && targetFile.length() >= task.fallbackEstimatedSize * 0.95) {
                val existingSize = targetFile.length()
                bundleDownloadedBytes += existingSize
                componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
                    progress = 1.0f,
                    downloadedBytes = existingSize,
                    totalBytes = existingSize,
                    speedText = "완료",
                    isCompleted = true
                )
                continue
            }

            // Execute parallel segmented range download for this component
            val success = downloadComponentSegmented(
                taskTitle = task.title,
                url = url,
                targetFile = targetFile,
                modelsDir = modelsDir,
                fallbackSize = task.fallbackEstimatedSize,
                onProgress = { taskDownloaded, taskTotal, speedText, etaSec, segmentBars ->
                    val overallDownloaded = bundleDownloadedBytes + taskDownloaded
                    val overallTotal = max(totalBundleBytes, overallDownloaded)
                    val overallProgress = (overallDownloaded.toFloat() / overallTotal).coerceIn(0f, 0.999f)
                    val taskProgress = (taskDownloaded.toFloat() / taskTotal).coerceIn(0f, 0.999f)

                    componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
                        progress = taskProgress,
                        downloadedBytes = taskDownloaded,
                        totalBytes = taskTotal,
                        speedText = speedText,
                        isCompleted = false
                    )

                    emit(
                        DownloadStatus(
                            modelId = model.id,
                            progress = overallProgress,
                            downloadedBytes = overallDownloaded,
                            totalBytes = overallTotal,
                            speedText = speedText,
                            etaSeconds = etaSec,
                            activeComponentName = "${task.title} 분할 다운로드 중 (${targetFile.name})",
                            mainProgress = componentStatusMap[FdmComponentType.MAIN_MODEL]?.progress ?: 0f,
                            visionProgress = componentStatusMap[FdmComponentType.VISION_TOWER]?.progress ?: 0f,
                            mtpProgress = componentStatusMap[FdmComponentType.MTP_DRAFTER]?.progress ?: 0f,
                            templateProgress = componentStatusMap[FdmComponentType.TEMPLATE]?.progress ?: 0f,
                            segments = segmentBars,
                            components = componentStatusMap.values.toList(),
                            isCompleted = false
                        )
                    )
                },
                onError = { err ->
                    emit(
                        DownloadStatus(
                            modelId = model.id,
                            progress = 0f,
                            downloadedBytes = bundleDownloadedBytes,
                            totalBytes = totalBundleBytes,
                            speedText = "오류",
                            errorMessage = err,
                            isCompleted = false
                        )
                    )
                }
            )

            if (!success) {
                return@flow
            }

            val finalTaskSize = targetFile.length()
            bundleDownloadedBytes += finalTaskSize
            componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
                progress = 1.0f,
                downloadedBytes = finalTaskSize,
                totalBytes = finalTaskSize,
                speedText = "완료",
                isCompleted = true
            )
        }

        // All components completed
        emit(
            DownloadStatus(
                modelId = model.id,
                progress = 1.0f,
                downloadedBytes = bundleDownloadedBytes,
                totalBytes = bundleDownloadedBytes,
                speedText = "완료",
                etaSeconds = 0,
                activeComponentName = "다운로드 완료",
                mainProgress = 1.0f,
                visionProgress = if (visionFile != null) 1.0f else 0f,
                mtpProgress = if (mtpFile != null) 1.0f else 0f,
                templateProgress = if (templateFile != null) 1.0f else 0f,
                segments = List(8) { 1.0f },
                components = componentStatusMap.values.toList(),
                isCompleted = true,
                localMainPath = mainFile.absolutePath,
                localVisionPath = visionFile?.absolutePath,
                localMtpPath = mtpFile?.absolutePath,
                localTemplatePath = templateFile?.absolutePath
            )
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Downloads a file using parallel HTTP Range requests and RandomAccessFile.
     */
    private suspend fun downloadComponentSegmented(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        fallbackSize: Long,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        val tempFile = File(modelsDir, "${targetFile.name}.downloading")
        val metaFile = File(modelsDir, "${targetFile.name}.meta")

        var totalSize = fallbackSize
        var supportsRange = false

        // 1. Probe server for Range support and Content-Length
        try {
            val probeReq = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Range", "bytes=0-0")
                .build()

            httpClient.newCall(probeReq).execute().use { probeResp ->
                if (probeResp.isSuccessful || probeResp.code == 206) {
                    val contentRange = probeResp.header("Content-Range")
                    val acceptRanges = probeResp.header("Accept-Ranges")
                    if (probeResp.code == 206 || acceptRanges?.contains("bytes", ignoreCase = true) == true) {
                        supportsRange = true
                    }
                    if (!contentRange.isNullOrBlank() && contentRange.contains("/")) {
                        val parsedTotal = contentRange.substringAfter("/").trim().toLongOrNull()
                        if (parsedTotal != null && parsedTotal > 0L) {
                            totalSize = parsedTotal
                        }
                    } else {
                        val cl = probeResp.header("Content-Length")?.toLongOrNull()
                        if (cl != null && cl > 1L) {
                            totalSize = cl
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Range probe failed, falling back to estimated size: ${e.message}")
        }

        // Determine segment count
        val segmentCount = if (supportsRange && totalSize >= MIN_SEGMENT_SIZE) DEFAULT_SEGMENTS else 1

        Log.i(tag, "[$taskTitle] Starting download (Size: $totalSize bytes, Segments: $segmentCount, RangeSupport: $supportsRange)")

        val segmentStarts = LongArray(segmentCount)
        val segmentEnds = LongArray(segmentCount)
        val segmentTotals = LongArray(segmentCount)
        val segmentDownloaded = Array(segmentCount) { AtomicLong(0L) }

        val chunkSize = totalSize / segmentCount
        for (i in 0 until segmentCount) {
            segmentStarts[i] = i * chunkSize
            segmentEnds[i] = if (i == segmentCount - 1) totalSize - 1L else (i + 1) * chunkSize - 1L
            segmentTotals[i] = segmentEnds[i] - segmentStarts[i] + 1L
        }

        // Check if existing download can be resumed
        var isResuming = false
        if (tempFile.exists() && metaFile.exists()) {
            try {
                val metaLines = metaFile.readLines()
                if (metaLines.isNotEmpty() && metaLines[0].trim().toLongOrNull() == totalSize) {
                    val savedParts = metaLines.drop(1)
                    if (savedParts.size == segmentCount) {
                        for (i in 0 until segmentCount) {
                            val savedBytes = savedParts[i].trim().toLongOrNull() ?: 0L
                            segmentDownloaded[i].set(savedBytes.coerceIn(0L, segmentTotals[i]))
                        }
                        isResuming = true
                        Log.i(tag, "[$taskTitle] Resuming existing segmented download from .meta file")
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to read resume meta, starting fresh: ${e.message}")
            }
        }

        if (!isResuming) {
            try {
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.setLength(totalSize)
                }
                saveMeta(metaFile, totalSize, segmentDownloaded)
            } catch (e: Exception) {
                val err = "디스크 공간 할당 실패: ${e.localizedMessage}"
                onError(err)
                return false
            }
        }

        var downloadFailed = false
        var failureMessage = ""

        try {
            coroutineScope {
                // Monitor coroutine for real-time MB/s and ETA calculation
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = segmentDownloaded.sumOf { it.get() }
                var lastMetaSaveTime = System.currentTimeMillis()

                val monitorJob = async(Dispatchers.Default) {
                    while (isActive) {
                        delay(250)
                        val now = System.currentTimeMillis()
                        val currentDownloaded = segmentDownloaded.sumOf { it.get() }
                        val timeDeltaSec = max((now - lastProgressTime) / 1000.0, 0.001)
                        val bytesDelta = (currentDownloaded - lastProgressBytes).coerceAtLeast(0L)

                        val speedMbps = (bytesDelta.toDouble() / timeDeltaSec) / (1024.0 * 1024.0)
                        val speedText = String.format("%.2f MB/s", speedMbps)
                        val remainingBytes = (totalSize - currentDownloaded).coerceAtLeast(0L)
                        val bytesPerSec = (speedMbps * 1024.0 * 1024.0).toLong()
                        val etaSec = if (bytesPerSec > 0) (remainingBytes / bytesPerSec).toInt() else 0

                        lastProgressTime = now
                        lastProgressBytes = currentDownloaded

                        // Build 8-slot visual segment progress bars for UI
                        val segmentBars = List(8) { slot ->
                            if (segmentCount == 1) {
                                (currentDownloaded.toFloat() / totalSize).coerceIn(0f, 1f)
                            } else {
                                val segIndex = (slot * segmentCount / 8).coerceIn(0, segmentCount - 1)
                                (segmentDownloaded[segIndex].get().toFloat() / segmentTotals[segIndex]).coerceIn(0f, 1f)
                            }
                        }

                        onProgress(currentDownloaded, totalSize, speedText, etaSec, segmentBars)

                        // Periodically flush meta file for resume safety
                        if (now - lastMetaSaveTime >= 2000L) {
                            saveMeta(metaFile, totalSize, segmentDownloaded)
                            lastMetaSaveTime = now
                        }

                        if (currentDownloaded >= totalSize) {
                            break
                        }
                    }
                }

                // Launch parallel segment downloader workers
                val workers = (0 until segmentCount).map { i ->
                    async(Dispatchers.IO) {
                        val downloadedSoFar = segmentDownloaded[i].get()
                        if (downloadedSoFar >= segmentTotals[i]) {
                            return@async // This segment is already complete
                        }

                        val reqStart = segmentStarts[i] + downloadedSoFar
                        val reqEnd = segmentEnds[i]

                        val reqBuilder = Request.Builder()
                            .url(url)
                            .header("User-Agent", USER_AGENT)

                        if (supportsRange) {
                            reqBuilder.header("Range", "bytes=$reqStart-$reqEnd")
                        }

                        val response = httpClient.newCall(reqBuilder.build()).execute()
                        if (!response.isSuccessful && response.code != 206) {
                            throw RuntimeException("HTTP ${response.code} error on segment $i")
                        }

                        val body = response.body ?: throw RuntimeException("Empty body on segment $i")
                        body.byteStream().use { input ->
                            // Each segment worker has its own independent file descriptor
                            RandomAccessFile(tempFile, "rw").use { raf ->
                                raf.seek(reqStart)
                                val buf = ByteArray(64 * 1024)
                                var readLen = 0
                                while (isActive && input.read(buf).also { readLen = it } != -1) {
                                    raf.write(buf, 0, readLen)
                                    segmentDownloaded[i].addAndGet(readLen.toLong())
                                }
                            }
                        }
                    }
                }

                try {
                    workers.awaitAll()
                } finally {
                    monitorJob.cancel()
                }
            }

            // Verify completed size
            if (tempFile.length() >= totalSize) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                if (metaFile.exists()) metaFile.delete()
                Log.i(tag, "[$taskTitle] Segmented download completed successfully: ${targetFile.name} (${targetFile.length()} bytes)")
                return true
            } else {
                downloadFailed = true
                failureMessage = "다운로드 크기 불일치 (${tempFile.length()} / $totalSize bytes)"
            }

        } catch (e: Exception) {
            downloadFailed = true
            failureMessage = "네트워크 전송 오류: ${e.localizedMessage ?: e.message}"
            Log.e(tag, "[$taskTitle] Error during segmented download", e)
            saveMeta(metaFile, totalSize, segmentDownloaded)
        }

        if (downloadFailed) {
            onError(failureMessage)
            return false
        }

        return true
    }

    private fun saveMeta(metaFile: File, totalSize: Long, segments: Array<AtomicLong>) {
        try {
            val content = buildString {
                appendLine(totalSize)
                segments.forEach {
                    appendLine(it.get())
                }
            }
            metaFile.writeText(content)
        } catch (_: Exception) {}
    }
}
