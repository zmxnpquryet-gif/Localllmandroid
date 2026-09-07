package com.example.engine

import android.util.Log
import com.example.model.LlmModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
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
        private const val DEFAULT_SEGMENTS = 4
        private const val MIN_SEGMENT_SIZE = 15 * 1024 * 1024L // 15MB

        fun safeFraction(downloaded: Long, total: Long): Float {
            if (total <= 0L || downloaded <= 0L) return 0f
            val f = downloaded.toFloat() / total.toFloat()
            return if (f.isNaN() || f.isInfinite()) 0f else f.coerceIn(0f, 1f)
        }
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun downloadUnifiedBundle(
        model: LlmModel,
        modelsDir: File
    ): Flow<DownloadStatus> = channelFlow {
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

            // Download component
            val success = downloadComponent(
                taskTitle = task.title,
                url = url,
                targetFile = targetFile,
                modelsDir = modelsDir,
                fallbackSize = task.fallbackEstimatedSize,
                onProgress = { taskDownloaded, taskTotal, speedText, etaSec, segmentBars ->
                    val overallDownloaded = bundleDownloadedBytes + taskDownloaded
                    val overallTotal = max(totalBundleBytes, overallDownloaded)
                    val overallProgress = safeFraction(overallDownloaded, overallTotal)
                    val taskProgress = safeFraction(taskDownloaded, taskTotal)

                    componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
                        progress = taskProgress,
                        downloadedBytes = taskDownloaded,
                        totalBytes = taskTotal,
                        speedText = speedText,
                        isCompleted = false
                    )

                    send(
                        DownloadStatus(
                            modelId = model.id,
                            progress = overallProgress,
                            downloadedBytes = overallDownloaded,
                            totalBytes = overallTotal,
                            speedText = speedText,
                            etaSeconds = etaSec,
                            activeComponentName = "${task.title} 다운로드 중 (${targetFile.name})",
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
                    send(
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
                return@channelFlow
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
        send(
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

    private suspend fun downloadComponent(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        fallbackSize: Long,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        val tempFile = File(modelsDir, "${targetFile.name}.downloading")
        var totalSize = fallbackSize
        var supportsRange = false

        try {
            val headReq = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .head()
                .build()

            httpClient.newCall(headReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    val ar = resp.header("Accept-Ranges")
                    if (cl != null && cl > 1000L) totalSize = cl
                    if (ar?.contains("bytes", ignoreCase = true) == true) supportsRange = true
                }
            }
        } catch (e: Exception) {
            Log.d(tag, "HEAD probe failed: ${e.message}")
        }

        if (!supportsRange) {
            try {
                val probeReq = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0")
                    .build()

                httpClient.newCall(probeReq).execute().use { resp ->
                    if (resp.code == 206) {
                        supportsRange = true
                        val cr = resp.header("Content-Range")
                        if (cr != null && cr.contains("/")) {
                            val parsed = cr.substringAfter("/").trim().toLongOrNull()
                            if (parsed != null && parsed > 1000L) totalSize = parsed
                        }
                    } else if (resp.isSuccessful) {
                        val cl = resp.header("Content-Length")?.toLongOrNull()
                        if (cl != null && cl > 1000L) totalSize = cl
                    }
                }
            } catch (e: Exception) {
                Log.d(tag, "Range probe check: ${e.message}")
            }
        }

        Log.i(tag, "[$taskTitle] Target size: $totalSize bytes (Range: $supportsRange)")

        return if (supportsRange && totalSize >= MIN_SEGMENT_SIZE) {
            val ok = downloadSegmented(
                taskTitle = taskTitle,
                url = url,
                tempFile = tempFile,
                targetFile = targetFile,
                totalSize = totalSize,
                segmentCount = DEFAULT_SEGMENTS,
                onProgress = onProgress,
                onError = { err ->
                    Log.w(tag, "Segmented download failed: $err, falling back to single stream")
                    false
                }
            )
            if (!ok) {
                downloadSingleStream(taskTitle, url, tempFile, targetFile, totalSize, onProgress, onError)
            } else true
        } else {
            downloadSingleStream(taskTitle, url, tempFile, targetFile, totalSize, onProgress, onError)
        }
    }

    private suspend fun downloadSegmented(
        taskTitle: String,
        url: String,
        tempFile: File,
        targetFile: File,
        totalSize: Long,
        segmentCount: Int,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Boolean
    ): Boolean {
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

        var downloadFailed = false
        var failureReason = ""

        try {
            coroutineScope {
                var lastProgressTime = System.currentTimeMillis()
                var lastProgressBytes = 0L

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

                        val segmentBars = List(8) { slot ->
                            val segIndex = (slot * segmentCount / 8).coerceIn(0, segmentCount - 1)
                            safeFraction(segmentDownloaded[segIndex].get(), segmentTotals[segIndex])
                        }

                        onProgress(currentDownloaded, totalSize, speedText, etaSec, segmentBars)

                        if (currentDownloaded >= totalSize) break
                    }
                }

                val workers = (0 until segmentCount).map { i ->
                    async(Dispatchers.IO) {
                        val reqStart = segmentStarts[i]
                        val reqEnd = segmentEnds[i]

                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", USER_AGENT)
                            .header("Range", "bytes=$reqStart-$reqEnd")
                            .build()

                        val resp = httpClient.newCall(req).execute()
                        if (!resp.isSuccessful && resp.code != 206) {
                            throw RuntimeException("HTTP ${resp.code} on segment $i")
                        }

                        val body = resp.body ?: throw RuntimeException("Empty body on segment $i")
                        body.byteStream().use { input ->
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

            if (tempFile.exists() && tempFile.length() > 0) {
                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                Log.i(tag, "[$taskTitle] Segmented download completed: ${targetFile.name} (${targetFile.length()} bytes)")
                return true
            } else {
                downloadFailed = true
                failureReason = "임시 파일이 비어 있습니다."
            }
        } catch (e: Exception) {
            downloadFailed = true
            failureReason = e.localizedMessage ?: "분할 다운로드 네트워크 실패"
            Log.w(tag, "[$taskTitle] Segmented download error: $failureReason", e)
        }

        if (downloadFailed) {
            return onError(failureReason)
        }

        return true
    }

    private suspend fun downloadSingleStream(
        taskTitle: String,
        url: String,
        tempFile: File,
        targetFile: File,
        totalSize: Long,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                onError("HTTP 오류 ${resp.code}: ${resp.message}")
                return false
            }

            val body = resp.body ?: run {
                onError("서버 응답 본문이 비어 있습니다.")
                return false
            }

            val actualLength = body.contentLength().takeIf { it > 0 } ?: totalSize
            var downloaded = 0L
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buf = ByteArray(64 * 1024)
                    var readLen = 0
                    while (input.read(buf).also { readLen = it } != -1) {
                        output.write(buf, 0, readLen)
                        downloaded += readLen

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 250L) {
                            val timeDelta = max((now - lastTime) / 1000.0, 0.001)
                            val speedMbps = ((downloaded - lastBytes).toDouble() / timeDelta) / (1024.0 * 1024.0)
                            val speedText = String.format("%.2f MB/s", speedMbps)
                            val bytesPerSec = (speedMbps * 1024.0 * 1024.0).toLong()
                            val remaining = (actualLength - downloaded).coerceAtLeast(0L)
                            val etaSec = if (bytesPerSec > 0) (remaining / bytesPerSec).toInt() else 0

                            val frac = safeFraction(downloaded, actualLength)
                            val segmentBars = List(8) { frac }

                            onProgress(downloaded, actualLength, speedText, etaSec, segmentBars)
                            lastTime = now
                            lastBytes = downloaded
                        }
                    }
                }
            }

            if (targetFile.exists()) targetFile.delete()
            tempFile.renameTo(targetFile)
            Log.i(tag, "[$taskTitle] Single stream download completed: ${targetFile.name} (${targetFile.length()} bytes)")
            return true
        } catch (e: Exception) {
            val err = "다운로드 실패: ${e.localizedMessage ?: e.message}"
            Log.e(tag, "[$taskTitle] Single stream download failed", e)
            onError(err)
            return false
        }
    }
}
