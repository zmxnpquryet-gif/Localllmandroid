package com.localllm.android.ui

import android.app.Application
import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.android.data.ModelStorageManager
import com.localllm.android.data.local.ChatDatabase
import com.localllm.android.data.repository.ChatRepository
import com.localllm.android.engine.GgufMetadataDetector
import com.localllm.android.engine.GenerationChunk
import com.localllm.android.engine.LlmEngine
import com.localllm.android.engine.McpClient
import com.localllm.android.engine.ModelDownloader
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.Conversation
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.MessageRole
import com.localllm.android.model.ModelCatalog
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.R
import com.localllm.android.LocalLlmApp
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot
import com.localllm.android.memory.MemoryWatchdog
import com.localllm.android.voice.InteractiveVoiceState
import com.localllm.android.voice.TtsEngineMode
import com.localllm.android.voice.VoiceManager
import android.net.Uri
import com.localllm.android.server.OllamaApiServer
import com.localllm.android.service.ModelDownloadService
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
import java.util.Locale
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

    /**
     * Unsaved draft conversation. Empty chats live in memory only and hit the
     * database with their first message — the drawer never fills with blanks.
     */
    private var pendingConversation: Conversation? = null

    // Messages for active conversation
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val settingsPrefs = application.getSharedPreferences("app_settings_prefs", android.content.Context.MODE_PRIVATE)

    // Generation settings
    private val _settings = MutableStateFlow(
        GenerationSettings(
            hfToken = settingsPrefs.getString("hf_token", "") ?: "",
            runtime = parseStoredRuntime(settingsPrefs.getString("runtime", null)),
            themeColorName = settingsPrefs.getString("theme_color", "artistic") ?: "artistic",
            darkModePreference = settingsPrefs.getString("dark_mode", "system") ?: "system",
            temperature = settingsPrefs.getFloat("temperature", 0.7f),
            topP = settingsPrefs.getFloat("top_p", 0.9f),
            topK = settingsPrefs.getInt("top_k", 40),
            contextWindow = settingsPrefs.getInt("context_window", 4096),
            repetitionPenalty = settingsPrefs.getFloat("repetition_penalty", 1.1f),
            enableMtp = settingsPrefs.getBoolean("enable_mtp", true),
            enableIndexingAcceleration = settingsPrefs.getBoolean("enable_indexing", true),
            enableGpuAcceleration = settingsPrefs.getBoolean("enable_gpu", true),
            gpuLayers = settingsPrefs.getInt("gpu_layers", 99),
            showPerformanceMetrics = settingsPrefs.getBoolean("show_metrics", true),
            apiServerBindAddress = settingsPrefs.getString("api_server_bind_address", "127.0.0.1") ?: "127.0.0.1",
            apiServerRequireAuth = settingsPrefs.getBoolean("api_server_require_auth", true),
            languagePreference = settingsPrefs.getString("language_preference", "system") ?: "system",
            mcpServerUrl = settingsPrefs.getString("mcp_server_url", "") ?: "",
            isMcpEnabled = settingsPrefs.getBoolean("is_mcp_enabled", false)
        )
    )
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    // ---- memory protection ----
    private val memoryGuard: MemoryGuardStore? = MemoryGuard.get()
    private val memoryWatchdog = MemoryWatchdog(
        scope = viewModelScope,
        memory = { MemorySnapshot.read(getApplication()) },
        onCritical = { snapshot -> onMemoryCritical(snapshot) }
    )
    private val _memoryGuardState = MutableStateFlow(currentMemoryGuardUiState())
    val memoryGuardState: StateFlow<MemoryGuardUiState> = _memoryGuardState.asStateFlow()
    private var duplicateVoiceTurns = 0

    // Models catalog restored from persistent metadata and reconciled with physical storage
    private val _models = MutableStateFlow(modelStorageManager.loadModels())
    val models: StateFlow<List<LlmModel>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<LlmModel?>(null)
    val activeModel: StateFlow<LlmModel?> = _activeModel.asStateFlow()

    // Active screen: "chat", "models", "settings", "voice_mode"
    private val _currentScreen = MutableStateFlow(AppScreen.CHAT)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    // Streaming state
    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _streamingMessage = MutableStateFlow<ChatMessage?>(null)
    val streamingMessage: StateFlow<ChatMessage?> = _streamingMessage.asStateFlow()

    // Pending attachment (Image or File)
    private val _pendingAttachment = MutableStateFlow<ChatAttachment?>(null)
    val pendingAttachment: StateFlow<ChatAttachment?> = _pendingAttachment.asStateFlow()

    // Status banner / engine toast
    private val _engineStatusMessage = MutableStateFlow<String?>(localizedString(R.string.vm_status_download_required))
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

    private val _apiServerStatusMessage = MutableStateFlow<String?>(localizedString(R.string.vm_api_status_idle))
    val apiServerStatusMessage: StateFlow<String?> = _apiServerStatusMessage.asStateFlow()

    private val _apiRequestCount = MutableStateFlow(0)
    val apiRequestCount: StateFlow<Int> = _apiRequestCount.asStateFlow()

    private val _localIpAddress = MutableStateFlow("127.0.0.1")
    val localIpAddress: StateFlow<String> = _localIpAddress.asStateFlow()

    private val _apiServerApiKey = MutableStateFlow(OllamaApiServer.generateSecureApiKey())
    val apiServerApiKey: StateFlow<String> = _apiServerApiKey.asStateFlow()

    private var apiServer: OllamaApiServer? = null
    private var modelLoadingJob: Job? = null

    // FDM Active Download Status
    private val _activeDownloadStatus = MutableStateFlow<com.localllm.android.engine.DownloadStatus?>(null)
    val activeDownloadStatus: StateFlow<com.localllm.android.engine.DownloadStatus?> = _activeDownloadStatus.asStateFlow()

    // MCP Connection result
    private val _mcpStatusText = MutableStateFlow<String>(localizedString(R.string.vm_mcp_disconnected))
    val mcpStatusText: StateFlow<String> = _mcpStatusText.asStateFlow()

    private val _mcpTools = MutableStateFlow<List<com.localllm.android.engine.McpTool>>(emptyList())
    val mcpTools: StateFlow<List<com.localllm.android.engine.McpTool>> = _mcpTools.asStateFlow()

    /** Prompt-ready rendering of the *actually connected* MCP tools. Null when none. */
    private val _mcpToolsContext = MutableStateFlow<String?>(null)

    private var currentStreamJob: Job? = null
    private var lastVoiceInput: String? = null
    private var messageCollectionJob: Job? = null

    init {
        // One-time stale-prefs migration (P1-1): versions with MoE auto-assign
        // persisted runtime=SD_ENGINE into prefs via selectModel. After the
        // default-to-llama.cpp change that stale value would silently route every
        // GGUF through the TEST engine. Runs once against the RAW stored value
        // (before active-model restoration overwrites _settings) and never touches
        // an intentional global choice again (adversarial review finding).
        if (!settingsPrefs.getBoolean("migrated_sd_engine_prefs_v1", false)) {
            if (settingsPrefs.getString("runtime", null) == ModelRuntimeType.SD_ENGINE.name) {
                val explicitSd = _models.value.any {
                    it.isDownloaded && it.runtimeType == ModelRuntimeType.SD_ENGINE && it.runtimeTypeOverrideByUser
                }
                if (!explicitSd) {
                    settingsPrefs.edit().putString("runtime", ModelRuntimeType.LLAMA_CPP.name).apply()
                    if (_settings.value.runtime == ModelRuntimeType.SD_ENGINE) {
                        _settings.value = _settings.value.copy(runtime = ModelRuntimeType.LLAMA_CPP)
                    }
                }
            }
            settingsPrefs.edit().putBoolean("migrated_sd_engine_prefs_v1", true).apply()
        }

        val savedActiveId = modelStorageManager.getActiveModelId()
        val restoredModel = _models.value.firstOrNull { it.id == savedActiveId && it.isDownloaded }
            ?: _models.value.firstOrNull { it.isDownloaded }

        if (restoredModel != null) {
            _activeModel.value = restoredModel
            _settings.value = _settings.value.copy(
                runtime = restoredModel.runtimeType,
                enableMtp = if (restoredModel.supportsMtp) true else _settings.value.enableMtp
            )
            _engineStatusMessage.value = localizedString(R.string.vm_status_model_restored, restoredModel.name)
        } else {
            _activeModel.value = null
            _engineStatusMessage.value = localizedString(R.string.vm_status_no_default_model)
        }

        // Memory protection: apply the degradation ladder accumulated by previous
        // abnormal runs (OOM kill / native crash mid-inference) before the first load.
        memoryGuard?.let { guard ->
            if (guard.isEnabled() && guard.level() > 0) {
                _settings.value = guard.applyLevel(_settings.value)
            }
            val report = LocalLlmApp.consumeStartupReport()
            if (report != null && report.abnormalPreviousRun) {
                _engineStatusMessage.value = localizedString(R.string.vm_status_memory_reduced, guard.level())
            }
        }
        refreshMemoryGuardState()

        // Restore MCP session: tool handles live per-process, so re-list them when
        // the user left MCP enabled with a saved URL.
        if (_settings.value.isMcpEnabled && _settings.value.mcpServerUrl.isNotBlank()) {
            connectMcp(_settings.value.mcpServerUrl)
        } else if (_settings.value.isMcpEnabled) {
            _settings.value = _settings.value.copy(isMcpEnabled = false)
            settingsPrefs.edit().putBoolean("is_mcp_enabled", false).apply()
        }

        // Auto-create initial conversation if empty (in-memory until first message).
        // Stale empty rows from older versions are pruned first.
        viewModelScope.launch {
            repository.pruneEmptyConversations()
            conversations.collect { list ->
                if (_currentConversationId.value == null) {
                    if (list.isEmpty()) {
                        createNewConversation()
                    } else {
                        selectConversation(list.first().id)
                    }
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

    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    @Deprecated("타입 안전성을 위해 navigateTo(AppScreen)을 사용하세요.", ReplaceWith("navigateTo(AppScreen.fromRoute(screenRoute))"))
    fun navigateTo(screenRoute: String) {
        _currentScreen.value = AppScreen.fromRoute(screenRoute)
    }

    fun createNewConversation() {
        stopGeneration()
        setCurrentToPending(newPendingConversation())
    }

    private fun newPendingConversation(): Conversation {
        return Conversation(
            id = UUID.randomUUID().toString(),
            title = localizedString(R.string.vm_conversation_default_title),
            modelId = _activeModel.value?.id ?: ""
        )
    }

    private fun setCurrentToPending(pending: Conversation) {
        pendingConversation = pending
        _currentConversationId.value = pending.id
        messageCollectionJob?.cancel()
        _messages.value = emptyList()
        messageCollectionJob = viewModelScope.launch {
            repository.getMessagesForConversation(pending.id).collect { msgList ->
                _messages.value = msgList
            }
        }
    }

    /** Current conversation id, materializing an in-memory draft when none exists. */
    private fun ensureConversationId(): String {
        _currentConversationId.value?.let { return it }
        val pending = newPendingConversation()
        setCurrentToPending(pending)
        return pending.id
    }

    /** Persists the in-memory draft exactly once, on its first message. */
    private suspend fun persistPendingIfNeeded(convId: String) {
        val pending = pendingConversation
        if (pending != null && pending.id == convId) {
            repository.saveConversation(pending)
            pendingConversation = null
        }
    }

    fun selectConversation(conversationId: String) {
        pendingConversation?.let { pending ->
            if (pending.id == conversationId) {
                if (_currentConversationId.value == conversationId && messageCollectionJob != null) return
                stopGeneration()
                setCurrentToPending(pending)
                return
            }
            // Switching away discards the unsent draft (it holds no messages by definition).
            pendingConversation = null
        }
        if (_currentConversationId.value == conversationId && messageCollectionJob != null) return
        stopGeneration()
        _currentConversationId.value = conversationId
        messageCollectionJob?.cancel()
        messageCollectionJob = viewModelScope.launch {
            repository.getMessagesForConversation(conversationId).collect { msgList ->
                _messages.value = msgList
            }
        }
    }

    fun deleteConversation(id: String) {
        stopGeneration()
        if (pendingConversation?.id == id) pendingConversation = null
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
        stopGeneration()
        pendingConversation = null
        viewModelScope.launch {
            repository.clearAll()
            _messages.value = emptyList()
            _currentConversationId.value = null
            createNewConversation()
        }
    }

    fun renameConversation(id: String, newTitle: String) {
        pendingConversation?.let {
            if (it.id == id) {
                pendingConversation = it.copy(title = newTitle)
                return
            }
        }
        viewModelScope.launch {
            repository.renameConversation(id, newTitle)
        }
    }

    fun setPendingAttachment(attachment: ChatAttachment?) {
        _pendingAttachment.value = attachment
    }

    fun selectModel(model: LlmModel) {
        if (!model.isDownloaded) {
            _engineStatusMessage.value = localizedString(R.string.vm_status_model_not_downloaded, model.name)
            return
        }

        // Swapping weights under a running generation would release the native
        // context it is using; stop first (loadModel also serializes via Mutex).
        stopGeneration()
        modelLoadingJob?.cancel()
        modelLoadingJob = viewModelScope.launch {
            _isModelLoading.value = true
            _modelLoadingProgress.value = 0.05f
            _modelLoadingStage.value = localizedString(R.string.vm_stage_preparing_load)

            // Sync runtime type and auto-apply MTP if model supports it. A user-chosen
            // runtime wins: picking an SDengine model must not silently fall back to
            // llama.cpp, and vice versa.
            val isGguf = model.runtimeType != ModelRuntimeType.LITE_RT
            val runtimeToApply = when {
                model.runtimeTypeOverrideByUser -> model.runtimeType
                _settings.value.runtime == ModelRuntimeType.SD_ENGINE && isGguf -> ModelRuntimeType.SD_ENGINE
                else -> model.runtimeType
            }
            _settings.value = _settings.value.copy(
                runtime = runtimeToApply,
                enableMtp = if (model.supportsMtp) true else _settings.value.enableMtp
            )
            settingsPrefs.edit().putString("runtime", runtimeToApply.name).apply()

            // Model loading is the biggest single allocation in the app: mark it so an
            // OS kill during the load is recorded and lowers the next run's settings.
            val loadSettings = _settings.value
            memoryGuard?.markInferenceStart(
                modelName = model.name,
                runtime = loadSettings.runtime.name,
                contextWindow = loadSettings.contextWindow,
                gpuLayers = loadSettings.gpuLayers,
                gpuAcceleration = loadSettings.enableGpuAcceleration
            )
            try {
                val status = llmEngine.loadModel(model, _settings.value) { stage, progress ->
                    _modelLoadingStage.value = stage
                    _modelLoadingProgress.value = progress
                }

                if (llmEngine.isModelReady()) {
                    _activeModel.value = model
                    _modelLoadingProgress.value = 1.0f
                    _modelLoadingStage.value = localizedString(R.string.vm_stage_load_complete)
                    _engineStatusMessage.value = status
                    modelStorageManager.saveActiveModelId(model.id)
                    delay(400)
                } else {
                    _activeModel.value = null
                    _modelLoadingProgress.value = 0f
                    _modelLoadingStage.value = localizedString(R.string.vm_stage_load_failed)
                    _engineStatusMessage.value = status
                    delay(2000)
                }
            } catch (t: Throwable) {
                android.util.Log.e("MainViewModel", "Model loading error", t)
                _activeModel.value = null
                _modelLoadingProgress.value = 0f
                _modelLoadingStage.value = localizedString(R.string.vm_stage_load_failed)
                _engineStatusMessage.value = localizedString(R.string.vm_status_model_load_exception, t.localizedMessage ?: t.message)
                delay(2000)
            } finally {
                // Loading completes -> hide loading bar
                memoryGuard?.markInferenceEnd()
                _isModelLoading.value = false
                _modelLoadingProgress.value = 0f
                _modelLoadingStage.value = ""
            }
        }
    }

    fun switchRuntime(runtime: ModelRuntimeType) {
        _settings.value = _settings.value.copy(runtime = runtime)
        settingsPrefs.edit().putString("runtime", runtime.name).apply()
        // SDengine runs GGUF containers, so any downloaded GGUF is a candidate; the
        // other runtimes match on their own model type.
        val downloadedMatch = when (runtime) {
            ModelRuntimeType.SD_ENGINE -> _models.value.firstOrNull {
                it.isDownloaded && it.runtimeType != ModelRuntimeType.LITE_RT
            }
            else -> _models.value.firstOrNull { it.runtimeType == runtime && it.isDownloaded }
        }
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
            _engineStatusMessage.value = when (runtime) {
                ModelRuntimeType.LLAMA_CPP -> localizedString(R.string.vm_status_no_llamacpp_model)
                ModelRuntimeType.LITE_RT -> localizedString(R.string.vm_status_no_litert_model)
                ModelRuntimeType.SD_ENGINE -> com.localllm.engine.SDEngine.advisoryText()
            }
        }
    }

    /**
     * Explicit runtime choice for one model (Model Manager). Marks the choice as a
     * user override so MoE auto-detection never flips it back.
     */
    fun setModelRuntime(modelId: String, runtime: ModelRuntimeType) {
        val updated = _models.value.map { model ->
            if (model.id == modelId) {
                model.copy(runtimeType = runtime, runtimeTypeOverrideByUser = true)
            } else {
                model
            }
        }
        _models.value = updated
        modelStorageManager.saveModels(updated)
        val target = updated.firstOrNull { it.id == modelId } ?: return
        if (_activeModel.value?.id == modelId) {
            _settings.value = _settings.value.copy(runtime = runtime)
            settingsPrefs.edit().putString("runtime", runtime.name).apply()
            selectModel(target)
        } else {
            _engineStatusMessage.value = localizedString(R.string.vm_status_runtime_override, target.name, runtime.label)
        }
    }

    fun updateHfToken(token: String) {
        val clean = token.trim()
        _settings.value = _settings.value.copy(hfToken = clean)
        settingsPrefs.edit().putString("hf_token", clean).apply()
    }

    fun updateSettings(newSettings: GenerationSettings) {
        val previous = _settings.value
        _settings.value = newSettings
        settingsPrefs.edit().apply {
            putString("hf_token", newSettings.hfToken)
            putString("theme_color", newSettings.themeColorName)
            putString("dark_mode", newSettings.darkModePreference)
            putFloat("temperature", newSettings.temperature)
            putFloat("top_p", newSettings.topP)
            putInt("top_k", newSettings.topK)
            putInt("context_window", newSettings.contextWindow)
            putFloat("repetition_penalty", newSettings.repetitionPenalty)
            putBoolean("enable_mtp", newSettings.enableMtp)
            putBoolean("enable_indexing", newSettings.enableIndexingAcceleration)
            putBoolean("enable_gpu", newSettings.enableGpuAcceleration)
            putInt("gpu_layers", newSettings.gpuLayers)
            putString("runtime", newSettings.runtime.name)
            putBoolean("show_metrics", newSettings.showPerformanceMetrics)
            putString("api_server_bind_address", newSettings.apiServerBindAddress)
            putBoolean("api_server_require_auth", newSettings.apiServerRequireAuth)
            putString("language_preference", newSettings.languagePreference)
            putString("mcp_server_url", newSettings.mcpServerUrl)
            putBoolean("is_mcp_enabled", newSettings.isMcpEnabled)
            apply()
        }
        // Re-enabling GPU is an explicit retry: forget the remembered driver failure
        // so LiteRT offers the GPU backend again.
        if (!previous.enableGpuAcceleration && newSettings.enableGpuAcceleration) {
            settingsPrefs.edit().putBoolean(LlmEngine.KEY_LITERT_GPU_UNAVAILABLE, false).apply()
        }
        // Sampling options (temperature/topP/topK/system prompt) apply per request,
        // so only runtime-affecting changes justify a full weight reload. Reloading on
        // every slider tick used to thrash the native context dozens of times per drag.
        val needsReload = previous.runtime != newSettings.runtime ||
                previous.contextWindow != newSettings.contextWindow ||
                previous.enableGpuAcceleration != newSettings.enableGpuAcceleration ||
                previous.gpuLayers != newSettings.gpuLayers
        if (!needsReload) return
        viewModelScope.launch {
            try {
                val status = llmEngine.loadModel(_activeModel.value, newSettings)
                _engineStatusMessage.value = status
            } catch (t: Throwable) {
                android.util.Log.e("MainViewModel", "Model reload error with new settings", t)
                _engineStatusMessage.value = localizedString(R.string.vm_status_settings_apply_error, t.localizedMessage ?: t.message)
            }
        }
    }

    fun setLanguagePreference(pref: String) {
        val updated = _settings.value.copy(languagePreference = pref)
        updateSettings(updated)
    }

    fun setReasoningEffort(effort: Float) {
        _settings.value = _settings.value.copy(reasoningEffort = effort)
    }

    /** Downloads the on-device Whisper tiny STT model (~75MB, one time). */
    fun downloadLocalStt() {
        viewModelScope.launch {
            voiceManager.localStt.downloadModel()
        }
    }

    fun deleteLocalStt() {
        viewModelScope.launch {
            voiceManager.localStt.deleteModel()
        }
    }

    /**
     * Installs the Korean neural TTS voice (sherpa-onnx supertonic-3, ~145MB) and
     * switches to it, so picking a voice in the UI actually changes what is spoken.
     */
    fun downloadLocalTts() {
        viewModelScope.launch {
            val installed = voiceManager.localTts.downloadModel()
            if (installed) {
                voiceManager.setTtsMode(TtsEngineMode.LOCAL_NEURAL)
                _engineStatusMessage.value = localizedString(R.string.vm_status_tts_installed)
            }
        }
    }

    fun deleteLocalTts() {
        viewModelScope.launch {
            voiceManager.localTts.deleteModel()
            voiceManager.setTtsMode(TtsEngineMode.SYSTEM)
        }
    }

    fun selectTtsMode(mode: TtsEngineMode) = voiceManager.setTtsMode(mode)

    fun setTtsSpeaker(speakerId: Int) = voiceManager.setTtsSpeaker(speakerId)

    fun setTtsSpeed(speed: Float) = voiceManager.setTtsSpeed(speed)

    fun previewTts() {
        voiceManager.speak(localizedString(R.string.voice_tts_preview_text))
    }

    fun connectMcp(url: String) {        viewModelScope.launch {
            _mcpStatusText.value = localizedString(R.string.vm_mcp_checking)
            val result = mcpClient.connectServer(url)
            if (result.isSuccess) {
                _mcpTools.value = result.tools
                _mcpToolsContext.value = McpClient.buildToolsContext(result.serverName, result.tools)
                _mcpStatusText.value = localizedString(R.string.vm_mcp_connected, result.serverName, result.latencyMs, result.tools.size)
            } else {
                mcpClient.disconnect()
                _mcpTools.value = emptyList()
                _mcpToolsContext.value = null
                _mcpStatusText.value = localizedString(R.string.vm_mcp_failed, result.message)
            }
            _settings.value = _settings.value.copy(
                mcpServerUrl = url,
                isMcpEnabled = result.isSuccess
            )
            settingsPrefs.edit()
                .putString("mcp_server_url", url)
                .putBoolean("is_mcp_enabled", result.isSuccess)
                .apply()
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
                val downloader = com.localllm.android.engine.ModelDownloader()
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
                _engineStatusMessage.value = localizedString(R.string.vm_status_no_downloaded_model)
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
                _engineStatusMessage.value = localizedString(
                    R.string.vm_status_vision_toggle,
                    updated.name,
                    if (updated.hasMmproj) localizedString(R.string.vm_state_enabled_linked) else localizedString(R.string.vm_state_disabled)
                )
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
                _engineStatusMessage.value = localizedString(
                    R.string.vm_status_drafter_toggle,
                    updated.name,
                    if (updated.supportsMtp) localizedString(R.string.vm_state_enabled_accel) else localizedString(R.string.vm_state_disabled)
                )
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
        val derivedFileName = ModelStorageManager.sanitizeFileName(
            cleanMain.substringAfterLast('/', "custom_model$defaultMainExt"),
            fallback = "custom_model$defaultMainExt"
        )
        val finalFileName = if (customFileName.isNotBlank()) {
            ModelStorageManager.sanitizeFileName(customFileName, fallback = derivedFileName)
        } else derivedFileName

        val visionFileName = if (cleanVision.isNotBlank()) {
            ModelStorageManager.sanitizeFileName(
                cleanVision.substringAfterLast('/', "mmproj_vision.gguf"),
                fallback = "mmproj_vision.gguf"
            )
        } else null
        val mtpFileName = if (cleanMtp.isNotBlank()) {
            ModelStorageManager.sanitizeFileName(
                cleanMtp.substringAfterLast('/', "mtp_draft.gguf"),
                fallback = "mtp_draft.gguf"
            )
        } else null
        val templateFileName = if (cleanTemplate.isNotBlank()) {
            ModelStorageManager.sanitizeFileName(
                cleanTemplate.substringAfterLast('/', "prompt_template.json"),
                fallback = "prompt_template.json"
            )
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
        descriptionParts.add(localizedString(R.string.vm_desc_filename, finalFileName))
        if (runtime == ModelRuntimeType.LITE_RT) descriptionParts.add(localizedString(R.string.vm_desc_litert_template))
        if (effectiveReasoning) descriptionParts.add(localizedString(R.string.vm_desc_thinking))
        if (effectiveVision) descriptionParts.add(
            if (cleanVision.isNotBlank()) localizedString(R.string.vm_desc_vision_included)
            else localizedString(R.string.vm_desc_vision_embedded)
        )
        if (effectiveMtp) descriptionParts.add(
            if (cleanMtp.isNotBlank()) localizedString(R.string.vm_desc_mtp_included)
            else localizedString(R.string.vm_desc_drafter_embedded)
        )
        if (cleanToken.isNotBlank()) descriptionParts.add(localizedString(R.string.vm_desc_token_applied))

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
            description = localizedString(R.string.vm_desc_custom_model, descriptionParts.joinToString(" • ")),
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
        val rawPrompt = userPrompt.trim()
        val trimmed = if (rawPrompt.length > MAX_USER_MESSAGE_CHARS) {
            _engineStatusMessage.value = localizedString(R.string.vm_status_message_too_long, MAX_USER_MESSAGE_CHARS)
            rawPrompt.take(MAX_USER_MESSAGE_CHARS)
        } else {
            rawPrompt
        }
        val attachment = _pendingAttachment.value
        if (trimmed.isEmpty() && attachment == null) return

        val convId = _currentConversationId.value ?: ensureConversationId()

        // 1. Create and save user message
        val userMessage = ChatMessage(
            conversationId = convId,
            role = MessageRole.USER,
            content = trimmed,
            attachment = attachment
        )
        _pendingAttachment.value = null

        viewModelScope.launch {
            persistPendingIfNeeded(convId)
            repository.saveMessage(userMessage)

            // Auto-generate title if this is the first message
            val currentList = _messages.value
            if (currentList.isEmpty() || currentList.size <= 1) {
                val title = if (trimmed.length > 20) "${trimmed.take(20)}..." else trimmed
                repository.renameConversation(convId, title.ifBlank { localizedString(R.string.vm_conversation_photo_title) })
            }

            // Check if model is downloaded and ready
            val currentModel = _activeModel.value
            if (currentModel == null || !currentModel.isDownloaded) {
                val noticeMessage = ChatMessage(
                    conversationId = convId,
                    role = MessageRole.ASSISTANT,
                    content = localizedString(R.string.vm_notice_no_model),
                    isStreaming = false
                )
                repository.saveMessage(noticeMessage)
                return@launch
            }

            if (!llmEngine.isModelReady()) {
                _engineStatusMessage.value = localizedString(R.string.vm_status_loading_memory, currentModel.name)
                _isModelLoading.value = true
                _modelLoadingProgress.value = 0.15f
                _modelLoadingStage.value = localizedString(R.string.vm_stage_loading_weights)
                val loadResult = try {
                    llmEngine.loadModel(currentModel, _settings.value) { stage, progress ->
                        _modelLoadingStage.value = stage
                        _modelLoadingProgress.value = progress
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("MainViewModel", "Auto-load on sendMessage failed", t)
                    localizedString(R.string.vm_status_model_load_exception, t.localizedMessage ?: t.message)
                } finally {
                    _isModelLoading.value = false
                    _modelLoadingProgress.value = 0f
                    _modelLoadingStage.value = ""
                }

                _engineStatusMessage.value = loadResult

                if (!llmEngine.isModelReady()) {
                    val noticeMessage = ChatMessage(
                        conversationId = convId,
                        role = MessageRole.ASSISTANT,
                        content = localizedString(R.string.vm_notice_model_load_failed, loadResult),
                        isStreaming = false
                    )
                    repository.saveMessage(noticeMessage)
                    return@launch
                }
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
                beginInferenceGuard()
                try {
                    llmEngine.streamGenerate(
                        prompt = trimmed,
                        history = history,
                        settings = _settings.value,
                        attachment = userMessage.attachment,
                        mcpToolsContext = if (_settings.value.isMcpEnabled) _mcpToolsContext.value else null
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
                            // Surfaces an automatic backend switch (e.g. LiteRT GPU → CPU)
                            // that happened during this generation.
                            llmEngine.consumeBackendNote()?.let { note ->
                                _engineStatusMessage.value = note
                            }
                        }
                    }
                } catch (e: Exception) {
                    val errorMsg = streamPlaceholder.copy(
                        content = localizedString(
                            R.string.vm_error_prefix,
                            e.localizedMessage ?: localizedString(R.string.vm_error_inference_generic)
                        ),
                        isStreaming = false
                    )
                    repository.saveMessage(errorMsg)
                    _streamingMessage.value = null
                    _isGenerating.value = false
                } finally {
                    // Clears the in-flight marker: reaching this point means the process
                    // survived the generation (an OS kill would leave the marker behind).
                    endInferenceGuard()
                }
            }
        }
    }

    // ==========================================
    // Memory protection
    // ==========================================

    data class MemoryGuardUiState(
        val enabled: Boolean,
        val level: Int,
        val incidents: List<MemoryGuardStore.Incident>
    )

    private fun parseStoredRuntime(stored: String?): ModelRuntimeType = try {
        if (stored.isNullOrBlank()) ModelRuntimeType.LLAMA_CPP else ModelRuntimeType.valueOf(stored)
    } catch (_: Throwable) {
        ModelRuntimeType.LLAMA_CPP
    }

    private fun currentMemoryGuardUiState(): MemoryGuardUiState {
        val guard = memoryGuard
        return MemoryGuardUiState(
            enabled = guard?.isEnabled() ?: false,
            level = guard?.level() ?: 0,
            incidents = guard?.incidents() ?: emptyList()
        )
    }

    private fun refreshMemoryGuardState() {
        _memoryGuardState.value = currentMemoryGuardUiState()
    }

    fun setMemoryGuardEnabled(enabled: Boolean) {
        memoryGuard?.setEnabled(enabled)
        refreshMemoryGuardState()
    }

    fun resetMemoryGuard() {
        memoryGuard?.reset()
        refreshMemoryGuardState()
    }

    fun memoryGuardLog(): String = memoryGuard?.logTail() ?: ""

    /** Marks an inference as in flight, so an OS kill during it is detected next launch. */
    private fun beginInferenceGuard() {
        val current = _settings.value
        memoryGuard?.markInferenceStart(
            modelName = _activeModel.value?.name,
            runtime = current.runtime.name,
            contextWindow = current.contextWindow,
            gpuLayers = current.gpuLayers,
            gpuAcceleration = current.enableGpuAcceleration
        )
        memoryWatchdog.start()
    }

    private fun endInferenceGuard() {
        memoryWatchdog.stop()
        memoryGuard?.markInferenceEnd()
    }

    /**
     * The watchdog fires while the process still has room to react: stop the
     * generation, record why, and lower the next run's settings instead of letting
     * the OS kill the app with no chance to write anything down.
     */
    private fun onMemoryCritical(snapshot: MemorySnapshot) {
        android.util.Log.w(
            "MainViewModel",
            "Critical memory during inference: avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory}"
        )
        memoryGuard?.record(
            cause = MemoryGuardStore.Cause.LOW_MEMORY_STOP,
            detail = "watchdog stop: avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory}",
            modelName = _activeModel.value?.name,
            raiseLevel = true
        )
        refreshMemoryGuardState()
        stopGeneration()
        try {
            llmEngine.stopGeneration()
        } catch (t: Throwable) {
            android.util.Log.w("MainViewModel", "Engine stop after memory warning failed", t)
        }
        _engineStatusMessage.value = localizedString(R.string.vm_status_memory_stop, memoryGuard?.level() ?: 0)
    }

    fun stopGeneration() {
        currentStreamJob?.cancel()
        currentStreamJob = null
        // Propagate to the native loop: cancelling the collector alone left
        // llama.cpp / LiteRT still generating in the background.
        try {
            llmEngine.stopGeneration()
        } catch (t: Throwable) {
            android.util.Log.w("MainViewModel", "Engine stop error", t)
        }
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
            _engineStatusMessage.value = localizedString(R.string.vm_voice_no_model)
            voiceManager.speak(localizedString(R.string.vm_voice_no_model))
            return
        }
        voiceManager.startListening(
            onResult = { userSpokenText ->
                // Anti-feedback guard: silence hallucinations and TTS echo would otherwise
                // loop forever. A single repeat re-listens instead of ending the session,
                // because one mis-recognition should not hang up the conversation.
                if (userSpokenText.isBlank() || userSpokenText == lastVoiceInput) {
                    duplicateVoiceTurns++
                    if (duplicateVoiceTurns >= 2) {
                        voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                        return@startListening
                    }
                    startInteractiveVoiceSession()
                    return@startListening
                }
                duplicateVoiceTurns = 0
                lastVoiceInput = userSpokenText
                voiceManager.setVoiceState(InteractiveVoiceState.PROCESSING)
                viewModelScope.launch {
                    try {
                        // The engine (unlike the chat path) may hold no loaded model
                        // after a cold start — load on demand instead of crashing.
                        if (!llmEngine.isModelReady()) {
                            val target = _activeModel.value
                            if (target == null || !target.isDownloaded) {
                                voiceManager.speak(localizedString(R.string.vm_voice_no_model))
                                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                                return@launch
                            }
                            _engineStatusMessage.value = localizedString(R.string.vm_status_loading_memory, target.name)
                            val loadResult = try {
                                llmEngine.loadModel(target, _settings.value)
                            } catch (t: Throwable) {
                                localizedString(R.string.vm_status_model_load_exception, t.localizedMessage ?: t.message)
                            }
                            if (!llmEngine.isModelReady()) {
                                voiceManager.speak(localizedString(R.string.vm_voice_load_failed, loadResult))
                                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                                return@launch
                            }
                            _engineStatusMessage.value = loadResult
                        }
                        val convId = _currentConversationId.value ?: ensureConversationId()
                        val userMsg = ChatMessage(conversationId = convId, role = MessageRole.USER, content = userSpokenText)
                        persistPendingIfNeeded(convId)
                        repository.saveMessage(userMsg)

                        // Stream or generate response
                        val voiceHistory = _messages.value.takeLast(6).map { it.role.name to it.content }
                        beginInferenceGuard()
                        try {
                            llmEngine.streamGenerate(
                                prompt = userSpokenText,
                                history = voiceHistory,
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
                        } finally {
                            endInferenceGuard()
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("MainViewModel", "Voice session error", e)
                        voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
                        voiceManager.speak(localizedString(R.string.vm_voice_error))
                    }
                }
            },
            onError = { _ ->
                voiceManager.setVoiceState(InteractiveVoiceState.IDLE)
            },
            // Hands-free: the recorder ends the turn itself when the speaker goes quiet.
            autoEndpoint = true
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
                val targetFileName = ModelStorageManager.sanitizeFileName(
                    displayName.ifBlank { "imported_model.gguf" },
                    fallback = "imported_model.gguf"
                )
                val destFile = File(modelsDir, targetFileName)

                _engineStatusMessage.value = localizedString(R.string.vm_status_copying_gguf)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                val detected = GgufMetadataDetector.detect(destFile)
                val hasVision = detected.hasVision
                val hasDrafter = detected.hasDrafter
                val isLiteRt = detected.detectedRuntime == ModelRuntimeType.LITE_RT

                // First-party engine peek (additive only): verify the container with the
                // new GGUF reader. Never overrides the shipping detector on failure.
                val engineNote = try {
                    com.localllm.engine.GgufReader.open(destFile).use { reader ->
                        localizedString(R.string.vm_desc_engine_note, reader.tensors.size)
                    }
                } catch (_: Throwable) {
                    null
                }

                val cleanName = targetFileName
                    .removeSuffix(".gguf")
                    .removeSuffix(".bin")
                    .removeSuffix(".litertlm")
                    .removeSuffix(".task")
                    .removeSuffix(".tflite")
                    .replace("-", " ")
                    .replace("_", " ")

                val sdEngineCandidate = !isLiteRt && detected.isSdEngineCandidate
                val customModel = LlmModel(
                    id = "custom-${UUID.randomUUID()}",
                    name = cleanName,
                    repoId = if (isLiteRt) "local/litert-imported" else "local/imported",
                    fileName = targetFileName,
                    // Default is always llama.cpp for GGUF (even MoE): SDengine
                    // is opt-in via Model Manager's per-model runtime override.
                    runtimeType = detected.detectedRuntime,
                    sizeBytes = destFile.length(),
                    isDownloaded = true,
                    downloadStatus = "COMPLETED",
                    localFilePath = destFile.absolutePath,
                    isBundledModel = isLiteRt || detected.isUnifiedBundle,
                    description = buildString {
                        if (isLiteRt) {
                            append(localizedString(R.string.vm_desc_imported_litert))
                            if (hasVision) append(localizedString(R.string.vm_desc_imported_litert_vision))
                            if (hasDrafter) append(localizedString(R.string.vm_desc_imported_litert_drafter))
                        } else {
                            append(localizedString(R.string.vm_desc_imported_gguf))
                            if (hasVision) append(localizedString(R.string.vm_desc_imported_gguf_vision))
                            if (hasDrafter) append(localizedString(R.string.vm_desc_imported_gguf_drafter))
                            if (sdEngineCandidate) append(localizedString(R.string.vm_desc_imported_moe_sdengine))
                        }
                        if (engineNote != null) append(" • $engineNote")
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
                _engineStatusMessage.value = localizedString(R.string.vm_status_import_complete, cleanName)
                selectModel(customModel)
            } catch (e: Exception) {
                _engineStatusMessage.value = localizedString(R.string.vm_status_import_failed, e.localizedMessage)
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
                getSettings = { _settings.value },
                initialApiKey = _apiServerApiKey.value
            )
        }

        _apiServerApiKey.value = apiServer?.currentApiKey ?: ""
        refreshLocalIp()

        apiServer?.onRequestProcessed = { count ->
            _apiRequestCount.value = count
        }

        apiServer?.start { isRunning, msg ->
            _isApiModeEnabled.value = isRunning
            _apiServerStatusMessage.value = msg
            _engineStatusMessage.value = localizedString(
                R.string.vm_status_api_server,
                if (isRunning) localizedString(R.string.vm_state_running) else localizedString(R.string.vm_state_stopped)
            )
            _apiRequestCount.value = apiServer?.requestCount ?: 0
            _apiServerApiKey.value = apiServer?.currentApiKey ?: ""
        }
    }

    fun regenerateApiKey() {
        val newKey = apiServer?.regenerateApiKey() ?: OllamaApiServer.generateSecureApiKey()
        _apiServerApiKey.value = newKey
    }

    fun stopApiServer() {
        apiServer?.stop { isRunning, msg ->
            _isApiModeEnabled.value = isRunning
            _apiServerStatusMessage.value = msg
            _engineStatusMessage.value = localizedString(R.string.vm_status_api_stopped)
        }
    }

    fun setApiServerExternalAccess(enabled: Boolean) {
        val bindAddr = if (enabled) "0.0.0.0" else "127.0.0.1"
        val updated = _settings.value.copy(apiServerBindAddress = bindAddr)
        _settings.value = updated
        settingsPrefs.edit().putString("api_server_bind_address", bindAddr).apply()

        if (_isApiModeEnabled.value) {
            restartApiServer()
        }
    }

    fun restartApiServer() {
        stopApiServer()
        startApiServer()
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

    private fun localizedString(@StringRes id: Int, vararg args: Any?): String {
        val pref = _settings.value.languagePreference
        val locale = when (pref) {
            "ko" -> Locale.KOREAN
            "en" -> Locale.ENGLISH
            else -> Locale.getDefault()
        }
        val config = Configuration(getApplication<Application>().resources.configuration).apply { setLocale(locale) }
        @Suppress("UNCHECKED_CAST")
        return getApplication<Application>().createConfigurationContext(config).getString(id, *(args as Array<Any>))
    }

    private companion object {
        /** Guards the encrypted DB and prompt builder against multi-megabyte pastes. */
        const val MAX_USER_MESSAGE_CHARS = 32_768
    }
}
