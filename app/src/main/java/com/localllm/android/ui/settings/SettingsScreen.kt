package com.localllm.android.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.android.R
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTopBar
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mcpStatusText by viewModel.mcpStatusText.collectAsStateWithLifecycle()
    val mcpTools by viewModel.mcpTools.collectAsStateWithLifecycle()
    val tokenSaved by viewModel.mcpTokenSaved.collectAsStateWithLifecycle()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsStateWithLifecycle()
    val apiPort by viewModel.apiServerPort.collectAsStateWithLifecycle()
    val localIpAddress by viewModel.localIpAddress.collectAsStateWithLifecycle()
    val memoryGuardState by viewModel.memoryGuardState.collectAsStateWithLifecycle()
    val llamaGpuOffloadAvailable = remember { viewModel.isLlamaGpuOffloadAvailable() }
    val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()
    val activeToolTemplate by viewModel.activeToolTemplate.collectAsStateWithLifecycle()
    val documentStats by viewModel.documentStats.collectAsStateWithLifecycle()

    var showClearDialog by remember { mutableStateOf(false) }
    var showMemoryLog by remember { mutableStateOf(false) }
    var memoryLogText by remember { mutableStateOf("") }

    GScaffold(
        topBar = {
            GTopBar(
                title = { GText(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    GIconButton(onClick = { viewModel.navigateTo(AppScreen.CHAT) }) {
                        GIcon(
                            imageVector = GIcons.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassTheme.colors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                RuntimeSettingsSection(
                    runtime = settings.runtime,
                    onSwitchRuntime = viewModel::switchRuntime
                )

                HardwareSettingsSection(
                    settings = settings,
                    llamaGpuOffloadAvailable = llamaGpuOffloadAvailable,
                    onUpdateSettings = viewModel::updateSettings
                )

                MemoryGuardSettingsSection(
                    memoryGuardState = memoryGuardState,
                    onSetEnabled = viewModel::setMemoryGuardEnabled,
                    onViewLog = {
                        memoryLogText = viewModel.memoryGuardLog().ifBlank { "—" }
                        showMemoryLog = true
                    },
                    onReset = viewModel::resetMemoryGuard
                )

                KvCacheSettingsSection(
                    settings = settings,
                    onUpdateSettings = viewModel::updateSettings
                )

                SamplingSettingsSection(
                    settings = settings,
                    onUpdateSettings = viewModel::updateSettings
                )

                McpSettingsSection(
                    initialMcpUrl = settings.mcpServerUrl,
                    mcpStatusText = mcpStatusText,
                    mcpTools = mcpTools,
                    tokenSaved = tokenSaved,
                    onClearToken = viewModel::clearMcpToken,
                    onConnect = viewModel::connectMcp
                )

                ToolDialectSettingsSection(
                    settings = settings,
                    activeModel = activeModel,
                    activeToolTemplate = activeToolTemplate,
                    onSetTemplate = viewModel::setToolCallTemplate,
                    onUpdateSettings = viewModel::updateSettings
                )

                HuggingFaceSettingsSection(
                    settings = settings,
                    onUpdateHfToken = viewModel::updateHfToken,
                    onSetHfSource = viewModel::setHfSource
                )

                ThemeAndLanguageSections(
                    settings = settings,
                    onUpdateSettings = viewModel::updateSettings,
                    onSetLanguage = viewModel::setLanguagePreference
                )

                DocumentsAndApiSections(
                    settings = settings,
                    documentStats = documentStats,
                    isApiModeEnabled = isApiModeEnabled,
                    apiPort = apiPort,
                    localIpAddress = localIpAddress,
                    onClearDocuments = viewModel::clearDocumentIndex,
                    onSetApiEnabled = viewModel::setApiModeEnabled,
                    onSetApiExternalAccess = viewModel::setApiServerExternalAccess,
                    onOpenApiScreen = { viewModel.navigateTo(AppScreen.API_MODE) },
                    onShowClearChatsDialog = { showClearDialog = true }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showMemoryLog) {
        GDialog(
            onDismissRequest = { showMemoryLog = false },
            title = { GText(stringResource(R.string.settings_memory_log_title), style = GlassTheme.type.titleSmall) },
            text = {
                Column(
                    modifier = Modifier
                        .height(320.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    GText(text = memoryLogText, fontSize = 11.sp, color = GlassTheme.colors.onSurfaceVariant)
                }
            },
            confirmButton = {
                GTextButton(onClick = { showMemoryLog = false }) {
                    GText(stringResource(R.string.settings_memory_close), color = GlassTheme.colors.primary)
                }
            }
        )
    }

    if (showClearDialog) {
        GDialog(
            onDismissRequest = { showClearDialog = false },
            title = { GText(stringResource(R.string.clear_all_chats_confirm_title)) },
            text = { GText(stringResource(R.string.clear_all_chats_confirm_desc)) },
            confirmButton = {
                val clearedToast = stringResource(R.string.chats_cleared)
                GTextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearDialog = false
                        Toast.makeText(context, clearedToast, Toast.LENGTH_SHORT).show()
                    }
                ) {
                    GText(stringResource(R.string.delete), color = GlassTheme.colors.error)
                }
            },
            dismissButton = {
                GTextButton(onClick = { showClearDialog = false }) {
                    GText(stringResource(R.string.cancel))
                }
            }
        )
    }
}
