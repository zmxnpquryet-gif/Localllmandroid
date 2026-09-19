package com.localllm.android.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GOutlineButton
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GSlider
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTextField
import com.localllm.android.ui.glass.GTopBar
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.glass.LiquidBackground
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.localllm.android.R
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val mcpStatusText by viewModel.mcpStatusText.collectAsState()
    val mcpTools by viewModel.mcpTools.collectAsState()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsState()
    val apiPort by viewModel.apiServerPort.collectAsState()
    val localIpAddress by viewModel.localIpAddress.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var mcpUrlInput by remember { mutableStateOf(settings.mcpServerUrl) }
    var systemPromptInput by remember { mutableStateOf(settings.systemPrompt) }
    var hfTokenInput by remember { mutableStateOf(settings.hfToken) }
    var isTokenVisible by remember { mutableStateOf(false) }

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
            modifier = Modifier.fillMaxSize()
        ) {
            LiquidBackground()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // 1. Runtime Selection (LiteRT LM vs llama.cpp)
            SettingsCard(
                title = stringResource(R.string.settings_runtime_title),
                icon = GIcons.Memory
            ) {
                GText(
                    text = stringResource(R.string.settings_runtime_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    GFilterChip(
                        selected = settings.runtime == ModelRuntimeType.LLAMA_CPP,
                        onClick = { viewModel.switchRuntime(ModelRuntimeType.LLAMA_CPP) },
                        label = { GText("llama.cpp (GGUF)") },
                        modifier = Modifier.weight(1f)
                    )
                    GFilterChip(
                        selected = settings.runtime == ModelRuntimeType.LITE_RT,
                        onClick = { viewModel.switchRuntime(ModelRuntimeType.LITE_RT) },
                        label = { GText("LiteRT LM (NPU)") },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                GFilterChip(
                    selected = settings.runtime == ModelRuntimeType.SD_ENGINE,
                    onClick = { viewModel.switchRuntime(ModelRuntimeType.SD_ENGINE) },
                    label = { GText(stringResource(R.string.settings_sdengine_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (settings.runtime == ModelRuntimeType.SD_ENGINE) {
                    Spacer(modifier = Modifier.height(8.dp))
                    GText(
                        text = com.localllm.engine.SDEngine.advisoryText(),
                        style = GlassTheme.type.bodySmall,
                        color = GlassTheme.colors.error
                    )
                }
            }

            // 2. Hardware Acceleration & MTP Options
            SettingsCard(
                title = stringResource(R.string.settings_performance_title),
                icon = GIcons.Bolt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText(stringResource(R.string.settings_mtp_title), style = GlassTheme.type.bodyMedium)
                        GText(stringResource(R.string.settings_mtp_desc), fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
                    }
                    GSwitch(
                        checked = settings.enableMtp,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(enableMtp = it)) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText(stringResource(R.string.settings_indexing_title), style = GlassTheme.type.bodyMedium)
                        GText(stringResource(R.string.settings_indexing_desc), fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
                    }
                    GSwitch(
                        checked = settings.enableIndexingAcceleration,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(enableIndexingAcceleration = it)) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText(stringResource(R.string.settings_metrics_title), style = GlassTheme.type.bodyMedium)
                        GText(stringResource(R.string.settings_metrics_desc), fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
                    }
                    GSwitch(
                        checked = settings.showPerformanceMetrics,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(showPerformanceMetrics = it)) }
                    )
                }
            }

            // 3. Generation Hyperparameters (Context 4096 default, Temp, Top-P, Top-K)
            SettingsCard(
                title = stringResource(R.string.settings_params_title),
                icon = GIcons.Tune
            ) {
                // Context Window
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GText(stringResource(R.string.settings_context_window))
                    GText(stringResource(R.string.settings_context_window_value, settings.contextWindow), color = GlassTheme.colors.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(2048, 4096, 8192, 16384).forEach { ctx ->
                        GFilterChip(
                            selected = settings.contextWindow == ctx,
                            onClick = { viewModel.updateSettings(settings.copy(contextWindow = ctx)) },
                            label = { GText("$ctx") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Temperature
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GText(stringResource(R.string.settings_temperature))
                    GText(String.format("%.2f", settings.temperature), color = GlassTheme.colors.primary)
                }
                GSlider(
                    value = settings.temperature,
                    onValueChange = { viewModel.updateSettings(settings.copy(temperature = it)) },
                    valueRange = 0.0f..1.5f,
                    steps = 14
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Top-P
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GText(stringResource(R.string.settings_top_p_sampling))
                    GText(String.format("%.2f", settings.topP), color = GlassTheme.colors.primary)
                }
                GSlider(
                    value = settings.topP,
                    onValueChange = { viewModel.updateSettings(settings.copy(topP = it)) },
                    valueRange = 0.1f..1.0f,
                    steps = 9
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Top-K
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GText("Top-K")
                    GText("${settings.topK}", color = GlassTheme.colors.primary)
                }
                GSlider(
                    value = settings.topK.toFloat(),
                    onValueChange = { viewModel.updateSettings(settings.copy(topK = it.toInt())) },
                    valueRange = 10f..100f,
                    steps = 9
                )
            }

            // 4. Custom System Prompt
            SettingsCard(
                title = stringResource(R.string.settings_custom_system_prompt),
                icon = GIcons.Tune
            ) {
                GTextField(
                    value = systemPromptInput,
                    onValueChange = {
                        systemPromptInput = it
                        viewModel.updateSettings(settings.copy(systemPrompt = it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { GText(stringResource(R.string.settings_system_prompt_label)) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GButton(
                        onClick = {
                            val defaultPrompt = ""
                            systemPromptInput = defaultPrompt
                            viewModel.updateSettings(settings.copy(systemPrompt = defaultPrompt))
                        }
                    ) {
                        GText(stringResource(R.string.settings_reset))
                    }
                }
            }

            // 5. MCP (Model Context Protocol) URL Integration
            SettingsCard(
                title = stringResource(R.string.settings_mcp_section),
                icon = GIcons.Hub
            ) {
                GText(
                    text = stringResource(R.string.settings_mcp_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                GTextField(
                    value = mcpUrlInput,
                    onValueChange = { mcpUrlInput = it },
                    label = { GText("MCP Server URL") },
                    placeholder = { GText("https://mcp.weather.dev/sse") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GText(
                        text = stringResource(R.string.settings_mcp_status, mcpStatusText),
                        fontSize = 12.sp,
                        color = GlassTheme.colors.primary,
                        modifier = Modifier.weight(1f)
                    )
                    GButton(onClick = { viewModel.connectMcp(mcpUrlInput) }) {
                        GText(stringResource(R.string.settings_mcp_connect))
                    }
                }
                if (mcpTools.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    GText(
                        text = stringResource(R.string.settings_mcp_connected_tools, mcpTools.joinToString(", ") { it.name }),
                        fontSize = 12.sp,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }

            // 6. Hugging Face Personal Access Token (Optional)
            SettingsCard(
                title = stringResource(R.string.settings_hf_token_title),
                icon = GIcons.Key
            ) {
                GText(
                    text = stringResource(R.string.settings_hf_token_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                GTextField(
                    value = hfTokenInput,
                    onValueChange = {
                        hfTokenInput = it
                        viewModel.updateHfToken(it)
                    },
                    label = { GText(stringResource(R.string.settings_hf_token_label)) },
                    placeholder = { GText("hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        GIconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            GIcon(
                                imageVector = if (isTokenVisible) GIcons.VisibilityOff else GIcons.Visibility,
                                contentDescription = if (isTokenVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show)
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                GText(
                    text = if (settings.hfToken.isNotBlank()) stringResource(R.string.settings_hf_token_set) else stringResource(R.string.settings_hf_token_unset),
                    fontSize = 12.sp,
                    color = if (settings.hfToken.isNotBlank()) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                )
            }

            // 7. Theme & Dark/Light Mode Personalization
            SettingsCard(
                title = stringResource(R.string.settings_theme_section),
                icon = GIcons.Palette
            ) {
                GText(stringResource(R.string.settings_display_mode), style = GlassTheme.type.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GFilterChip(
                        selected = settings.darkModePreference == "dark",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "dark")) },
                        label = { GText(stringResource(R.string.settings_theme_dark_on)) },
                        leadingIcon = { GIcon(GIcons.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    GFilterChip(
                        selected = settings.darkModePreference == "light",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "light")) },
                        label = { GText(stringResource(R.string.settings_theme_white)) },
                        leadingIcon = { GIcon(GIcons.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    GFilterChip(
                        selected = settings.darkModePreference == "system",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "system")) },
                        label = { GText(stringResource(R.string.settings_theme_dark_system)) },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                GText(stringResource(R.string.settings_theme_palette), style = GlassTheme.type.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                val themes = listOf(
                    Triple("artistic", "Artistic Purple", Color(0xFFD0BCFF)),
                    Triple("liquid", "Liquid Glass", Color(0xFF7DD3FC)),
                    Triple("chatgpt", "Emerald Green", Color(0xFF10A37F)),
                    Triple("cyber", "Cyber Neon", Color(0xFF00FF9D)),
                    Triple("obsidian", "Obsidian Violet", Color(0xFFA855F7)),
                    Triple("amber", "Sunset Amber", Color(0xFFF59E0B)),
                    Triple("frost", "Arctic Frost", Color(0xFF38BDF8))
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    themes.forEach { (key, name, color) ->
                        val isSelected = settings.themeColorName == key
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                viewModel.updateSettings(settings.copy(themeColorName = key))
                            }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) GlassTheme.colors.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    GIcon(
                                        imageVector = GIcons.Check,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            GText(
                                text = name.split(" ").first(),
                                fontSize = 11.sp,
                                color = GlassTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 6.5 Language Selection
            SettingsCard(
                title = stringResource(R.string.settings_language_title),
                icon = GIcons.Language
            ) {
                GText(
                    text = stringResource(R.string.settings_language_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val languages = listOf(
                        "system" to stringResource(R.string.settings_language_system),
                        "en" to stringResource(R.string.settings_language_en),
                        "ko" to stringResource(R.string.settings_language_ko)
                    )
                    languages.forEach { (code, label) ->
                        GFilterChip(
                            selected = settings.languagePreference == code,
                            onClick = { viewModel.setLanguagePreference(code) },
                            label = { GText(label) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 7. API Dedicated Mode (Port 11434 Ollama / OpenAI Server)
            SettingsCard(
                title = stringResource(R.string.settings_api_mode_section),
                icon = GIcons.Dns
            ) {
                GText(
                    text = stringResource(R.string.settings_api_mode_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText(
                            text = if (isApiModeEnabled) stringResource(R.string.settings_api_server_on) else stringResource(R.string.settings_api_server_off),
                            style = GlassTheme.type.bodyMedium,
                            color = if (isApiModeEnabled) GlassTheme.colors.primary else GlassTheme.colors.onSurface
                        )
                        GText(
                            text = if (isApiModeEnabled) {
                                if (settings.isApiExternalAccessEnabled) stringResource(R.string.settings_api_url_external, localIpAddress, apiPort)
                                else stringResource(R.string.settings_api_url_local, apiPort)
                            } else stringResource(R.string.settings_api_waiting),
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }

                    GSwitch(
                        checked = isApiModeEnabled,
                        onCheckedChange = { viewModel.setApiModeEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText(stringResource(R.string.api_external_access_title), style = GlassTheme.type.bodyMedium)
                        GText(
                            text = if (settings.isApiExternalAccessEnabled)
                                stringResource(R.string.settings_api_bind_all)
                            else
                                stringResource(R.string.settings_api_bind_loopback),
                            fontSize = 12.sp,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                    GSwitch(
                        checked = settings.isApiExternalAccessEnabled,
                        onCheckedChange = { viewModel.setApiServerExternalAccess(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                GOutlineButton(
                    onClick = { viewModel.navigateTo(AppScreen.API_MODE) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    GIcon(
                        imageVector = GIcons.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    GText(stringResource(R.string.settings_api_open_detail))
                }
            }

            // 8. Encrypted SQLite Storage Management
            SettingsCard(
                title = stringResource(R.string.settings_storage_title),
                icon = GIcons.Lock
            ) {
                GText(
                    text = stringResource(R.string.settings_storage_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                GButton(
                    onClick = { showClearDialog = true },
                    danger = true
                ) {
                    GIcon(
                        imageVector = GIcons.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    GText(stringResource(R.string.settings_storage_delete_all))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            }
        }
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

@Composable
private fun SettingsCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable () -> Unit
) {
    GCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 18.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                GText(
                    text = title,
                    style = GlassTheme.type.titleMedium,
                    color = GlassTheme.colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}
