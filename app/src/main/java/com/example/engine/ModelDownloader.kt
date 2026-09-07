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
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
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
 * Uses HTTP Range requests and parallel part files merged with kernel zero-copy transferTo.
 * Supports resume, cancellation, ETA, and per-segment progress monitoring.
 */
class ModelDownloader {

    private val tag = "ModelDownloader"

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Android; Mobile) LocalLLM/1.1.0 FDM"
        private const val DEFAULT_SEGMENTS = 4
        private const val MIN_SEGMENT_SIZE = 5 * 1024 * 1024L // 5MB minimum for segmented
        private const val BUFFER_SIZE = 256 * 1024 // 256KB buffer for max I/O throughput

        fun safeFraction(downloaded: Long, total: Long): Float {
            if (total <= 0L || downloaded <= 0L) return 0f
            val f = downloaded.toFloat() / total.toFloat()
            return if (f.isNaN() || f.isInfinite()) 0f else f.coerceIn(0f, 1f)
        }

        fun withHfAuth(builder: Request.Builder, requestUrl: String, token: String?): Request.Builder {
            val trimmed = token?.trim()
            if (!trimmed.isNullOrBlank() &&
                requestUrl.contains("huggingface.co", ignoreCase = true) &&
                !requestUrl.contains(".cdn.", ignoreCase = true) &&
                !requestUrl.contains("cloudfront.net", ignoreCase = true) &&
                !requestUrl.contains("amazonaws.com", ignoreCase = true)
            ) {
                builder.header("Authorization", "Bearer $trimmed")
            }
            return builder
        }
    }

    private val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 32
    }
    private val connectionPool = ConnectionPool(16, 5, TimeUnit.MINUTES)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    fun downloadUnifiedBundle(
        model: LlmModel,
        modelsDir: File,
        hfTokenOverride: String? = null
    ): Flow<DownloadStatus> = channelFlow {
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val effectiveToken = hfTokenOverride?.ifBlank { null } ?: model.hfToken?.ifBlank { null }
        val mainFile = File(modelsDir, model.fileName)
        val visionFile = if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
            File(modelsDir, model.mmprojFileName ?: "mmproj-${model.fileName}")
        } else null
        val mtpFile = if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
            File(modelsDir, model.mtpDrafterFileName ?: "draft-${model.fileName}")
        } else null
        val templateFile = if (model.templateFileUrl.isNotBlank() || model.templateFileName != null) {
            val defaultExt = if (model.templateFileUrl.contains(".jinja", ignoreCase = true)) "jinja" else "json"
            File(modelsDir, model.templateFileName ?: "${model.fileName.substringBeforeLast('.')}-template.$defaultExt")
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
                val isGguf = targetFile.name.endsWith(".gguf", ignoreCase = true)
                val isValid = if (isGguf) isGgufFile(targetFile) else true
                if (isValid) {
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
                } else {
                    Log.w(tag, "[$componentType] Existing file is not valid GGUF, deleting and re-downloading: ${targetFile.name}")
                    targetFile.delete()
                }
            }

            // Download component
            val success = downloadComponent(
                taskTitle = task.title,
                url = url,
                targetFile = targetFile,
                modelsDir = modelsDir,
                fallbackSize = task.fallbackEstimatedSize,
                token = effectiveToken,
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

            // Verify GGUF format if target is a GGUF file
            if (targetFile.name.endsWith(".gguf", ignoreCase = true)) {
                if (!isGgufFile(targetFile)) {
                    val preview = readTextPreview(targetFile)
                    val errorReason = when {
                        preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true) ->
                            "다운로드 실패: Hugging Face 인증 필요 (401 Unauthorized). 설정에서 유효한 HF 토큰을 입력해 주세요."
                        preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                            "다운로드 실패: 모델 파일 대신 HTML 웹페이지가 다운로드되었습니다. 링크 및 권한을 확인하세요."
                        else ->
                            "다운로드 완료 후 파일 검증 실패: 유효한 GGUF 파일 형식이 아닙니다."
                    }
                    Log.e(tag, "[$componentType] Validation failed for ${targetFile.name}: $errorReason")
                    targetFile.delete()
                    send(
                        DownloadStatus(
                            modelId = model.id,
                            progress = 0f,
                            downloadedBytes = bundleDownloadedBytes,
                            totalBytes = totalBundleBytes,
                            speedText = "오류",
                            errorMessage = errorReason,
                            isCompleted = false
                        )
                    )
                    return@channelFlow
                }
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

    private data class ProbeResult(
        val finalUrl: String,
        val totalBytes: Long,
        val supportsRange: Boolean
    )

    private fun probeUrl(initialUrl: String, fallbackSize: Long, token: String? = null): ProbeResult {
        var resolvedUrl = initialUrl
        var totalBytes = fallbackSize
        var supportsRange = false

        // 1. Probe with Range: bytes=0-0 to resolve redirects and test Range in 1 request
        try {
            val rangeReq = withHfAuth(
                Request.Builder()
                    .url(initialUrl)
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0"),
                initialUrl,
                token
            ).build()

            httpClient.newCall(rangeReq).execute().use { resp ->
                resolvedUrl = resp.request.url.toString()
                if (resp.code == 206) {
                    supportsRange = true
                    val cr = resp.header("Content-Range")
                    if (cr != null && cr.contains("/")) {
                        val parsed = cr.substringAfter("/").trim().toLongOrNull()
                        if (parsed != null && parsed > 1000L) totalBytes = parsed
                    }
                } else if (resp.isSuccessful) {
                    val cl = resp.header("Content-Length")?.toLongOrNull()
                    if (cl != null && cl > 1000L) totalBytes = cl
                    val ar = resp.header("Accept-Ranges")
                    if (ar?.contains("bytes", ignoreCase = true) == true) supportsRange = true
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Range probe check failed: ${e.message}")
        }

        // 2. Fallback HEAD probe if Range didn't confirm
        if (!supportsRange) {
            try {
                val headReq = withHfAuth(
                    Request.Builder()
                        .url(resolvedUrl)
                        .header("User-Agent", USER_AGENT)
                        .head(),
                    resolvedUrl,
                    token
                ).build()

                httpClient.newCall(headReq).execute().use { resp ->
                    resolvedUrl = resp.request.url.toString()
                    if (resp.isSuccessful) {
                        val cl = resp.header("Content-Length")?.toLongOrNull()
                        if (cl != null && cl > 1000L) totalBytes = cl
                        val ar = resp.header("Accept-Ranges")
                        if (ar?.contains("bytes", ignoreCase = true) == true) supportsRange = true
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "HEAD probe check failed: ${e.message}")
            }
        }

        return ProbeResult(resolvedUrl, totalBytes, supportsRange)
    }

    private suspend fun downloadComponent(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        fallbackSize: Long,
        token: String? = null,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        val probe = probeUrl(url, fallbackSize, token)
        val finalUrl = probe.finalUrl
        val totalSize = probe.totalBytes
        val supportsRange = probe.supportsRange

        Log.i(tag, "[$taskTitle] Target size: $totalSize bytes (Range: $supportsRange, URL: $finalUrl)")

        return if (supportsRange && totalSize >= MIN_SEGMENT_SIZE) {
            val ok = downloadSegmented(
                taskTitle = taskTitle,
                url = finalUrl,
                targetFile = targetFile,
                modelsDir = modelsDir,
                totalSize = totalSize,
                segmentCount = DEFAULT_SEGMENTS,
                token = token,
                onProgress = onProgress,
                onError = { err ->
                    Log.w(tag, "Segmented download failed: $err, falling back to single stream")
                    false
                }
            )
            if (!ok) {
                downloadSingleStream(taskTitle, finalUrl, targetFile, modelsDir, totalSize, token, onProgress, onError)
            } else true
        } else {
            downloadSingleStream(taskTitle, finalUrl, targetFile, modelsDir, totalSize, token, onProgress, onError)
        }
    }

    private suspend fun downloadSegmented(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        totalSize: Long,
        segmentCount: Int,
        token: String? = null,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Boolean
    ): Boolean {
        val partFiles = (0 until segmentCount).map { i -> File(modelsDir, "${targetFile.name}.part_$i") }
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
                        val partFile = partFiles[i]
                        val segTotal = segmentTotals[i]
                        var attempt = 0
                        val maxAttempts = 3

                        while (isActive && attempt < maxAttempts) {
                            attempt++
                            val currentPartLen = if (partFile.exists()) partFile.length() else 0L
                            if (currentPartLen >= segTotal) {
                                segmentDownloaded[i].set(segTotal)
                                return@async
                            }

                            val reqStart = segmentStarts[i] + currentPartLen
                            val reqEnd = segmentEnds[i]

                            try {
                                val req = withHfAuth(
                                    Request.Builder()
                                        .url(url)
                                        .header("User-Agent", USER_AGENT)
                                        .header("Range", "bytes=$reqStart-$reqEnd"),
                                    url,
                                    token
                                ).build()

                                val resp = httpClient.newCall(req).execute()
                                if (!resp.isSuccessful && resp.code != 206 && resp.code != 200) {
                                    resp.close()
                                    throw RuntimeException("HTTP ${resp.code} on segment $i")
                                }

                                val body = resp.body ?: throw RuntimeException("Empty body on segment $i")
                                body.byteStream().use { input ->
                                    BufferedOutputStream(FileOutputStream(partFile, currentPartLen > 0), BUFFER_SIZE).use { output ->
                                        val buf = ByteArray(BUFFER_SIZE)
                                        var readLen = 0
                                        while (isActive && input.read(buf).also { readLen = it } != -1) {
                                            output.write(buf, 0, readLen)
                                            val newLen = segmentDownloaded[i].addAndGet(readLen.toLong())
                                            if (newLen >= segTotal) break
                                        }
                                        output.flush()
                                    }
                                }

                                if (partFile.length() >= segTotal) {
                                    segmentDownloaded[i].set(segTotal)
                                    return@async
                                }
                            } catch (e: Exception) {
                                Log.w(tag, "Segment $i attempt $attempt failed: ${e.message}")
                                if (attempt >= maxAttempts) throw e
                                delay(500)
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

            // Verify all parts are complete
            val allComplete = (0 until segmentCount).all { i ->
                partFiles[i].exists() && partFiles[i].length() >= segmentTotals[i]
            }

            if (allComplete) {
                mergeParts(partFiles, targetFile, modelsDir)
                Log.i(tag, "[$taskTitle] Segmented download & zero-copy merge completed: ${targetFile.name} (${targetFile.length()} bytes)")
                return true
            } else {
                downloadFailed = true
                failureReason = "일부 분할 청크 다운로드 불완전"
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
                        if (count <= 0) break
                        transferred += count
                    }
                }
            }
            outChannel.force(true)
        }

        if (targetFile.exists()) targetFile.delete()
        tempMerged.renameTo(targetFile)
        partFiles.forEach { it.delete() }
    }

    private suspend fun downloadSingleStream(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        totalSize: Long,
        token: String? = null,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        val tempFile = File(modelsDir, "${targetFile.name}.downloading")
        try {
            val req = withHfAuth(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT),
                url,
                token
            ).build()

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
                BufferedOutputStream(FileOutputStream(tempFile, false), BUFFER_SIZE).use { output ->
                    val buf = ByteArray(BUFFER_SIZE)
                    var readLen = 0
                    while (input.read(buf).also { readLen = it } != -1) {
                        output.write(buf, 0, readLen)
                        downloaded += readLen

                        val now = System.currentTimeMillis()
                        if (now - lastTime >= 200L) {
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
                    output.flush()
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

    private fun isGgufFile(file: File): Boolean {
        if (!file.exists() || file.length() < 4) return false
        return try {
            FileInputStream(file).use { fis ->
                val h = ByteArray(4)
                fis.read(h) == 4 &&
                    h[0] == 'G'.code.toByte() &&
                    h[1] == 'G'.code.toByte() &&
                    h[2] == 'U'.code.toByte() &&
                    h[3] == 'F'.code.toByte()
            }
        } catch (_: Exception) { false }
    }

    private fun readTextPreview(file: File): String {
        if (!file.exists() || file.length() == 0L) return ""
        return try {
            FileInputStream(file).use { fis ->
                val buf = ByteArray(256)
                val len = fis.read(buf)
                if (len > 0) String(buf, 0, len, Charsets.UTF_8).trim() else ""
            }
        } catch (_: Exception) { "" }
    }
}
