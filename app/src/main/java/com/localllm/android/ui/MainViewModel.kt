package com.localllm.android.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.localllm.android.OffconAIApplication
import com.localllm.android.R
import com.localllm.android.data.ModelStorageManager
import com.localllm.android.data.local.ChatDatabase
import com.localllm.android.data.preferences.AppSettingsStore
import com.localllm.android.data.repository.ChatRepository
import com.localllm.android.document.DocumentStats
import com.localllm.android.document.DocumentSummary
import com.localllm.android.engine.DownloadStatus
import com.localllm.android.engine.HfSource
import com.localllm.android.engine.LlmEngine
import com.localllm.android.engine.McpTool
import com.localllm.android.engine.McpToolCall
import com.localllm.android.i18n.AppStrings
import com.localllm.android.i18n.LocalizedStringProvider
import com.localllm.android.memory.MemoryGuard
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.memory.MemorySnapshot
import com.localllm.android.memory.MemoryWatchdog
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.Conversation
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.model.ToolCallTemplate
import com.localllm.android.ui.controllers.ApiServerController
import com.localllm.android.ui.controllers.ChatTurnRunner
import com.localllm.android.ui.controllers.ConversationController
import com.localllm.android.ui.controllers.McpSessionController
import com.localllm.android.ui.controllers.ModelPortfolioController
import com.localllm.android.ui.controllers.VoiceSessionController
import com.localllm.android.update.AppUpdateInfo
import com.localllm.android.update.UpdateChecker
import com.localllm.android.voice.TtsEngineMode
import com.localllm.android.voice.VoiceAudioDecision
import com.localllm.android.voice.VoiceManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Thin UI orchestration ViewModel for OffconAI.
 * Delegates domain state and side effects to cohesive controllers under `ui/controllers/`
 * and `data/preferences/AppSettingsStore` while preserving reactive `StateFlow` bindings.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    data class MemoryGuardUiState(
        val enabled: Boolean,
        val level: Int,
        val incidents: List<MemoryGuardStore.Incident>
    )

    val llmEngine: LlmEngine = getApplication<OffconAIApplication>().llmEngine
    val voiceManager = VoiceManager(application)

    private val settingsStore = AppSettingsStore(application)
    private val _settings = MutableStateFlow(
        run {
            settingsStore.migrateLegacyRuntimeIfNeeded()
            settingsStore.loadSettings()
        }
    )
    val settings: StateFlow<GenerationSettings> = _settings.asStateFlow()

    private val strings = LocalizedStringProvider(application) { _settings.value.languagePreference }

    private val _currentScreen = MutableStateFlow(AppScreen.CHAT)
    val currentScreen: StateFlow<AppScreen> = _currentScreen.asStateFlow()

    private val _engineStatusMessage = MutableStateFlow<String?>(strings.get(R.string.vm_status_download_required))
    val engineStatusMessage: StateFlow<String?> = _engineStatusMessage.asStateFlow()

    // ---- Memory protection ----
    private val memoryGuard: MemoryGuardStore? = MemoryGuard.get()
    private val memoryWatchdog = MemoryWatchdog(
        scope = viewModelScope,
        memory = { MemorySnapshot.read(getApplication()) },
        onCritical = { snapshot -> onMemoryCritical(snapshot) }
    )
    private val _memoryGuardState = MutableStateFlow(currentMemoryGuardUiState())
    val memoryGuardState: StateFlow<MemoryGuardUiState> = _memoryGuardState.asStateFlow()

    // ---- App update banner ----
    private val updatePrefs = application.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val _appUpdate = MutableStateFlow<AppUpdateInfo?>(null)
    val appUpdate: StateFlow<AppUpdateInfo?> = _appUpdate.asStateFlow()

    // ---- Domain controllers ----
    private var mcpRef: McpSessionController? = null
    private val modelController: ModelPortfolioController
    private val mcpController: McpSessionController
    private val conversationController: ConversationController
    private val voiceController: VoiceSessionController
    private val apiController: ApiServerController
    private val chatRunner: ChatTurnRunner

    init {
        AppStrings.attach(application.resources)
        HfSource.useMirror = _settings.value.hfSource == "mirror"

        val database = ChatDatabase.getInstance(application)
        val repository = ChatRepository(database.chatDao())
        val modelStorageManager = ModelStorageManager(application)

        modelController = ModelPortfolioController(
            application = application,
            scope = viewModelScope,
            modelStorageManager = modelStorageManager,
            settingsStore = settingsStore,
            llmEngine = llmEngine,
            memoryGuard = memoryGuard,
            strings = strings,
            currentSettings = { _settings.value },
            updateSettingsState = { _settings.value = it },
            onRefreshMcpToolsContext = { mcpRef?.refreshToolsContext() },
            onStopGeneration = { stopGeneration() },
            onUpdateHfToken = { updateHfToken(it) },
            postStatus = { _engineStatusMessage.value = it }
        )

        mcpController = McpSessionController(
            scope = viewModelScope,
            settingsStore = settingsStore,
            strings = strings,
            currentSettings = { _settings.value },
            currentToolTemplate = { modelController.activeToolTemplate.value },
            updateSettingsState = { _settings.value = it }
        )
        mcpRef = mcpController

        conversationController = ConversationController(
            application = application,
            scope = viewModelScope,
            repository = repository,
            strings = strings,
            activeModelId = { modelController.activeModel.value?.id ?: "" },
            languagePreference = { _settings.value.languagePreference },
            onStopGeneration = { stopGeneration() },
            postStatus = { _engineStatusMessage.value = it }
        )

        voiceController = VoiceSessionController(
            application = application,
            scope = viewModelScope,
            voiceManager = voiceManager,
            llmEngine = llmEngine,
            conversations = conversationController,
            strings = strings,
            activeModel = { modelController.activeModel.value },
            currentSettings = { _settings.value },
            beginInferenceGuard = ::beginInferenceGuard,
            endInferenceGuard = ::endInferenceGuard,
            postStatus = { _engineStatusMessage.value = it }
        )

        apiController = ApiServerController(
            application = application,
            scope = viewModelScope,
            settingsStore = settingsStore,
            strings = strings,
            currentSettings = { _settings.value },
            updateSettingsState = { _settings.value = it },
            postStatus = { _engineStatusMessage.value = it }
        )

        chatRunner = ChatTurnRunner(
            scope = viewModelScope,
            llmEngine = llmEngine,
            conversations = conversationController,
            models = modelController,
            mcp = mcpController,
            strings = strings,
            currentSettings = { _settings.value },
            beginInferenceGuard = ::beginInferenceGuard,
            endInferenceGuard = ::endInferenceGuard,
            postStatus = { _engineStatusMessage.value = it }
        )

        modelController.restoreInitialModel()
        conversationController.initialize()
        checkForAppUpdate()

        memoryGuard?.let { guard ->
            if (guard.isEnabled() && !_settings.value.ignoreMemoryLimits && guard.level() > 0) {
                _settings.value = guard.applyLevel(_settings.value)
            }
            val report = OffconAIApplication.consumeStartupReport()
            if (report != null && report.abnormalPreviousRun) {
                _engineStatusMessage.value = strings.get(R.string.vm_status_memory_reduced, guard.level())
            }
        }
        refreshMemoryGuardState()

        apiController.reattachIfRunning()
        mcpController.restoreSessionIfEnabled()
    }

    // ---- Reactive state flows delegated to domain controllers ----
    val conversations: StateFlow<List<Conversation>> get() = conversationController.conversations
    val currentConversationId: StateFlow<String?> get() = conversationController.currentConversationId
    val messages: StateFlow<List<ChatMessage>> get() = conversationController.messages
    val pendingAttachment: StateFlow<ChatAttachment?> get() = conversationController.pendingAttachment
    val documentStats: StateFlow<DocumentStats> get() = conversationController.documentStats
    val activeDocumentIds: StateFlow<Set<String>> get() = conversationController.activeDocumentIds
    val documentLibrary: StateFlow<List<DocumentSummary>> get() = conversationController.documentLibrary

    val models: StateFlow<List<LlmModel>> get() = modelController.models
    val activeModel: StateFlow<LlmModel?> get() = modelController.activeModel
    val activeToolTemplate: StateFlow<ToolCallTemplate> get() = modelController.activeToolTemplate
    val voiceAudioDecision: StateFlow<VoiceAudioDecision?> get() = modelController.voiceAudioDecision
    val isModelLoading: StateFlow<Boolean> get() = modelController.isModelLoading
    val modelLoadingProgress: StateFlow<Float> get() = modelController.modelLoadingProgress
    val modelLoadingStage: StateFlow<String> get() = modelController.modelLoadingStage
    val activeDownloadStatus: StateFlow<DownloadStatus?> get() = modelController.activeDownloadStatus

    val isGenerating: StateFlow<Boolean> get() = chatRunner.isGenerating
    val streamingMessage: StateFlow<ChatMessage?> get() = chatRunner.streamingMessage

    val mcpStatusText: StateFlow<String> get() = mcpController.mcpStatusText
    val mcpTools: StateFlow<List<McpTool>> get() = mcpController.mcpTools
    val mcpTokenSaved: StateFlow<Boolean> get() = mcpController.mcpTokenSaved
    val pendingToolApproval: StateFlow<McpToolCall?> get() = mcpController.pendingToolApproval

    internal var approvalTimeoutMs: Long
        get() = mcpController.approvalTimeoutMs
        set(value) {
            mcpController.approvalTimeoutMs = value
        }

    val nativeOmniActive: StateFlow<Boolean> get() = voiceController.nativeOmniActive
    val nativeOmniTranscript: StateFlow<String> get() = voiceController.nativeOmniTranscript

    val isApiModeEnabled: StateFlow<Boolean> get() = apiController.isApiModeEnabled
    val apiServerPort: StateFlow<Int> get() = apiController.apiServerPort
    val apiServerStatusMessage: StateFlow<String?> get() = apiController.apiServerStatusMessage
    val apiRequestCount: StateFlow<Int> get() = apiController.apiRequestCount
    val localIpAddress: StateFlow<String> get() = apiController.localIpAddress
    val apiServerApiKey: StateFlow<String> get() = apiController.apiServerApiKey

    // ---- Navigation & Updates ----
    fun navigateTo(screen: AppScreen) {
        _currentScreen.value = screen
    }

    fun isLlamaGpuOffloadAvailable(): Boolean = llmEngine.isLlamaGpuOffloadAvailable()

    fun dismissAppUpdate() {
        _appUpdate.value?.let { update ->
            updatePrefs.edit().putString(DISMISSED_UPDATE_KEY, update.latestVersion).apply()
        }
        _appUpdate.value = null
    }

    private fun checkForAppUpdate() {
        viewModelScope.launch {
            val update = runCatching { UpdateChecker().latestRelease() }.getOrNull() ?: return@launch
            if (update.latestVersion == updatePrefs.getString(DISMISSED_UPDATE_KEY, null)) return@launch
            _appUpdate.value = update
        }
    }

    // ---- Conversation & RAG actions ----
    fun createNewConversation() = conversationController.createNewConversation()
    fun selectConversation(conversationId: String) = conversationController.selectConversation(conversationId)
    fun deleteConversation(id: String) = conversationController.deleteConversation(id)
    fun clearAllHistory() = conversationController.clearAllHistory()
    fun renameConversation(id: String, newTitle: String) = conversationController.renameConversation(id, newTitle)
    fun setPendingAttachment(attachment: ChatAttachment?) = conversationController.setPendingAttachment(attachment)
    fun importDocument(uri: Uri, displayName: String) = conversationController.importDocument(uri, displayName)
    fun toggleDocumentSelection(documentId: String) = conversationController.toggleDocumentSelection(documentId)
    fun deleteIndexedDocument(documentId: String) = conversationController.deleteIndexedDocument(documentId)
    fun clearDocumentIndex() = conversationController.clearDocumentIndex()

    // ---- Model portfolio actions ----
    fun selectModel(model: LlmModel) = modelController.selectModel(model)
    fun switchRuntime(runtime: ModelRuntimeType) = modelController.switchRuntime(runtime)
    fun setModelRuntime(modelId: String, runtime: ModelRuntimeType) = modelController.setModelRuntime(modelId, runtime)
    fun setToolCallTemplate(template: ToolCallTemplate) = modelController.setToolCallTemplate(template)
    fun downloadModel(modelId: String, hfTokenOverride: String? = null) = modelController.downloadModel(modelId, hfTokenOverride)
    fun cancelDownload(modelId: String) = modelController.cancelDownload(modelId)
    fun deleteModel(modelId: String) = modelController.deleteModel(modelId)
    fun toggleModelVision(modelId: String) = modelController.toggleModelVision(modelId)
    fun toggleModelDrafter(modelId: String) = modelController.toggleModelDrafter(modelId)
    fun importLocalModel(uri: Uri, displayName: String) = modelController.importLocalModel(uri, displayName)

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
    ) = modelController.addCustomFdmBundle(
        name = name,
        runtime = runtime,
        mainUrl = mainUrl,
        customFileName = customFileName,
        visionUrl = visionUrl,
        mtpUrl = mtpUrl,
        templateUrl = templateUrl,
        supportsReasoning = supportsReasoning,
        autoStartDownload = autoStartDownload,
        hfToken = hfToken,
        hasEmbeddedVision = hasEmbeddedVision,
        hasEmbeddedDrafter = hasEmbeddedDrafter
    )

    fun addCustomModel(repoId: String, fileName: String, runtime: ModelRuntimeType, hasVision: Boolean) {
        val mainUrl = "https://huggingface.co/$repoId/resolve/main/$fileName"
        val visionUrl = if (hasVision) "https://huggingface.co/$repoId/resolve/main/mmproj-$fileName" else ""
        addCustomFdmBundle(
            name = repoId.substringAfterLast('/'),
            runtime = runtime,
            mainUrl = mainUrl,
            customFileName = fileName,
            visionUrl = visionUrl,
            autoStartDownload = true
        )
    }

    // ---- Settings actions ----
    fun updateHfToken(token: String) {
        val clean = token.trim()
        _settings.value = _settings.value.copy(hfToken = clean)
        settingsStore.saveHfToken(clean)
    }

    fun updateSettings(newSettings: GenerationSettings) {
        val previous = _settings.value
        _settings.value = newSettings
        settingsStore.saveSettings(newSettings, previous)

        if (previous.toolCallsEnabled != newSettings.toolCallsEnabled ||
            previous.isMcpEnabled != newSettings.isMcpEnabled
        ) {
            mcpController.refreshToolsContext()
        }

        val needsReload = previous.runtime != newSettings.runtime ||
            previous.contextWindow != newSettings.contextWindow ||
            previous.enableGpuAcceleration != newSettings.enableGpuAcceleration ||
            previous.gpuLayers != newSettings.gpuLayers ||
            previous.kvCacheQuantization != newSettings.kvCacheQuantization ||
            previous.ignoreMemoryLimits != newSettings.ignoreMemoryLimits
        if (!needsReload) return

        viewModelScope.launch {
            try {
                _engineStatusMessage.value = llmEngine.loadModel(modelController.activeModel.value, newSettings)
            } catch (t: Throwable) {
                Log.e("MainViewModel", "Model reload error with new settings", t)
                _engineStatusMessage.value = strings.get(R.string.vm_status_settings_apply_error, t.localizedMessage ?: t.message)
            }
        }
    }

    fun setLanguagePreference(pref: String) = updateSettings(_settings.value.copy(languagePreference = pref))

    fun setHfSource(source: String) {
        HfSource.useMirror = source == "mirror"
        updateSettings(_settings.value.copy(hfSource = source))
    }

    fun setReasoningEffort(effort: Float) {
        _settings.value = _settings.value.copy(reasoningEffort = effort)
    }

    // ---- MCP & Chat Inference actions ----
    fun connectMcp(url: String, bearerToken: String? = null) = mcpController.connectMcp(url, bearerToken)
    fun clearMcpToken() = mcpController.clearToken()
    fun resolveToolApproval(approved: Boolean) = mcpController.resolveApproval(approved)
    fun sendMessage(userPrompt: String) = chatRunner.sendMessage(userPrompt)

    fun stopGeneration() {
        mcpController.cancelPendingApproval()
        voiceController.stopAllVoiceActivity()
        chatRunner.cancelActiveTurn()
        try {
            llmEngine.stopGeneration()
        } catch (t: Throwable) {
            Log.w("MainViewModel", "Engine stop error", t)
        }
    }

    // ---- Voice & Omni actions ----
    fun downloadLocalStt() = voiceController.downloadLocalStt()
    fun deleteLocalStt() = voiceController.deleteLocalStt()
    fun downloadLocalTts() = voiceController.downloadLocalTts()
    fun deleteLocalTts() = voiceController.deleteLocalTts()
    fun selectTtsMode(mode: TtsEngineMode) = voiceController.selectTtsMode(mode)
    fun setTtsSpeaker(speakerId: Int) = voiceController.setTtsSpeaker(speakerId)
    fun setTtsSpeed(speed: Float) = voiceController.setTtsSpeed(speed)
    fun previewTts() = voiceController.previewTts()
    fun toggleNativeOmniTurn() = voiceController.toggleNativeOmniTurn()
    fun startNativeOmniTurn() = voiceController.startNativeOmniTurn()
    fun stopNativeOmniTurn() = voiceController.stopNativeOmniTurn()
    fun stopNativeOmniSession() = voiceController.stopNativeOmniSession()
    fun startInteractiveVoiceSession() = voiceController.startInteractiveVoiceSession()

    // ---- API Server actions ----
    fun setApiModeEnabled(enabled: Boolean) = apiController.setApiModeEnabled(enabled)
    fun startApiServer() = apiController.startApiServer()
    fun stopApiServer() = apiController.stopApiServer()
    fun restartApiServer() = apiController.restartApiServer()
    fun setApiServerExternalAccess(enabled: Boolean) = apiController.setExternalAccess(enabled)
    fun regenerateApiKey() = apiController.regenerateApiKey()
    fun refreshLocalIp() = apiController.refreshLocalIp()

    // ---- Memory Guard actions ----
    private fun currentMemoryGuardUiState(): MemoryGuardUiState = MemoryGuardUiState(
        enabled = memoryGuard?.isEnabled() ?: false,
        level = memoryGuard?.level() ?: 0,
        incidents = memoryGuard?.incidents() ?: emptyList()
    )

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

    private fun beginInferenceGuard() {
        val current = _settings.value
        memoryGuard?.markInferenceStart(
            modelName = modelController.activeModel.value?.name,
            runtime = current.runtime.name,
            contextWindow = current.contextWindow,
            gpuLayers = current.gpuLayers,
            gpuAcceleration = current.enableGpuAcceleration
        )
        if (!current.ignoreMemoryLimits) memoryWatchdog.start()
    }

    private fun endInferenceGuard() {
        memoryWatchdog.stop()
        memoryGuard?.markInferenceEnd()
    }

    private fun onMemoryCritical(snapshot: MemorySnapshot) {
        if (_settings.value.ignoreMemoryLimits) return
        Log.w(
            "MainViewModel",
            "Critical memory during inference: avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory}"
        )
        memoryGuard?.record(
            cause = MemoryGuardStore.Cause.LOW_MEMORY_STOP,
            detail = "watchdog stop: avail=${snapshot.availMb}MB total=${snapshot.totalMb}MB low=${snapshot.lowMemory}",
            modelName = modelController.activeModel.value?.name,
            raiseLevel = true
        )
        refreshMemoryGuardState()
        stopGeneration()
        _engineStatusMessage.value = strings.get(R.string.vm_status_memory_stop, memoryGuard?.level() ?: 0)
    }

    override fun onCleared() {
        super.onCleared()
        voiceController.release()
        modelController.cancelActiveLoad()
        apiController.detach()
    }

    private companion object {
        const val DISMISSED_UPDATE_KEY = "dismissed_update_version"
    }
}
