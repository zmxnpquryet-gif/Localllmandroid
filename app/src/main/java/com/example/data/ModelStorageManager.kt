package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.model.LlmModel
import com.example.model.ModelCatalog
import com.example.model.ModelRuntimeType
import com.example.engine.GgufMetadataDetector
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ModelStorageManager persists downloaded models, custom models,
 * and the active model selection across app restarts.
 * It also reconciles metadata with physical files present on disk.
 */
class ModelStorageManager(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("model_storage_prefs", Context.MODE_PRIVATE)

    private val modelsDir: File = File(context.filesDir, "models").apply {
        if (!exists()) mkdirs()
    }

    private val metadataFile: File = File(context.filesDir, "models_metadata.json")

    /**
     * Loads the combined list of models:
     * 1. Starts with default catalog models.
     * 2. Overlays saved metadata (including user-added custom models and status).
     * 3. Verifies physical file existence on disk to guarantee accurate download status.
     */
    fun loadModels(): List<LlmModel> {
        val defaultList = ModelCatalog.defaultModels.toMutableList()
        val savedModelsMap = mutableMapOf<String, LlmModel>()
        val customModels = mutableListOf<LlmModel>()

        // 1. Read metadata file if present
        if (metadataFile.exists()) {
            try {
                val jsonStr = metadataFile.readText()
                val array = JSONArray(jsonStr)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val model = parseModelJson(obj)
                    if (model.id.startsWith("custom-")) {
                        customModels.add(model)
                    } else {
                        savedModelsMap[model.id] = model
                    }
                }
            } catch (e: Exception) {
                Log.e("ModelStorageManager", "Error reading models metadata", e)
            }
        }

        // 2. Merge defaults with saved metadata
        val mergedList = mutableListOf<LlmModel>()
        // Custom models first
        mergedList.addAll(customModels)

        for (defaultModel in defaultList) {
            val saved = savedModelsMap[defaultModel.id]
            if (saved != null) {
                mergedList.add(
                    defaultModel.copy(
                        isDownloaded = saved.isDownloaded,
                        isVisionDownloaded = saved.isVisionDownloaded,
                        isMtpDownloaded = saved.isMtpDownloaded,
                        isTemplateDownloaded = saved.isTemplateDownloaded,
                        localFilePath = saved.localFilePath,
                        localMmprojPath = saved.localMmprojPath,
                        localMtpDrafterPath = saved.localMtpDrafterPath,
                        localTemplatePath = saved.localTemplatePath,
                        downloadStatus = saved.downloadStatus,
                        supportsReasoning = saved.supportsReasoning,
                        hasMmproj = saved.hasMmproj,
                        supportsMtp = saved.supportsMtp,
                        templateFileName = saved.templateFileName ?: defaultModel.templateFileName,
                        templateFileUrl = saved.templateFileUrl.ifBlank { defaultModel.templateFileUrl }
                    )
                )
            } else {
                mergedList.add(defaultModel)
            }
        }

        // 3. Physical file reconciliation
        return mergedList.map { model ->
            reconcileWithDisk(model)
        }
    }

    /**
     * Reconciles a model's state with actual files on disk.
     */
    private fun reconcileWithDisk(model: LlmModel): LlmModel {
        val mainFile = if (!model.localFilePath.isNullOrBlank()) {
            File(model.localFilePath)
        } else {
            File(modelsDir, model.fileName)
        }

        val mainExists = mainFile.exists() && mainFile.length() > 0

        // Auto-detect embedded features from GGUF metadata or filename
        val detected = if (mainExists && model.runtimeType == ModelRuntimeType.LLAMA_CPP) {
            GgufMetadataDetector.detect(mainFile)
        } else null

        val effectiveHasMmproj = model.hasMmproj || (detected?.hasVisionTower == true)
        val effectiveSupportsMtp = model.supportsMtp || (detected?.hasDrafter == true)

        // Vision tower file (external mmproj OR embedded vision)
        val visionFile = if (!model.localMmprojPath.isNullOrBlank()) {
            File(model.localMmprojPath)
        } else if (!model.mmprojFileName.isNullOrBlank()) {
            File(modelsDir, model.mmprojFileName)
        } else if (effectiveHasMmproj) {
            File(modelsDir, "mmproj-${model.fileName}")
        } else null
        val externalVisionExists = visionFile?.let { it.exists() && it.length() > 0 } ?: false
        val isVisionReady = externalVisionExists || (effectiveHasMmproj && mainExists)

        // MTP Drafter file (external drafter OR embedded MTP)
        val mtpFile = if (!model.localMtpDrafterPath.isNullOrBlank()) {
            File(model.localMtpDrafterPath)
        } else if (!model.mtpDrafterFileName.isNullOrBlank()) {
            File(modelsDir, model.mtpDrafterFileName)
        } else if (effectiveSupportsMtp) {
            File(modelsDir, "draft-${model.fileName}")
        } else null
        val externalMtpExists = mtpFile?.let { it.exists() && it.length() > 0 } ?: false
        val isMtpReady = externalMtpExists || (effectiveSupportsMtp && mainExists)

        // Template file (supports modern Jinja chat_template and JSON)
        val templateFile = if (!model.localTemplatePath.isNullOrBlank()) {
            File(model.localTemplatePath)
        } else if (!model.templateFileName.isNullOrBlank()) {
            File(modelsDir, model.templateFileName)
        } else if (model.runtimeType == ModelRuntimeType.LITE_RT) {
            val base = model.fileName.substringBeforeLast('.')
            val jinja = File(modelsDir, "$base-template.jinja")
            val chatJinja = File(modelsDir, "chat_template.jinja")
            val json = File(modelsDir, "$base-template.json")
            when {
                jinja.exists() -> jinja
                chatJinja.exists() -> chatJinja
                json.exists() -> json
                else -> jinja
            }
        } else null
        val templateExists = templateFile?.let { it.exists() && it.length() > 0 } ?: false

        return model.copy(
            hasMmproj = effectiveHasMmproj,
            supportsMtp = effectiveSupportsMtp,
            isDownloaded = mainExists,
            downloadStatus = if (mainExists) "COMPLETED" else if (model.isDownloading) "DOWNLOADING" else "IDLE",
            localFilePath = if (mainExists) mainFile.absolutePath else null,
            isVisionDownloaded = isVisionReady,
            localMmprojPath = if (externalVisionExists) visionFile?.absolutePath else null,
            isMtpDownloaded = isMtpReady,
            localMtpDrafterPath = if (externalMtpExists) mtpFile?.absolutePath else null,
            isTemplateDownloaded = templateExists,
            localTemplatePath = if (templateExists) templateFile?.absolutePath else null
        )
    }

    /**
     * Saves all models metadata to disk.
     */
    fun saveModels(models: List<LlmModel>) {
        try {
            val array = JSONArray()
            for (model in models) {
                array.put(modelToJson(model))
            }
            metadataFile.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.e("ModelStorageManager", "Failed to save models metadata", e)
        }
    }

    /**
     * Deletes physical model files from disk and removes from storage.
     */
    fun deleteModelFiles(model: LlmModel) {
        try {
            model.localFilePath?.let { File(it).delete() }
            model.localMmprojPath?.let { File(it).delete() }
            model.localMtpDrafterPath?.let { File(it).delete() }
            model.localTemplatePath?.let { File(it).delete() }

            // Also check standard filenames
            File(modelsDir, model.fileName).delete()
            model.mmprojFileName?.let { File(modelsDir, it).delete() }
            model.mtpDrafterFileName?.let { File(modelsDir, it).delete() }
            model.templateFileName?.let { File(modelsDir, it).delete() }
        } catch (e: Exception) {
            Log.e("ModelStorageManager", "Error deleting model files for ${model.name}", e)
        }
    }

    fun saveActiveModelId(modelId: String?) {
        prefs.edit().putString("active_model_id", modelId).apply()
    }

    fun getActiveModelId(): String? {
        return prefs.getString("active_model_id", null)
    }

    private fun modelToJson(m: LlmModel): JSONObject {
        val obj = JSONObject()
        obj.put("id", m.id)
        obj.put("name", m.name)
        obj.put("repoId", m.repoId)
        obj.put("fileName", m.fileName)
        obj.put("runtimeType", m.runtimeType.name)
        obj.put("sizeBytes", m.sizeBytes)
        obj.put("supportsMtp", m.supportsMtp)
        obj.put("supportsReasoning", m.supportsReasoning)
        obj.put("hasMmproj", m.hasMmproj)
        obj.put("mmprojFileName", m.mmprojFileName ?: "")
        obj.put("mtpDrafterFileName", m.mtpDrafterFileName ?: "")
        obj.put("templateFileName", m.templateFileName ?: "")
        obj.put("templateFileUrl", m.templateFileUrl)
        obj.put("mainModelUrl", m.mainModelUrl)
        obj.put("visionTowerUrl", m.visionTowerUrl)
        obj.put("mtpDrafterUrl", m.mtpDrafterUrl)
        obj.put("isDownloaded", m.isDownloaded)
        obj.put("isVisionDownloaded", m.isVisionDownloaded)
        obj.put("isMtpDownloaded", m.isMtpDownloaded)
        obj.put("isTemplateDownloaded", m.isTemplateDownloaded)
        obj.put("downloadStatus", m.downloadStatus)
        obj.put("description", m.description)
        obj.put("quantization", m.quantization)
        obj.put("localFilePath", m.localFilePath ?: "")
        obj.put("localMmprojPath", m.localMmprojPath ?: "")
        obj.put("localMtpDrafterPath", m.localMtpDrafterPath ?: "")
        obj.put("localTemplatePath", m.localTemplatePath ?: "")
        obj.put("hfToken", m.hfToken ?: "")
        return obj
    }

    private fun parseModelJson(obj: JSONObject): LlmModel {
        val runtimeTypeStr = obj.optString("runtimeType", "LLAMA_CPP")
        val runtimeType = try {
            ModelRuntimeType.valueOf(runtimeTypeStr)
        } catch (_: Exception) {
            ModelRuntimeType.LLAMA_CPP
        }

        return LlmModel(
            id = obj.getString("id"),
            name = obj.optString("name", "Unnamed Model"),
            repoId = obj.optString("repoId", ""),
            fileName = obj.optString("fileName", "model.gguf"),
            runtimeType = runtimeType,
            sizeBytes = obj.optLong("sizeBytes", 2_000_000_000L),
            supportsMtp = obj.optBoolean("supportsMtp", false),
            supportsReasoning = obj.optBoolean("supportsReasoning", false),
            hasMmproj = obj.optBoolean("hasMmproj", false),
            mmprojFileName = obj.optString("mmprojFileName", "").ifBlank { null },
            mtpDrafterFileName = obj.optString("mtpDrafterFileName", "").ifBlank { null },
            templateFileName = obj.optString("templateFileName", "").ifBlank { null },
            templateFileUrl = obj.optString("templateFileUrl", ""),
            mainModelUrl = obj.optString("mainModelUrl", ""),
            visionTowerUrl = obj.optString("visionTowerUrl", ""),
            mtpDrafterUrl = obj.optString("mtpDrafterUrl", ""),
            isDownloaded = obj.optBoolean("isDownloaded", false),
            isVisionDownloaded = obj.optBoolean("isVisionDownloaded", false),
            isMtpDownloaded = obj.optBoolean("isMtpDownloaded", false),
            isTemplateDownloaded = obj.optBoolean("isTemplateDownloaded", false),
            downloadStatus = obj.optString("downloadStatus", "IDLE"),
            description = obj.optString("description", ""),
            quantization = obj.optString("quantization", "Q4_K_M"),
            localFilePath = obj.optString("localFilePath", "").ifBlank { null },
            localMmprojPath = obj.optString("localMmprojPath", "").ifBlank { null },
            localMtpDrafterPath = obj.optString("localMtpDrafterPath", "").ifBlank { null },
            localTemplatePath = obj.optString("localTemplatePath", "").ifBlank { null },
            hfToken = obj.optString("hfToken", "").ifBlank { null }
        )
    }
}
