package com.localllm.android.ui.controllers

import com.localllm.android.R
import com.localllm.android.data.crypto.SecretPrefs
import com.localllm.android.data.preferences.AppSettingsStore
import com.localllm.android.engine.McpClient
import com.localllm.android.engine.McpTool
import com.localllm.android.engine.McpToolCall
import com.localllm.android.engine.McpToolProtocol
import com.localllm.android.i18n.LocalizedStringProvider
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.ToolCallTemplate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Owns the Model Context Protocol (MCP) connection, host-scoped bearer token safety,
 * prompt tool-schema rendering, and explicit per-call user approval gating.
 */
internal class McpSessionController(
    private val scope: CoroutineScope,
    private val settingsStore: AppSettingsStore,
    private val strings: LocalizedStringProvider,
    private val currentSettings: () -> GenerationSettings,
    private val currentToolTemplate: () -> ToolCallTemplate,
    private val updateSettingsState: (GenerationSettings) -> Unit
) {
    val mcpClient = McpClient()
    private var mcpServerName: String = "MCP"

    private val _mcpStatusText = MutableStateFlow(strings.get(R.string.vm_mcp_disconnected))
    val mcpStatusText: StateFlow<String> = _mcpStatusText.asStateFlow()

    private val _mcpTools = MutableStateFlow<List<McpTool>>(emptyList())
    val mcpTools: StateFlow<List<McpTool>> = _mcpTools.asStateFlow()

    private val _mcpToolsContext = MutableStateFlow<String?>(null)
    val mcpToolsContext: StateFlow<String?> = _mcpToolsContext.asStateFlow()

    private val _mcpTokenSaved = MutableStateFlow(!settingsStore.secrets.get(SecretPrefs.MCP_TOKEN).isNullOrBlank())
    val mcpTokenSaved: StateFlow<Boolean> = _mcpTokenSaved.asStateFlow()

    private val _pendingToolApproval = MutableStateFlow<McpToolCall?>(null)
    val pendingToolApproval: StateFlow<McpToolCall?> = _pendingToolApproval.asStateFlow()

    private var toolApprovalDecision: CompletableDeferred<Boolean>? = null
    var approvalTimeoutMs: Long = 60_000L

    fun restoreSessionIfEnabled() {
        val initial = currentSettings()
        if (initial.isMcpEnabled && initial.mcpServerUrl.isNotBlank()) {
            connectMcp(initial.mcpServerUrl)
        } else if (initial.isMcpEnabled) {
            updateSettingsState(initial.copy(isMcpEnabled = false))
            settingsStore.saveMcpConnectionState(initial.mcpServerUrl, enabled = false)
        }
    }

    fun refreshToolsContext() {
        val template = currentToolTemplate()
        val settings = currentSettings()
        val tools = mcpClient.currentTools()
        _mcpToolsContext.value = if (
            template == ToolCallTemplate.NONE ||
            !settings.toolCallsEnabled ||
            !settings.isMcpEnabled ||
            tools.isEmpty()
        ) {
            null
        } else {
            McpToolProtocol.buildToolsPrompt(mcpServerName, tools, template)
        }
    }

    fun clearToken() {
        settingsStore.secrets.remove(SecretPrefs.MCP_TOKEN)
        _mcpTokenSaved.value = false
    }

    fun connectMcp(url: String, bearerToken: String? = null) {
        scope.launch {
            _mcpStatusText.value = strings.get(R.string.vm_mcp_checking)
            val host = hostOf(url)
            if (bearerToken != null) {
                settingsStore.secrets.put(SecretPrefs.MCP_TOKEN, bearerToken.trim())
                _mcpTokenSaved.value = bearerToken.isNotBlank()
            }
            var token = settingsStore.secrets.get(SecretPrefs.MCP_TOKEN)
            val lastHost = settingsStore.getLastMcpHost()
            val tokenWithheld = bearerToken == null &&
                !token.isNullOrBlank() &&
                !lastHost.isNullOrBlank() &&
                host != lastHost
            if (tokenWithheld) token = null

            val result = mcpClient.connectServer(url, token)
            if (result.isSuccess) {
                mcpServerName = result.serverName
                _mcpTools.value = result.tools
                _mcpTokenSaved.value = !settingsStore.secrets.get(SecretPrefs.MCP_TOKEN).isNullOrBlank()
                _mcpStatusText.value = strings.get(
                    R.string.vm_mcp_connected,
                    result.serverName,
                    result.latencyMs,
                    result.tools.size
                ) + if (tokenWithheld) " " + strings.get(R.string.settings_mcp_token_not_sent) else ""
                settingsStore.saveMcpConnectionState(url, enabled = true, lastHost = host)
            } else {
                mcpClient.disconnect()
                mcpServerName = "MCP"
                _mcpTools.value = emptyList()
                _mcpStatusText.value = strings.get(R.string.vm_mcp_failed, result.message)
                settingsStore.saveMcpConnectionState(url, enabled = false)
            }
            updateSettingsState(currentSettings().copy(mcpServerUrl = url, isMcpEnabled = result.isSuccess))
            refreshToolsContext()
        }
    }

    suspend fun requestApproval(call: McpToolCall): Boolean {
        val decision = CompletableDeferred<Boolean>()
        toolApprovalDecision = decision
        _pendingToolApproval.value = call
        return try {
            withTimeoutOrNull(approvalTimeoutMs) { decision.await() } ?: false
        } finally {
            if (toolApprovalDecision === decision) toolApprovalDecision = null
            _pendingToolApproval.value = null
        }
    }

    fun resolveApproval(approved: Boolean) {
        toolApprovalDecision?.complete(approved)
    }

    fun cancelPendingApproval() {
        toolApprovalDecision?.complete(false)
    }

    private fun hostOf(url: String): String = try {
        url.trim().toHttpUrl().host.lowercase()
    } catch (_: Throwable) {
        ""
    }
}
