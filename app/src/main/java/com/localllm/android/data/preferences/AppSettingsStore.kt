package com.localllm.android.data.preferences

import android.content.Context
import android.content.SharedPreferences
import com.localllm.android.data.crypto.SecretPrefs
import com.localllm.android.engine.LlmEngine
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.KvCacheQuantization
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.model.ToolCallTemplate
import com.localllm.android.server.OllamaApiServer

/**
 * Single source of truth for persisted [GenerationSettings], encrypted credentials
 * ([SecretPrefs]), and per-model tool-dialect overrides.
 * Shared by both the UI process ([com.localllm.android.ui.MainViewModel]) and the
 * background API server ([com.localllm.android.server.ApiServerService]).
 */
class AppSettingsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    val secrets: SecretPrefs = SecretPrefs(prefs)

    fun migrateLegacyRuntimeIfNeeded(): Boolean {
        if (prefs.getString(KEY_RUNTIME, null) == "SD_ENGINE") {
            prefs.edit().putString(KEY_RUNTIME, ModelRuntimeType.LLAMA_CPP.name).apply()
            return true
        }
        return false
    }

    fun loadSettings(): GenerationSettings = GenerationSettings(
        hfToken = secrets.get(SecretPrefs.HF_TOKEN) ?: "",
        runtime = parseStoredRuntime(prefs.getString(KEY_RUNTIME, null)),
        themeColorName = prefs.getString("theme_color", "offcon") ?: "offcon",
        darkModePreference = prefs.getString("dark_mode", "system") ?: "system",
        temperature = prefs.getFloat("temperature", 0.7f),
        topP = prefs.getFloat("top_p", 0.9f),
        topK = prefs.getInt("top_k", 40),
        contextWindow = prefs.getInt("context_window", 4096),
        repetitionPenalty = prefs.getFloat("repetition_penalty", 1.1f),
        enableMtp = prefs.getBoolean("enable_mtp", true),
        enableIndexingAcceleration = prefs.getBoolean("enable_indexing", true),
        enableGpuAcceleration = prefs.getBoolean("enable_gpu", true),
        gpuLayers = prefs.getInt("gpu_layers", 99),
        kvCacheQuantization = KvCacheQuantization.fromId(prefs.getString("kv_cache_quantization", null)),
        ignoreMemoryLimits = prefs.getBoolean("ignore_memory_limits", false),
        showPerformanceMetrics = prefs.getBoolean("show_metrics", true),
        apiServerBindAddress = prefs.getString("api_server_bind_address", "127.0.0.1") ?: "127.0.0.1",
        apiServerRequireAuth = prefs.getBoolean("api_server_require_auth", true),
        languagePreference = prefs.getString("language_preference", "system") ?: "system",
        hfSource = prefs.getString("hf_source", "official") ?: "official",
        toolCallsEnabled = prefs.getBoolean("tool_calls_enabled", true),
        builtInSystemPromptEnabled = prefs.getBoolean("built_in_system_prompt_enabled", true),
        mcpServerUrl = prefs.getString("mcp_server_url", "") ?: "",
        isMcpEnabled = prefs.getBoolean("is_mcp_enabled", false)
    )

    fun saveSettings(newSettings: GenerationSettings, previous: GenerationSettings) {
        prefs.edit().apply {
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
            putString("kv_cache_quantization", newSettings.kvCacheQuantization.id)
            putBoolean("ignore_memory_limits", newSettings.ignoreMemoryLimits)
            putString(KEY_RUNTIME, newSettings.runtime.name)
            putBoolean("show_metrics", newSettings.showPerformanceMetrics)
            putBoolean("tool_calls_enabled", newSettings.toolCallsEnabled)
            putBoolean("built_in_system_prompt_enabled", newSettings.builtInSystemPromptEnabled)
            putString("api_server_bind_address", newSettings.apiServerBindAddress)
            putBoolean("api_server_require_auth", newSettings.apiServerRequireAuth)
            putString("language_preference", newSettings.languagePreference)
            putString("hf_source", newSettings.hfSource)
            putString("mcp_server_url", newSettings.mcpServerUrl)
            putBoolean("is_mcp_enabled", newSettings.isMcpEnabled)
            if (!previous.enableGpuAcceleration && newSettings.enableGpuAcceleration) {
                putBoolean(LlmEngine.KEY_LITERT_GPU_UNAVAILABLE, false)
            }
            apply()
        }
        if (newSettings.hfToken != previous.hfToken) {
            secrets.put(SecretPrefs.HF_TOKEN, newSettings.hfToken)
        }
    }

    fun saveRuntime(runtime: ModelRuntimeType) {
        prefs.edit().putString(KEY_RUNTIME, runtime.name).apply()
    }

    fun saveHfToken(token: String) {
        secrets.put(SecretPrefs.HF_TOKEN, token)
    }

    fun saveApiBindAddress(bindAddress: String) {
        prefs.edit().putString("api_server_bind_address", bindAddress).apply()
    }

    fun saveMcpConnectionState(url: String, enabled: Boolean, lastHost: String? = null) {
        prefs.edit().apply {
            putString("mcp_server_url", url)
            putBoolean("is_mcp_enabled", enabled)
            if (lastHost != null) putString("mcp_last_host", lastHost)
            apply()
        }
    }

    fun getLastMcpHost(): String? = prefs.getString("mcp_last_host", null)

    fun getToolTemplateOverride(modelId: String): String? =
        prefs.getString(toolTemplatePrefKey(modelId), null)

    fun setToolTemplateOverride(modelId: String, template: ToolCallTemplate) {
        prefs.edit().apply {
            if (template == ToolCallTemplate.AUTO) remove(toolTemplatePrefKey(modelId))
            else putString(toolTemplatePrefKey(modelId), template.id)
            apply()
        }
    }

    fun loadOrCreateApiKey(): String = Companion.loadOrCreateApiKey(prefs)

    fun saveApiKey(newKey: String) {
        secrets.put(SecretPrefs.API_SERVER_KEY, newKey)
    }

    companion object {
        const val PREFS_NAME = "app_settings_prefs"
        private const val KEY_RUNTIME = "runtime"

        private fun toolTemplatePrefKey(modelId: String) = "tool_call_template_$modelId"

        fun parseStoredRuntime(stored: String?): ModelRuntimeType = try {
            if (stored.isNullOrBlank()) ModelRuntimeType.LLAMA_CPP else ModelRuntimeType.valueOf(stored)
        } catch (_: Throwable) {
            ModelRuntimeType.LLAMA_CPP
        }

        fun loadOrCreateApiKey(prefs: SharedPreferences): String {
            val secrets = SecretPrefs(prefs)
            secrets.get(SecretPrefs.API_SERVER_KEY)?.let { return it }
            return OllamaApiServer.generateSecureApiKey().also {
                secrets.put(SecretPrefs.API_SERVER_KEY, it)
            }
        }

        fun readServingSettings(prefs: SharedPreferences): GenerationSettings = GenerationSettings(
            runtime = parseStoredRuntime(prefs.getString(KEY_RUNTIME, null)),
            contextWindow = prefs.getInt("context_window", 4096),
            temperature = prefs.getFloat("temperature", 0.7f),
            topP = prefs.getFloat("top_p", 0.9f),
            topK = prefs.getInt("top_k", 40),
            apiServerBindAddress = prefs.getString("api_server_bind_address", "127.0.0.1") ?: "127.0.0.1",
            apiServerRequireAuth = prefs.getBoolean("api_server_require_auth", true),
            kvCacheQuantization = KvCacheQuantization.fromId(prefs.getString("kv_cache_quantization", null)),
            ignoreMemoryLimits = prefs.getBoolean("ignore_memory_limits", false),
            toolCallsEnabled = prefs.getBoolean("tool_calls_enabled", true),
            builtInSystemPromptEnabled = prefs.getBoolean("built_in_system_prompt_enabled", true)
        )
    }
}
