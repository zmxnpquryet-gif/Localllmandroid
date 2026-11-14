package com.localllm.android.ui.controllers

import android.app.Application
import android.net.Uri
import android.util.Log
import com.localllm.android.R
import com.localllm.android.data.ModelStorageManager
import com.localllm.android.data.preferences.AppSettingsStore
import com.localllm.android.engine.DownloadStatus
import com.localllm.android.engine.GgufMetadataDetector
import com.localllm.android.engine.LlmEngine
import com.localllm.android.engine.ModelDownloader
import com.localllm.android.engine.gguf.GgufReader
import com.localllm.android.i18n.LocalizedStringProvider
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.model.ToolCallTemplate
import com.localllm.android.model.ToolCallTemplates
import com.localllm.android.service.ModelDownloadService
import com.localllm.android.voice.AudioInputRouter
import com.localllm.android.voice.VoiceAudioDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

/**
 * Owns the local model catalog, download lifecycle, custom bundle registration,
 * SAF file imports, active model activation, and tool/audio capability routing.
 */
internal class ModelPortfolioController(
    private val application: Application,
    private val scope: CoroutineScope,
    private val modelStorageManager: ModelStorageManager,
    private val settingsStore: AppSettingsStore,
    private val llmEngine: LlmEngine,
    private val memoryGuard: MemoryGuardStore?,
    private val strings: LocalizedStringProvider,
    private val currentSettings: () -> GenerationSettings,
    private val updateSettingsState: (GenerationSettings) -> Unit,
    private val onRefreshMcpToolsContext: () -> Unit,
    private val onStopGeneration: () -> Unit,
    private val onUpdateHfToken: (String) -> Unit,
    private val postStatus: (String) -> Unit
) {
    private val _models = MutableStateFlow(modelStorageManager.loadModels())
    val models: StateFlow<List<LlmModel>> = _models.asStateFlow()

    private val _activeModel = MutableStateFlow<LlmModel?>(null)
    val activeModel: StateFlow<LlmModel?> = _activeModel.asStateFlow()

    private val _activeToolTemplate = MutableStateFlow(ToolCallTemplate.NONE)
    val activeToolTemplate: StateFlow<ToolCallTemplate> = _activeToolTemplate.asStateFlow()

    private val _voiceAudioDecision = MutableStateFlow<VoiceAudioDecision?>(null)
    val voiceAudioDecision: StateFlow<VoiceAudioDecision?> = _voiceAudioDecision.asStateFlow()

    private val _isModelLoading = MutableStateFlow(false)
    val isModelLoading: StateFlow<Boolean> = _isModelLoading.asStateFlow()

    private val _modelLoadingProgress = MutableStateFlow(0f)
    val modelLoadingProgress: StateFlow<Float> = _modelLoadingProgress.asStateFlow()

    private val _modelLoadingStage = MutableStateFlow("")
    val modelLoadingStage: StateFlow<String> = _modelLoadingStage.asStateFlow()

    private val _activeDownloadStatus = MutableStateFlow<DownloadStatus?>(null)
    val activeDownloadStatus: StateFlow<DownloadStatus?> = _activeDownloadStatus.asStateFlow()

    private var modelLoadingJob: Job? = null
    private var inProcessDownloadJob: Job? = null

    fun restoreInitialModel() {
        val savedActiveId = modelStorageManager.getActiveModelId()
        val restoredModel = _models.value.firstOrNull { it.id == savedActiveId && it.isDownloaded }
            ?: _models.value.firstOrNull { it.isDownloaded }

        if (restoredModel != null) {
            activateModelMetadata(restoredModel)
            val base = currentSettings()
            updateSettingsState(
                base.copy(
                    runtime = restoredModel.runtimeType,
                    enableMtp = if (restoredModel.supportsMtp) true else base.enableMtp
                )
            )
            postStatus(strings.get(R.string.vm_status_model_restored, restoredModel.name))
        } else {
            activateModelMetadata(null)
            postStatus(strings.get(R.string.vm_status_no_default_model))
        }

        scope.launch {
            ModelDownloadService.currentDownloadStatus.collect { status ->
                _activeDownloadStatus.value = status
                if (status != null) applyDownloadProgress(status)
            }
        }
    }

    fun activateModelMetadata(model: LlmModel?) {
        _activeModel.value = model
        _activeToolTemplate.value = model?.let { active ->
            ToolCallTemplates.resolve(active, settingsStore.getToolTemplateOverride(active.id))
        } ?: ToolCallTemplate.NONE
        _voiceAudioDecision.value = AudioInputRouter.route(model)
        onRefreshMcpToolsContext()
    }

    fun setToolCallTemplate(template: ToolCallTemplate) {
        val model = _activeModel.value ?: return
        settingsStore.setToolTemplateOverride(model.id, template)
        activateModelMetadata(model)
    }

    fun selectModel(model: LlmModel) {
        if (!model.isDownloaded) {
            postStatus(strings.get(R.string.vm_status_model_not_downloaded, model.name))
            return
        }

        onStopGeneration()
        modelLoadingJob?.cancel()
        modelLoadingJob = scope.launch {
            _isModelLoading.value = true
            _modelLoadingProgress.value = 0.05f
            _modelLoadingStage.value = strings.get(R.string.vm_stage_preparing_load)

            val runtimeToApply = model.runtimeType
            val base = currentSettings()
            val updatedSettings = base.copy(
                runtime = runtimeToApply,
                enableMtp = if (model.supportsMtp) true else base.enableMtp
            )
            updateSettingsState(updatedSettings)
            settingsStore.saveRuntime(runtimeToApply)

            memoryGuard?.markInferenceStart(
                modelName = model.name,
                runtime = updatedSettings.runtime.name,
                contextWindow = updatedSettings.contextWindow,
                gpuLayers = updatedSettings.gpuLayers,
                gpuAcceleration = updatedSettings.enableGpuAcceleration
            )
            try {
                val status = llmEngine.loadModel(model, updatedSettings) { stage, progress ->
                    _modelLoadingStage.value = stage
                    _modelLoadingProgress.value = progress
                }

                if (llmEngine.isModelReady()) {
                    activateModelMetadata(model)
                    _modelLoadingProgress.value = 1.0f
                    _modelLoadingStage.value = strings.get(R.string.vm_stage_load_complete)
                    postStatus(status)
                    modelStorageManager.saveActiveModelId(model.id)
                    delay(400)
                } else {
                    activateModelMetadata(null)
                    _modelLoadingProgress.value = 0f
                    _modelLoadingStage.value = strings.get(R.string.vm_stage_load_failed)
                    postStatus(status)
                    delay(2000)
                }
            } catch (t: Throwable) {
                Log.e("ModelPortfolioController", "Model loading error", t)
                activateModelMetadata(null)
                _modelLoadingProgress.value = 0f
                _modelLoadingStage.value = strings.get(R.string.vm_stage_load_failed)
                postStatus(strings.get(R.string.vm_status_model_load_exception, t.localizedMessage ?: t.message))
                delay(2000)
            } finally {
                memoryGuard?.markInferenceEnd()
                _isModelLoading.value = false
                _modelLoadingProgress.value = 0f
                _modelLoadingStage.value = ""
            }
        }
    }

    /** Loads the active model on demand when a cold-started session sends a message. */
    suspend fun ensureLoadedOnDemand(model: LlmModel): String {
        postStatus(strings.get(R.string.vm_status_loading_memory, model.name))
        _isModelLoading.value = true
        _modelLoadingProgress.value = 0.15f
        _modelLoadingStage.value = strings.get(R.string.vm_stage_loading_weights)
        val loadResult = try {
            llmEngine.loadModel(model, currentSettings()) { stage, progress ->
                _modelLoadingStage.value = stage
                _modelLoadingProgress.value = progress
            }
        } catch (t: Throwable) {
            Log.e("ModelPortfolioController", "Auto-load failed", t)
            strings.get(R.string.vm_status_model_load_exception, t.localizedMessage ?: t.message)
        } finally {
            _isModelLoading.value = false
            _modelLoadingProgress.value = 0f
            _modelLoadingStage.value = ""
        }
        postStatus(loadResult)
        return loadResult
    }

    fun switchRuntime(runtime: ModelRuntimeType) {
        updateSettingsState(currentSettings().copy(runtime = runtime))
        settingsStore.saveRuntime(runtime)
        val downloadedMatch = _models.value.firstOrNull { it.runtimeType == runtime && it.isDownloaded }
        if (downloadedMatch != null) {
            selectModel(downloadedMatch)
        } else {
            activateModelMetadata(null)
            scope.launch {
                runCatching { llmEngine.loadModel(null, currentSettings()) }
            }
            postStatus(
                if (runtime == ModelRuntimeType.LLAMA_CPP) strings.get(R.string.vm_status_no_llamacpp_model)
                else strings.get(R.string.vm_status_no_litert_model)
            )
        }
    }

    fun setModelRuntime(modelId: String, runtime: ModelRuntimeType) {
        val updated = _models.value.map { model ->
            if (model.id == modelId) model.copy(runtimeType = runtime, runtimeTypeOverrideByUser = true)
            else model
        }
        _models.value = updated
        modelStorageManager.saveModels(updated)
        val target = updated.firstOrNull { it.id == modelId } ?: return
        if (_activeModel.value?.id == modelId) {
            updateSettingsState(currentSettings().copy(runtime = runtime))
            settingsStore.saveRuntime(runtime)
            selectModel(target)
        } else {
            postStatus(strings.get(R.string.vm_status_runtime_override, target.name, runtime.label))
        }
    }

    fun downloadModel(modelId: String, hfTokenOverride: String? = null) {
        val targetModel = _models.value.find { it.id == modelId } ?: return
        if (targetModel.isDownloading) return

        val effectiveToken = hfTokenOverride?.ifBlank { null }
            ?: targetModel.hfToken?.ifBlank { null }
            ?: currentSettings().hfToken.ifBlank { null }

        _models.value = _models.value.map {
            if (it.id == modelId) {
                it.copy(
                    isDownloading = true,
                    downloadStatus = "DOWNLOADING",
                    downloadProgress = 0f,
                    hfToken = effectiveToken ?: it.hfToken
                )
            } else it
        }

        val updatedModel = _models.value.find { it.id == modelId } ?: targetModel
        try {
            ModelDownloadService.startDownload(application, updatedModel, effectiveToken)
        } catch (e: Throwable) {
            Log.e("ModelPortfolioController", "Service start failed, using in-process fallback: ${e.message}", e)
            startInProcessDownloadFallback(updatedModel, effectiveToken)
        }
    }

    fun cancelDownload(modelId: String) {
        inProcessDownloadJob?.cancel()
        inProcessDownloadJob = null
        runCatching { ModelDownloadService.cancelDownload(application, modelId) }
        _activeDownloadStatus.value = null
        _models.value = _models.value.map { m ->
            if (m.id == modelId) {
                m.copy(isDownloading = false, downloadStatus = "IDLE", downloadProgress = 0f)
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
                activateModelMetadata(null)
                modelStorageManager.saveActiveModelId(null)
                scope.launch { llmEngine.loadModel(null, currentSettings()) }
                postStatus(strings.get(R.string.vm_status_no_downloaded_model))
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
        if (_activeModel.value?.id == modelId) {
            val updated = _models.value.first { it.id == modelId }
            activateModelMetadata(updated)
            postStatus(
                strings.get(
                    R.string.vm_status_vision_toggle,
                    updated.name,
                    if (updated.hasMmproj) strings.get(R.string.vm_state_enabled_linked)
                    else strings.get(R.string.vm_state_disabled)
                )
            )
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
        if (_activeModel.value?.id == modelId) {
            val updated = _models.value.first { it.id == modelId }
            activateModelMetadata(updated)
            postStatus(
                strings.get(
                    R.string.vm_status_drafter_toggle,
                    updated.name,
                    if (updated.supportsMtp) strings.get(R.string.vm_state_enabled_accel)
                    else strings.get(R.string.vm_state_disabled)
                )
            )
        }
    }

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

        if (cleanToken.isNotBlank() && currentSettings().hfToken.isBlank()) {
            onUpdateHfToken(cleanToken)
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
        val templateFileName = when {
            cleanTemplate.isNotBlank() -> ModelStorageManager.sanitizeFileName(
                cleanTemplate.substringAfterLast('/', "prompt_template.json"),
                fallback = "prompt_template.json"
            )
            runtime == ModelRuntimeType.LITE_RT -> "${finalFileName.substringBeforeLast('.')}-template.json"
            else -> null
        }

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

        val descriptionParts = buildList {
            add(strings.get(R.string.vm_desc_filename, finalFileName))
            if (runtime == ModelRuntimeType.LITE_RT) add(strings.get(R.string.vm_desc_litert_template))
            if (effectiveReasoning) add(strings.get(R.string.vm_desc_thinking))
            if (effectiveVision) {
                add(
                    if (cleanVision.isNotBlank()) strings.get(R.string.vm_desc_vision_included)
                    else strings.get(R.string.vm_desc_vision_embedded)
                )
            }
            if (effectiveMtp) {
                add(
                    if (cleanMtp.isNotBlank()) strings.get(R.string.vm_desc_mtp_included)
                    else strings.get(R.string.vm_desc_drafter_embedded)
                )
            }
            if (cleanToken.isNotBlank()) add(strings.get(R.string.vm_desc_token_applied))
        }

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
            description = strings.get(R.string.vm_desc_custom_model, descriptionParts.joinToString(" • ")),
            hfToken = cleanToken.ifBlank { null }
        )

        _models.value = listOf(newModel) + _models.value
        modelStorageManager.saveModels(_models.value)

        if (autoStartDownload) {
            downloadModel(newModel.id, cleanToken.ifBlank { null })
        }
    }

    fun importLocalModel(uri: Uri, displayName: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val modelsDir = File(application.filesDir, "models").apply { if (!exists()) mkdirs() }
                val targetFileName = ModelStorageManager.sanitizeFileName(
                    displayName.ifBlank { "imported_model.gguf" },
                    fallback = "imported_model.gguf"
                )
                val destFile = File(modelsDir, targetFileName)

                postStatus(strings.get(R.string.vm_status_copying_gguf))
                application.contentResolver.openInputStream(uri)?.use { input ->
                    destFile.outputStream().use { output -> input.copyTo(output) }
                }

                val detected = GgufMetadataDetector.detect(destFile)
                val hasVision = detected.hasVision
                val hasDrafter = detected.hasDrafter
                val isLiteRt = detected.detectedRuntime == ModelRuntimeType.LITE_RT

                val engineNote = runCatching {
                    GgufReader.open(destFile).use { reader ->
                        strings.get(R.string.vm_desc_engine_note, reader.tensors.size)
                    }
                }.getOrNull()

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
                            append(strings.get(R.string.vm_desc_imported_litert))
                            if (hasVision) append(strings.get(R.string.vm_desc_imported_litert_vision))
                            if (hasDrafter) append(strings.get(R.string.vm_desc_imported_litert_drafter))
                        } else {
                            append(strings.get(R.string.vm_desc_imported_gguf))
                            if (hasVision) append(strings.get(R.string.vm_desc_imported_gguf_vision))
                            if (hasDrafter) append(strings.get(R.string.vm_desc_imported_gguf_drafter))
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
                postStatus(strings.get(R.string.vm_status_import_complete, cleanName))
                selectModel(customModel)
            } catch (e: Exception) {
                postStatus(strings.get(R.string.vm_status_import_failed, e.localizedMessage))
            }
        }
    }

    fun cancelActiveLoad() {
        modelLoadingJob?.cancel()
    }

    private fun startInProcessDownloadFallback(model: LlmModel, hfToken: String?) {
        inProcessDownloadJob?.cancel()
        inProcessDownloadJob = scope.launch {
            try {
                val modelsDir = File(application.filesDir, "models").apply { if (!exists()) mkdirs() }
                ModelDownloader().downloadUnifiedBundle(model, modelsDir, hfToken).collect { status ->
                    _activeDownloadStatus.value = status
                    applyDownloadProgress(status)
                }
            } catch (e: Throwable) {
                Log.e("ModelPortfolioController", "In-process fallback download error", e)
                _models.value = _models.value.map { m ->
                    if (m.id == model.id) m.copy(isDownloading = false, downloadStatus = "FAILED") else m
                }
            }
        }
    }

    private fun applyDownloadProgress(status: DownloadStatus) {
        val modelId = status.modelId
        _models.value = _models.value.map { m ->
            if (m.id == modelId) {
                m.copy(
                    isDownloading = !status.isCompleted && status.errorMessage == null,
                    downloadStatus = when {
                        status.isCompleted -> "COMPLETED"
                        status.errorMessage != null -> "FAILED"
                        else -> "DOWNLOADING"
                    },
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
            _models.value.find { it.id == modelId }?.let(::selectModel)
        }
    }
}
