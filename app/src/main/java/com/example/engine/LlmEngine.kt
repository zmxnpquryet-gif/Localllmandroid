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
import java.io.File
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
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
    suspend fun loadModel(model: LlmModel?, settings: GenerationSettings): String = withContext(Dispatchers.IO) {
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
        if (!modelFile.exists() || modelFile.length() == 0L) {
            unloadCurrentModel()
            return@withContext "모델 파일이 디스크에 존재하지 않습니다: ${modelFile.name}"
        }

        unloadCurrentModel()

        // Re-initialize LlamaHelper if needed
        val helper = llamaHelper ?: LlamaHelper(context.contentResolver, scope, llmFlow).also {
            llamaHelper = it
        }

        val mmprojPath = if (model.hasMmproj && !model.localMmprojPath.isNullOrBlank() && File(model.localMmprojPath).exists()) {
            model.localMmprojPath
        } else null

        val contextWindow = settings.contextWindow.coerceIn(512, 16384)

        return@withContext suspendCancellableCoroutine { continuation ->
            try {
                Log.d(tag, "Loading GGUF model: ${modelFile.absolutePath} (ctx: $contextWindow, mmproj: $mmprojPath)")

                helper.load(
                    modelFile.absolutePath,
                    contextWindow,
                    mmprojPath
                ) { contextId ->
                    activeModel = model
                    isModelLoaded = true
                    isVisionTowerLoaded = mmprojPath != null

                    val visionText = if (isVisionTowerLoaded) " + mmproj 비전타워" else ""
                    val resultMsg = "[llama.cpp GGUF] ${model.name} 온디바이스 로드 완료 (Context: $contextWindow$visionText)"
                    Log.i(tag, resultMsg)

                    if (continuation.isActive) {
                        continuation.resume(resultMsg)
                    }
                }
            } catch (e: Throwable) {
                Log.e(tag, "Error loading GGUF model", e)
                isModelLoaded = false
                activeModel = null
                if (continuation.isActive) {
                    continuation.resume("모델 로드 실패: ${e.localizedMessage ?: e.message}")
                }
            }
        }
    }

    private fun unloadCurrentModel() {
        try {
            llamaHelper?.release()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing llama context: ${e.message}")
        }
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
            attachment.uriString
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
