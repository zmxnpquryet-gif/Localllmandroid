package com.localllm.android.engine

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.localllm.android.memory.MemoryEstimator
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.GenerationSettings
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
import com.localllm.engine.SDEngine
import com.localllm.engine.SampleConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.nehuatl.llamacpp.LlamaAndroid
import org.nehuatl.llamacpp.LlamaContext
import java.io.File
import java.io.FileInputStream
import java.util.Random

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
 * High-performance on-device Local LLM Inference Engine.
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
    }

    private val tag = "LlmEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var llamaAndroid: LlamaAndroid? = null
    private var currentLlamaContext: LlamaContext? = null
    private var onTokenGenerated: ((String) -> Unit)? = null

    private var litertEngine: Engine? = null
    private var litertConversation: Conversation? = null

    private var sdEngine: SDEngine? = null

    @Volatile
    private var sdStopRequested = false
    private var activeSdJob: Job? = null

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
            llamaAndroid = LlamaAndroid(context.contentResolver).apply {
                try { setContextLimit(16) } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            Log.e(tag, "Failed to initialize LlamaAndroid runtime wrapper", e)
        }
    }

    private fun openPfd(file: File): ParcelFileDescriptor {
        return try {
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (e: Throwable) {
            Log.d(tag, "ParcelFileDescriptor.open direct failed (${e.message}), trying ContentResolver...")
            context.contentResolver.openFileDescriptor(Uri.fromFile(file), "r")
        } ?: throw IllegalStateException("모델 파일 디스크립터를 열 수 없습니다: ${file.path}")
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
        requestedContextWindow: Int
    ): Pair<MemorySnapshot, MemoryEstimator.Decision?> {
        val snapshot = memorySnapshot()
        val estimate = MemoryEstimator.estimate(modelFile, requestedContextWindow)
        return snapshot to estimate?.let { MemoryEstimator.decide(it, snapshot, requestedContextWindow) }
    }

    private fun memorySnapshot(): MemorySnapshot = MemorySnapshot.read(context)

    /**
     * Loads a model into memory from local disk (LiteRT, llama.cpp GGUF or SDengine).
     *
     * Holds [inferenceMutex] for the whole swap so a generation can never run
     * against a half-released native context.
     *
     * SDengine loads run through the first-party MoE runtime and keep their TEST
     * advisories attached to the status message. A failed SDengine load retries
     * once through llama.cpp instead of leaving the user without a working model.
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
                systemInstruction = if (settings.systemPrompt.isNotBlank()) Contents.of(Content.Text(settings.systemPrompt)) else null,
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

        var effectiveContext = settings.contextWindow
        val (snapshot, decision) = memoryDecision(modelFile, settings.contextWindow)
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
                onStageUpdate?.invoke("가용 메모리 부족", 0f)
                MemoryGuard.get()?.record(
                    cause = MemoryGuardStore.Cause.LOAD_REFUSED,
                    detail = "${modelFile.name}: ${decision.reason}",
                    modelName = model.name
                )
                return@withContext "가용 메모리 부족: ${model.name} 로드에 약 ${decision.estimate?.totalMb()}MB가 필요하지만 " +
                        "가용 RAM이 ${snapshot.availMb}MB입니다. 백그라운드 앱을 정리하거나 더 작은 양자화 모델을 선택하세요."
            }
            MemoryEstimator.Status.REDUCED -> {
                effectiveContext = decision.effectiveContextWindow
                onStageUpdate?.invoke(
                    "메모리 보호: 컨텍스트 ${settings.contextWindow} → $effectiveContext 자동 하향 (${decision.reason})",
                    0.10f
                )
                Log.w(tag, "컨텍스트 자동 하향: ${decision.reason}")
            }
            else -> onStageUpdate?.invoke("모델 무결성 검증 중...", 0.10f)
        }
        val effectiveSettings =
            if (effectiveContext == settings.contextWindow) settings else settings.copy(contextWindow = effectiveContext)

        // Branch by runtime type
        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            onStageUpdate?.invoke("LiteRT 모델 파일 검증 중...", 0.10f)

            val preview = try {
                FileInputStream(modelFile).use { fis ->
                    val buf = ByteArray(256)
                    val len = fis.read(buf)
                    if (len > 0) String(buf, 0, len, Charsets.UTF_8).trim() else ""
                }
            } catch (_: Exception) { "" }

            if (preview.contains("Unauthorized", ignoreCase = true) || preview.contains("401", ignoreCase = true)) {
                unloadCurrentModel()
                onStageUpdate?.invoke("인증 실패 (401)", 0f)
                return@withContext "모델 파일 오류: Hugging Face 인증 필요 (401 Unauthorized). 설정에서 HF 토큰을 입력 후 모델을 다시 다운로드하세요."
            }
            if (preview.startsWith("<!DOCTYPE", ignoreCase = true) || preview.startsWith("<html", ignoreCase = true)) {
                unloadCurrentModel()
                onStageUpdate?.invoke("HTML 오류 페이지", 0f)
                return@withContext "모델 파일 오류: 다운로드된 파일이 모델 바이너리가 아닌 HTML 웹페이지입니다."
            }
            if (modelFile.length() < 1024 * 1024L) {
                unloadCurrentModel()
                onStageUpdate?.invoke("파일 크기 오류", 0f)
                return@withContext "모델 파일 오류: 파일 크기가 비정상적으로 작습니다 (${modelFile.length()} bytes). 올바른 모델 바이너리가 아닙니다."
            }

            onStageUpdate?.invoke("LiteRT JNI 네이티브 라이브러리 검증 중...", 0.20f)
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
                    onStageUpdate?.invoke("이전 GPU 초기화 실패 기록 → CPU로 시작", 0.30f)
                !hasOpenCl ->
                    onStageUpdate?.invoke("OpenCL 드라이버 없음 → GPU 건너뜀", 0.30f)
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
                    onStageUpdate?.invoke("LiteRT $backendName 초기화 중...", 0.45f)
                    attempt = Engine(config)
                    onStageUpdate?.invoke("LiteRT 가중치 매핑 및 모델 초기화...", 0.70f)
                    attempt.initialize()

                    val conv = createConfiguredConversation(attempt, effectiveSettings)

                    successEngine = attempt
                    successConv = conv
                    usedBackendName = backendName
                    break
                } catch (t: Throwable) {
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
                onStageUpdate?.invoke("LiteRT 초기화 실패", 0f)
                val summary = failures.joinToString(" | ") { "${it.first}: ${it.second}" }.ifBlank { "알 수 없는 오류" }
                MemoryGuard.get()?.record(
                    cause = MemoryGuardStore.Cause.LOAD_REFUSED,
                    detail = summary.take(400),
                    modelName = model.name
                )
                return@withContext "LiteRT LM 엔진 초기화 오류: $summary"
            }

            litertEngine = successEngine
            litertConversation = successConv
            litertBackendName = usedBackendName
            activeModel = model
            isModelLoaded = true
            isVisionTowerLoaded = usedBackendName.contains("비전 연동")

            val visionMsg = if (isVisionTowerLoaded) " + 통합 올인원 비전타워" else ""
            val drafterMsg = if (model.supportsMtp) " + 통합 드래프터" else ""
            val templateMsg = if (model.localTemplatePath != null) " (Jinja 템플릿 적용)" else ""
            val fallbackNote = failures.firstOrNull { it.first.contains("GPU") }
                ?.let { " · GPU/OpenCL 초기화 실패 → CPU 대체 실행 (${it.second})" }
                ?: ""
            val resultMsg = "[LiteRT LM] ${model.name} 온디바이스 로드 완료 [$usedBackendName]$visionMsg$drafterMsg$templateMsg$fallbackNote"
            Log.i(tag, resultMsg)
            onStageUpdate?.invoke("로드 완료", 1.0f)
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

        if (effectiveSettings.runtime == ModelRuntimeType.SD_ENGINE) {
            unloadCurrentModel()
            onStageUpdate?.invoke("SDengine(TEST) 가중치 바인딩 중...", 0.30f)
            val guard = MemoryGuard.get()
            val availMb = memorySnapshot().availMb.takeIf { it > 0 } ?: 2048L
            val residentCap = minOf((availMb * 0.4).toLong() * MemorySnapshot.MB, 3L * 1024L * 1024L * 1024L)
            try {
                val engine = SDEngine()
                val info = engine.openModel(modelFile, residentCapBytes = residentCap)
                sdEngine = engine
                activeModel = model
                isModelLoaded = true
                isVisionTowerLoaded = false
                val resultMsg = "[SDengine][TEST] ${model.name} 로드 완료 (layers=${info.nLayers}, experts=${info.nExperts}, " +
                        "resident=${engine.residentBytes() / MemorySnapshot.MB}MB, tensors=${info.tensorCount}) · " +
                        SDEngine.advisoryText()
                Log.i(tag, resultMsg)
                onStageUpdate?.invoke("로드 완료", 1.0f)
                return@withContext resultMsg
            } catch (t: Throwable) {
                unloadCurrentModel()
                val reason = (t.localizedMessage ?: t.message ?: t.javaClass.simpleName).take(200)
                Log.e(tag, "SDengine 로드 실패: $reason", t)
                guard?.record(
                    cause = MemoryGuardStore.Cause.MANUAL,
                    detail = "SDengine load failed: $reason",
                    modelName = model.name
                )
                onStageUpdate?.invoke("SDengine 로드 실패 → llama.cpp 대체 실행", 0.30f)
                val fallbackStatus = loadModelLocked(
                    model,
                    effectiveSettings.copy(runtime = ModelRuntimeType.LLAMA_CPP),
                    onStageUpdate
                )
                return@withContext "SDengine 로드 실패($reason) — llama.cpp 대체 실행. $fallbackStatus"
            }
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

        onStageUpdate?.invoke("llama.cpp 네이티브 컨텍스트 생성 중...", 0.35f)

        val contextWindow = effectiveSettings.contextWindow.coerceIn(512, 16384)
        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val targetGpuLayers = if (effectiveSettings.enableGpuAcceleration) effectiveSettings.gpuLayers.coerceIn(0, 99) else 0

        val tokenCallback: (String) -> Unit = { token ->
            onTokenGenerated?.invoke(token)
        }

        data class GgufInitCandidate(
            val desc: String,
            val useMmap: Boolean,
            val useMmproj: Boolean,
            val ctxLength: Int,
            val gpuLayers: Int
        )

        val candidates = mutableListOf<GgufInitCandidate>()

        // 1. GPU Acceleration candidates (if enabled)
        if (targetGpuLayers > 0) {
            if (mmprojPath != null) {
                candidates.add(GgufInitCandidate("GPU 가속 (${targetGpuLayers}L) + mmproj 비전타워", useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = targetGpuLayers))
            }
            candidates.add(GgufInitCandidate("GPU 가속 (${targetGpuLayers}L, ${contextWindow} ctx)", useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = targetGpuLayers))
        }

        // 2. CPU fallback candidates
        if (mmprojPath != null) {
            candidates.add(GgufInitCandidate("네이티브 mmap + mmproj 비전타워", useMmap = true, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))
            candidates.add(GgufInitCandidate("직접 메모리 로드 + mmproj 비전타워", useMmap = false, useMmproj = true, ctxLength = contextWindow, gpuLayers = 0))
        }
        candidates.add(GgufInitCandidate("네이티브 mmap (${contextWindow} ctx)", useMmap = true, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))
        candidates.add(GgufInitCandidate("직접 메모리 로드 (${contextWindow} ctx)", useMmap = false, useMmproj = false, ctxLength = contextWindow, gpuLayers = 0))

        // 3. Fallback to reduced context if large context fails
        if (contextWindow > 2048) {
            candidates.add(GgufInitCandidate("안정화 mmap 모드 (2048 ctx)", useMmap = true, useMmproj = false, ctxLength = 2048, gpuLayers = 0))
            candidates.add(GgufInitCandidate("절전 직접 메모리 모드 (1024 ctx)", useMmap = false, useMmproj = false, ctxLength = 1024, gpuLayers = 0))
        }

        var newLlamaContext: LlamaContext? = null
        var loadedWithMmproj = false
        var successfulDesc = ""
        var lastError: Throwable? = null

        for ((idx, cand) in candidates.withIndex()) {
            val progressFraction = 0.40f + (idx.toFloat() / candidates.size.toFloat()) * 0.45f
            onStageUpdate?.invoke("${cand.desc} 로드 시도 중...", progressFraction)
            Log.d(tag, "GGUF 로드 시도 (${idx + 1}/${candidates.size}): ${cand.desc}")

            var modelPfd: ParcelFileDescriptor? = null
            var mmprojPfd: ParcelFileDescriptor? = null

            try {
                modelPfd = openPfd(modelFile)
                val modelFd = modelPfd.detachFd()

                val modelUriString = Uri.fromFile(modelFile).toString()
                val params = mutableMapOf<String, Any>(
                    "model" to modelUriString,
                    "model_fd" to modelFd,
                    "use_mmap" to cand.useMmap,
                    "use_mlock" to false,
                    "n_ctx" to cand.ctxLength,
                    "embedding" to false,
                    "n_batch" to 512,
                    "n_threads" to threadCount,
                    "n_gpu_layers" to cand.gpuLayers,
                    "vocab_only" to false,
                    "lora" to "",
                    "lora_scaled" to 1.0,
                    "rope_freq_base" to 0.0,
                    "rope_freq_scale" to 0.0
                )

                if (cand.useMmproj && mmprojPath != null) {
                    try {
                        mmprojPfd = openPfd(File(mmprojPath))
                        params["mmproj_fd"] = mmprojPfd.detachFd()
                    } catch (me: Throwable) {
                        Log.w(tag, "mmproj 파일 디스크립터 생성 실패 (${me.message}), 텍스트 단독 모드로 진행")
                    }
                }

                // Generate random positive context ID
                val newContextId = abs(Random().nextInt()).coerceAtLeast(1)

                // Instantiate LlamaContext directly - no reflection on private fields!
                val createdContext = LlamaContext(newContextId, params)
                if (createdContext.context == 0L) {
                    throw IllegalStateException("llama.cpp 네이티브 컨텍스트 핸들(0) 반환 실패")
                }

                createdContext.setTokenCallback(tokenCallback)

                if (cand.ctxLength < contextWindow) {
                    Log.w(tag, "[주의] 메모리/환경 제약으로 인해 컨텍스트 길이가 ${contextWindow}에서 ${cand.ctxLength}로 축소되어 로드되었습니다.")
                }

                newLlamaContext = createdContext
                loadedWithMmproj = cand.useMmproj && params.containsKey("mmproj_fd")
                successfulDesc = cand.desc
                Log.i(tag, "GGUF 로드 성공: contextId=$newContextId, 옵션=${cand.desc}")
                break
            } catch (e: Throwable) {
                Log.w(tag, "GGUF 로드 시도 실패 (${cand.desc}): ${e.localizedMessage ?: e.message}")
                lastError = e
            } finally {
                try { modelPfd?.close() } catch (_: Throwable) {}
                try { mmprojPfd?.close() } catch (_: Throwable) {}
            }
        }

        if (newLlamaContext == null) {
            unloadCurrentModel()
            val errorMsg = lastError?.localizedMessage ?: lastError?.message ?: "llama.cpp 네이티브 컨텍스트 생성 실패"
            Log.e(tag, "모든 GGUF 로드 전략 실패: $errorMsg", lastError)
            onStageUpdate?.invoke("로드 실패", 0f)
            return@withContext "모델 로드 실패: $errorMsg"
        }

        currentLlamaContext = newLlamaContext
        activeModel = model
        isModelLoaded = true
        isVisionTowerLoaded = loadedWithMmproj

        val visionText = if (isVisionTowerLoaded) " + mmproj 비전타워" else ""
        val resultMsg = "[llama.cpp GGUF] ${model.name} 온디바이스 로드 완료 ($successfulDesc, ${threadCount}T$visionText)"
        Log.i(tag, resultMsg)
        onStageUpdate?.invoke("로드 완료", 1.0f)
        return@withContext resultMsg
    }

    private fun abs(value: Int): Int = if (value < 0) -value else value

    private fun unloadCurrentModel() {
        try {
            currentLlamaContext?.release()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing currentLlamaContext: ${e.message}")
        }
        currentLlamaContext = null
        onTokenGenerated = null

        try {
            litertEngine?.close()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing LiteRT engine: ${e.message}")
        }
        litertEngine = null
        litertConversation = null
        litertBackendName = ""

        try {
            sdEngine?.close()
        } catch (e: Throwable) {
            Log.w(tag, "Error releasing SDengine: ${e.message}")
        }
        sdEngine = null
        sdStopRequested = false
        activeSdJob = null

        activeModel = null
        isModelLoaded = false
        isVisionTowerLoaded = false
    }

    fun isModelReady(): Boolean = isModelLoaded && activeModel != null

    fun getActiveModel(): LlmModel? = activeModel
    fun isVisionLoaded(): Boolean = isVisionTowerLoaded

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
        numPredictOverride: Int? = null
    ): Flow<GenerationChunk> = flow {
        if (!inferenceMutex.tryLock()) {
            throw IllegalStateException("BUSY_INFERENCE: 다른 추론 요청이 진행 중입니다. 잠시 후 다시 시도하세요.")
        }
        try {
            try {
                emitAll(inferenceFlow(prompt, history, settings, attachment, mcpToolsContext, numPredictOverride))
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
                backendNote = "LiteRT GPU(OpenCL) 샘플러를 사용할 수 없어 CPU 백엔드로 전환했습니다. " +
                        "다음 실행부터는 GPU를 건너뜁니다 (설정에서 GPU 가속을 다시 켜면 재시도)."
                emitAll(inferenceFlow(prompt, history, settings, attachment, mcpToolsContext, numPredictOverride))
            }
        } finally {
            clearActiveInferenceState()
            inferenceMutex.unlock()
        }
    }.flowOn(Dispatchers.Default)

    private fun clearActiveInferenceState() {
        onTokenGenerated = null
        try {
            activeCompletionJob?.cancel()
        } catch (_: Throwable) {}
        activeCompletionJob = null
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
        numPredictOverride: Int?
    ): Flow<GenerationChunk> = flow {
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

        val startTime = System.currentTimeMillis()
        var firstTokenTime: Long? = null
        var totalTokens = 0

        val reasoningParser = ReasoningStreamParser()

        if (settings.runtime == ModelRuntimeType.SD_ENGINE && sdEngine != null) {
            val engine = sdEngine
                ?: throw IllegalStateException("SDengine이 준비되지 않았습니다.")
            val maxNewTokens = numPredictOverride?.coerceIn(1, 16384) ?: 256
            sdStopRequested = false
            val tokenChannel = Channel<String>(Channel.UNLIMITED)
            val worker = scope.launch(Dispatchers.IO) {
                try {
                    val stats = engine.generate(
                        prompt = formattedPrompt,
                        maxTokens = maxNewTokens,
                        sample = SampleConfig(
                            temperature = settings.temperature,
                            topK = settings.topK,
                            topP = settings.topP,
                            seed = 0L
                        ),
                        shouldStop = { sdStopRequested },
                        onToken = { tokenChannel.trySend(it) }
                    )
                    Log.i(
                        tag,
                        "SDengine 생성 완료: ${stats.generatedTokens}토큰 / ${stats.ms}ms " +
                                "(stopped=${stats.stopped}, expertHits=${stats.expertHits}, " +
                                "streamed=${stats.expertBytesStreamed / (1024 * 1024)}MB)"
                    )
                } catch (t: Throwable) {
                    Log.e(tag, "SDengine 추론 오류", t)
                } finally {
                    tokenChannel.close()
                }
            }
            activeSdJob = worker
            try {
                for (rawWord in tokenChannel) {
                    if (firstTokenTime == null) firstTokenTime = System.currentTimeMillis()
                    totalTokens++
                    val now = System.currentTimeMillis()
                    val elapsedSec = ((now - (firstTokenTime ?: now)) / 1000f).coerceAtLeast(0.001f)
                    reasoningParser.processChunk(rawWord)
                    emit(
                        GenerationChunk(
                            token = rawWord,
                            isReasoning = reasoningParser.isInReasoningMode,
                            currentReasoningText = reasoningParser.reasoningBuffer.toString(),
                            currentContentText = reasoningParser.contentBuffer.toString(),
                            tps = totalTokens / elapsedSec,
                            promptSpeed = 0f,
                            totalTokens = totalTokens,
                            isComplete = false
                        )
                    )
                }
                reasoningParser.finish()
                val finalElapsedSec =
                    ((System.currentTimeMillis() - (firstTokenTime ?: startTime)) / 1000f).coerceAtLeast(0.001f)
                emit(
                    GenerationChunk(
                        token = "",
                        isReasoning = false,
                        currentReasoningText = reasoningParser.reasoningBuffer.toString(),
                        currentContentText = reasoningParser.contentBuffer.toString(),
                        tps = if (totalTokens > 0) totalTokens / finalElapsedSec else 0f,
                        promptSpeed = 0f,
                        totalTokens = totalTokens,
                        isComplete = true
                    )
                )
            } finally {
                if (activeSdJob === worker) activeSdJob = null
                // A blocking generate() cannot be interrupted by cancel(): ask the
                // decode loop to stop so the worker thread does not keep decoding.
                sdStopRequested = true
                worker.cancel()
            }
            return@flow
        }

        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            // Fresh conversation per request: the app always passes the full formatted
            // history inside the prompt, so reusing one stateful runtime conversation
            // would duplicate context and leak one chat's state into another.
            // Per-request sampler also honors temperature/topP/topK without a reload.
            val engine = litertEngine
                ?: throw IllegalStateException("LiteRT 엔진이 준비되지 않았습니다.")
            val conv = try {
                createConfiguredConversation(engine, settings)
            } catch (e: Throwable) {
                throw IllegalStateException("LiteRT 대화 세션을 준비하지 못했습니다: ${e.localizedMessage ?: e.message}")
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
                val reason = e.localizedMessage ?: e.message
                Log.e(tag, "LiteRT inference error", e)
                if (totalTokens == 0 &&
                    litertBackendName.contains("GPU") &&
                    LiteRtAcceleratorPolicy.isDriverRelatedFailure(reason)
                ) {
                    throw LiteRtGpuUnavailableException(reason ?: "unknown")
                }
                throw RuntimeException("LiteRT 추론 오류: ${e.localizedMessage ?: e.message}")
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
            ?: throw IllegalStateException("추론 엔진이 초기화되지 않았습니다.")

        val imagePath = if (attachment?.isImage == true && isVisionTowerLoaded) {
            val raw = attachment.uriString
            if (raw.startsWith("content://") || raw.startsWith("file://")) raw
            else Uri.fromFile(File(raw)).toString()
        } else null
        val hasImage = imagePath != null

        Log.d(tag, "Starting real GGUF inference with prompt length: ${formattedPrompt.length}, isImage: $hasImage")

        // Compute genuine prompt tokens using the actual model tokenizer
        val realPromptTokens = try {
            llamaCtx.tokenize(formattedPrompt).size
        } catch (e: Throwable) {
            Log.d(tag, "llamaCtx.tokenize failed (${e.message}), estimating")
            maxOf(1, formattedPrompt.length / 3)
        }

        val completionParams = mutableMapOf<String, Any>(
            "prompt" to formattedPrompt,
            "emit_partial_completion" to true,
            "temperature" to settings.temperature.toDouble().coerceAtLeast(0.01),
            "top_k" to settings.topK.coerceAtLeast(1),
            "top_p" to settings.topP.toDouble().coerceIn(0.01, 1.0),
            "n_predict" to (numPredictOverride?.coerceIn(1, 16384) ?: -1)
        )
        if (imagePath != null) {
            completionParams["image_path"] = imagePath
        }

        val tokenChannel = Channel<String>(Channel.UNLIMITED)
        // Belt and braces with inferenceMutex: if a cancelled run's native loop is
        // still winding down, ask it to stop before starting a new decode.
        // (llamacpp-kotlin 0.4.0 exposes no KV/session reset API — verified via
        // javap — so prompts stay fully self-contained: the whole history is
        // formatted into every request and LiteRT uses a fresh session per call.)
        try {
            if (llamaCtx.isPredicting()) llamaCtx.stopCompletion()
        } catch (e: Throwable) {
            Log.w(tag, "Pre-generation stop request failed: ${e.message}")
        }
        onTokenGenerated = { token ->
            tokenChannel.trySend(token)
        }

        val completionJob = scope.launch(Dispatchers.IO) {
            try {
                llamaCtx.completion(completionParams)
            } catch (ce: Throwable) {
                Log.e(tag, "Error during launchCompletion: ${ce.message}", ce)
            } finally {
                tokenChannel.close()
            }
        }
        activeCompletionJob = completionJob

        try {
            for (rawWord in tokenChannel) {
                if (firstTokenTime == null) {
                    firstTokenTime = System.currentTimeMillis()
                }
                totalTokens++

                val currentTime = System.currentTimeMillis()
                val elapsedSinceFirstTokenSec = ((currentTime - (firstTokenTime ?: currentTime)) / 1000f).coerceAtLeast(0.001f)
                val currentTps = totalTokens / elapsedSinceFirstTokenSec

                val promptEvalTimeSec = ((firstTokenTime!! - startTime) / 1000f).coerceAtLeast(0.001f)
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

            reasoningParser.finish()

            val finalElapsedSec = ((System.currentTimeMillis() - (firstTokenTime ?: startTime)) / 1000f).coerceAtLeast(0.001f)
            val finalTps = if (totalTokens > 0) totalTokens / finalElapsedSec else 0f
            val promptEvalTimeSec = (((firstTokenTime ?: System.currentTimeMillis()) - startTime) / 1000f).coerceAtLeast(0.001f)
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
            onTokenGenerated = null
            if (activeCompletionJob === completionJob) activeCompletionJob = null
            completionJob.cancel()
        }
    }.flowOn(Dispatchers.Default)

    /**
     * Requests cancellation of an in-flight generation. Wired to the native stop
     * primitives — cancelling only the UI collector coroutine never stopped the
     * native loop, leaving CPU/GPU work running in the background.
     */
    fun stopGeneration() {
        sdStopRequested = true
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

    fun release() {
        if (inferenceMutex.tryLock()) {
            try {
                unloadCurrentModel()
            } finally {
                inferenceMutex.unlock()
            }
        } else {
            // An inference owns the mutex: ask the native loop to stop instead of
            // releasing a context it is actively using.
            stopGeneration()
            Log.w(tag, "release() deferred while inference is running")
        }
    }
}
