package com.localllm.android.engine

import android.content.Context
import android.util.Log
import com.localllm.android.R
import com.localllm.android.engine.llama.LlamaRuntime
import com.localllm.android.i18n.AppStrings
import com.localllm.android.memory.MemoryEstimator
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

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
 * On-device OffconAI inference facade.
 * Serializes model swaps and inference behind a single [Mutex], applies pre-load
 * memory estimation, formats structured prompts, and delegates runtime execution
 * to [LiteRtSessionRunner] or [LlamaSessionRunner].
 */
class LlmEngine(private val context: Context) {

    companion object {
        const val KEY_LITERT_GPU_UNAVAILABLE = "litert_gpu_unavailable"
        private const val INFERENCE_UNWIND_GRACE_MS = 5_000L
        private const val MEDIA_MARKER = "<__media__>"
    }

    private val tag = "LlmEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val inferenceMutex = Mutex()

    private val settingsPrefs by lazy {
        context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
    }

    private val liteRtRunner by lazy { LiteRtSessionRunner(context, settingsPrefs) }
    private val llamaRunner by lazy { LlamaSessionRunner(context, scope, INFERENCE_UNWIND_GRACE_MS) }

    @Volatile
    private var backendNote: String? = null
    private var activeModel: LlmModel? = null
    private var isModelLoaded: Boolean = false
    private var isVisionTowerLoaded: Boolean = false

    init {
        try {
            LlamaRuntime.ensureLoaded()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to load llama.cpp runtime", e)
        }
    }

    /** One-shot note about an automatic backend switch, consumed by the UI banner. */
    fun consumeBackendNote(): String? {
        val note = backendNote
        backendNote = null
        return note
    }

    fun isModelReady(): Boolean = isModelLoaded && activeModel != null

    /** True only when the linked llama.cpp/ggml build contains an offload backend. */
    fun isLlamaGpuOffloadAvailable(): Boolean = LlamaRuntime.capabilities().gpuOffload

    suspend fun loadModel(
        model: LlmModel?,
        settings: GenerationSettings,
        onStageUpdate: ((stage: String, progress: Float) -> Unit)? = null
    ): String {
        inferenceMutex.lock()
        try {
            return withContext(Dispatchers.IO) {
                loadModelLocked(model, settings, onStageUpdate)
            }
        } finally {
            inferenceMutex.unlock()
        }
    }

    private suspend fun loadModelLocked(
        model: LlmModel?,
        settings: GenerationSettings,
        onStageUpdate: ((stage: String, progress: Float) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        if (model == null || !model.isDownloaded) {
            unloadCurrentModel()
            return@withContext AppStrings.get(R.string.eng_no_model)
        }

        val modelPath = model.localFilePath
        if (modelPath.isNullOrBlank()) {
            unloadCurrentModel()
            return@withContext AppStrings.get(R.string.eng_no_path)
        }

        val modelFile = File(modelPath)
        if (!modelFile.exists() || !modelFile.isFile || modelFile.length() == 0L) {
            unloadCurrentModel()
            return@withContext AppStrings.get(R.string.eng_no_file, modelFile.name)
        }

        var effectiveContext = settings.contextWindow
        val snapshot = MemorySnapshot.read(context)
        val decision = if (settings.ignoreMemoryLimits) {
            null
        } else {
            MemoryEstimator.estimate(modelFile, settings.contextWindow, settings.kvCacheQuantization.bytesPerElement)
                ?.let { MemoryEstimator.decide(it, snapshot, settings.contextWindow) }
        }

        when (decision?.status) {
            MemoryEstimator.Status.REFUSED -> {
                unloadCurrentModel()
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_nomem), 0f)
                MemoryGuard.get()?.record(
                    cause = MemoryGuardStore.Cause.LOAD_REFUSED,
                    detail = "${modelFile.name}: ${decision.reason}",
                    modelName = model.name
                )
                return@withContext AppStrings.get(
                    R.string.eng_no_mem_model,
                    model.name,
                    decision.estimate?.totalMb(),
                    snapshot.availMb
                )
            }
            MemoryEstimator.Status.REDUCED -> {
                effectiveContext = decision.effectiveContextWindow
                onStageUpdate?.invoke(
                    AppStrings.get(R.string.eng_mem_guard, settings.contextWindow, effectiveContext, decision.reason),
                    0.10f
                )
            }
            else -> onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_verify), 0.10f)
        }

        val effectiveSettings =
            if (effectiveContext == settings.contextWindow) settings else settings.copy(contextWindow = effectiveContext)

        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            val outcome = liteRtRunner.load(model, modelFile, effectiveSettings, ::unloadCurrentModel, onStageUpdate)
            if (outcome.loaded) {
                activeModel = model
                isModelLoaded = true
                isVisionTowerLoaded = liteRtRunner.isVisionLoaded
            }
            return@withContext outcome.message
        }

        val outcome = llamaRunner.load(model, modelFile, effectiveSettings, ::unloadCurrentModel, onStageUpdate)
        if (outcome.loaded) {
            activeModel = model
            isModelLoaded = true
            isVisionTowerLoaded = llamaRunner.isVisionLoaded
        }
        outcome.message
    }

    private fun unloadCurrentModel() {
        llamaRunner.release()
        liteRtRunner.release()
        activeModel = null
        isModelLoaded = false
        isVisionTowerLoaded = false
    }

    private suspend fun acquireInferenceLock(): Boolean {
        if (inferenceMutex.tryLock()) return true
        val deadline = System.currentTimeMillis() + INFERENCE_UNWIND_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (inferenceMutex.tryLock()) return true
            delay(25)
        }
        return false
    }

    fun streamGenerate(
        prompt: String,
        history: List<Pair<String, String>>,
        settings: GenerationSettings,
        attachment: ChatAttachment?,
        mcpToolsContext: String? = null,
        numPredictOverride: Int? = null,
        documentContext: String? = null
    ): Flow<GenerationChunk> = flow {
        if (!acquireInferenceLock()) {
            throw IllegalStateException("BUSY_INFERENCE: " + AppStrings.get(R.string.eng_busy))
        }
        try {
            try {
                emitAll(inferenceFlow(prompt, history, settings, attachment, mcpToolsContext, numPredictOverride, documentContext))
            } catch (gpuFailure: LiteRtGpuUnavailableException) {
                val model = activeModel ?: throw gpuFailure
                Log.w(tag, "LiteRT GPU sampler failed -> reloading on CPU: ${gpuFailure.message}")
                settingsPrefs.edit().putBoolean(KEY_LITERT_GPU_UNAVAILABLE, true).apply()
                loadModelLocked(
                    model,
                    settings.copy(enableGpuAcceleration = false, gpuLayers = 0),
                    null
                )
                backendNote = AppStrings.get(R.string.eng_sampler_cpu)
                emitAll(inferenceFlow(prompt, history, settings, attachment, mcpToolsContext, numPredictOverride, documentContext))
            }
        } finally {
            llamaRunner.clearActiveCompletion()
            liteRtRunner.clearActiveConversation()
            inferenceMutex.unlock()
        }
    }.flowOn(Dispatchers.Default)

    private fun inferenceFlow(
        prompt: String,
        history: List<Pair<String, String>>,
        settings: GenerationSettings,
        attachment: ChatAttachment?,
        mcpToolsContext: String?,
        numPredictOverride: Int?,
        documentContext: String? = null
    ): Flow<GenerationChunk> = flow {
        val model = activeModel
            ?: throw IllegalStateException(AppStrings.get(R.string.eng_no_selected))
        if (!isModelLoaded) {
            throw IllegalStateException(AppStrings.get(R.string.eng_not_loaded, model.name))
        }

        val usesLlamaVision = settings.runtime != ModelRuntimeType.LITE_RT &&
            attachment?.isImage == true &&
            isVisionTowerLoaded
        val promptForFormatting =
            if (usesLlamaVision) MEDIA_MARKER + "\n" + prompt else prompt

        val structuredMessages = buildList {
            history.forEach { (role, text) ->
                val templateRole = if (role.equals("assistant", ignoreCase = true)) {
                    TemplateRole.ASSISTANT
                } else {
                    TemplateRole.USER
                }
                add(TemplateConversation.text(templateRole, text))
            }
            add(TemplateConversation.text(TemplateRole.USER, promptForFormatting))
        }

        val formattedPrompt = PromptFormatter.formatConversation(
            templateType = model.promptTemplateType,
            templateJsonPath = model.localTemplatePath,
            messages = structuredMessages,
            toolsContext = mcpToolsContext,
            supportsReasoning = model.supportsReasoning,
            includeBuiltInSystemPrompt = settings.builtInSystemPromptEnabled,
            documentContext = documentContext
        )

        val startTime = System.currentTimeMillis()
        val reasoningParser = ReasoningStreamParser(
            startInReasoning = formattedPrompt.trimEnd().endsWith("<think>")
        )

        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            emitAll(liteRtRunner.stream(formattedPrompt, settings, attachment, reasoningParser, startTime))
        } else {
            emitAll(llamaRunner.stream(formattedPrompt, settings, attachment, numPredictOverride, reasoningParser, startTime))
        }
    }.flowOn(Dispatchers.Default)

    fun stopGeneration() {
        liteRtRunner.cancelActive()
        llamaRunner.requestStop()
    }
}
