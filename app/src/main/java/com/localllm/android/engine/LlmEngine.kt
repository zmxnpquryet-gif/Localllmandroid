package com.localllm.android.engine

import com.localllm.android.R
import com.localllm.android.i18n.AppStrings

import android.content.Context
import android.net.Uri
import android.util.Log
import com.localllm.android.engine.llama.LlamaContext
import com.localllm.android.engine.llama.LlamaRuntime
import com.localllm.android.memory.MemoryEstimator
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.KvCacheQuantization
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileInputStream

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
 * On-device OffconAI inference engine.
 * Supports both native llama.cpp (GGUF) and Google LiteRT-LM backends with CPU/GPU/NPU acceleration.
 * Manages native lifecycle directly without fragile reflection hacks.
 */
class LlmEngine(private val context: Context) {

    companion object {
        /**
         * Persisted after a driver-level GPU failure (OpenCL missing/broken). Keeping the
         * GPU candidate in the list would make every load pay for the same failing native
         * init; the user re-enabling GPU acceleration in settings clears it.
         */
        const val KEY_LITERT_GPU_UNAVAILABLE = "litert_gpu_unavailable"

        /** Grace window for a cancelled generation to release the inference lock. */
        private const val INFERENCE_UNWIND_GRACE_MS = 5_000L
        /** Must match `mtmd_default_marker()` in the upstream mtmd library. */
        private const val MEDIA_MARKER = "<__media__>"
    }

    private val tag = "LlmEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentLlamaContext: LlamaContext? = null

    private var litertEngine: Engine? = null
    private var litertConversation: Conversation? = null


    /**
     * Backend the LiteRT engine was actually loaded with, e.g. "GPU 가속 (4096 ctx)".
     * LiteRT-LM's GPU path can load a model and still fail at sampling time when the
     * separate `libLiteRtTopKOpenClSampler.so` is missing from the bundle (reproduced on
     * a Galaxy S23 FE: "Can not find OpenCL library on this device"), so the inference
     * path needs to know whether the failing run was GPU-backed.
     */
    @Volatile
    private var litertBackendName = ""

    @Volatile
    private var backendNote: String? = null

    /** One-shot note about an automatic backend switch, for the status banner. */
    fun consumeBackendNote(): String? {
        val note = backendNote
        backendNote = null
        return note
    }

    private class LiteRtGpuUnavailableException(reason: String) :
        IllegalStateException("LiteRT GPU sampler unavailable: $reason")

    /**
     * Serializes all inference and model-swap operations. The native runtimes expose
     * a single shared context/conversation plus one global token callback, so two
     * concurrent generations would overwrite each other's callbacks and race on
     * release. UI/API callers fail fast with BUSY_INFERENCE instead of queuing.
     */
    private val inferenceMutex = Mutex()
    private var activeCompletionJob: Job? = null
    private var activeLitertConversation: Conversation? = null

    private var activeModel: LlmModel? = null
    private var isVisionTowerLoaded: Boolean = false
    private var isModelLoaded: Boolean = false

    init {
        try {
            LlamaRuntime.ensureLoaded()
        } catch (e: Throwable) {
            Log.e(tag, "Failed to load llama.cpp runtime", e)
        }
    }

    private val settingsPrefs by lazy {
        context.getSharedPreferences("app_settings_prefs", Context.MODE_PRIVATE)
    }

    /**
     * Footprint estimate taken before the weights are mapped: the GGUF weights
     * (mmap) plus the KV cache plus compute buffers. The old check compared the
     * file size with available RAM, which ignored the KV cache entirely.
     */
    private fun memoryDecision(
        modelFile: File,
        requestedContextWindow: Int,
        kvBytesPerElement: Double
    ): Pair<MemorySnapshot, MemoryEstimator.Decision?> {
        val snapshot = memorySnapshot()
        val estimate = MemoryEstimator.estimate(modelFile, requestedContextWindow, kvBytesPerElement)
        return snapshot to estimate?.let { MemoryEstimator.decide(it, snapshot, requestedContextWindow) }
    }

    private fun memorySnapshot(): MemorySnapshot = MemorySnapshot.read(context)

    /**
     * mtmd reads images from filesystem paths, while the attachment may be a
     * `content://` URI from the system photo picker. Materialize those into the
     * cache directory for the duration of one request.
     */
    private fun materializeImageForNative(attachment: ChatAttachment?): Pair<String?, File?> {
        if (attachment?.isImage != true) return null to null
        val raw = attachment.uriString
        val uri = try {
            Uri.parse(raw)
        } catch (_: Throwable) {
            return null to null
        }
        return when (uri.scheme) {
            "content" -> try {
                val temp = File(context.cacheDir, "llama_vision_${System.currentTimeMillis()}.jpg")
                val opened = context.contentResolver.openInputStream(uri)
                if (opened == null) {
                    null to null
                } else {
                    opened.use { input -> temp.outputStream().use { output -> input.copyTo(output) } }
                    temp.absolutePath to temp
                }
            } catch (e: Exception) {
                Log.w(tag, "Failed to cache content URI for llama.cpp vision: ${e.message}")
                null to null
            }
            "file" -> uri.path?.takeIf { File(it).isFile } to null
            else -> {
                val file = File(raw)
                file.absolutePath.takeIf { file.isFile } to null
            }
        }
    }

    /**
     * Loads a model into memory from local disk (LiteRT or llama.cpp GGUF).
     *
     * Holds [inferenceMutex] for the whole swap so a generation can never run
     * against a half-released native context.
     *
     */
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

    @OptIn(ExperimentalApi::class)
    private fun createConfiguredConversation(engine: Engine, settings: GenerationSettings): Conversation {
        return try {
            val sampler = SamplerConfig(
                topK = settings.topK.coerceAtLeast(1),
                topP = settings.topP.toDouble().coerceIn(0.01, 1.0),
                temperature = settings.temperature.toDouble().coerceAtLeast(0.01),
                seed = 0
            )
            val convConfig = ConversationConfig(
                systemInstruction = null,
                samplerConfig = sampler
            )
            engine.createConversation(convConfig)
        } catch (ce: Throwable) {
            Log.w(tag, "LiteRT createConversation with SamplerConfig failed, fallback to default: ${ce.message}")
            engine.createConversation()
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
        // Memory-guard bypass: load exactly the configured context and KV precision —
        // no footprint refusal, no automatic shrink. The estimate is skipped entirely
        // so nothing downstream can resize the load.
        val (snapshot, decision) = if (settings.ignoreMemoryLimits) {
            memorySnapshot() to null
        } else {
            memoryDecision(
                modelFile,
                settings.contextWindow,
                settings.kvCacheQuantization.bytesPerElement
            )
        }
        if (decision?.estimate != null) {
            Log.i(
                tag,
                "메모리 추정: 필요=${decision.estimate.totalMb()}MB (가중치=${decision.estimate.fileBytes / MemorySnapshot.MB}MB, " +
                        "KV=${decision.estimate.kvBytes / MemorySnapshot.MB}MB), 가용=${snapshot.availMb}MB/${snapshot.totalMb}MB, 판정=${decision.status}"
            )
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
                return@withContext AppStrings.get(R.string.eng_no_mem_model, model.name, decision.estimate?.totalMb(), snapshot.availMb)
            }
            MemoryEstimator.Status.REDUCED -> {
                effectiveContext = decision.effectiveContextWindow
                onStageUpdate?.invoke(
                    AppStrings.get(R.string.eng_mem_guard, settings.contextWindow, effectiveContext, decision.reason),
                    0.10f
                )
                Log.w(tag, "컨텍스트 자동 하향: ${decision.reason}")
            }
            else -> onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_verify), 0.10f)
        }
        val effectiveSettings =
            if (effectiveContext == settings.contextWindow) settings else settings.copy(contextWindow = effectiveContext)

        // Branch by runtime type
        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_verify), 0.10f)

            val preview = try {
                FileInputStream(modelFile).use { fis ->
                    val buf = ByteArray(256)
                    val len = fis.read(buf)
                    if (len > 0) String(buf, 0, len, Charsets.UTF_8).trim() else ""
                }
            } catch (_: Exception) { "" }

            if (preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true)) {
                unloadCurrentModel()
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_401), 0f)
                return@withContext AppStrings.get(R.string.eng_err_401)
            }
            if (preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true)) {
                unloadCurrentModel()
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_html), 0f)
                return@withContext AppStrings.get(R.string.eng_err_html)
            }
            if (modelFile.length() < 1024 * 1024L) {
                unloadCurrentModel()
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_size), 0f)
                return@withContext AppStrings.get(R.string.eng_err_size, modelFile.length())
            }

            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_jni), 0.20f)
            try {
                System.loadLibrary("litertlm_jni")
            } catch (t: Throwable) {
                Log.w(tag, "System.loadLibrary(litertlm_jni) check: ${t.message}")
            }

            unloadCurrentModel()

            val cacheDir = context.cacheDir.absolutePath
            val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val maxTokens = effectiveSettings.contextWindow.coerceIn(512, 8192)

            val nativeLibDir = context.applicationInfo.nativeLibraryDir
            val hasOpenCl = LiteRtAcceleratorPolicy.hasOpenClDriver()
            val gpuPreviouslyFailed = settingsPrefs.getBoolean(KEY_LITERT_GPU_UNAVAILABLE, false)
            val gpuAllowed = effectiveSettings.enableGpuAcceleration && hasOpenCl && !gpuPreviouslyFailed
            when {
                !effectiveSettings.enableGpuAcceleration ->
                    Log.i(tag, "GPU 가속이 설정에서 꺼져 있어 CPU 백엔드로 시작합니다.")
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
                    val gpuCandidate = configsToTry.firstOrNull { it.first == backendName } != null &&
                            backendName.contains("GPU")
                    if (gpuCandidate && LiteRtAcceleratorPolicy.isDriverRelatedFailure(reason)) {
                        settingsPrefs.edit().putBoolean(KEY_LITERT_GPU_UNAVAILABLE, true).apply()
                        Log.w(tag, "GPU/OpenCL 실패를 기록했습니다. 다음 로드부터는 GPU를 건너뜁니다: $reason")
                    }
                }
            }

            if (successEngine == null || successConv == null) {
                unloadCurrentModel()
                onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_litert_fail), 0f)
                val summary = failures.joinToString(" | ") { "${it.first}: ${it.second}" }.ifBlank { AppStrings.get(R.string.eng_unknown_err) }
                MemoryGuard.get()?.record(
                    cause = MemoryGuardStore.Cause.LOAD_REFUSED,
                    detail = summary.take(400),
                    modelName = model.name
                )
                return@withContext AppStrings.get(R.string.eng_litert_init_err, summary)
            }

            litertEngine = successEngine
            litertConversation = successConv
            litertBackendName = usedBackendName
            activeModel = model
            isModelLoaded = true
            isVisionTowerLoaded = usedBackendName.contains("비전 연동") || usedBackendName.contains("vision") || usedBackendName.contains("视觉")

            val visionMsg = if (isVisionTowerLoaded) " " + AppStrings.get(R.string.eng_msg_vision) else ""
            val drafterMsg = if (model.supportsMtp) " " + AppStrings.get(R.string.eng_msg_drafter) else ""
            val templateMsg = if (model.localTemplatePath != null) " " + AppStrings.get(R.string.eng_msg_template) else ""
            val fallbackNote = failures.firstOrNull { it.first.contains("GPU") }
                ?.let { " · " + AppStrings.get(R.string.eng_gpu_fallback, it.second) }
                ?: ""
            val resultMsg = AppStrings.get(R.string.eng_litert_loaded, model.name, usedBackendName, visionMsg, drafterMsg, templateMsg, fallbackNote)
            Log.i(tag, resultMsg)
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_loaded), 1.0f)
            return@withContext resultMsg
        }

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
                    AppStrings.get(R.string.eng_err_401)
                preview.contains("404", ignoreCase = true) || preview.contains("Not Found", ignoreCase = true) ->
                    AppStrings.get(R.string.eng_err_404)
                preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true) ->
                    AppStrings.get(R.string.eng_err_html2)
                else ->
                    AppStrings.get(R.string.eng_err_magic)
            }
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_integrity), 0f)
            return@withContext AppStrings.get(R.string.eng_fmt_err, errorReason)
        }

        // Check mmproj if present (support external mmproj file only)
        val mmprojPath = if (model.hasMmproj) {
            val externalFile = if (!model.localMmprojPath.isNullOrBlank()) {
                File(model.localMmprojPath)
            } else if (!model.mmprojFileName.isNullOrBlank()) {
                File(modelFile.parentFile ?: context.filesDir, model.mmprojFileName)
            } else {
                File(modelFile.parentFile ?: context.filesDir, "mmproj-${model.fileName}")
            }

            if (externalFile.exists() && externalFile.length() >= 4) {
                externalFile.absolutePath
            } else null
        } else null

        unloadCurrentModel()

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
        Log.i(
            tag,
            "KV 캐시 양자화: ${effectiveSettings.kvCacheQuantization.id} " +
                "(type_k/type_v=${effectiveSettings.kvCacheQuantization.ggmlTypeId}), " +
                "OOM 방어 우회=${effectiveSettings.ignoreMemoryLimits}"
        )

        data class GgufInitCandidate(
            val desc: String,
            val useMmap: Boolean,
            val useMmproj: Boolean,
            val ctxLength: Int,
            val gpuLayers: Int,
            /** 0 = use the configured KV precision; otherwise an explicit ggml_type id. */
            val kvType: Int = 0
        )

        val candidates = mutableListOf<GgufInitCandidate>()

        // 1. GPU Acceleration candidates (if enabled)
        if (targetGpuLayers > 0) {
            if (mmprojPath != null) {
                candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand1, targetGpuLayers), useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = targetGpuLayers))
            }
            candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand2, targetGpuLayers, contextWindow), useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = targetGpuLayers))
        }

        // 2. CPU fallback candidates
        if (mmprojPath != null) {
            candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand3), useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))
            candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand4), useMmap = false, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))
        }
        candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand5, contextWindow), useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))
        candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand6, contextWindow), useMmap = false, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))

        // 3. Fallback to reduced context if large context fails.
        //    Skipped when memory protection is disabled: the configured context is
        //    loaded as-is instead of being silently shrunk.
        if (!effectiveSettings.ignoreMemoryLimits && contextWindow > 2048) {
            candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand7), useMmap = true, useMmproj = false, ctxLength = 2048, gpuLayers = 0))
            candidates.add(GgufInitCandidate(AppStrings.get(R.string.eng_cand8), useMmap = false, useMmproj = false, ctxLength = 1024, gpuLayers = 0))
        }

        // 4. Last resort for a quantized V cache: some llama.cpp builds reject it
        //    without flash attention. Retry once at F16 with the SAME context so the
        //    model still loads (the status line reports the fallback) instead of
        //    failing outright.
        if (effectiveSettings.kvCacheQuantization != KvCacheQuantization.F16) {
            candidates.add(
                GgufInitCandidate(
                    AppStrings.get(R.string.eng_cand_kv_f16, contextWindow),
                    useMmap = true,
                    useMmproj = mmprojPath != null,
                    ctxLength = contextWindow,
                    gpuLayers = targetGpuLayers,
                    kvType = KvCacheQuantization.F16.ggmlTypeId
                )
            )
        }

        var newLlamaContext: LlamaContext? = null
        var loadedWithMmproj = false
        var successfulDesc = ""
        var lastError: Throwable? = null

        for ((idx, cand) in candidates.withIndex()) {
            val progressFraction = 0.40f + (idx.toFloat() / candidates.size.toFloat()) * 0.45f
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_try, cand.desc), progressFraction)
            Log.d(tag, "GGUF 로드 시도 (${idx + 1}/${candidates.size}): ${cand.desc}")

            val candidateMmprojPath = if (cand.useMmproj) mmprojPath else null
            val candidateKvType =
                if (cand.kvType != 0) cand.kvType else effectiveSettings.kvCacheQuantization.ggmlTypeId

            try {
                val params = mutableMapOf<String, Any>(
                    // Upstream llama.cpp takes filesystem paths; the engine already
                    // owns the model file, so no detached-fd hand-off is needed.
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

                val createdContext = LlamaContext(params)
                if (createdContext.context == 0L) {
                    throw IllegalStateException(AppStrings.get(R.string.eng_ctx_handle_fail))
                }

                if (cand.ctxLength < contextWindow) {
                    Log.w(tag, "[주의] 메모리/환경 제약으로 인해 컨텍스트 길이가 ${contextWindow}에서 ${cand.ctxLength}로 축소되어 로드되었습니다.")
                }

                newLlamaContext = createdContext
                loadedWithMmproj = candidateMmprojPath != null
                successfulDesc = cand.desc
                Log.i(tag, "GGUF 로드 성공: 옵션=${cand.desc}")
                break
            } catch (e: Throwable) {
                Log.w(tag, "GGUF 로드 시도 실패 (${cand.desc}): ${e.localizedMessage ?: e.message}")
                lastError = e
            }
        }

        if (newLlamaContext == null) {
            unloadCurrentModel()
            val errorMsg = lastError?.localizedMessage ?: lastError?.message ?: AppStrings.get(R.string.eng_ctx_create_fail)
            Log.e(tag, "모든 GGUF 로드 전략 실패: $errorMsg", lastError)
            onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_load_fail), 0f)
            return@withContext AppStrings.get(R.string.eng_load_fail, errorMsg)
        }

        currentLlamaContext = newLlamaContext
        activeModel = model
        isModelLoaded = true
        isVisionTowerLoaded = loadedWithMmproj

        val visionText = if (isVisionTowerLoaded) " " + AppStrings.get(R.string.eng_msg_mmproj) else ""
        val resultMsg = AppStrings.get(R.string.eng_llama_loaded, model.name, successfulDesc, threadCount, visionText)
        Log.i(tag, resultMsg)
        onStageUpdate?.invoke(AppStrings.get(R.string.eng_stage_loaded), 1.0f)
        return@withContext resultMsg
    }

    private fun unloadCurrentModel() {
        try {
            currentLlamaContext?.release()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing currentLlamaContext: ${e.message}")
        }
        currentLlamaContext = null

        try {
            litertEngine?.close()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing LiteRT engine: ${e.message}")
        }
        litertEngine = null
        litertConversation = null
        litertBackendName = ""

        activeModel = null
        isModelLoaded = false
        isVisionTowerLoaded = false
    }

    fun isModelReady(): Boolean = isModelLoaded && activeModel != null

    /** True only when the linked llama.cpp/ggml build contains an offload backend. */
    fun isLlamaGpuOffloadAvailable(): Boolean = LlamaRuntime.capabilities().gpuOffload

    /**
     * llama.cpp decode is blocking, so a stopped predecessor can still own the
     * native context after its collector has returned. Wait for it to leave the
     * decode loop before starting another generation.
     */
    private suspend fun awaitLlamaIdle(ctx: LlamaContext, timeoutMs: Long): Boolean {
        if (!ctx.isPredicting()) return true
        ctx.stopCompletion()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!ctx.isPredicting()) return true
            delay(20)
        }
        return !ctx.isPredicting()
    }

    /**
     * A cancelled predecessor can still be unwinding (its `finally` has not yet
     * released the lock) when the caller starts the next generation. Give that
     * window a short grace period; a genuinely busy engine still fails with
     * BUSY_INFERENCE afterwards. Polls `tryLock` instead of `withTimeoutOrNull
     * { lock() }`: a timeout firing right after a lock grant could leak the
     * mutex and wedge every later request on BUSY_INFERENCE.
     */
    private suspend fun acquireInferenceLock(): Boolean {
        if (inferenceMutex.tryLock()) return true
        val deadline = System.currentTimeMillis() + INFERENCE_UNWIND_GRACE_MS
        while (System.currentTimeMillis() < deadline) {
            if (inferenceMutex.tryLock()) return true
            delay(25)
        }
        return false
    }

    /**
     * Executes real on-device token generation and streams tokens.
     *
     * Only one generation may run at a time (the native runtimes share a single
     * context and a single global token callback). A second concurrent request
     * fails fast with `BUSY_INFERENCE` so callers can answer 429 instead of
     * interleaving two answers into one stream.
     */
    @OptIn(ExperimentalApi::class)
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
                // The GPU backend can load a model and still fail at sampling (the OpenCL
                // sampler library is not part of the bundled AAR). Remember the failure for
                // the next load and finish *this* request on the CPU backend instead of
                // handing the user a dead-end error.
                val model = activeModel
                if (model == null) throw gpuFailure
                Log.w(tag, "LiteRT GPU 샘플러 실패 → CPU 백엔드로 재시도: ${gpuFailure.message}")
                settingsPrefs.edit().putBoolean(KEY_LITERT_GPU_UNAVAILABLE, true).apply()
                val reloadStatus = loadModelLocked(
                    model,
                    settings.copy(enableGpuAcceleration = false, gpuLayers = 0),
                    null
                )
                Log.i(tag, "CPU 백엔드 재로드: $reloadStatus")
                backendNote = AppStrings.get(R.string.eng_sampler_cpu)
                emitAll(inferenceFlow(prompt, history, settings, attachment, mcpToolsContext, numPredictOverride, documentContext))
            }
        } finally {
            clearActiveInferenceState()
            inferenceMutex.unlock()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Stops any native work that outlives the collector and waits a bounded time
     * for it to actually finish. Blocking JNI calls ignore coroutine
     * cancellation, so `cancel()` alone would let a previous decode race the
     * next load or generation.
     */
    private suspend fun clearActiveInferenceState() {
        val completion = activeCompletionJob
        activeCompletionJob = null
        if (completion != null) {
            currentLlamaContext?.stopCompletion()
            withContext(NonCancellable) {
                withTimeoutOrNull(INFERENCE_UNWIND_GRACE_MS) { completion.join() }
            }
            completion.cancel()
        }

        val conv = activeLitertConversation
        activeLitertConversation = null
        if (conv != null) {
            try {
                conv.cancelProcess()
            } catch (_: Throwable) {}
            try {
                conv.close()
            } catch (e: Throwable) {
                Log.w(tag, "Error closing LiteRT conversation: ${e.message}")
            }
        }
    }

    @OptIn(ExperimentalApi::class)
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

        // Construct standard prompt formatted for on-device instruction-tuned model
        // (no user system prompt: the setting was removed; tools/reasoning blocks
        // are still composed by PromptFormatter itself).
        // mtmd needs its marker *inside* the user turn. Insert it before the
        // ChatML/template wrapping; the native bridge only falls back to
        // prepending it when a caller did not do this.
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
        var firstTokenTime: Long? = null
        var totalTokens = 0

        val reasoningParser = ReasoningStreamParser(
            startInReasoning = formattedPrompt.trimEnd().endsWith("<think>")
        )

        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            val engine = litertEngine
                ?: throw IllegalStateException(AppStrings.get(R.string.eng_litert_not_ready))
            val conv = try {
                createConfiguredConversation(engine, settings)
            } catch (e: Throwable) {
                throw IllegalStateException(AppStrings.get(R.string.eng_session_fail, e.localizedMessage ?: e.message))
            }
            activeLitertConversation = conv

            var tempVisionFile: File? = null
            val localImagePath: String? = if (attachment?.isImage == true && isVisionTowerLoaded) {
                val raw = attachment.uriString
                val uri = Uri.parse(raw)
                if (uri.scheme == "file") {
                    uri.path
                } else if (uri.scheme == "content") {
                    try {
                        val temp = File(context.cacheDir, "litert_vision_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            temp.outputStream().use { output ->
                                input.copyTo(output)
                            }
                        }
                        tempVisionFile = temp
                        temp.absolutePath
                    } catch (e: Exception) {
                        Log.w(tag, "Failed to cache content URI for LiteRT vision: ${e.message}")
                        null
                    }
                } else {
                    File(raw).takeIf { it.exists() }?.absolutePath
                }
            } else null

            val hasImage = localImagePath != null
            Log.d(tag, "Starting LiteRT inference with prompt length: ${formattedPrompt.length}, hasVisionInput: $hasImage")

            val contents = if (localImagePath != null) {
                Log.i(tag, "[LiteRT LM] 올인원 통합 비전 인코더 이미지 연동: $localImagePath")
                Contents.of(
                    Content.ImageFile(localImagePath),
                    Content.Text(formattedPrompt)
                )
            } else {
                Contents.of(Content.Text(formattedPrompt))
            }

            try {
                conv.sendMessageAsync(contents).collect { msg ->
                    val rawChunk = msg.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                    if (rawChunk.isNotEmpty()) {
                        if (firstTokenTime == null) {
                            firstTokenTime = System.currentTimeMillis()
                        }
                        totalTokens++

                        val currentTime = System.currentTimeMillis()
                        val elapsedSinceFirstTokenSec = ((currentTime - (firstTokenTime ?: currentTime)) / 1000f).coerceAtLeast(0.001f)
                        val currentTps = totalTokens / elapsedSinceFirstTokenSec

                        val promptEvalTimeSec = ((firstTokenTime!! - startTime) / 1000f).coerceAtLeast(0.001f)
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
                val fallbackPromptSpeed = (maxOf(1, formattedPrompt.length / 3)).toFloat() / promptEvalTimeSec

                // Query genuine native benchmark metrics directly from LiteRT-LM C++ runtime
                val benchmark = try {
                    conv.getBenchmarkInfo()
                } catch (e: Throwable) {
                    Log.d(tag, "conv.getBenchmarkInfo() failed (${e.message}), using fallback metrics")
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

                if (benchmark != null) {
                    Log.d(tag, "[LiteRT Benchmark] Prefill: ${benchmark.lastPrefillTokenCount} tokens @ ${benchmark.lastPrefillTokensPerSecond} t/s, Decode: ${benchmark.lastDecodeTokenCount} tokens @ ${benchmark.lastDecodeTokensPerSecond} tps")
                }

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
                    litertBackendName.contains("GPU") &&
                    LiteRtAcceleratorPolicy.isDriverRelatedFailure(reason)
                ) {
                    throw LiteRtGpuUnavailableException(reason ?: "unknown")
                }
                throw RuntimeException(AppStrings.get(R.string.eng_litert_infer_err, e.localizedMessage ?: e.message))
            } finally {
                if (activeLitertConversation === conv) activeLitertConversation = null
                try {
                    conv.close()
                } catch (ce: Throwable) {
                    Log.w(tag, "Error closing LiteRT conversation: ${ce.message}")
                }
                tempVisionFile?.delete()
            }
            return@flow
        }

        // Native llama.cpp (GGUF)
        val llamaCtx = currentLlamaContext
            ?: throw IllegalStateException(AppStrings.get(R.string.eng_not_init))

        val (imagePath, visionTempFile) =
            if (isVisionTowerLoaded) materializeImageForNative(attachment) else (null to null)
        val hasImage = imagePath != null

        Log.d(tag, "Starting real GGUF inference with prompt length: ${formattedPrompt.length}, isImage: $hasImage")

        // Compute genuine prompt tokens using the actual model tokenizer
        val realPromptTokens = try {
            llamaCtx.tokenize(formattedPrompt).size
        } catch (e: Throwable) {
            Log.d(tag, "llamaCtx.tokenize failed (${e.message}), estimating")
            maxOf(1, formattedPrompt.length / 3)
        }

        if (!awaitLlamaIdle(llamaCtx, INFERENCE_UNWIND_GRACE_MS)) {
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
        // Each completion clears the upstream KV cache first, so prompts stay
        // fully self-contained: the whole history is formatted into every request.
        val completionJob = scope.launch(Dispatchers.IO) {
            var failure: Throwable? = null
            try {
                llamaCtx.completion(completionParams) { token ->
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

        try {
            for (rawWord in tokenChannel) {
                val now = System.currentTimeMillis()
                val firstAt = firstTokenTime ?: now.also { firstTokenTime = it }
                totalTokens++

                val elapsedSinceFirstTokenSec =
                    ((now - firstAt) / 1000f).coerceAtLeast(0.001f)
                val currentTps = totalTokens / elapsedSinceFirstTokenSec
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
            llamaCtx.stopCompletion()
            completionJob.cancel()
            visionTempFile?.delete()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Requests cancellation of an in-flight generation. Wired to the native stop
     * primitives — cancelling only the UI collector coroutine never stopped the
     * native loop, leaving CPU/GPU work running in the background.
     */
    fun stopGeneration() {
        try {
            activeLitertConversation?.cancelProcess()
        } catch (e: Throwable) {
            Log.w(tag, "Error cancelling LiteRT process: ${e.message}")
        }
        val ctx = currentLlamaContext
        if (ctx != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    ctx.stopCompletion()
                } catch (e: Throwable) {
                    Log.w(tag, "Error stopping prediction: ${e.message}")
                }
            }
        }
    }

}
