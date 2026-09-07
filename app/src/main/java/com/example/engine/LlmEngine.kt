package com.example.engine

import android.content.Context
import android.util.Log
import com.example.model.ChatAttachment
import com.example.model.GenerationSettings
import com.example.model.LlmModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaHelper
import android.net.Uri
import java.io.File
import java.io.FileInputStream
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class GenerationChunk(
    val token: String,
    val isReasoning: Boolean,
    val currentReasoningText: String,
    val currentContentText: String,
    val tps: Float,
    val promptSpeed: Float,
    val totalTokens: Int,
    val isComplete: Boolean = false
)

/**
 * Genuine On-Device Local LLM Inference Engine powered by native llama.cpp.
 * Runs completely offline on device CPU/GPU with no external API or cloud dependency.
 */
class LlmEngine(private val context: Context) {

    private val tag = "LlmEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val llmFlow = MutableSharedFlow<LlamaHelper.LLMEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var llamaHelper: LlamaHelper? = null
    private var activeModel: LlmModel? = null
    private var isVisionTowerLoaded: Boolean = false
    private var isModelLoaded: Boolean = false

    init {
        try {
            llamaHelper = LlamaHelper(context.contentResolver, scope, llmFlow)
        } catch (e: Throwable) {
            Log.e(tag, "Failed to initialize LlamaHelper", e)
        }
    }

    /**
     * Loads a real GGUF model into memory from local disk.
     */
    suspend fun loadModel(
        model: LlmModel?,
        settings: GenerationSettings,
        onStageUpdate: ((stage: String, progress: Float) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        if (model == null || !model.isDownloaded) {
            unloadCurrentModel()
            return@withContext "다운로드된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 다운로드하거나 불러오세요."
        }

        val modelPath = model.localFilePath
        if (modelPath.isNullOrBlank()) {
            unloadCurrentModel()
            return@withContext "모델 파일 경로를 찾을 수 없습니다."
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists() || !modelFile.isFile || modelFile.length() == 0L) {
            unloadCurrentModel()
            return@withContext "모델 파일이 디스크에 존재하지 않거나 빈 파일입니다: ${modelFile.name}"
        }

        onStageUpdate?.invoke("GGUF 헤더 및 무결성 검증 중...", 0.10f)

        // Pre-flight check: Verify GGUF magic bytes (0x47, 0x47, 0x55, 0x46 -> "GGUF")
        val isValidGguf = try {
            FileInputStream(modelFile).use { fis ->
                val header = ByteArray(4)
                val read = fis.read(header)
                read == 4 && header[0] == 'G'.code.toByte() && header[1] == 'G'.code.toByte() &&
                        header[2] == 'U'.code.toByte() && header[3] == 'F'.code.toByte()
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to read GGUF header", e)
            false
        }

        if (!isValidGguf) {
            unloadCurrentModel()
            val preview = try {
                FileInputStream(modelFile).use { fis ->
                    val buf = ByteArray(256)
                    val len = fis.read(buf)
                    if (len > 0) String(buf, 0, len, Charsets.UTF_8).trim() else ""
                }
            } catch (_: Exception) { "" }

            val errorReason = when {
                preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true) ->
                    "Hugging Face 인증 필요 (401 Unauthorized). 설정에서 HF 토큰을 입력 후 모델을 다시 다운로드하세요."
                preview.contains("404", ignoreCase = true) || preview.contains("Not Found", ignoreCase = true) ->
                    "모델 다운로드 링크가 유효하지 않습니다 (404 Not Found)."
                preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                    "다운로드된 파일이 모델 바이너리가 아닌 HTML 에러 페이지입니다. 파일을 삭제하고 올바른 URL로 다시 다운로드하세요."
                else ->
                    "파일 헤더가 GGUF 매직넘버('GGUF')와 일치하지 않습니다. 손상되었거나 유효하지 않은 파일입니다."
            }
            onStageUpdate?.invoke("무결성 검증 실패", 0f)
            return@withContext "모델 파일 형식 오류: $errorReason"
        }

        // Check mmproj if present
        val mmprojPath = if (model.hasMmproj && !model.localMmprojPath.isNullOrBlank() && File(model.localMmprojPath).exists()) {
            val mmFile = File(model.localMmprojPath)
            if (mmFile.length() >= 4) {
                val mmGguf = try {
                    FileInputStream(mmFile).use { fis ->
                        val h = ByteArray(4)
                        fis.read(h) == 4 && h[0] == 'G'.code.toByte() && h[1] == 'G'.code.toByte() &&
                                h[2] == 'U'.code.toByte() && h[3] == 'F'.code.toByte()
                    }
                } catch (_: Exception) { false }
                if (mmGguf) model.localMmprojPath else null
            } else null
        } else null

        unloadCurrentModel()

        onStageUpdate?.invoke("llama.cpp 네이티브 컨텍스트 생성 중...", 0.35f)

        // Re-initialize LlamaHelper if needed
        val helper = LlamaHelper(context.contentResolver, scope, llmFlow).also {
            llamaHelper = it
        }

        val contextWindow = settings.contextWindow.coerceIn(512, 16384)

        // Format as valid file:// URIs so Android ContentResolver can resolve file descriptor
        val modelUriString = Uri.fromFile(modelFile).toString()
        val mmprojUriString = mmprojPath?.let { Uri.fromFile(File(it)).toString() }

        Log.d(tag, "Loading GGUF model via URI: $modelUriString (ctx: $contextWindow, mmproj: $mmprojUriString)")

        onStageUpdate?.invoke("네이티브 메모리 매핑(mmap) 및 가중치 로드 중...", 0.65f)

        return@withContext withTimeoutOrNull(90_000L) {
            suspendCancellableCoroutine<String> { continuation ->
                var isResumed = false

                // Listen for engine error events concurrently
                val errorJob = scope.launch {
                    llmFlow.collect { event ->
                        if (event is LlamaHelper.LLMEvent.Error) {
                            Log.e(tag, "Engine reported error during load: ${event.message}")
                            if (!isResumed && continuation.isActive) {
                                isResumed = true
                                unloadCurrentModel()
                                onStageUpdate?.invoke("로드 실패: ${event.message}", 0f)
                                continuation.resume("모델 로드 실패: ${event.message}")
                            }
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    errorJob.cancel()
                }

                try {
                    helper.load(
                        modelUriString,
                        contextWindow,
                        mmprojUriString
                    ) { contextId ->
                        errorJob.cancel()
                        if (!isResumed && continuation.isActive) {
                            isResumed = true
                            activeModel = model
                            isModelLoaded = true
                            isVisionTowerLoaded = mmprojPath != null

                            val visionText = if (isVisionTowerLoaded) " + mmproj 비전타워" else ""
                            val resultMsg = "[llama.cpp GGUF] ${model.name} 온디바이스 로드 완료 (Context: $contextWindow$visionText)"
                            Log.i(tag, resultMsg)
                            onStageUpdate?.invoke("로드 완료", 1.0f)
                            continuation.resume(resultMsg)
                        }
                    }
                } catch (e: Throwable) {
                    errorJob.cancel()
                    Log.e(tag, "Error invoking helper.load", e)
                    if (!isResumed && continuation.isActive) {
                        isResumed = true
                        unloadCurrentModel()
                        onStageUpdate?.invoke("로드 예외: ${e.localizedMessage ?: e.message}", 0f)
                        continuation.resume("모델 로드 예외: ${e.localizedMessage ?: e.message}")
                    }
                }
            }
        } ?: run {
            unloadCurrentModel()
            onStageUpdate?.invoke("로드 시간 초과", 0f)
            "모델 로드 시간 초과 (90초). 모델 크기가 기기 가용 RAM보다 크거나 로드 중 응답이 없습니다."
        }
    }

    private fun unloadCurrentModel() {
        try {
            llamaHelper?.release()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing llama context: ${e.message}")
        }
        llamaHelper = null
        activeModel = null
        isModelLoaded = false
        isVisionTowerLoaded = false
    }

    fun isModelReady(): Boolean = isModelLoaded && activeModel != null

    fun getActiveModel(): LlmModel? = activeModel
    fun isVisionLoaded(): Boolean = isVisionTowerLoaded

    /**
     * Executes real on-device token generation and streams tokens.
     */
    fun streamGenerate(
        prompt: String,
        history: List<Pair<String, String>>,
        settings: GenerationSettings,
        attachment: ChatAttachment?,
        mcpToolsContext: String? = null
    ): Flow<GenerationChunk> = flow {
        val helper = llamaHelper
            ?: throw IllegalStateException("추론 엔진이 초기화되지 않았습니다.")
        val model = activeModel
            ?: throw IllegalStateException("선택된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 로드하세요.")

        if (!isModelLoaded) {
            throw IllegalStateException("${model.name} 모델이 아직 로드되지 않았습니다. 모델을 먼저 로드해 주세요.")
        }

        // Construct standard prompt formatted for on-device instruction-tuned model
        val formattedPrompt = PromptFormatter.formatPrompt(
            templateType = model.promptTemplateType,
            templateJsonPath = model.localTemplatePath,
            systemPrompt = settings.systemPrompt,
            history = history,
            userPrompt = prompt,
            toolsContext = mcpToolsContext,
            supportsReasoning = model.supportsReasoning
        )

        val imagePath = if (attachment?.isImage == true && isVisionTowerLoaded) {
            val raw = attachment.uriString
            if (raw.startsWith("content://") || raw.startsWith("file://")) raw
            else Uri.fromFile(File(raw)).toString()
        } else null
        val hasImage = imagePath != null

        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var totalTokens = 0

        val reasoningBuffer = StringBuilder()
        val contentBuffer = StringBuilder()
        var isInReasoningMode = false

        Log.d(tag, "Starting real inference with prompt length: ${formattedPrompt.length}, isImage: $hasImage")

        // Trigger native inference in LlamaHelper
        helper.predict(
            formattedPrompt,
            imagePath,
            hasImage
        )

        // Collect genuine real-time tokens from native llama.cpp stream
        llmFlow.collect { event ->
            when (event) {
                is LlamaHelper.LLMEvent.Started -> {
                    Log.d(tag, "Inference started")
                }

                is LlamaHelper.LLMEvent.Ongoing -> {
                    val rawWord = event.word
                    if (firstTokenTime == null) {
                        firstTokenTime = System.currentTimeMillis()
                    }
                    totalTokens++

                    val currentTime = System.currentTimeMillis()
                    val elapsedSinceFirstTokenSec = ((currentTime - (firstTokenTime ?: currentTime)) / 1000f).coerceAtLeast(0.001f)
                    val currentTps = totalTokens / elapsedSinceFirstTokenSec

                    val promptEvalTimeSec = ((firstTokenTime!! - startTime) / 1000f).coerceAtLeast(0.001f)
                    val promptSpeed = ((formattedPrompt.length / 3f) / promptEvalTimeSec).coerceIn(10f, 500f)

                    // Track <think> and </think> tags from reasoning models (e.g., DeepSeek-R1)
                    if (rawWord.contains("<think>")) {
                        isInReasoningMode = true
                        val before = rawWord.substringBefore("<think>")
                        val after = rawWord.substringAfter("<think>")
                        if (before.isNotEmpty()) contentBuffer.append(before)
                        if (after.isNotEmpty()) reasoningBuffer.append(after)
                    } else if (rawWord.contains("</think>")) {
                        val before = rawWord.substringBefore("</think>")
                        val after = rawWord.substringAfter("</think>")
                        if (before.isNotEmpty()) reasoningBuffer.append(before)
                        isInReasoningMode = false
                        if (after.isNotEmpty()) contentBuffer.append(after)
                    } else {
                        if (isInReasoningMode) {
                            reasoningBuffer.append(rawWord)
                        } else {
                            contentBuffer.append(rawWord)
                        }
                    }

                    emit(
                        GenerationChunk(
                            token = rawWord,
                            isReasoning = isInReasoningMode,
                            currentReasoningText = reasoningBuffer.toString(),
                            currentContentText = contentBuffer.toString(),
                            tps = currentTps,
                            promptSpeed = promptSpeed,
                            totalTokens = totalTokens,
                            isComplete = false
                        )
                    )
                }

                is LlamaHelper.LLMEvent.Done -> {
                    val finalElapsedSec = ((System.currentTimeMillis() - (firstTokenTime ?: startTime)) / 1000f).coerceAtLeast(0.001f)
                    val finalTps = if (totalTokens > 0) totalTokens / finalElapsedSec else 0f
                    val promptEvalTimeSec = (((firstTokenTime ?: System.currentTimeMillis()) - startTime) / 1000f).coerceAtLeast(0.001f)
                    val promptSpeed = ((formattedPrompt.length / 3f) / promptEvalTimeSec).coerceIn(10f, 500f)

                    emit(
                        GenerationChunk(
                            token = "",
                            isReasoning = false,
                            currentReasoningText = reasoningBuffer.toString(),
                            currentContentText = contentBuffer.toString(),
                            tps = finalTps,
                            promptSpeed = promptSpeed,
                            totalTokens = totalTokens,
                            isComplete = true
                        )
                    )
                    return@collect
                }

                is LlamaHelper.LLMEvent.Error -> {
                    Log.e(tag, "Inference error: ${event.message}")
                    throw RuntimeException("로컬 추론 오류: ${event.message}")
                }

                else -> {
                    // Other lifecycle events
                }
            }
        }
    }.flowOn(Dispatchers.Default)

    fun stopGeneration() {
        try {
            llamaHelper?.stopPrediction()
        } catch (e: Throwable) {
            Log.w(tag, "Error stopping prediction: ${e.message}")
        }
    }

    fun release() {
        unloadCurrentModel()
    }

}
