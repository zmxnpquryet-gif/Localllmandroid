package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ModelStorageManager
import com.example.data.local.ChatDatabase
import com.example.data.repository.ChatRepository
import com.example.engine.GgufMetadataDetector
import com.example.engine.GenerationChunk
import com.example.engine.LlmEngine
import com.example.engine.McpClient
import com.example.engine.ModelDownloader
import com.example.model.ChatAttachment
import com.example.model.ChatMessage
import com.example.model.Conversation
import com.example.model.GenerationSettings
import com.example.model.LlmModel
import com.example.model.MessageRole
import com.example.model.ModelCatalog
import com.example.model.ModelRuntimeType
import com.example.voice.InteractiveVoiceState
import com.example.voice.VoiceManager
import android.net.Uri
import com.example.server.OllamaApiServer
import com.example.service.ModelDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.net.NetworkInterface
import java.util.Collections
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val database = ChatDatabase.getInstance(application)
    private val repository = ChatRepository(database.chatDao())
    private val modelStorageManager = ModelStorageManager(application)
    val llmEngine = LlmEngine(application)
    private val modelDownloader = ModelDownloader()
    val voiceManager = VoiceManager(application)
    private val mcpClient = McpClient()

    // Conversations from encrypted SQLite Room
    val conversations: StateFlow<List<Conversation>> = repository.conversations
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Active conversation
    private val _currentConversationId = MutableStateFlow<String?>(null)
    val currentConversationId: StateFlow<String?> = _currentConversationId.asStateFlow()

    // Messages for active conversation
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val settingsPrefs = application.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)

    // Generation settings
    private val _settings = MutableStateFlow(
        GenerationSettings(
            hfToken = settingsPrefs.getString("hf_token", "") ?: "",
            themeColorName = settingsPrefs.getString("theme_color", "artistic") ?: "artistic",
            darkModePreference = settingsPrefs.getString("dark_mode", "system") ?: "system",
            systemPrompt = settingsPrefs.getString("system_prompt", "") ?: "",
            temperature = settingsPrefs.getFloat("temperature", 0.7f),
            topP = settingsPrefs.getFloat("top_p", 0.9f),
            topK = settingsPrefs.getInt("top_k", 40),
            contextWindow = settingsPrefs.getInt("context_window", 4096),
            repetitionPenalty = settingsPrefs.getFloat("repetition_penalty", 1.1f),
            enableMtp = settingsPrefs.getBoolean("enable_mtp", true),
            enableIndexingAcceleration = settingsPrefs.getBoolean("enable_indexing", true),
            showPerformanceMetrics = settingsPrefs.getBoolean("show_metrics", true),
            mcpServerUrl = settingsPrefs.getString("mcp_server_url", "") ?: "",
            isMcpEnabled = settingsPrefs.getBoolean("is_mcp_enabled", false)
        )
    )
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    // Models catalog restored from persistent metadata and reconciled with physical storage
    private val _models = MutableStateFlow(modelStorageManager.loadModels())
    val models: StateFlow<List<LlmModel>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<LlmModel?>(null)
    val activeModel: StateFlow<LlmModel?> = _activeModel.asStateFlow()

    // Active screen: "chat", "models", "settings", "voice_mode"
    private val _currentScreen = MutableStateFlow("chat")
    val currentScreen: StateFlow<String> = _currentScreen.asStateFlow()

    // Streaming state
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    // Pending attachment (Image or File)
    private val _pendingAttachment = MutableStateFlow<ChatAttachment?>(null)
    val pendingAttachment: StateFlow<ChatAttachment?> = _pendingAttachment.asStateFlow()

    // Status banner / engine toast
    private val _engineStatusMessage = MutableStateFlow<String?>("로컬 모델 다운로드 필요 (상단 허브)")
    val engineStatusMessage: StateFlow<String?> = _engineStatusMessage.asStateFlow()

    // Model Loading State & Progress (0f..1f, visible during loading, disappears when finished)
    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    private val _modelLoadingProgress = MutableStateFlow(0f)
    val modelLoadingProgress: StateFlow<Float> = _modelLoadingProgress.asStateFlow()

    private val _modelLoadingStage = MutableStateFlow("")
    val modelLoadingStage: StateFlow<String> = _modelLoadingStage.asStateFlow()

    // API Mode (Port 11434 Server)
    private val _isApiModeEnabled = MutableStateFlow(false)
    val isApiModeEnabled: StateFlow<Boolean> = _isApiModeEnabled.asStateFlow()

    private val _apiServerPort = MutableStateFlow(OllamaApiServer.DEFAULT_PORT)
    val apiServerPort: StateFlow<Int> = _apiServerPort.asStateFlow()

    private val _apiServerStatusMessage = MutableStateFlow<String?>("대기 중")
    val apiServerStatusMessage: StateFlow<String?> = _apiServerStatusMessage.asStateFlow()

    private val _apiRequestCount = MutableStateFlow(0)
    val apiRequestCount: StateFlow<Int> = _apiRequestCount.asStateFlow()

    private val _localIpAddress = MutableStateFlow("127.0.0.1")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private var apiServer: OllamaApiServer? = null
    private var modelLoadingJob: Job? = null

    // FDM Active Download Status
    private val _activeDownloadStatus = MutableStateFlow<com.example.engine.DownloadStatus?>(null)
    val activeDownloadStatus: StateFlow<com.example.engine.DownloadStatus?> = _activeDownloadStatus.asStateFlow()

    private var activeDownloadJob: Job? = null

    // MCP Connection result
    private val _mcpStatusText = MutableStateFlow<String>("미연결")
    val mcpStatusText: StateFlow<String> = _mcpStatusText.asStateFlow()

    private var currentStreamJob: Job? = null
    private var messageCollectionJob: Job? = null

    init {
        val savedActiveId = modelStorageManager.getActiveModelId()
        val restoredModel = _models.value.firstOrNull { it.id == savedActiveId && it.isDownloaded }
            ?: _models.value.firstOrNull { it.isDownloaded }

        if (restoredModel != null) {
            _settings.value = _settings.value.copy(
                runtime = restoredModel.runtimeType,
                enableMtp = if (restoredModel.supportsMtp) true else _settings.value.enableMtp
            )
            viewModelScope.launch {
                try {
                    val initStatus = llmEngine.loadModel(restoredModel, _settings.value)
                    _engineStatusMessage.value = initStatus
                    if (llmEngine.isModelReady()) {
                        _activeModel.value = restoredModel
                        modelStorageManager.saveActiveModelId(restoredModel.id)
                    } else {
                        _activeModel.value = null
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MainViewModel", "Initial model load failed", t)
                    _activeModel.value = null
                    _engineStatusMessage.value = "초기 모델 로드 오류: ${t.localizedMessage ?: t.message}"
                }
            }
        } else {
            _activeModel.value = null
            _engineStatusMessage.value = "기본 모델 미탑재: 모델 관리자에서 최신 모델을 다운로드하세요."
        }

        // Auto-create initial conversation if empty
        viewModelScope.launch {
            conversations.collect { list ->
                if (list.isEmpty() && _currentConversationId.value == null) {
                    createNewConversation()
                } else if (_currentConversationId.value == null && list.isNotEmpty()) {
                    selectConversation(list.first().id)
                }
            }
        }

        // Observe Foreground Service background download status
        viewModelScope.launch {
            ModelDownloadService.currentDownloadStatus.collect { status ->
                _activeDownloadStatus.value = status
                if (status != null) {
                    val modelId = status.modelId
                    _models.value = _models.value.map { m ->
                        if (m.id == modelId) {
                            m.copy(
                                isDownloading = !status.isCompleted && status.errorMessage == null,
                                downloadStatus = if (status.isCompleted) "COMPLETED" else if (status.errorMessage != null) "FAILED" else "DOWNLOADING",
                                downloadProgress = status.progress,
                                downloadSpeedText = status.speedText,
                                downloadEtaSeconds = status.etaSeconds,
                                mainDownloadProgress = status.mainProgress,
                                visionDownloadProgress = status.visionProgress,
                                mtpDownloadProgress = status.mtpProgress,
                                templateDownloadProgress = status.templateProgress,
                                isDownloaded = if (status.isCompleted) true else m.isDownloaded,
                                isVisionDownloaded = if (m.hasMmproj || m.visionTowerUrl.isNotBlank()) status.isCompleted else m.isVisionDownloaded,
                                isMtpDownloaded = if (m.supportsMtp || m.mtpDrafterUrl.isNotBlank()) status.isCompleted else m.isMtpDownloaded,
                                isTemplateDownloaded = if (m.templateFileUrl.isNotBlank() || m.templateFileName != null) status.isCompleted else m.isTemplateDownloaded,
                                localFilePath = status.localMainPath ?: m.localFilePath,
                                localMmprojPath = status.localVisionPath ?: m.localMmprojPath,
                                localMtpDrafterPath = status.localMtpPath ?: m.localMtpDrafterPath,
                                localTemplatePath = status.localTemplatePath ?: m.localTemplatePath
                            )
                        } else m
                    }

                    if (status.isCompleted) {
                        modelStorageManager.saveModels(_models.value)
                        modelStorageManager.saveActiveModelId(modelId)
                        val downloadedModel = _models.value.find { it.id == modelId }
                        if (downloadedModel != null) {
                            selectModel(downloadedModel)
                        }
                    }
                }
            }
        }
    }

    fun navigateTo(screen: String) {
        _currentScreen.value = screen
    }

    fun createNewConversation() {
        viewModelScope.launch {
            val newId = UUID.randomUUID().toString()
            val newConv = Conversation(
                id = newId,
                title = "새로운 대화",
                modelId = _activeModel.value?.id ?: "",
                systemPrompt = _settings.value.systemPrompt
            )
            repository.saveConversation(newConv)
            selectConversation(newId)
        }
    }

    fun selectConversation(conversationId: String) {
        _currentConversationId.value = conversationId
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            repository.getMessagesForConversation(conversationId).collect { msgList ->
                _messages.value = msgList
            }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch {
            repository.deleteConversation(id)
            if (_currentConversationId.value == id) {
                val remaining = conversations.value.filter { it.id != id }
                if (remaining.isNotEmpty()) {
                    selectConversation(remaining.first().id)
                } else {
                    createNewConversation()
                }
            }
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            database.chatDao().clearAllConversations()
            _messages.value = emptyList()
            _currentConversationId.value = null
            createNewConversation()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        viewModelScope.launch {
            repository.renameConversation(id, newTitle)
        }
    }

    fun setPendingAttachment(attachment: ChatAttachment?) {
        _pendingAttachment.value = attachment
    }

    fun selectModel(model: LlmModel) {
        if (!model.isDownloaded) {
            _engineStatusMessage.value = "${model.name} 모델은 아직 다운로드되지 않았습니다. 모델 관리자에서 다운로드해 주세요."
            return
        }

        modelLoadingJob?.cancel()
        modelLoadingJob = viewModelScope.launch {
            _isModelLoading.value = true
            _modelLoadingProgress.value = 0.05f
            _modelLoadingStage.value = "로딩 준비 중..."

            // Sync runtime type and auto-apply MTP if model supports it
            _settings.value = _settings.value.copy(
                runtime = model.runtimeType,
                enableMtp = if (model.supportsMtp) true else _settings.value.enableMtp
            )

            try {
                val status = llmEngine.loadModel(model, _settings.value) { stage, progress ->
                    _modelLoadingStage.value = stage
                    _modelLoadingProgress.value = progress
                }

                if (llmEngine.isModelReady()) {
                    _activeModel.value = model
                    _modelLoadingProgress.value = 1.0f
                    _modelLoadingStage.value = "로드 완료"
                    _engineStatusMessage.value = status
                    modelStorageManager.saveActiveModelId(model.id)
                    delay(400)
                } else {
                    _activeModel.value = null
                    _modelLoadingProgress.value = 0f
                    _modelLoadingStage.value = "로드 실패"
                    _engineStatusMessage.value = status
                    delay(2000)
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainViewModel", "Model loading error", t)
                _activeModel.value = null
                _modelLoadingProgress.value = 0f
                _modelLoadingStage.value = "로드 실패"
                _engineStatusMessage.value = "모델 로드 예외: ${t.localizedMessage ?: t.message}"
                delay(2000)
            } finally {
                // Loading completes -> hide loading bar
                _isModelLoading.value = false
                _modelLoadingProgress.value = 0f
                _modelLoadingStage.value = ""
            }
        }
    }

    fun switchRuntime(runtime: ModelRuntimeType) {
        _settings.value = _settings.value.copy(runtime = runtime)
        val downloadedMatch = _models.value.firstOrNull { it.runtimeType == runtime && it.isDownloaded }
        if (downloadedMatch != null) {
            selectModel(downloadedMatch)
        } else {
            _activeModel.value = null
            viewModelScope.launch {
                try {
                    llmEngine.loadModel(null, _settings.value)
                } catch (t: Throwable) {
                    android.util.Log.w("MainViewModel", "Model unload error", t)
                }
            }
            _engineStatusMessage.value = "${if (runtime == ModelRuntimeType.LLAMA_CPP) "llama.cpp" else "LiteRT LM"} 형식의 다운로드된 모델이 없습니다. 모델 관리자에서 다운로드하세요."
        }
    }

    fun updateHfToken(token: String) {
        val clean = token.trim()
        _settings.value = _settings.value.copy(hfToken = clean)
        settingsPrefs.edit().putString("hf_token", clean).apply()
    }

    fun updateSettings(newSettings: GenerationSettings) {
        _settings.value = newSettings
        settingsPrefs.edit().apply {
            putString("hf_token", newSettings.hfToken)
            putString("theme_color", newSettings.themeColorName)
            putString("dark_mode", newSettings.darkModePreference)
            putString("system_prompt", newSettings.systemPrompt)
            putFloat("temperature", newSettings.temperature)
            putFloat("top_p", newSettings.topP)
            putInt("top_k", newSettings.topK)
            putInt("context_window", newSettings.contextWindow)
            putFloat("repetition_penalty", newSettings.repetitionPenalty)
            putBoolean("enable_mtp", newSettings.enableMtp)
            putBoolean("enable_indexing", newSettings.enableIndexingAcceleration)
            putBoolean("show_metrics", newSettings.showPerformanceMetrics)
            putString("mcp_server_url", newSettings.mcpServerUrl)
            putBoolean("is_mcp_enabled", newSettings.isMcpEnabled)
            apply()
        }
        viewModelScope.launch {
            try {
                val status = llmEngine.loadModel(_activeModel.value, newSettings)
                _engineStatusMessage.value = status
            } catch (t: Throwable) {
                android.util.Log.e("MainViewModel", "Model reload error with new settings", t)
                _engineStatusMessage.value = "설정 적용 오류: ${t.localizedMessage ?: t.message}"
            }
        }
    }

    fun setReasoningEffort(effort: Float) {
        _settings.value = _settings.value.copy(reasoningEffort = effort)
    }

    fun connectMcp(url: String) {
        viewModelScope.launch {
            _mcpStatusText.value = "연결 확인 중..."
            val result = mcpClient.connectServer(url)
            _mcpStatusText.value = if (result.isSuccess) {
                "${result.serverName} (${result.latencyMs}ms, ${result.tools.size}개 도구)"
            } else {
                "연결 실패: ${result.message}"
            }
            _settings.value = _settings.value.copy(
                mcpServerUrl = url,
                isMcpEnabled = result.isSuccess
            )
        }
    }

    private var inProcessDownloadJob: kotlinx.coroutines.Job? = null

    fun downloadModel(modelId: String, hfTokenOverride: String? = null) {
        val targetModel = _models.value.find { it.id == modelId } ?: return
        if (targetModel.isDownloading) return

        val effectiveToken = hfTokenOverride?.ifBlank { null }
            ?: targetModel.hfToken?.ifBlank { null }
            ?: _settings.value.hfToken.ifBlank { null }

        _models.value = _models.value.map {
            if (it.id == modelId) it.copy(
                isDownloading = true,
                downloadStatus = "DOWNLOADING",
                downloadProgress = 0f,
                hfToken = effectiveToken ?: it.hfToken
            ) else it
        }

        val updatedModel = _models.value.find { it.id == modelId } ?: targetModel

        try {
            ModelDownloadService.startDownload(getApplication(), updatedModel, effectiveToken)
        } catch (e: Throwable) {
            android.util.Log.e("MainViewModel", "Service start failed, using in-process fallback: ${e.message}", e)
            startInProcessDownloadFallback(updatedModel, effectiveToken)
        }
    }

    private fun startInProcessDownloadFallback(model: LlmModel, hfToken: String? = null) {
        inProcessDownloadJob?.cancel()
        inProcessDownloadJob = viewModelScope.launch {
            try {
                val modelsDir = java.io.File(getApplication<android.app.Application>().filesDir, "models").apply { if (!exists()) mkdirs() }
                val downloader = com.example.engine.ModelDownloader()
                downloader.downloadUnifiedBundle(model, modelsDir, hfToken).collect { status ->
                    _activeDownloadStatus.value = status
                    _models.value = _models.value.map { m ->
                        if (m.id == model.id) {
                            m.copy(
                                isDownloading = !status.isCompleted && status.errorMessage == null,
                                downloadStatus = if (status.isCompleted) "COMPLETED" else if (status.errorMessage != null) "FAILED" else "DOWNLOADING",
                                downloadProgress = status.progress,
                                downloadSpeedText = status.speedText,
                                downloadEtaSeconds = status.etaSeconds,
                                mainDownloadProgress = status.mainProgress,
                                visionDownloadProgress = status.visionProgress,
                                mtpDownloadProgress = status.mtpProgress,
                                templateDownloadProgress = status.templateProgress,
                                isDownloaded = if (status.isCompleted) true else m.isDownloaded,
                                isVisionDownloaded = if (m.hasMmproj || m.visionTowerUrl.isNotBlank()) status.isCompleted else m.isVisionDownloaded,
                                isMtpDownloaded = if (m.supportsMtp || m.mtpDrafterUrl.isNotBlank()) status.isCompleted else m.isMtpDownloaded,
                                isTemplateDownloaded = if (m.templateFileUrl.isNotBlank() || m.templateFileName != null) status.isCompleted else m.isTemplateDownloaded,
                                localFilePath = status.localMainPath ?: m.localFilePath,
                                localMmprojPath = status.localVisionPath ?: m.localMmprojPath,
                                localMtpDrafterPath = status.localMtpPath ?: m.localMtpDrafterPath,
                                localTemplatePath = status.localTemplatePath ?: m.localTemplatePath
                            )
                        } else m
                    }

                    if (status.isCompleted) {
                        modelStorageManager.saveModels(_models.value)
                        modelStorageManager.saveActiveModelId(model.id)
                        val downloadedModel = _models.value.find { it.id == model.id }
                        if (downloadedModel != null) {
                            selectModel(downloadedModel)
                        }
                    }
                }
            } catch (e: Throwable) {
                android.util.Log.e("MainViewModel", "In-process fallback download error", e)
                _models.value = _models.value.map { m ->
                    if (m.id == model.id) m.copy(isDownloading = false, downloadStatus = "FAILED") else m
                }
            }
        }
    }

    fun cancelDownload(modelId: String) {
        inProcessDownloadJob?.cancel()
        inProcessDownloadJob = null
        try {
            ModelDownloadService.cancelDownload(getApplication(), modelId)
        } catch (e: Throwable) {
            android.util.Log.w("MainViewModel", "Error canceling service download: ${e.message}")
        }
        _activeDownloadStatus.value = null
        _models.value = _models.value.map { m ->
            if (m.id == modelId) {
                m.copy(
                    isDownloading = false,
                    downloadStatus = "IDLE",
                    downloadProgress = 0f
                )
            } else m
        }
        modelStorageManager.saveModels(_models.value)
    }

    fun deleteModel(modelId: String) {
        val targetModel = _models.value.find { it.id == modelId }
        if (targetModel != null) {
            modelStorageManager.deleteModelFiles(targetModel)
        }
        _models.value = if (targetModel?.id?.startsWith("custom-") == true) {
            _models.value.filter { it.id != modelId }
        } else {
            _models.value.map { m ->
                if (m.id == modelId) {
                    m.copy(
                        isDownloaded = false,
                        isVisionDownloaded = false,
                        isMtpDownloaded = false,
                        isTemplateDownloaded = false,
                        downloadProgress = 0f,
                        downloadStatus = "IDLE",
                        localFilePath = null,
                        localMmprojPath = null,
                        localMtpDrafterPath = null,
                        localTemplatePath = null
                    )
                } else m
            }
        }
        modelStorageManager.saveModels(_models.value)
        if (_activeModel.value?.id == modelId) {
            val nextDownloaded = _models.value.firstOrNull { it.isDownloaded && it.id != modelId }
            if (nextDownloaded != null) {
                selectModel(nextDownloaded)
            } else {
                _activeModel.value = null
                modelStorageManager.saveActiveModelId(null)
                viewModelScope.launch {
                    llmEngine.loadModel(null, _settings.value)
                }
                _engineStatusMessage.value = "다운로드된 모델이 없습니다. 모델 관리자에서 다운로드해 주세요."
            }
        }
    }

    fun toggleModelVision(modelId: String) {
        _models.value = _models.value.map { m ->
            if (m.id == modelId) {
                val newVision = !m.hasMmproj
                m.copy(
                    hasMmproj = newVision,
                    isVisionDownloaded = if (newVision) (m.isDownloaded || m.isVisionDownloaded) else false
                )
            } else m
        }
        modelStorageManager.saveModels(_models.value)
        _activeModel.value?.let { active ->
            if (active.id == modelId) {
                val updated = _models.value.first { it.id == modelId }
                _activeModel.value = updated
                _engineStatusMessage.value = "${updated.name}: 비전 타워 ${if (updated.hasMmproj) "활성화 (실행 시 연동)" else "비활성화"}"
            }
        }
    }

    fun toggleModelDrafter(modelId: String) {
        _models.value = _models.value.map { m ->
            if (m.id == modelId) {
                val newDrafter = !m.supportsMtp
                m.copy(
                    supportsMtp = newDrafter,
                    isMtpDownloaded = if (newDrafter) (m.isDownloaded || m.isMtpDownloaded) else false
                )
            } else m
        }
        modelStorageManager.saveModels(_models.value)
        _activeModel.value?.let { active ->
            if (active.id == modelId) {
                val updated = _models.value.first { it.id == modelId }
                _activeModel.value = updated
                _engineStatusMessage.value = "${updated.name}: 드래프터 ${if (updated.supportsMtp) "활성화 (가속 연동)" else "비활성화"}"
            }
        }
    }

    /**
     * Adds an external custom model via FDM multi-link format.
     * Supports:
     * - Direct custom filename entry
     * - LiteRT prompt template file url/path
     * - Explicit reasoning / thinking mode toggle
     * - Embedded Vision Tower and Embedded Speculative Drafter detection and manual toggles
     */
    fun addCustomFdmBundle(
        name: String,
        runtime: ModelRuntimeType,
        mainUrl: String,
        customFileName: String = "",
        visionUrl: String = "",
        mtpUrl: String = "",
        templateUrl: String = "",
        supportsReasoning: Boolean = false,
        autoStartDownload: Boolean = true,
        hfToken: String = "",
        hasEmbeddedVision: Boolean = false,
        hasEmbeddedDrafter: Boolean = false
    ) {
        val cleanMain = mainUrl.trim()
        val cleanVision = visionUrl.trim()
        val cleanMtp = mtpUrl.trim()
        val cleanTemplate = templateUrl.trim()
        val cleanToken = hfToken.trim()

        if (cleanToken.isNotBlank() && _settings.value.hfToken.isBlank()) {
            updateHfToken(cleanToken)
        }

        val defaultMainExt = if (runtime == ModelRuntimeType.LITE_RT) ".bin" else ".gguf"
        val derivedFileName = cleanMain.substringAfterLast('/', "custom_model$defaultMainExt")
        val finalFileName = if (customFileName.isNotBlank()) customFileName.trim() else derivedFileName

        val visionFileName = if (cleanVision.isNotBlank()) cleanVision.substringAfterLast('/', "mmproj_vision.gguf") else null
        val mtpFileName = if (cleanMtp.isNotBlank()) cleanMtp.substringAfterLast('/', "mtp_draft.gguf") else null
        val templateFileName = if (cleanTemplate.isNotBlank()) {
            cleanTemplate.substringAfterLast('/', "prompt_template.json")
        } else if (runtime == ModelRuntimeType.LITE_RT) {
            "${finalFileName.substringBeforeLast('.')}-template.json"
        } else null

        val modelName = name.ifBlank {
            finalFileName.substringBeforeLast('.').ifBlank { "Custom Model" }
        }

        val effectiveReasoning = supportsReasoning ||
                modelName.contains("R1", ignoreCase = true) ||
                modelName.contains("Reasoning", ignoreCase = true) ||
                modelName.contains("Thinking", ignoreCase = true) ||
                finalFileName.contains("R1", ignoreCase = true)

        val autoDetectedVision = GgufMetadataDetector.isVisionModel(finalFileName) || GgufMetadataDetector.isVisionModel(modelName)
        val autoDetectedDrafter = GgufMetadataDetector.isDrafterModel(finalFileName) || GgufMetadataDetector.isDrafterModel(modelName)

        val effectiveVision = hasEmbeddedVision || cleanVision.isNotBlank() || autoDetectedVision
        val effectiveMtp = hasEmbeddedDrafter || cleanMtp.isNotBlank() || autoDetectedDrafter

        val descriptionParts = mutableListOf<String>()
        descriptionParts.add("파일명: $finalFileName")
        if (runtime == ModelRuntimeType.LITE_RT) descriptionParts.add("LiteRT LM 템플릿 지원")
        if (effectiveReasoning) descriptionParts.add("사고 과정(Thinking) 지원")
        if (effectiveVision) descriptionParts.add(if (cleanVision.isNotBlank()) "비전타워 포함" else "내장 비전타워")
        if (effectiveMtp) descriptionParts.add(if (cleanMtp.isNotBlank()) "MTP 드래프터 포함" else "내장 드래프터")
        if (cleanToken.isNotBlank()) descriptionParts.add("인증 토큰 적용")

        val newModel = LlmModel(
            id = "custom-" + UUID.randomUUID().toString().take(8),
            name = modelName,
            repoId = "external/custom-model",
            fileName = finalFileName,
            runtimeType = runtime,
            sizeBytes = 2_100_000_000L,
            supportsMtp = effectiveMtp,
            supportsReasoning = effectiveReasoning,
            hasMmproj = effectiveVision,
            mmprojFileName = visionFileName,
            mtpDrafterFileName = mtpFileName,
            templateFileName = templateFileName,
            templateFileUrl = cleanTemplate,
            mainModelUrl = cleanMain,
            visionTowerUrl = cleanVision,
            mtpDrafterUrl = cleanMtp,
            isBundledModel = true,
            isDownloaded = false,
            description = "커스텀 모델 (" + descriptionParts.joinToString(" • ") + ")",
            hfToken = cleanToken.ifBlank { null }
        )

        _models.value = listOf(newModel) + _models.value
        modelStorageManager.saveModels(_models.value)

        if (autoStartDownload) {
            downloadModel(newModel.id, cleanToken.ifBlank { null })
        }
    }

    fun addCustomModel(repoId: String, fileName: String, runtime: ModelRuntimeType, hasVision: Boolean) {
        val mainUrl = "https://huggingface.co/$repoId/resolve/main/$fileName"
        val visionUrl = if (hasVision) "https://huggingface.co/$repoId/resolve/main/mmproj-$fileName" else ""
        addCustomFdmBundle(
            name = repoId.substringAfterLast('/'),
            runtime = runtime,
            mainUrl = mainUrl,
            customFileName = fileName,
            visionUrl = visionUrl,
            mtpUrl = "",
            templateUrl = "",
            supportsReasoning = false,
            autoStartDownload = true
        )
    }

    fun sendMessage(userPrompt: String) {
        val trimmed = userPrompt.trim()
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return

        val convId = _currentConversationId.value ?: return

        // 1. Create and save user message
        val userMessage = ChatMessage(
            conversationId = convId,
            role = MessageRole.USER,
            content = trimmed,
            attachment = attachment
        )
        _pendingAttachment.value = null

        viewModelScope.launch {
            repository.saveMessage(userMessage)

            // Auto-generate title if this is the first message
            val currentList = _messages.value
            if (currentList.isEmpty() || currentList.size <= 1) {
                val title = if (trimmed.length > 20) "${trimmed.take(20)}..." else trimmed
                repository.renameConversation(convId, title.ifBlank { "사진 대화" })
            }

            // Check if model is downloaded and ready
            val currentModel = _activeModel.value
            if (currentModel == null || !currentModel.isDownloaded) {
                val noticeMessage = ChatMessage(
                    conversationId = convId,
                    role = MessageRole.ASSISTANT,
                    content = "다운로드된 모델이 없습니다. 상단 메뉴 또는 모델 관리에서 모델을 먼저 다운로드해 주세요.",
                    isStreaming = false
                )
                repository.saveMessage(noticeMessage)
                return@launch
            }

            if (!llmEngine.isModelReady()) {
                val noticeMessage = ChatMessage(
                    conversationId = convId,
                    role = MessageRole.ASSISTANT,
                    content = "모델이 아직 메모리에 로드되지 않았습니다 (${_engineStatusMessage.value}). 모델 관리자에서 모델을 다시 선택해 로드해 주세요.",
                    isStreaming = false
                )
                repository.saveMessage(noticeMessage)
                return@launch
            }

            // 2. Start streaming assistant response
            val assistantMessageId = UUID.randomUUID().toString()
            _isGenerating.value = true

            val streamPlaceholder = ChatMessage(
                id = assistantMessageId,
                conversationId = convId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            )
            _streamingMessage.value = streamPlaceholder

            val history = currentList.takeLast(6).map { it.role.name to it.content }

            currentStreamJob?.cancel()
            currentStreamJob = launch {
                try {
                    llmEngine.streamGenerate(
                        prompt = trimmed,
                        history = history,
                        settings = _settings.value,
                        attachment = userMessage.attachment,
                        mcpToolsContext = if (_settings.value.isMcpEnabled) _settings.value.mcpServerUrl else null
                    ).collect { chunk ->
                        _streamingMessage.value = streamPlaceholder.copy(
                            content = chunk.currentContentText,
                            reasoning = chunk.currentReasoningText.ifBlank { null },
                            isReasoningStreaming = chunk.isReasoning,
                            tps = chunk.tps,
                            promptSpeed = chunk.promptSpeed,
                            contextTokens = chunk.totalTokens,
                            isStreaming = !chunk.isComplete
                        )

                        if (chunk.isComplete) {
                            val finalMsg = _streamingMessage.value?.copy(isStreaming = false)
                            if (finalMsg != null) {
                                repository.saveMessage(finalMsg)
                            }
                            _streamingMessage.value = null
                            _isGenerating.value = false
                        }
                    }
                } catch (e: Exception) {
                    val errorMsg = streamPlaceholder.copy(
                        content = "오류 발생: ${e.localizedMessage ?: "추론 중 문제가 발생했습니다."}",
                        isStreaming = false
                    )
                    repository.saveMessage(errorMsg)
                    _streamingMessage.value = null
                    _isGenerating.value = false
                }
            }
        }
    }

    fun stopGeneration() {
        currentStreamJob?.cancel()
        currentStreamJob = null
        val cur = _streamingMessage.value
        if (cur != null) {
            viewModelScope.launch {
                repository.saveMessage(cur.copy(isStreaming = false))
                _streamingMessage.value = null
                _isGenerating.value = false
            }
        } else {
            _isGenerating.value = false
        }
    }

    /**
     * Voice Mode hands-free conversational loop:
     * User speaks -> Local LLM generates response -> TTS speaks back
     */
    fun startInteractiveVoiceSession() {
        if (_activeModel.value == null || !_activeModel.value!!.isDownloaded) {
            voiceManager.speak("다운로드된 로컬 모델이 없습니다. 모델 관리자에서 모델을 먼저 다운로드해 주세요.")
            return
        }
        voiceManager.startListening(
            onResult = { userSpokenText ->
                voiceManager.setVoiceState(InteractiveVoiceState.PROCESSING)
                viewModelScope.launch {
                    val convId = _currentConversationId.value ?: return@launch
                    val userMsg = ChatMessage(conversationId = convId, role = MessageRole.USER, content = userSpokenText)
                    repository.saveMessage(userMsg)

                    // Stream or generate response
                    val answerBuilder = StringBuilder()
                    llmEngine.streamGenerate(
                        prompt = userSpokenText,
                        history = emptyList(),
                        settings = _settings.value,
                        attachment = null
                    ).collect { chunk ->
                        if (chunk.isComplete) {
                            val assistantMsg = ChatMessage(
                                conversationId = convId,
                                role = MessageRole.ASSISTANT,
                                content = chunk.currentContentText,
                                reasoning = chunk.currentReasoningText.ifBlank { null },
                                tps = chunk.tps,
                                promptSpeed = chunk.promptSpeed
                            )
                            repository.saveMessage(assistantMsg)

                            // Speak response back
                            voiceManager.speak(chunk.currentContentText) {
                                // Once done speaking, listen again for natural dialogue
                                startInteractiveVoiceSession()
                            }
                        }
                    }
                }
            },
            onError = { _ ->
                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
            }
        )
    }

    /**
     * Import a local GGUF model file from device storage (via SAF / File Picker)
     */
    fun importLocalModel(uri: Uri, displayName: String) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val context = getApplication<Application>()
                val modelsDir = File(context.filesDir, "models").apply { if (!exists()) mkdirs() }
                val targetFileName = displayName.ifBlank { "imported_model.gguf" }
                val destFile = File(modelsDir, targetFileName)

                _engineStatusMessage.value = "기기에서 GGUF 모델 복사 중..."
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val detected = GgufMetadataDetector.detect(destFile)
                val hasVision = detected.hasVision
                val hasDrafter = detected.hasDrafter
                val isLiteRt = detected.detectedRuntime == ModelRuntimeType.LITE_RT

                val cleanName = targetFileName
                    .removeSuffix(".gguf")
                    .removeSuffix(".bin")
                    .removeSuffix(".litertlm")
                    .removeSuffix(".task")
                    .removeSuffix(".tflite")
                    .replace("-", " ")
                    .replace("_", " ")

                val customModel = LlmModel(
                    id = "custom-${UUID.randomUUID()}",
                    name = cleanName,
                    repoId = if (isLiteRt) "local/litert-imported" else "local/imported",
                    fileName = targetFileName,
                    runtimeType = detected.detectedRuntime,
                    sizeBytes = destFile.length(),
                    isDownloaded = true,
                    downloadStatus = "COMPLETED",
                    localFilePath = destFile.absolutePath,
                    isBundledModel = isLiteRt || detected.isUnifiedBundle,
                    description = buildString {
                        if (isLiteRt) {
                            append("기기 저장소에서 불러온 LiteRT 올인원 통합 모델")
                            if (hasVision) append(" • 통합 비전타워(Vision Encoder)")
                            if (hasDrafter) append(" • 통합 드래프터(MTP)")
                        } else {
                            append("기기 저장소에서 직접 불러온 로컬 GGUF 모델")
                            if (hasVision) append(" • 내장 비전타워")
                            if (hasDrafter) append(" • 내장 드래프터")
                        }
                    },
                    quantization = if (isLiteRt) "LiteRT" else "GGUF",
                    hasMmproj = hasVision,
                    supportsMtp = hasDrafter,
                    isVisionDownloaded = hasVision,
                    isMtpDownloaded = hasDrafter
                )

                val updatedList = listOf(customModel) + _models.value
                _models.value = updatedList
                modelStorageManager.saveModels(updatedList)
                _engineStatusMessage.value = "모델 가져오기 완료: $cleanName"
                selectModel(customModel)
            } catch (e: Exception) {
                _engineStatusMessage.value = "모델 가져오기 실패: ${e.localizedMessage}"
            }
        }
    }

    // ==========================================
    // API Server Mode Controls (Port 11434)
    // ==========================================
    fun setApiModeEnabled(enabled: Boolean) {
        _isApiModeEnabled.value = enabled
        if (enabled) {
            startApiServer()
        } else {
            stopApiServer()
        }
    }

    fun startApiServer() {
        if (apiServer == null) {
            apiServer = OllamaApiServer(
                context = getApplication(),
                llmEngine = llmEngine,
                getActiveModel = { _activeModel.value },
                getAllModels = { _models.value.filter { it.isDownloaded } },
                getSettings = { _settings.value }
            )
        }

        refreshLocalIp()

        apiServer?.start { isRunning, msg ->
            _isApiModeEnabled.value = isRunning
            _apiServerStatusMessage.value = msg
            _engineStatusMessage.value = "API 서버 (포트 11434): ${if (isRunning) "실행 중" else "중지됨"}"
            _apiRequestCount.value = apiServer?.requestCount ?: 0
        }
    }

    fun stopApiServer() {
        apiServer?.stop { isRunning, msg ->
            _isApiModeEnabled.value = isRunning
            _apiServerStatusMessage.value = msg
            _engineStatusMessage.value = "API 서버가 중지되었습니다."
        }
    }

    fun refreshLocalIp() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (intf in interfaces) {
                    val addrs = Collections.list(intf.inetAddresses)
                    for (addr in addrs) {
                        if (!addr.isLoopbackAddress) {
                            val sAddr = addr.hostAddress ?: ""
                            val isIPv4 = sAddr.indexOf(':') < 0
                            if (isIPv4) {
                                _localIpAddress.value = sAddr
                                return@launch
                            }
                        }
                    }
                }
                _localIpAddress.value = "127.0.0.1"
            } catch (_: Exception) {
                _localIpAddress.value = "127.0.0.1"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        voiceManager.release()
        modelLoadingJob?.cancel()
        apiServer?.stop { _, _ -> }
    }
}
