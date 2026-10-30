package com.localllm.android.engine

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

import android.util.Log
import com.localllm.android.model.LlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
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
    /** True only when the finished file's SHA-256 matched a hash published by the host. */
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
    /** Overall integrity signal for the finished bundle. NONE while downloading. */
    val integrityNote: IntegrityNote = IntegrityNote.NONE
)

enum class IntegrityNote {
    NONE,
    /** The main weights' SHA-256 matched the hash the host published. */
    VERIFIED,
    /** The host published no hash: finished with a format check only. */
    NO_HASH_PUBLISHED
}

/**
 * High-performance Multi-thread Segmented Range Downloader (FDM engine).
 * Uses HTTP Range requests and parallel part files merged with kernel zero-copy transferTo.
 * Supports resume, cancellation, ETA, and per-segment progress monitoring.
 */
class ModelDownloader(client: OkHttpClient? = null) {

    private val tag = "ModelDownloader"

    companion object {
        private val USER_AGENT = "Mozilla/5.0 (Android; Mobile) LocalLLM/${com.localllm.android.BuildConfig.VERSION_NAME} FDM"
        private const val DEFAULT_SEGMENTS = 4
        private const val MIN_SEGMENT_SIZE = 5 * 1024 * 1024L // 5MB minimum for segmented
        private const val BUFFER_SIZE = 256 * 1024 // 256KB buffer for max I/O throughput
        private val SHA256_HEX = Regex("^[0-9a-f]{64}$")

        /**
         * Extracts a SHA-256 from Hugging Face's `X-Linked-ETag` header. For LFS files
         * (every GGUF / LiteRT weight) that header is the file's SHA-256; for small
         * git-stored files the ETag is a 40-char git SHA-1 and is deliberately ignored.
         */
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

        /**
         * Attaches the Hugging Face bearer token only when the *actual request host*
         * is Hugging Face infrastructure over HTTPS. String-matching the URL is not
         * sufficient: an attacker-controlled host could embed "huggingface.co" in its
         * path/query (e.g. https://evil.example/?next=huggingface.co) and harvest the
         * token. CDN pre-signed redirect targets carry their own auth, so the token
         * is withheld there as well.
         */
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
            // The hf-mirror.com mirror is operated by a third party: never send the
            // user's Hugging Face token there. Mirror downloads work unauthenticated.
            if (!isHfInfra) return false
            // Redirect targets / CDN endpoints embed their own pre-signed auth.
            // Never forward the user's token there.
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
                title = AppStrings.get(R.string.dl_title_main),
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
                    title = AppStrings.get(R.string.dl_title_vision),
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
                    title = AppStrings.get(R.string.dl_title_mtp),
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
                    title = AppStrings.get(R.string.dl_title_template),
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

            // If already fully downloaded and valid, still check the published hash:
            // files from older builds (or an interrupted verification) were never
            // hash-checked, and accepting them blindly would grandfather tampering in.
            if (targetFile.exists() && targetFile.length() > 0 && targetFile.length() >= task.fallbackEstimatedSize * 0.95) {
                val isGguf = targetFile.name.endsWith(".gguf", ignoreCase = true)
                val isLiteRt = GgufMetadataDetector.isLiteRtModel(targetFile.name)
                val isValid = if (isGguf) isGgufFile(targetFile)
                else if (isLiteRt) isLitertModelFile(targetFile)
                else true

                if (isValid) {
                    when (verifyExistingFile(task.title, url, targetFile, task.fallbackEstimatedSize, effectiveToken)) {
                        ExistingVerdict.VERIFIED -> {
                            val existingSize = targetFile.length()
                            bundleDownloadedBytes += existingSize
                            componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
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
                            // The host published no hash (or we are offline): keep the
                            // file on the format check alone, but never claim verification.
                            val existingSize = targetFile.length()
                            bundleDownloadedBytes += existingSize
                            componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
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
                            // Fall through to a fresh download below.
                        }
                    }
                } else {
                    Log.w(tag, "[$componentType] Existing file is invalid, deleting and re-downloading: ${targetFile.name}")
                    deleteOrQuarantine(targetFile)
                }
            }

            // Download component
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

            if (!success) {
                return@channelFlow
            }

            // Verify format if target is GGUF or LiteRT
            val isGguf = targetFile.name.endsWith(".gguf", ignoreCase = true)
            val isLiteRt = GgufMetadataDetector.isLiteRtModel(targetFile.name)

            if (isGguf) {
                if (!isGgufFile(targetFile)) {
                    val preview = readTextPreview(targetFile)
                    val errorReason = when {
                        preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true) ->
                            AppStrings.get(R.string.dl_fail_401)
                        preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                            AppStrings.get(R.string.dl_fail_html)
                        else ->
                            AppStrings.get(R.string.dl_verify_gguf)
                    }
                    Log.e(tag, "[$componentType] Validation failed for ${targetFile.name}: $errorReason")
                    targetFile.delete()
                    send(
                        DownloadStatus(
                            modelId = model.id,
                            progress = 0f,
                            downloadedBytes = bundleDownloadedBytes,
                            totalBytes = totalBundleBytes,
                            speedText = AppStrings.get(R.string.dl_error),
                            errorMessage = errorReason,
                            isCompleted = false
                        )
                    )
                    return@channelFlow
                }
            } else if (isLiteRt) {
                if (!isLitertModelFile(targetFile)) {
                    val preview = readTextPreview(targetFile)
                    val errorReason = when {
                        preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true) ->
                            AppStrings.get(R.string.dl_fail_401)
                        preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                            AppStrings.get(R.string.dl_fail_html)
                        targetFile.length() < 1024 * 1024L ->
                            AppStrings.get(R.string.dl_fail_small, targetFile.length())
                        else ->
                            AppStrings.get(R.string.dl_verify_litert)
                    }
                    Log.e(tag, "[$componentType] LiteRT validation failed for ${targetFile.name}: $errorReason")
                    deleteOrQuarantine(targetFile)
                    send(
                        DownloadStatus(
                            modelId = model.id,
                            progress = 0f,
                            downloadedBytes = bundleDownloadedBytes,
                            totalBytes = totalBundleBytes,
                            speedText = AppStrings.get(R.string.dl_error),
                            errorMessage = errorReason,
                            components = componentStatusMap.values.toList(),
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
                speedText = AppStrings.get(R.string.dl_done),
                isCompleted = true,
                isSha256Verified = sha256Verified
            )
        }

        // All components completed. The headline signal covers the main weights:
        // a bundle whose weights verified is VERIFIED even if a tiny sidecar file
        // (template) had no published hash; anything else is reported honestly.
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

    private data class ProbeResult(
        val finalUrl: String,
        val totalBytes: Long,
        val supportsRange: Boolean,
        val authError: Boolean = false,
        val errorMsg: String? = null,
        val expectedSha256: String? = null
    )

    private data class ContentRange(val start: Long, val end: Long, val total: Long)

    private fun parseContentRange(value: String?): ContentRange? {
        val match = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)", RegexOption.IGNORE_CASE)
            .matchEntire(value?.trim() ?: return null) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: return null
        return if (start <= end && end < total) ContentRange(start, end, total) else null
    }

    /**
     * The hash header sits on the first (huggingface.co) hop, not on the CDN response
     * OkHttp ends with — hence the walk over the redirect chain. Internal for tests:
     * the fakes cannot reproduce OkHttp's redirect chaining, so the chain walk is
     * pinned by a dedicated unit test instead.
     */
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

        // 1. Probe with Range: bytes=0-0 to resolve redirects and test Range in 1 request
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
                // Only successful responses speak for the file: an error page's
                // headers must never become the expected hash.
                if (resp.code == 206 || resp.isSuccessful) expectedSha256 = linkedSha256(resp)
                if (resp.code == 401 || resp.code == 403) {
                    isAuthError = true
                    errorReason = AppStrings.get(R.string.dl_probe_401, resp.code)
                } else if (resp.code == 404) {
                    errorReason = AppStrings.get(R.string.dl_probe_404)
                } else if (resp.code == 206) {
                    val range = parseContentRange(resp.header("Content-Range"))
                    if (range != null && range.start == 0L && range.end == 0L) {
                        supportsRange = true
                        totalBytes = range.total
                    } else {
                        errorReason = "Invalid Content-Range on probe"
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

        // 2. Fallback HEAD probe if Range didn't confirm and no auth error
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
                        val ar = resp.header("Accept-Ranges")
                        if (ar?.contains("bytes", ignoreCase = true) == true) supportsRange = true
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

        val downloaded = fetchComponent(taskTitle, probe, targetFile, modelsDir, token, onProgress, onError)
        if (!downloaded) return false

        val expected = probe.expectedSha256
        if (expected == null) {
            // The host published no hash (non-LFS file or a non-HF mirror): say so
            // instead of implying a verification happened.
            Log.w(tag, "[$taskTitle] No SHA-256 published by host; integrity not verified (format check only)")
            onVerified(false)
            return true
        }

        onProgress(targetFile.length(), targetFile.length(), "SHA-256 검증 중", 0, List(8) { 1f })
        // A cancelled or failed verification leaves the file alone on purpose: the
        // existing-file path re-verifies it on the next run instead of trusting it.
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

    private enum class ExistingVerdict { VERIFIED, UNVERIFIABLE, MISMATCH }

    /**
     * Re-checks a file that is already on disk against the hash the host publishes
     * today. MISMATCH deletes nothing here — the caller falls through to a fresh
     * download, which replaces the file.
     */
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
            Log.i(tag, "[$taskTitle] Existing file SHA-256 verified: $actual")
            ExistingVerdict.VERIFIED
        } else {
            Log.e(tag, "[$taskTitle] Existing file SHA-256 mismatch: expected=$expected actual=$actual")
            ExistingVerdict.MISMATCH
        }
    }

    /**
     * Deletes a rejected file. When deletion itself fails (locks, storage errors),
     * the file is quarantined out of the way so the existing-file path can never
     * accept it on the next run.
     */
    private fun deleteOrQuarantine(file: File) {
        if (file.delete()) return
        val quarantine = File(file.parentFile, "${file.name}.corrupt-${System.currentTimeMillis()}")
        if (!file.renameTo(quarantine)) {
            Log.e(tag, "Could not remove rejected file: ${file.absolutePath}")
        } else {
            Log.w(tag, "Quarantined undeletable file as ${quarantine.name}")
        }
    }

    private suspend fun fetchComponent(
        taskTitle: String,
        probe: ProbeResult,
        targetFile: File,
        modelsDir: File,
        token: String?,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
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
        // Resume sidecar: part files are only valid for the exact total they were
        // started with. Without it, a changed remote revision on a hash-less host
        // would silently merge stale parts with fresh bytes.
        val metaFile = File(modelsDir, "${targetFile.name}.partsmeta")
        val metaTotal = try {
            if (metaFile.isFile) metaFile.readText().trim().toLongOrNull() else null
        } catch (_: Exception) {
            null
        }
        when {
            metaTotal == null -> {
                // No sidecar means no proof the parts belong to this remote
                // revision: fail closed instead of merging stale bytes.
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
                        val timeDeltaSec = max((now - lastProgressTime) / 1000.0, 0.001)
                        val bytesDelta = (currentDownloaded - lastProgressBytes).coerceAtLeast(0L)

                        val speedMbps = (bytesDelta.toDouble() / timeDeltaSec) / (1024.0 * 1024.0)
                        val speedText = String.format(Locale.US, "%.2f MB/s", speedMbps)
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
                            if (currentPartLen > segTotal) {
                                throw IOException("Oversized part on segment $i")
                            }
                            if (currentPartLen == segTotal) {
                                segmentDownloaded[i].set(segTotal)
                                return@async
                            }

                            val reqStart = segmentStarts[i] + currentPartLen
                            val reqEnd = segmentEnds[i]

                            try {
                                val req = withHfAuth(
                                    Request.Builder()
                                        .url(HfSource.rewrite(url))
                                        .header("User-Agent", USER_AGENT)
                                        .header("Range", "bytes=$reqStart-$reqEnd"),
                                    url,
                                    token
                                ).build()

                                httpClient.newCall(req).execute().use { resp ->
                                    if (resp.code != 206) {
                                        throw IOException("Expected HTTP 206 on segment $i, got ${resp.code}")
                                    }
                                    val range = parseContentRange(resp.header("Content-Range"))
                                    if (range != ContentRange(reqStart, reqEnd, totalSize)) {
                                        throw IOException("Invalid Content-Range on segment $i")
                                    }
                                    val body = resp.body ?: throw IOException("Empty body on segment $i")
                                    val expectedLength = reqEnd - reqStart + 1L
                                    if (body.contentLength() != -1L && body.contentLength() != expectedLength) {
                                        throw IOException("Invalid Content-Length on segment $i")
                                    }
                                    try {
                                        body.byteStream().use { input ->
                                            BufferedOutputStream(FileOutputStream(partFile, currentPartLen > 0), BUFFER_SIZE).use { output ->
                                                val buf = ByteArray(BUFFER_SIZE)
                                                var remaining = expectedLength
                                                while (remaining > 0L) {
                                                    ensureActive()
                                                    val readLen = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                                    if (readLen == -1) throw IOException("Short body on segment $i")
                                                    output.write(buf, 0, readLen)
                                                    remaining -= readLen
                                                    segmentDownloaded[i].addAndGet(readLen.toLong())
                                                }
                                                ensureActive()
                                                if (input.read() != -1) throw IOException("Oversized body on segment $i")
                                            }
                                        }
                                        if (partFile.length() != segTotal) {
                                            throw IOException("Invalid part length on segment $i")
                                        }
                                    } catch (e: Exception) {
                                        RandomAccessFile(partFile, "rw").use { it.setLength(currentPartLen) }
                                        segmentDownloaded[i].set(currentPartLen)
                                        throw e
                                    }
                                }
                                segmentDownloaded[i].set(segTotal)
                                return@async
                            } catch (e: CancellationException) {
                                throw e
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
                partFiles[i].exists() && partFiles[i].length() == segmentTotals[i]
            }

            if (allComplete) {
                mergeParts(partFiles, targetFile, modelsDir)
                File(modelsDir, "${targetFile.name}.partsmeta").delete()
                Log.i(tag, "[$taskTitle] Segmented download & zero-copy merge completed: ${targetFile.name} (${targetFile.length()} bytes)")
                return true
            } else {
                downloadFailed = true
                failureReason = AppStrings.get(R.string.dl_chunk_incomplete)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            downloadFailed = true
            failureReason = e.localizedMessage ?: AppStrings.get(R.string.dl_net_fail)
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
        token: String? = null,
        onProgress: suspend (Long, Long, String, Int, List<Float>) -> Unit,
        onError: suspend (String) -> Unit
    ): Boolean {
        val tempFile = File(modelsDir, "${targetFile.name}.downloading")
        var published = false
        try {
            val req = withHfAuth(
                Request.Builder()
                    .url(HfSource.rewrite(url))
                    .header("User-Agent", USER_AGENT),
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
                    BufferedOutputStream(FileOutputStream(tempFile, false), BUFFER_SIZE).use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var readLen = 0
                        while (input.read(buf).also { readLen = it } != -1) {
                            // A blocking socket read does not observe coroutine
                            // cancellation; check between chunks so Stop is prompt.
                            currentCoroutineContext().ensureActive()
                            output.write(buf, 0, readLen)
                            downloaded += readLen

                            val now = System.currentTimeMillis()
                            if (now - lastTime >= 200L) {
                                val timeDelta = max((now - lastTime) / 1000.0, 0.001)
                                val speedMbps = ((downloaded - lastBytes).toDouble() / timeDelta) / (1024.0 * 1024.0)
                                val speedText = String.format(Locale.US, "%.2f MB/s", speedMbps)
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

                // A cleanly-ended chunked body can truncate silently; never
                // publish a short file as a completed download.
                if (actualLength > 0 && downloaded != actualLength) {
                    tempFile.delete()
                    onError("다운로드가 중단되었습니다: ${downloaded}/${actualLength} bytes")
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

    private fun isLitertModelFile(file: File): Boolean {
        if (!file.exists() || file.length() < 1024 * 1024L) return false
        val preview = readTextPreview(file)
        if (preview.contains("Unauthorized", ignoreCase = true) ||
            preview.contains("401", ignoreCase = true) ||
            preview.startsWith("<!DOCTYPE", ignoreCase = true) ||
            preview.startsWith("<html", ignoreCase = true) ||
            preview.contains("{\"error\"", ignoreCase = true)
        ) {
            return false
        }
        return true
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
