package com.localllm.android.engine

import android.content.Context
import android.util.Log
import com.localllm.android.R
import com.localllm.android.engine.llama.LlamaContext
import com.localllm.android.engine.llama.LlamaRuntime
import com.localllm.android.i18n.AppStrings
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.KvCacheQuantization
import com.localllm.android.model.LlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Owns the native `llama.cpp` GGUF context lifecycle, fallback candidate matrix,
 * and token streaming channel.
 */
internal class LlamaSessionRunner(
    private val context: Context,
    private val ioScope: CoroutineScope,
    private val unwindGraceMs: Long
) {
    private val tag = "LlamaSessionRunner"

    private var llamaContext: LlamaContext? = null
    @Volatile
    private var activeCompletionJob: Job? = null
    var isVisionLoaded: Boolean = false
        private set

    data class LoadOutcome(
        val loaded: Boolean,
        val message: String
    )

    private data class GgufInitCandidate(
        val desc: String,
        val useMmap: Boolean,
        val useMmproj: Boolean,
        val ctxLength: Int,
        val gpuLayers: Int,
        val kvType: Int = 0
    )

    fun load(
        model: LlmModel,
        modelFile: File,
        effectiveSettings: GenerationSettings,
        unloadAll: () -> Unit,
        onStageUpdate: ((stage: String, progress: Float) -> Unit)?
    ): LoadOutcome {
        if (!ModelFileValidator.isGgufFile(modelFile)) {
            unloadAll()
            val errorReason = ModelFileValidator.diagnoseInvalidGgufForLoad(modelFile)
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_integrity), 0f)
            return LoadOutcome(false, AppStrings.get(R.string.eng_fmt_err, errorReason))
        }

        val mmprojPath = resolveMmprojPath(model, modelFile)
        unloadAll()
        onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_llama_ctx), 0.35f)

        val contextWindow = effectiveSettings.contextWindow.coerceIn(512, 16384)
        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val nativeCapabilities = LlamaRuntime.capabilities()
        val gpuRequested = effectiveSettings.enableGpuAcceleration && effectiveSettings.gpuLayers > 0
        val targetGpuLayers = if (gpuRequested && nativeCapabilities.gpuOffload) {
            effectiveSettings.gpuLayers.coerceIn(1, 99)
        } else {
            0
        }
        if (gpuRequested && !nativeCapabilities.gpuOffload) {
            val detail = nativeCapabilities.loadError?.let { " ($it)" } ?: ""
            Log.i(tag, "llama.cpp build has no GPU offload backend; using CPU$detail")
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_llama_cpu_only), 0.36f)
        }

        val candidates = buildCandidates(
            effectiveSettings = effectiveSettings,
            contextWindow = contextWindow,
            targetGpuLayers = targetGpuLayers,
            mmprojPath = mmprojPath
        )

        var createdLlamaContext: LlamaContext? = null
        var loadedWithMmproj = false
        var successfulDesc = ""
        var lastError: Throwable? = null

        for ((idx, cand) in candidates.withIndex()) {
            val progressFraction = 0.40f + (idx.toFloat() / candidates.size.toFloat()) * 0.45f
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_try, cand.desc), progressFraction)

            val candidateMmprojPath = if (cand.useMmproj) mmprojPath else null
            val candidateKvType =
                if (cand.kvType != 0) cand.kvType else effectiveSettings.kvCacheQuantization.ggmlTypeId

            try {
                val params = mutableMapOf<String, Any>(
                    "model_path" to modelFile.absolutePath,
                    "use_mmap" to cand.useMmap,
                    "use_mlock" to false,
                    "n_ctx" to cand.ctxLength,
                    "n_batch" to 512,
                    "n_threads" to threadCount,
                    "n_gpu_layers" to cand.gpuLayers,
                    "vocab_only" to false,
                    "type_k" to candidateKvType,
                    "type_v" to candidateKvType
                )
                if (candidateMmprojPath != null) {
                    params["mmproj_path"] = candidateMmprojPath
                }

                val attempt = LlamaContext(params)
                if (attempt.context == 0L) {
                    throw IllegalStateException(AppStrings.get(R.string.eng_ctx_handle_fail))
                }

                createdLlamaContext = attempt
                loadedWithMmproj = candidateMmprojPath != null
                successfulDesc = cand.desc
                break
            } catch (e: Throwable) {
                Log.w(tag, "GGUF load candidate failed (${cand.desc}): ${e.localizedMessage ?: e.message}")
                lastError = e
            }
        }

        if (createdLlamaContext == null) {
            unloadAll()
            val errorMsg = lastError?.localizedMessage ?: lastError?.message
                ?: AppStrings.get(R.string.eng_ctx_create_fail)
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_load_fail), 0f)
            return LoadOutcome(false, AppStrings.get(R.string.eng_load_fail, errorMsg))
        }

        llamaContext = createdLlamaContext
        isVisionLoaded = loadedWithMmproj

        val visionText = if (isVisionLoaded) " " + AppStrings.get(R.string.eng_msg_mmproj) else ""
        val resultMsg = AppStrings.get(R.string.eng_llama_loaded, model.name, successfulDesc, threadCount, visionText)
        Log.i(tag, resultMsg)
        onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_loaded), 1.0f)
        return LoadOutcome(true, resultMsg)
    }

    fun stream(
        formattedPrompt: String,
        settings: GenerationSettings,
        attachment: ChatAttachment?,
        numPredictOverride: Int?,
        reasoningParser: ReasoningStreamParser,
        startTime: Long
    ): Flow<GenerationChunk> = flow {
        val ctx = llamaContext
            ?: throw IllegalStateException(AppStrings.get(R.string.eng_not_init))

        val (imagePath, visionTempFile) = if (isVisionLoaded) {
            ModelFileValidator.materializeVisionAttachment(context, attachment, "llama_vision")
        } else {
            null to null
        }

        val realPromptTokens = try {
            ctx.tokenize(formattedPrompt).size
        } catch (_: Throwable) {
            maxOf(1, formattedPrompt.length / 3)
        }

        if (!awaitIdle(ctx, unwindGraceMs)) {
            throw IllegalStateException("BUSY_INFERENCE: " + AppStrings.get(R.string.eng_busy))
        }

        val completionParams = mutableMapOf<String, Any>(
            "prompt" to formattedPrompt,
            "temperature" to settings.temperature.toDouble().coerceAtLeast(0.01),
            "top_k" to settings.topK.coerceAtLeast(1),
            "top_p" to settings.topP.toDouble().coerceIn(0.01, 1.0),
            "repetition_penalty" to settings.repetitionPenalty.toDouble().coerceAtLeast(0.1),
            "n_predict" to (numPredictOverride?.coerceIn(1, 16384) ?: -1)
        )
        if (imagePath != null) {
            completionParams["image_path"] = imagePath
        }

        val tokenChannel = Channel<String>(Channel.UNLIMITED)
        val completionFailure = CompletableDeferred<Throwable?>()
        val completionJob = ioScope.launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                ctx.completion(completionParams) { token ->
                    tokenChannel.trySend(token)
                }
            } catch (ce: CancellationException) {
                failure = ce
                throw ce
            } catch (ce: Throwable) {
                failure = ce
                Log.e(tag, "llama.cpp completion failed: ${ce.message}", ce)
            } finally {
                completionFailure.complete(failure)
                tokenChannel.close()
            }
        }
        activeCompletionJob = completionJob

        var firstTokenTime: Long? = null
        var totalTokens = 0

        try {
            for (rawWord in tokenChannel) {
                val now = System.currentTimeMillis()
                val firstAt = firstTokenTime ?: now.also { firstTokenTime = it }
                totalTokens++

                val elapsedSec = ((now - firstAt) / 1000f).coerceAtLeast(0.001f)
                val currentTps = totalTokens / elapsedSec
                val promptEvalTimeSec = ((firstAt - startTime) / 1000f).coerceAtLeast(0.001f)
                val promptSpeed = realPromptTokens.toFloat() / promptEvalTimeSec

                reasoningParser.processChunk(rawWord)

                emit(
                    GenerationChunk(
                        token = rawWord,
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

            completionFailure.await()?.let { throw it }
            reasoningParser.finish()

            val finalElapsedSec =
                ((System.currentTimeMillis() - (firstTokenTime ?: startTime)) / 1000f).coerceAtLeast(0.001f)
            val finalTps = if (totalTokens > 0) totalTokens / finalElapsedSec else 0f
            val promptEvalTimeSec =
                (((firstTokenTime ?: System.currentTimeMillis()) - startTime) / 1000f).coerceAtLeast(0.001f)
            val promptSpeed = realPromptTokens.toFloat() / promptEvalTimeSec

            emit(
                GenerationChunk(
                    token = "",
                    isReasoning = false,
                    currentReasoningText = reasoningParser.reasoningBuffer.toString(),
                    currentContentText = reasoningParser.contentBuffer.toString(),
                    tps = finalTps,
                    promptSpeed = promptSpeed,
                    totalTokens = totalTokens,
                    isComplete = true
                )
            )
        } finally {
            if (activeCompletionJob === completionJob) activeCompletionJob = null
            ctx.stopCompletion()
            completionJob.cancel()
            visionTempFile?.delete()
        }
    }

    fun requestStop() {
        val ctx = llamaContext ?: return
        ioScope.launch(Dispatchers.IO) {
            try {
                ctx.stopCompletion()
            } catch (e: Throwable) {
                Log.w(tag, "Error stopping llama.cpp prediction: ${e.message}")
            }
        }
    }

    suspend fun clearActiveCompletion() {
        val completion = activeCompletionJob
        activeCompletionJob = null
        if (completion != null) {
            llamaContext?.stopCompletion()
            withContext(NonCancellable) {
                withTimeoutOrNull(unwindGraceMs) { completion.join() }
            }
            completion.cancel()
        }
    }

    fun release() {
        try {
            llamaContext?.release()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing llamaContext: ${e.message}")
        }
        llamaContext = null
        isVisionLoaded = false
    }

    private fun resolveMmprojPath(model: LlmModel, modelFile: File): String? {
        if (!model.hasMmproj) return null
        val parent = modelFile.parentFile ?: context.filesDir
        val candidate = when {
            !model.localMmprojPath.isNullOrBlank() -> File(model.localMmprojPath)
            !model.mmprojFileName.isNullOrBlank() -> File(parent, model.mmprojFileName)
            else -> File(parent, "mmproj-${model.fileName}")
        }
        return candidate.takeIf { it.exists() && it.length() >= 4L }?.absolutePath
    }

    private fun buildCandidates(
        effectiveSettings: GenerationSettings,
        contextWindow: Int,
        targetGpuLayers: Int,
        mmprojPath: String?
    ): List<GgufInitCandidate> = buildList {
        if (targetGpuLayers > 0) {
            if (mmprojPath != null) {
                add(GgufInitCandidate(AppStrings.get(R.string.eng_cand1, targetGpuLayers), true, true, contextWindow, targetGpuLayers))
            }
            add(GgufInitCandidate(AppStrings.get(R.string.eng_cand2, targetGpuLayers, contextWindow), true, false, contextWindow, targetGpuLayers))
        }
        if (mmprojPath != null) {
            add(GgufInitCandidate(AppStrings.get(R.string.eng_cand3), true, true, contextWindow, 0))
            add(GgufInitCandidate(AppStrings.get(R.string.eng_cand4), false, true, contextWindow, 0))
        }
        add(GgufInitCandidate(AppStrings.get(R.string.eng_cand5, contextWindow), true, false, contextWindow, 0))
        add(GgufInitCandidate(AppStrings.get(R.string.eng_cand6, contextWindow), false, false, contextWindow, 0))

        if (!effectiveSettings.ignoreMemoryLimits && contextWindow > 2048) {
            add(GgufInitCandidate(AppStrings.get(R.string.eng_cand7), true, false, 2048, 0))
            add(GgufInitCandidate(AppStrings.get(R.string.eng_cand8), false, false, 1024, 0))
        }
        if (effectiveSettings.kvCacheQuantization != KvCacheQuantization.F16) {
            add(
                GgufInitCandidate(
                    desc = AppStrings.get(R.string.eng_cand_kv_f16, contextWindow),
                    useMmap = true,
                    useMmproj = mmprojPath != null,
                    ctxLength = contextWindow,
                    gpuLayers = targetGpuLayers,
                    kvType = KvCacheQuantization.F16.ggmlTypeId
                )
            )
        }
    }

    private suspend fun awaitIdle(ctx: LlamaContext, timeoutMs: Long): Boolean {
        if (!ctx.isPredicting()) return true
        ctx.stopCompletion()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ctx.isPredicting()) return true
            delay(20)
        }
        return !ctx.isPredicting()
    }
}
