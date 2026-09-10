package com.localllm.android.engine

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
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

    private val tag = "LlmEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var llamaAndroid: LlamaAndroid? = null
    private var currentLlamaContext: LlamaContext? = null
    private var onTokenGenerated: ((String) -> Unit)? = null

    private var litertEngine: Engine? = null
    private var litertConversation: Conversation? = null

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

    data class MemoryDiagnosticInfo(
        val availMemMb: Long,
        val totalMemMb: Long,
        val modelSizeMb: Long,
        val isLowMemory: Boolean,
        val isCriticallyLow: Boolean
    )

    private fun checkMemoryDiagnostics(modelFile: File): MemoryDiagnosticInfo {
        return try {
            val actManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            actManager?.getMemoryInfo(memInfo)
            val availMemMb = memInfo.availMem / (1024 * 1024)
            val totalMemMb = memInfo.totalMem / (1024 * 1024)
            val modelSizeMb = modelFile.length() / (1024 * 1024)
            val isCriticallyLow = memInfo.lowMemory || (availMemMb < 150)
            Log.i(tag, "메모리 진단: 가용 RAM=${availMemMb}MB / 전체 RAM=${totalMemMb}MB, 모델 크기=${modelSizeMb}MB, 저메모리 상태=${memInfo.lowMemory}")

            if (modelSizeMb > availMemMb) {
                Log.w(tag, "경고: 모델 크기(${modelSizeMb}MB)가 가용 메모리(${availMemMb}MB)를 초과하여 OOM 위험이 있습니다.")
            }
            MemoryDiagnosticInfo(availMemMb, totalMemMb, modelSizeMb, memInfo.lowMemory, isCriticallyLow)
        } catch (e: Throwable) {
            Log.w(tag, "메모리 진단 정보 조회 실패: ${e.message}")
            MemoryDiagnosticInfo(availMemMb = 2048, totalMemMb = 4096, modelSizeMb = modelFile.length() / (1024 * 1024), isLowMemory = false, isCriticallyLow = false)
        }
    }

    /**
     * Loads a model into memory from local disk (LiteRT or llama.cpp GGUF).
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

        val memDiag = checkMemoryDiagnostics(modelFile)
        if (memDiag.isCriticallyLow && memDiag.modelSizeMb > memDiag.availMemMb * 1.5) {
            unloadCurrentModel()
            onStageUpdate?.invoke("가용 메모리 부족", 0f)
            return@withContext "기기 가용 RAM(${memDiag.availMemMb}MB)이 극도로 부족하여 ${memDiag.modelSizeMb}MB 모델 로드를 중단했습니다. 백그라운드 앱을 정리하거나 더 작은 양자화 모델을 선택하세요."
        }

        if (memDiag.modelSizeMb > memDiag.availMemMb) {
            onStageUpdate?.invoke("메모리 경고: 가용(${memDiag.availMemMb}MB) < 모델(${memDiag.modelSizeMb}MB)", 0.10f)
        } else {
            onStageUpdate?.invoke("모델 무결성 검증 중...", 0.10f)
        }

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
            val maxTokens = settings.contextWindow.coerceIn(512, 8192)

            val configsToTry = mutableListOf<Pair<String, EngineConfig>>()

            // 1. Hardware acceleration candidates (GPU / NPU)
            if (settings.enableGpuAcceleration) {
                if (model.hasMmproj) {
                    configsToTry.add(
                        "GPU 가속 (비전 연동)" to EngineConfig(
                            modelPath = modelFile.absolutePath,
                            backend = Backend.GPU(),
                            visionBackend = Backend.GPU(),
                            maxNumTokens = maxTokens,
                            cacheDir = cacheDir
                        )
                    )
                }

                configsToTry.add(
                    "GPU 가속 (${maxTokens} ctx)" to EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.GPU(),
                        visionBackend = null,
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDir
                    )
                )

                val nativeLibDir = context.applicationInfo.nativeLibraryDir
                configsToTry.add(
                    "NPU 가속 (${maxTokens} ctx)" to EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.NPU(nativeLibDir),
                        visionBackend = null,
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDir
                    )
                )
            }

            // 2. Multithread CPU with vision
            if (model.hasMmproj) {
                configsToTry.add(
                    "CPU 멀티스레드(${threadCount}T, 비전 연동)" to EngineConfig(
                        modelPath = modelFile.absolutePath,
                        backend = Backend.CPU(threadCount = threadCount, numOfThreads = threadCount),
                        visionBackend = Backend.CPU(threadCount = threadCount, numOfThreads = threadCount),
                        maxNumTokens = maxTokens,
                        cacheDir = cacheDir
                    )
                )
            }

            // 3. Multithread CPU standard text decoder
            configsToTry.add(
                "CPU 멀티스레드(${threadCount}T, ${maxTokens} ctx)" to EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(threadCount = threadCount, numOfThreads = threadCount),
                    visionBackend = null,
                    maxNumTokens = maxTokens,
                    cacheDir = cacheDir
                )
            )

            // 4. Pure CPU with native model context (maxNumTokens = null)
            configsToTry.add(
                "CPU 기본 컨텍스트(${threadCount}T)" to EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(threadCount = threadCount, numOfThreads = threadCount),
                    visionBackend = null,
                    maxNumTokens = null,
                    cacheDir = null
                )
            )

            // 5. CPU default fallback
            configsToTry.add(
                "CPU 기본 백엔드" to EngineConfig(
                    modelPath = modelFile.absolutePath,
                    backend = Backend.CPU(),
                    visionBackend = null,
                    maxNumTokens = null,
                    cacheDir = null
                )
            )

            var lastError: Throwable? = null
            var successEngine: Engine? = null
            var successConv: Conversation? = null
            var usedBackendName = ""

            for ((backendName, config) in configsToTry) {
                try {
                    onStageUpdate?.invoke("LiteRT $backendName 초기화 중...", 0.45f)
                    val engine = Engine(config)
                    onStageUpdate?.invoke("LiteRT 가중치 매핑 및 모델 초기화...", 0.70f)
                    engine.initialize()

                    val conv = try {
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

                    successEngine = engine
                    successConv = conv
                    usedBackendName = backendName
                    break
                } catch (t: Throwable) {
                    Log.w(tag, "LiteRT init failed with $backendName: ${t.message}", t)
                    lastError = t
                }
            }

            if (successEngine == null || successConv == null) {
                unloadCurrentModel()
                onStageUpdate?.invoke("LiteRT 초기화 실패", 0f)
                val errMsg = lastError?.localizedMessage ?: lastError?.message ?: "알 수 없는 오류"
                return@withContext "LiteRT LM 엔진 초기화 오류: $errMsg"
            }

            litertEngine = successEngine
            litertConversation = successConv
            activeModel = model
            isModelLoaded = true
            isVisionTowerLoaded = usedBackendName.contains("비전 연동")

            val visionMsg = if (isVisionTowerLoaded) " + 통합 올인원 비전타워" else ""
            val drafterMsg = if (model.supportsMtp) " + 통합 드래프터" else ""
            val templateMsg = if (model.localTemplatePath != null) " (Jinja 템플릿 적용)" else ""
            val resultMsg = "[LiteRT LM] ${model.name} 온디바이스 로드 완료 [$usedBackendName]$visionMsg$drafterMsg$templateMsg"
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

        val contextWindow = settings.contextWindow.coerceIn(512, 16384)
        val threadCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        val targetGpuLayers = if (settings.enableGpuAcceleration) settings.gpuLayers.coerceIn(0, 99) else 0

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
    @OptIn(ExperimentalApi::class)
    fun streamGenerate(
        prompt: String,
        history: List<Pair<String, String>>,
        settings: GenerationSettings,
        attachment: ChatAttachment?,
        mcpToolsContext: String? = null
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

        if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            val conv = litertConversation
                ?: throw IllegalStateException("LiteRT 대화 세션이 준비되지 않았습니다.")

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
                Log.e(tag, "LiteRT inference error", e)
                throw RuntimeException("LiteRT 추론 오류: ${e.localizedMessage ?: e.message}")
            } finally {
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
            "n_predict" to -1
        )
        if (imagePath != null) {
            completionParams["image_path"] = imagePath
        }

        val tokenChannel = Channel<String>(Channel.UNLIMITED)
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
            completionJob.cancel()
        }
    }.flowOn(Dispatchers.Default)

    fun stopGeneration() {
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
        try {
            if (activeModel?.runtimeType == ModelRuntimeType.LITE_RT) {
                litertConversation = litertEngine?.createConversation()
            }
        } catch (e: Throwable) {
            Log.w(tag, "Error resetting LiteRT conversation: ${e.message}")
        }
    }

    fun release() {
        unloadCurrentModel()
    }
}
