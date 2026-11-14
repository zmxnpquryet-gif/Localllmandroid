package com.localllm.android.engine

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

internal class LiteRtGpuUnavailableException(reason: String) :
    IllegalStateException("LiteRT GPU sampler unavailable: $reason")

/**
 * Owns the Google LiteRT-LM session lifecycle, accelerator candidate selection,
 * and streaming inference execution.
 */
@OptIn(ExperimentalApi::class)
internal class LiteRtSessionRunner(
    private val context: Context,
    private val settingsPrefs: SharedPreferences
) {
    private val tag = "LiteRtSessionRunner"

    private var engine: Engine? = null
    private var conversation: Conversation? = null
    @Volatile
    private var activeConversation: Conversation? = null
    @Volatile
    var loadedBackendName: String = ""
        private set
    var isVisionLoaded: Boolean = false
        private set

    val isLoaded: Boolean
        get() = engine != null

    data class LoadOutcome(
        val loaded: Boolean,
        val message: String
    )

    fun load(
        model: LlmModel,
        modelFile: File,
        effectiveSettings: GenerationSettings,
        unloadAll: () -> Unit,
        onStageUpdate: ((stage: String, progress: Float) -> Unit)?
    ): LoadOutcome {
        onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_verify), 0.10f)
        ModelFileValidator.diagnoseInvalidLiteRtForLoad(modelFile)?.let { (stage, err) ->
            unloadAll()
            onStageUpdate?.invoke(stage, 0f)
            return LoadOutcome(false, err)
        }

        onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_jni), 0.20f)
        try {
            System.loadLibrary("litertlm_jni")
        } catch (t: Throwable) {
            Log.w(tag, "System.loadLibrary(litertlm_jni) check: ${t.message}")
        }

        unloadAll()

        val cacheDir = context.cacheDir.absolutePath
        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val maxTokens = effectiveSettings.contextWindow.coerceIn(512, 8192)

        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val hasOpenCl = LiteRtAcceleratorPolicy.hasOpenClDriver()
        val gpuPreviouslyFailed = settingsPrefs.getBoolean(LlmEngine.KEY_LITERT_GPU_UNAVAILABLE, false)
        val gpuAllowed = effectiveSettings.enableGpuAcceleration && hasOpenCl && !gpuPreviouslyFailed
        when {
            !effectiveSettings.enableGpuAcceleration ->
                Log.i(tag, "GPU acceleration disabled in settings; starting LiteRT on CPU.")
            gpuPreviouslyFailed ->
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_gpu_prev), 0.30f)
            !hasOpenCl ->
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_no_opencl), 0.30f)
        }

        val candidates = LiteRtAcceleratorPolicy.buildCandidates(
            gpuAllowed = gpuAllowed,
            hasOpenCl = hasOpenCl,
            hasNpu = LiteRtAcceleratorPolicy.hasNpuSupport(nativeLibDir),
            hasVision = model.hasMmproj,
            maxTokens = maxTokens,
            threadCount = threadCount
        )

        fun backendOf(candidate: LiteRtAcceleratorPolicy.Candidate) = when (candidate.kind) {
            LiteRtAcceleratorPolicy.Kind.GPU -> Backend.GPU()
            LiteRtAcceleratorPolicy.Kind.NPU -> Backend.NPU(nativeLibDir)
            LiteRtAcceleratorPolicy.Kind.CPU ->
                if (candidate.maxNumTokens == null && !candidate.useCacheDir) Backend.CPU()
                else Backend.CPU(threadCount = candidate.threadCount, numOfThreads = candidate.threadCount)
        }

        val configsToTry = candidates.map { candidate ->
            candidate.label to EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = backendOf(candidate),
                visionBackend = if (candidate.withVision) backendOf(candidate) else null,
                maxNumTokens = candidate.maxNumTokens,
                cacheDir = if (candidate.useCacheDir) cacheDir else null
            )
        }

        var successEngine: Engine? = null
        var successConv: Conversation? = null
        var usedBackendName = ""
        val failures = mutableListOf<Pair<String, String>>()

        for ((backendName, config) in configsToTry) {
            var attempt: Engine? = null
            try {
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_init, backendName), 0.45f)
                attempt = Engine(config)
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_map), 0.70f)
                attempt.initialize()

                val conv = createConfiguredConversation(attempt, effectiveSettings)
                successEngine = attempt
                successConv = conv
                usedBackendName = backendName
                break
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                val reason = (t.localizedMessage ?: t.message ?: t.javaClass.simpleName).take(160)
                failures.add(backendName to reason)
                Log.w(tag, "LiteRT init failed with $backendName: $reason", t)
                try {
                    attempt?.close()
                } catch (_: Throwable) {
                }
                if (backendName.contains("GPU") && LiteRtAcceleratorPolicy.isDriverRelatedFailure(reason)) {
                    settingsPrefs.edit().putBoolean(LlmEngine.KEY_LITERT_GPU_UNAVAILABLE, true).apply()
                }
            }
        }

        if (successEngine == null || successConv == null) {
            unloadAll()
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_fail), 0f)
            val summary = failures.joinToString(" | ") { "${it.first}: ${it.second}" }
                .ifBlank { AppStrings.get(R.string.eng_unknown_err) }
            MemoryGuard.get()?.record(
                cause = MemoryGuardStore.Cause.LOAD_REFUSED,
                detail = summary.take(400),
                modelName = model.name
            )
            return LoadOutcome(false, AppStrings.get(R.string.eng_litert_init_err, summary))
        }

        engine = successEngine
        conversation = successConv
        loadedBackendName = usedBackendName
        isVisionLoaded = usedBackendName.contains("비전 연동") ||
            usedBackendName.contains("vision") ||
            usedBackendName.contains("视觉")

        val visionMsg = if (isVisionLoaded) " " + AppStrings.get(R.string.eng_msg_vision) else ""
        val drafterMsg = if (model.supportsMtp) " " + AppStrings.get(R.string.eng_msg_drafter) else ""
        val templateMsg = if (model.localTemplatePath != null) " " + AppStrings.get(R.string.eng_msg_template) else ""
        val fallbackNote = failures.firstOrNull { it.first.contains("GPU") }
            ?.let { " · " + AppStrings.get(R.string.eng_gpu_fallback, it.second) }
            ?: ""
        val resultMsg = AppStrings.get(
            R.string.eng_litert_loaded,
            model.name,
            usedBackendName,
            visionMsg,
            drafterMsg,
            templateMsg,
            fallbackNote
        )
        Log.i(tag, resultMsg)
        onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_loaded), 1.0f)
        return LoadOutcome(true, resultMsg)
    }

    fun stream(
        formattedPrompt: String,
        settings: GenerationSettings,
        attachment: ChatAttachment?,
        reasoningParser: ReasoningStreamParser,
        startTime: Long
    ): Flow<GenerationChunk> = flow {
        val activeEngine = engine
            ?: throw IllegalStateException(AppStrings.get(R.string.eng_litert_not_ready))
        val conv = try {
            createConfiguredConversation(activeEngine, settings)
        } catch (e: Throwable) {
            throw IllegalStateException(AppStrings.get(R.string.eng_session_fail, e.localizedMessage ?: e.message))
        }
        activeConversation = conv

        val (localImagePath, tempVisionFile) = if (isVisionLoaded) {
            ModelFileValidator.materializeVisionAttachment(context, attachment, "litert_vision")
        } else {
            null to null
        }

        val contents = if (localImagePath != null) {
            Contents.of(Content.ImageFile(localImagePath), Content.Text(formattedPrompt))
        } else {
            Contents.of(Content.Text(formattedPrompt))
        }

        var firstTokenTime: Long? = null
        var totalTokens = 0

        try {
            conv.sendMessageAsync(contents).collect { msg ->
                val rawChunk = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                if (rawChunk.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val firstAt = firstTokenTime ?: now.also { firstTokenTime = it }
                    totalTokens++

                    val elapsedSec = ((now - firstAt) / 1000f).coerceAtLeast(0.001f)
                    val currentTps = totalTokens / elapsedSec
                    val promptEvalTimeSec = ((firstAt - startTime) / 1000f).coerceAtLeast(0.001f)
                    val estimatedPromptTokens = maxOf(1, formattedPrompt.length / 3)
                    val promptSpeed = estimatedPromptTokens / promptEvalTimeSec

                    reasoningParser.processChunk(rawChunk)

                    emit(
                        GenerationChunk(
                            token = rawChunk,
                            isReasoning = reasoningParser.isInReasoningMode,
                            currentReasoningText = reasoningParser.reasoningBuffer.toString(),
                            currentContentText = reasoningParser.contentBuffer.toString(),
                            tps = currentTps,
                            promptSpeed = promptSpeed,
                            totalTokens = totalTokens,
                            isComplete = false
                        )
                    )
                }
            }

            reasoningParser.finish()

            val finalElapsedSec = ((System.currentTimeMillis() - (firstTokenTime ?: startTime)) / 1000f).coerceAtLeast(0.001f)
            val fallbackTps = if (totalTokens > 0) totalTokens / finalElapsedSec else 0f
            val promptEvalTimeSec = (((firstTokenTime ?: System.currentTimeMillis()) - startTime) / 1000f).coerceAtLeast(0.001f)
            val fallbackPromptSpeed = maxOf(1, formattedPrompt.length / 3).toFloat() / promptEvalTimeSec

            val benchmark = try {
                conv.getBenchmarkInfo()
            } catch (_: Throwable) {
                null
            }

            val finalTps = if (benchmark != null && benchmark.lastDecodeTokensPerSecond > 0) {
                benchmark.lastDecodeTokensPerSecond.toFloat()
            } else fallbackTps

            val finalPromptSpeed = if (benchmark != null && benchmark.lastPrefillTokensPerSecond > 0) {
                benchmark.lastPrefillTokensPerSecond.toFloat()
            } else fallbackPromptSpeed

            val finalTotalTokens = if (benchmark != null && benchmark.lastDecodeTokenCount > 0) {
                benchmark.lastDecodeTokenCount
            } else totalTokens

            emit(
                GenerationChunk(
                    token = "",
                    isReasoning = false,
                    currentReasoningText = reasoningParser.reasoningBuffer.toString(),
                    currentContentText = reasoningParser.contentBuffer.toString(),
                    tps = finalTps,
                    promptSpeed = finalPromptSpeed,
                    totalTokens = finalTotalTokens,
                    isComplete = true
                )
            )
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            val reason = e.localizedMessage ?: e.message
            Log.e(tag, "LiteRT inference error", e)
            if (totalTokens == 0 &&
                loadedBackendName.contains("GPU") &&
                LiteRtAcceleratorPolicy.isDriverRelatedFailure(reason)
            ) {
                throw LiteRtGpuUnavailableException(reason ?: "unknown")
            }
            throw RuntimeException(AppStrings.get(R.string.eng_litert_infer_err, reason))
        } finally {
            if (activeConversation === conv) activeConversation = null
            try {
                conv.close()
            } catch (ce: Throwable) {
                Log.w(tag, "Error closing LiteRT conversation: ${ce.message}")
            }
            tempVisionFile?.delete()
        }
    }

    fun cancelActive() {
        try {
            activeConversation?.cancelProcess()
        } catch (e: Throwable) {
            Log.w(tag, "Error cancelling LiteRT process: ${e.message}")
        }
    }

    fun clearActiveConversation() {
        val conv = activeConversation
        activeConversation = null
        if (conv != null) {
            try {
                conv.cancelProcess()
            } catch (_: Throwable) {
            }
            try {
                conv.close()
            } catch (e: Throwable) {
                Log.w(tag, "Error closing LiteRT conversation: ${e.message}")
            }
        }
    }

    fun release() {
        clearActiveConversation()
        try {
            engine?.close()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing LiteRT engine: ${e.message}")
        }
        engine = null
        conversation = null
        loadedBackendName = ""
        isVisionLoaded = false
    }

    private fun createConfiguredConversation(targetEngine: Engine, settings: GenerationSettings): Conversation {
        return try {
            val sampler = SamplerConfig(
                topK = settings.topK.coerceAtLeast(1),
                topP = settings.topP.toDouble().coerceIn(0.01, 1.0),
                temperature = settings.temperature.toDouble().coerceAtLeast(0.01),
                seed = 0
            )
            targetEngine.createConversation(
                ConversationConfig(
                    systemInstruction = null,
                    samplerConfig = sampler
                )
            )
        } catch (ce: Throwable) {
            Log.w(tag, "LiteRT SamplerConfig fallback to default: ${ce.message}")
            targetEngine.createConversation()
        }
    }
}
