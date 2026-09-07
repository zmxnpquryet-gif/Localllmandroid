package com.example.engine

import android.util.Log
import com.example.model.LlmModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

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

class ModelDownloader {

    private val tag = "ModelDownloader"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    /**
     * Downloads genuine GGUF models directly from Hugging Face or direct HTTP URLs.
     * Streams actual binary bytes to local storage without fake placeholders.
     */
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
        var totalBytesDownloadedSoFar = 0L

        var lastEmitTime = System.currentTimeMillis()
        var lastDownloadedForSpeed = 0L
        var currentSpeedText = "0.0 MB/s"

        for (task in tasks) {
            val componentType = task.type
            val targetFile = task.targetFile
            val url = task.url

            if (url.isBlank()) {
                continue
            }

            var taskTotalBytes = task.fallbackEstimatedSize
            var taskDownloaded = 0L

            val tempFile = File(modelsDir, "${targetFile.name}.downloading")

            try {
                Log.i(tag, "Starting real download of ${task.title} from: $url")
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile) OnDeviceLLM/1.0")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful || response.body == null) {
                    val errorMsg = "다운로드 서버 응답 오류 (HTTP ${response.code}): ${task.title}"
                    Log.e(tag, errorMsg)
                    emit(
                        DownloadStatus(
                            modelId = model.id,
                            progress = 0f,
                            downloadedBytes = totalBytesDownloadedSoFar,
                            totalBytes = totalBundleBytes,
                            speedText = "오류",
                            errorMessage = errorMsg,
                            isCompleted = false
                        )
                    )
                    return@flow
                }

                val body = response.body!!
                val contentLength = body.contentLength()
                if (contentLength > 0) {
                    taskTotalBytes = contentLength
                    totalBundleBytes = totalBundleBytes - task.fallbackEstimatedSize + taskTotalBytes
                }

                val inputStream: InputStream = body.byteStream()
                val outputStream = FileOutputStream(tempFile)

                val buffer = ByteArray(64 * 1024)
                var bytesRead: Int

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    outputStream.write(buffer, 0, bytesRead)
                    taskDownloaded += bytesRead
                    totalBytesDownloadedSoFar += bytesRead

                    val now = System.currentTimeMillis()
                    val timeDelta = now - lastEmitTime
                    if (timeDelta >= 250L) {
                        val bytesDelta = totalBytesDownloadedSoFar - lastDownloadedForSpeed
                        val speedMbps = (bytesDelta.toDouble() / (timeDelta.toDouble() / 1000.0)) / (1024.0 * 1024.0)
                        currentSpeedText = String.format("%.2f MB/s", speedMbps.coerceAtLeast(0.0))

                        lastEmitTime = now
                        lastDownloadedForSpeed = totalBytesDownloadedSoFar

                        val taskProg = (taskDownloaded.toFloat() / taskTotalBytes).coerceIn(0f, 0.99f)
                        val bundleProg = (totalBytesDownloadedSoFar.toFloat() / totalBundleBytes).coerceIn(0f, 0.99f)

                        val segments = List(8) { i ->
                            ((bundleProg * 8f) - i).coerceIn(0f, 1f)
                        }

                        val remainingBytes = (totalBundleBytes - totalBytesDownloadedSoFar).coerceAtLeast(0L)
                        val bytesPerSec = (speedMbps * 1024.0 * 1024.0).toLong()
                        val etaSeconds = if (bytesPerSec > 0) (remainingBytes / bytesPerSec).toInt() else 0

                        componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
                            progress = taskProg,
                            downloadedBytes = taskDownloaded,
                            totalBytes = taskTotalBytes,
                            speedText = currentSpeedText,
                            isCompleted = false
                        )

                        emit(
                            DownloadStatus(
                                modelId = model.id,
                                progress = bundleProg,
                                downloadedBytes = totalBytesDownloadedSoFar,
                                totalBytes = totalBundleBytes,
                                speedText = currentSpeedText,
                                etaSeconds = etaSeconds,
                                activeComponentName = "${task.title} 다운로드 중 (${targetFile.name})",
                                mainProgress = componentStatusMap[FdmComponentType.MAIN_MODEL]?.progress ?: 0f,
                                visionProgress = componentStatusMap[FdmComponentType.VISION_TOWER]?.progress ?: 0f,
                                mtpProgress = componentStatusMap[FdmComponentType.MTP_DRAFTER]?.progress ?: 0f,
                                templateProgress = componentStatusMap[FdmComponentType.TEMPLATE]?.progress ?: 0f,
                                segments = segments,
                                components = componentStatusMap.values.toList(),
                                isCompleted = false
                            )
                        )
                    }
                }

                outputStream.flush()
                outputStream.close()
                inputStream.close()

                if (targetFile.exists()) targetFile.delete()
                tempFile.renameTo(targetFile)
                Log.i(tag, "Successfully saved real file: ${targetFile.absolutePath} (${targetFile.length()} bytes)")

            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                val errorMsg = "${task.title} 다운로드 중 네트워크 오류: ${e.localizedMessage ?: e.message}"
                Log.e(tag, errorMsg, e)
                emit(
                    DownloadStatus(
                        modelId = model.id,
                        progress = 0f,
                        downloadedBytes = totalBytesDownloadedSoFar,
                        totalBytes = totalBundleBytes,
                        speedText = "오류",
                        errorMessage = errorMsg,
                        isCompleted = false
                    )
                )
                return@flow
            }

            componentStatusMap[componentType] = componentStatusMap[componentType]!!.copy(
                progress = 1.0f,
                downloadedBytes = taskTotalBytes,
                speedText = "완료",
                isCompleted = true
            )
        }

        emit(
            DownloadStatus(
                modelId = model.id,
                progress = 1.0f,
                downloadedBytes = totalBytesDownloadedSoFar,
                totalBytes = totalBytesDownloadedSoFar,
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
}
