package com.localllm.android.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.document.DocumentStats
import com.localllm.android.engine.McpTool
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ToolCallTemplate
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GOutlineButton
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTextField
import com.localllm.android.ui.glass.GlassTheme

@Composable
internal fun McpSettingsSection(
    initialMcpUrl: String,
    mcpStatusText: String,
    mcpTools: List<McpTool>,
    tokenSaved: Boolean,
    onClearToken: () -> Unit,
    onConnect: (url: String, bearerToken: String?) -> Unit
) {
    var mcpUrlInput by remember(initialMcpUrl) { mutableStateOf(initialMcpUrl) }
    var mcpTokenInput by remember { mutableStateOf("") }
    var mcpTokenTouched by remember { mutableStateOf(false) }

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
            placeholder = { GText("https://mcp.example.com/mcp") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        GTextField(
            value = mcpTokenInput,
            onValueChange = {
                mcpTokenInput = it
                mcpTokenTouched = true
            },
            label = { GText(stringResource(R.string.settings_mcp_token_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        GText(
            text = stringResource(R.string.settings_mcp_token_hint),
            fontSize = 11.sp,
            color = GlassTheme.colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GText(
                text = stringResource(if (tokenSaved) R.string.settings_mcp_token_saved else R.string.settings_mcp_token_none),
                fontSize = 11.sp,
                color = GlassTheme.colors.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            if (tokenSaved) {
                GTextButton(onClick = {
                    onClearToken()
                    mcpTokenInput = ""
                    mcpTokenTouched = false
                }) {
                    GText(stringResource(R.string.settings_mcp_token_clear))
                }
            }
        }
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
            GButton(onClick = { onConnect(mcpUrlInput, if (mcpTokenTouched) mcpTokenInput else null) }) {
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
}

@Composable
internal fun ToolDialectSettingsSection(
    settings: GenerationSettings,
    activeModel: LlmModel?,
    activeToolTemplate: ToolCallTemplate,
    onSetTemplate: (ToolCallTemplate) -> Unit,
    onUpdateSettings: (GenerationSettings) -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_tool_template_title),
        icon = GIcons.Bolt
    ) {
        GText(
            text = stringResource(R.string.settings_tool_template_desc),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        ToolCallTemplate.entries.chunked(3).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chunk.forEach { option ->
                    GFilterChip(
                        selected = activeToolTemplate == option,
                        onClick = { onSetTemplate(option) },
                        label = {
                            GText(
                                when (option) {
                                    ToolCallTemplate.AUTO -> stringResource(R.string.settings_tool_template_auto)
                                    ToolCallTemplate.NONE -> stringResource(R.string.settings_tool_template_off)
                                    else -> option.shortLabel
                                }
                            )
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
                repeat(3 - chunk.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
        if (activeModel == null) {
            GText(
                text = stringResource(R.string.vm_status_no_default_model),
                fontSize = 11.sp,
                color = GlassTheme.colors.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
        SettingToggleRow(
            title = stringResource(R.string.settings_tool_calls_title),
            subtitle = stringResource(R.string.settings_tool_calls_desc),
            checked = !settings.toolCallsEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(toolCallsEnabled = !it)) }
        )

        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = stringResource(R.string.settings_builtin_prompt_title),
            subtitle = stringResource(R.string.settings_builtin_prompt_desc),
            checked = !settings.builtInSystemPromptEnabled,
            onCheckedChange = { onUpdateSettings(settings.copy(builtInSystemPromptEnabled = !it)) }
        )
    }
}

@Composable
internal fun HuggingFaceSettingsSection(
    settings: GenerationSettings,
    onUpdateHfToken: (String) -> Unit,
    onSetHfSource: (String) -> Unit
) {
    var hfTokenInput by remember(settings.hfToken) { mutableStateOf(settings.hfToken) }
    var isTokenVisible by remember { mutableStateOf(false) }

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
                onUpdateHfToken(it)
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
            text = if (settings.hfToken.isNotBlank()) stringResource(R.string.settings_hf_token_set)
            else stringResource(R.string.settings_hf_token_unset),
            fontSize = 12.sp,
            color = if (settings.hfToken.isNotBlank()) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
        )
    }

    SettingsCard(
        title = stringResource(R.string.settings_hf_source_title),
        icon = GIcons.CloudDownload
    ) {
        GText(
            text = stringResource(R.string.settings_hf_source_desc),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "official" to stringResource(R.string.settings_hf_source_official),
                "mirror" to stringResource(R.string.settings_hf_source_mirror)
            ).forEach { (code, label) ->
                GFilterChip(
                    selected = settings.hfSource == code,
                    onClick = { onSetHfSource(code) },
                    label = { GText(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun ThemeAndLanguageSections(
    settings: GenerationSettings,
    onUpdateSettings: (GenerationSettings) -> Unit,
    onSetLanguage: (String) -> Unit
) {
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
                onClick = { onUpdateSettings(settings.copy(darkModePreference = "dark")) },
                label = { GText(stringResource(R.string.settings_theme_dark_on)) },
                leadingIcon = { GIcon(GIcons.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f)
            )
            GFilterChip(
                selected = settings.darkModePreference == "light",
                onClick = { onUpdateSettings(settings.copy(darkModePreference = "light")) },
                label = { GText(stringResource(R.string.settings_theme_white)) },
                leadingIcon = { GIcon(GIcons.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f)
            )
            GFilterChip(
                selected = settings.darkModePreference == "system",
                onClick = { onUpdateSettings(settings.copy(darkModePreference = "system")) },
                label = { GText(stringResource(R.string.settings_theme_dark_system)) },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        GText(stringResource(R.string.settings_theme_palette), style = GlassTheme.type.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))

        val themes = listOf(
            Triple("offcon", "Offcon Blue", Color(0xFF4F63F0)),
            Triple("artistic", "Artistic Purple", Color(0xFFD0BCFF)),
            Triple("liquid", "Liquid Glass", Color(0xFF7DD3FC)),
            Triple("chatgpt", "Emerald Green", Color(0xFF10A37F)),
            Triple("cyber", "Cyber Neon", Color(0xFF00FF9D)),
            Triple("obsidian", "Obsidian Violet", Color(0xFFA855F7)),
            Triple("amber", "Sunset Amber", Color(0xFFF59E0B)),
            Triple("frost", "Arctic Frost", Color(0xFF38BDF8))
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            themes.forEach { (key, name, color) ->
                val isSelected = settings.themeColorName == key
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.clickable {
                        onUpdateSettings(settings.copy(themeColorName = key))
                    }
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) GlassTheme.colors.onSurface else GlassTheme.colors.outline,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isSelected) {
                            GIcon(
                                imageVector = GIcons.Check,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(18.dp)
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
            listOf(
                "system" to stringResource(R.string.settings_language_system),
                "en" to stringResource(R.string.settings_language_en),
                "ko" to stringResource(R.string.settings_language_ko),
                "zh" to stringResource(R.string.settings_language_zh)
            ).forEach { (code, label) ->
                GFilterChip(
                    selected = settings.languagePreference == code,
                    onClick = { onSetLanguage(code) },
                    label = { GText(label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun DocumentsAndApiSections(
    settings: GenerationSettings,
    documentStats: DocumentStats,
    isApiModeEnabled: Boolean,
    apiPort: Int,
    localIpAddress: String,
    onClearDocuments: () -> Unit,
    onSetApiEnabled: (Boolean) -> Unit,
    onSetApiExternalAccess: (Boolean) -> Unit,
    onOpenApiScreen: () -> Unit,
    onShowClearChatsDialog: () -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_documents_title),
        icon = GIcons.Description
    ) {
        GText(
            text = stringResource(R.string.settings_documents_summary, documentStats.documents, documentStats.chunks),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
        if (documentStats.chunks > 0) {
            Spacer(modifier = Modifier.height(8.dp))
            GButton(onClick = onClearDocuments) {
                GText(stringResource(R.string.settings_documents_clear))
            }
        }
    }

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
            GSwitch(checked = isApiModeEnabled, onCheckedChange = onSetApiEnabled)
        }

        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = stringResource(R.string.api_external_access_title),
            subtitle = if (settings.isApiExternalAccessEnabled) stringResource(R.string.settings_api_bind_all)
            else stringResource(R.string.settings_api_bind_loopback),
            checked = settings.isApiExternalAccessEnabled,
            onCheckedChange = onSetApiExternalAccess
        )

        Spacer(modifier = Modifier.height(10.dp))
        GOutlineButton(
            onClick = onOpenApiScreen,
            modifier = Modifier.fillMaxWidth()
        ) {
            GIcon(imageVector = GIcons.Dns, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            GText(stringResource(R.string.settings_api_open_detail))
        }
    }

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
        GButton(onClick = onShowClearChatsDialog, danger = true) {
            GIcon(imageVector = GIcons.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            GText(stringResource(R.string.settings_storage_delete_all))
        }
    }
}
