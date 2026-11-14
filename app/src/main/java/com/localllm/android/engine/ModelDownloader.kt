package com.localllm.android.engine

import android.util.Log
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import com.localllm.android.model.LlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit
import kotlin.math.max

enum class FdmComponentType(val label: String, val badge: String) {
    MAIN_MODEL(AppStrings.get(R.string.dl_enum_main), "MAIN"),
    VISION_TOWER(AppStrings.get(R.string.dl_enum_vision), "VISION"),
    MTP_DRAFTER(AppStrings.get(R.string.dl_enum_mtp), "MTP"),
    TEMPLATE(AppStrings.get(R.string.dl_enum_template), "TEMPLATE")
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
    val isCompleted: Boolean = false,
    val isSha256Verified: Boolean = false
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
    val localTemplatePath: String? = null,
    val integrityNote: IntegrityNote = IntegrityNote.NONE
)

enum class IntegrityNote {
    NONE,
    VERIFIED,
    NO_HASH_PUBLISHED
}

/**
 * Multi-component model bundle downloader.
 * Resolves URLs and Hugging Face LFS SHA-256 headers, delegates HTTP Range/single-stream
 * I/O to [SegmentedTransferWorker], and validates container headers via [ModelFileValidator].
 */
class ModelDownloader(client: OkHttpClient? = null) {

    private val tag = "ModelDownloader"

    companion object {
        private val USER_AGENT = "Mozilla/5.0 (Android; Mobile) OffconAI/${com.localllm.android.BuildConfig.VERSION_NAME} FDM"
        private const val DEFAULT_SEGMENTS = 4
        private const val MIN_SEGMENT_SIZE = 5 * 1024 * 1024L
        private const val BUFFER_SIZE = 256 * 1024
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        internal fun parseLinkedSha256(etag: String?): String? {
            val cleaned = etag?.trim()?.removePrefix("W/")?.trim()?.trim('"')?.lowercase() ?: return null
            return cleaned.takeIf { SHA256_HEX.matches(it) }
        }

        internal fun sha256Hex(file: File, onChunk: () -> Unit = {}): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    digest.update(buf, 0, n)
                    onChunk()
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }

        fun safeFraction(downloaded: Long, total: Long): Float {
            if (total <= 0L || downloaded <= 0L) return 0f
            val f = downloaded.toFloat() / total.toFloat()
            return if (f.isNaN() || f.isInfinite()) 0f else f.coerceIn(0f, 1f)
        }

        fun withHfAuth(builder: Request.Builder, requestUrl: String, token: String?): Request.Builder {
            val trimmed = token?.trim()
            if (trimmed.isNullOrBlank()) return builder
            if (!isHuggingFaceApiHost(requestUrl)) return builder
            builder.header("Authorization", "Bearer $trimmed")
            return builder
        }

        internal fun isHuggingFaceApiHost(requestUrl: String): Boolean {
            val httpUrl = try {
                requestUrl.toHttpUrlOrNull()
            } catch (_: Throwable) {
                null
            } ?: return false
            if (!httpUrl.isHttps) return false
            val host = httpUrl.host.lowercase()
            val isHfInfra = host == "huggingface.co" ||
                host.endsWith(".huggingface.co") ||
                host == "hf.co" ||
                host.endsWith(".hf.co")
            if (!isHfInfra) return false
            if (host.startsWith("cdn-") || ".cdn." in host) return false
            if (host.endsWith(".cloudfront.net") || host.endsWith(".amazonaws.com")) return false
            return true
        }
    }

    private val dispatcher = Dispatcher().apply {
        maxRequests = 16
        maxRequestsPerHost = 8
    }
    private val connectionPool = ConnectionPool(8, 3, TimeUnit.MINUTES)

    private val httpClient: OkHttpClient = client ?: OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectionPool(connectionPool)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .retryOnConnectionFailure(true)
        .build()

    private val transferWorker = SegmentedTransferWorker(
        httpClient = httpClient,
        userAgent = USER_AGENT,
        bufferSize = BUFFER_SIZE,
        defaultSegments = DEFAULT_SEGMENTS,
        minSegmentSize = MIN_SEGMENT_SIZE
    )

    private data class DownloadTask(
        val type: FdmComponentType,
        val title: String,
        val targetFile: File,
        val url: String,
        val fallbackEstimatedSize: Long
    )

    private data class ProbeResult(
        val finalUrl: String,
        val totalBytes: Long,
        val supportsRange: Boolean,
        val authError: Boolean = false,
        val errorMsg: String? = null,
        val expectedSha256: String? = null
    )

    private enum class ExistingVerdict { VERIFIED, UNVERIFIABLE, MISMATCH }

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

        val tasks = buildTasks(model, mainFile, visionFile, mtpFile, templateFile)
        val componentStatusMap = tasks.associate { task ->
            task.type to FdmComponentStatus(
                type = task.type,
                title = task.title,
                fileName = task.targetFile.name,
                url = task.url,
                totalBytes = task.fallbackEstimatedSize
            )
        }.toMutableMap()

        val totalBundleBytes = tasks.sumOf { it.fallbackEstimatedSize }
        var bundleDownloadedBytes = 0L

        for (task in tasks) {
            val componentType = task.type
            val targetFile = task.targetFile
            val url = task.url
            if (url.isBlank()) continue

            if (targetFile.exists() && targetFile.length() > 0 && targetFile.length() >= task.fallbackEstimatedSize * 0.95) {
                if (isContainerValid(targetFile)) {
                    when (verifyExistingFile(task.title, url, targetFile, task.fallbackEstimatedSize, effectiveToken)) {
                        ExistingVerdict.VERIFIED -> {
                            val existingSize = targetFile.length()
                            bundleDownloadedBytes += existingSize
                            componentStatusMap[componentType] = componentStatusMap.getValue(componentType).copy(
                                progress = 1.0f,
                                downloadedBytes = existingSize,
                                totalBytes = existingSize,
                                speedText = AppStrings.get(R.string.dl_done),
                                isCompleted = true,
                                isSha256Verified = true
                            )
                            continue
                        }
                        ExistingVerdict.UNVERIFIABLE -> {
                            val existingSize = targetFile.length()
                            bundleDownloadedBytes += existingSize
                            componentStatusMap[componentType] = componentStatusMap.getValue(componentType).copy(
                                progress = 1.0f,
                                downloadedBytes = existingSize,
                                totalBytes = existingSize,
                                speedText = AppStrings.get(R.string.dl_done),
                                isCompleted = true,
                                isSha256Verified = false
                            )
                            continue
                        }
                        ExistingVerdict.MISMATCH -> {
                            Log.w(tag, "[$componentType] Existing file failed SHA-256, re-downloading: ${targetFile.name}")
                        }
                    }
                } else {
                    Log.w(tag, "[$componentType] Existing file is invalid, deleting and re-downloading: ${targetFile.name}")
                    deleteOrQuarantine(targetFile)
                }
            }

            var sha256Verified = false
            val success = downloadComponent(
                taskTitle = task.title,
                url = url,
                targetFile = targetFile,
                modelsDir = modelsDir,
                fallbackSize = task.fallbackEstimatedSize,
                token = effectiveToken,
                onVerified = { sha256Verified = it },
                onProgress = { taskDownloaded, taskTotal, speedText, etaSec, segmentBars ->
                    val overallDownloaded = bundleDownloadedBytes + taskDownloaded
                    val overallTotal = max(totalBundleBytes, overallDownloaded)
                    val overallProgress = safeFraction(overallDownloaded, overallTotal)
                    val taskProgress = safeFraction(taskDownloaded, taskTotal)

                    componentStatusMap[componentType] = componentStatusMap.getValue(componentType).copy(
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
                            activeComponentName = AppStrings.get(R.string.dl_downloading_comp, task.title, targetFile.name),
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
                            speedText = AppStrings.get(R.string.dl_error),
                            errorMessage = err,
                            components = componentStatusMap.values.toList(),
                            isCompleted = false
                        )
                    )
                }
            )

            if (!success) return@channelFlow

            val isGguf = targetFile.name.endsWith(".gguf", ignoreCase = true)
            val isLiteRt = GgufMetadataDetector.isLiteRtModel(targetFile.name)
            if ((isGguf || isLiteRt) && !isContainerValid(targetFile)) {
                val errorReason = ModelFileValidator.diagnoseInvalidDownload(targetFile, isGguf)
                Log.e(tag, "[$componentType] Validation failed for ${targetFile.name}: $errorReason")
                if (isGguf) targetFile.delete() else deleteOrQuarantine(targetFile)
                send(
                    DownloadStatus(
                        modelId = model.id,
                        progress = 0f,
                        downloadedBytes = bundleDownloadedBytes,
                        totalBytes = totalBundleBytes,
                        speedText = AppStrings.get(R.string.dl_error),
                        errorMessage = errorReason,
                        components = if (isLiteRt) componentStatusMap.values.toList() else emptyList(),
                        isCompleted = false
                    )
                )
                return@channelFlow
            }

            val finalTaskSize = targetFile.length()
            bundleDownloadedBytes += finalTaskSize
            componentStatusMap[componentType] = componentStatusMap.getValue(componentType).copy(
                progress = 1.0f,
                downloadedBytes = finalTaskSize,
                totalBytes = finalTaskSize,
                speedText = AppStrings.get(R.string.dl_done),
                isCompleted = true,
                isSha256Verified = sha256Verified
            )
        }

        val mainVerified = componentStatusMap[FdmComponentType.MAIN_MODEL]?.isSha256Verified == true
        send(
            DownloadStatus(
                modelId = model.id,
                progress = 1.0f,
                downloadedBytes = bundleDownloadedBytes,
                totalBytes = bundleDownloadedBytes,
                speedText = AppStrings.get(R.string.dl_done),
                etaSeconds = 0,
                activeComponentName = AppStrings.get(R.string.dl_all_done),
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
                localTemplatePath = templateFile?.absolutePath,
                integrityNote = if (mainVerified) IntegrityNote.VERIFIED else IntegrityNote.NO_HASH_PUBLISHED
            )
        )
    }.flowOn(Dispatchers.IO)

    private fun buildTasks(
        model: LlmModel,
        mainFile: File,
        visionFile: File?,
        mtpFile: File?,
        templateFile: File?
    ): List<DownloadTask> = buildList {
        add(
            DownloadTask(
                type = FdmComponentType.MAIN_MODEL,
                title = AppStrings.get(R.string.dl_title_main),
                targetFile = mainFile,
                url = model.mainModelUrl.ifBlank {
                    if (model.repoId.isNotBlank()) "https://huggingface.co/${model.repoId}/resolve/main/${model.fileName}" else ""
                },
                fallbackEstimatedSize = if (model.sizeBytes > 0) model.sizeBytes else 800_000_000L
            )
        )
        if (visionFile != null && model.visionTowerUrl.isNotBlank()) {
            add(DownloadTask(FdmComponentType.VISION_TOWER, AppStrings.get(R.string.dl_title_vision), visionFile, model.visionTowerUrl, 350_000_000L))
        }
        if (mtpFile != null && model.mtpDrafterUrl.isNotBlank()) {
            add(DownloadTask(FdmComponentType.MTP_DRAFTER, AppStrings.get(R.string.dl_title_mtp), mtpFile, model.mtpDrafterUrl, 400_000_000L))
        }
        if (templateFile != null && model.templateFileUrl.isNotBlank()) {
            add(DownloadTask(FdmComponentType.TEMPLATE, AppStrings.get(R.string.dl_title_template), templateFile, model.templateFileUrl, 50_000L))
        }
    }

    private fun isContainerValid(file: File): Boolean = when {
        file.name.endsWith(".gguf", ignoreCase = true) -> ModelFileValidator.isGgufFile(file)
        GgufMetadataDetector.isLiteRtModel(file.name) -> ModelFileValidator.isLiteRtModelFile(file)
        else -> true
    }

    internal fun linkedSha256(resp: okhttp3.Response): String? {
        var current: okhttp3.Response? = resp
        while (current != null) {
            parseLinkedSha256(current.header("X-Linked-ETag"))?.let { return it }
            current = current.priorResponse
        }
        return null
    }

    private fun probeUrl(initialUrl: String, fallbackSize: Long, token: String? = null): ProbeResult {
        var resolvedUrl = initialUrl
        var expectedSha256: String? = null
        var totalBytes = fallbackSize
        var supportsRange = false
        var isAuthError = false
        var errorReason: String? = null

        try {
            val rangeReq = withHfAuth(
                Request.Builder()
                    .url(HfSource.rewrite(initialUrl))
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-0"),
                initialUrl,
                token
            ).build()

            httpClient.newCall(rangeReq).execute().use { resp ->
                resolvedUrl = resp.request.url.toString()
                if (resp.code == 206 || resp.isSuccessful) expectedSha256 = linkedSha256(resp)
                when {
                    resp.code == 401 || resp.code == 403 -> {
                        isAuthError = true
                        errorReason = AppStrings.get(R.string.dl_probe_401, resp.code)
                    }
                    resp.code == 404 -> {
                        errorReason = AppStrings.get(R.string.dl_probe_404)
                    }
                    resp.code == 206 -> {
                        val range = ContentRange.parse(resp.header("Content-Range"))
                        if (range != null && range.start == 0L && range.end == 0L) {
                            supportsRange = true
                            totalBytes = range.total
                        } else {
                            errorReason = "Invalid Content-Range on probe"
                        }
                    }
                    resp.isSuccessful -> {
                        val cl = resp.header("Content-Length")?.toLongOrNull()
                        if (cl != null && cl > 1000L) totalBytes = cl
                        if (resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true) {
                            supportsRange = true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Range probe check failed: ${e.message}")
        }

        if (!supportsRange && !isAuthError && errorReason == null) {
            try {
                val headReq = withHfAuth(
                    Request.Builder()
                        .url(HfSource.rewrite(resolvedUrl))
                        .header("User-Agent", USER_AGENT)
                        .head(),
                    resolvedUrl,
                    token
                ).build()

                httpClient.newCall(headReq).execute().use { resp ->
                    resolvedUrl = resp.request.url.toString()
                    if (expectedSha256 == null && resp.isSuccessful) expectedSha256 = linkedSha256(resp)
                    if (resp.code == 401 || resp.code == 403) {
                        isAuthError = true
                        errorReason = AppStrings.get(R.string.dl_probe_401, resp.code)
                    } else if (resp.isSuccessful) {
                        val cl = resp.header("Content-Length")?.toLongOrNull()
                        if (cl != null && cl > 1000L) totalBytes = cl
                        if (resp.header("Accept-Ranges")?.contains("bytes", ignoreCase = true) == true) {
                            supportsRange = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(tag, "HEAD probe check failed: ${e.message}")
            }
        }

        return ProbeResult(resolvedUrl, totalBytes, supportsRange, isAuthError, errorReason, expectedSha256)
    }

    private suspend fun downloadComponent(
        taskTitle: String,
        url: String,
        targetFile: File,
        modelsDir: File,
        fallbackSize: Long,
        token: String? = null,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit,
        onVerified: (Boolean) -> Unit = {}
    ): Boolean {
        val probe = probeUrl(url, fallbackSize, token)
        if (probe.authError || probe.errorMsg != null) {
            val err = probe.errorMsg ?: AppStrings.get(R.string.dl_auth_fail)
            Log.e(tag, "[$taskTitle] Probe failed: $err")
            onError(err)
            return false
        }

        val downloaded = transferWorker.fetch(
            taskTitle = taskTitle,
            finalUrl = probe.finalUrl,
            totalSize = probe.totalBytes,
            supportsRange = probe.supportsRange,
            targetFile = targetFile,
            modelsDir = modelsDir,
            token = token,
            onProgress = onProgress,
            onError = onError
        )
        if (!downloaded) return false

        val expected = probe.expectedSha256
        if (expected == null) {
            Log.w(tag, "[$taskTitle] No SHA-256 published by host; integrity not verified (format check only)")
            onVerified(false)
            return true
        }

        onProgress(targetFile.length(), targetFile.length(), "SHA-256 검증 중", 0, List(8) { 1f })
        val actual = try {
            val job = currentCoroutineContext()[Job]
            withContext(Dispatchers.IO) { sha256Hex(targetFile) { job?.ensureActive() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "[$taskTitle] SHA-256 computation failed: ${e.message}")
            onError("다운로드 파일 무결성 검증 중 오류가 발생했습니다. 다시 시도해 주세요.")
            return false
        }
        if (!actual.equals(expected, ignoreCase = true)) {
            Log.e(tag, "[$taskTitle] SHA-256 mismatch: expected=$expected actual=$actual")
            deleteOrQuarantine(targetFile)
            onError("다운로드 파일 무결성 검증 실패: SHA-256이 서버가 게시한 값과 다릅니다. 파일을 삭제했으니 다시 받아 주세요.")
            return false
        }
        Log.i(tag, "[$taskTitle] SHA-256 verified: $actual")
        onVerified(true)
        return true
    }

    private suspend fun verifyExistingFile(
        taskTitle: String,
        url: String,
        targetFile: File,
        fallbackSize: Long,
        token: String?
    ): ExistingVerdict {
        val probe = probeUrl(url, fallbackSize, token)
        val expected = probe.expectedSha256 ?: return ExistingVerdict.UNVERIFIABLE
        val actual = try {
            val job = currentCoroutineContext()[Job]
            withContext(Dispatchers.IO) { sha256Hex(targetFile) { job?.ensureActive() } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "[$taskTitle] Existing file hash check failed: ${e.message}")
            return ExistingVerdict.UNVERIFIABLE
        }
        return if (actual.equals(expected, ignoreCase = true)) {
            ExistingVerdict.VERIFIED
        } else {
            ExistingVerdict.MISMATCH
        }
    }

    private fun deleteOrQuarantine(file: File) {
        if (file.delete()) return
        val quarantine = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        if (!file.renameTo(quarantine)) {
            Log.e(tag, "Could not remove rejected file: ${file.absolutePath}")
        } else {
            Log.w(tag, "Quarantined undeletable file as ${quarantine.name}")
        }
    }
}
