package com.localllm.android.ui.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GDivider
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTopBar
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.glass.LiquidBackground
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel

@Composable
fun ApiServerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsState()
    val apiServerStatusMessage by viewModel.apiServerStatusMessage.collectAsState()
    val apiPort by viewModel.apiServerPort.collectAsState()
    val localIpAddress by viewModel.localIpAddress.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()
    val requestCount by viewModel.apiRequestCount.collectAsState()
    val apiKey by viewModel.apiServerApiKey.collectAsState()
    var apiKeyRevealed by remember { mutableStateOf(false) }

    val baseUrl = "http://$localIpAddress:$apiPort"
    val loopbackUrl = "http://127.0.0.1:$apiPort"
    val effectiveUrl = if (settings.isApiExternalAccessEnabled) baseUrl else loopbackUrl

    GScaffold(
        topBar = {
            GTopBar(
                title = {
                    Column {
                        GText(stringResource(R.string.api_mode_title), style = GlassTheme.type.titleLarge)
                        GText(
                            text = stringResource(R.string.api_mode_subtitle, apiPort),
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    GIconButton(onClick = { viewModel.navigateTo(AppScreen.CHAT) }) {
                        GIcon(
                            imageVector = GIcons.ArrowBack,
                            contentDescription = stringResource(R.string.nav_back)
                        )
                    }
                },
                actions = {
                    GIconButton(onClick = { viewModel.refreshLocalIp() }) {
                        GIcon(
                            imageVector = GIcons.Refresh,
                            contentDescription = stringResource(R.string.api_refresh_ip_desc)
                        )
                    }
                }
            )
        },
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            LiquidBackground()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // Keeps the last card above the system taskbar / 3-button nav bar.
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // 1. Master Toggle Card
            GCard(
                cornerRadius = 16.dp,
                containerColor = if (isApiModeEnabled)
                    GlassTheme.colors.primaryContainer.copy(alpha = 0.4f)
                else
                    GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isApiModeEnabled) GlassTheme.colors.primary
                                        else GlassTheme.colors.outline.copy(alpha = 0.3f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                GIcon(
                                    imageVector = if (isApiModeEnabled) GIcons.PowerSettingsNew else GIcons.Stop,
                                    contentDescription = null,
                                    tint = if (isApiModeEnabled) GlassTheme.colors.onPrimary else GlassTheme.colors.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                GText(
                                    text = if (isApiModeEnabled) stringResource(R.string.api_server_running) else stringResource(R.string.api_server_stopped),
                                    style = GlassTheme.type.titleMedium,
                                    color = GlassTheme.colors.onSurface
                                )
                                GText(
                                    text = if (isApiModeEnabled) stringResource(R.string.api_server_waiting, apiPort) else stringResource(R.string.api_server_switch_hint),
                                    style = GlassTheme.type.bodySmall,
                                    color = GlassTheme.colors.onSurfaceVariant
                                )
                            }
                        }

                        GSwitch(
                            checked = isApiModeEnabled,
                            onCheckedChange = { viewModel.setApiModeEnabled(it) }
                        )
                    }

                    if (!apiServerStatusMessage.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        GText(
                            text = apiServerStatusMessage ?: "",
                            style = GlassTheme.type.labelSmall,
                            color = if (isApiModeEnabled) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }

            // 2. External Network Access (LAN) Toggle Card
            GCard(
                cornerRadius = 16.dp,
                containerColor = if (settings.isApiExternalAccessEnabled)
                    GlassTheme.colors.secondaryContainer.copy(alpha = 0.35f)
                else
                    GlassTheme.colors.surfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (settings.isApiExternalAccessEnabled) GlassTheme.colors.secondary
                                        else GlassTheme.colors.outline.copy(alpha = 0.3f)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                GIcon(
                                    imageVector = GIcons.Lan,
                                    contentDescription = null,
                                    tint = if (settings.isApiExternalAccessEnabled) GlassTheme.colors.onSecondary else GlassTheme.colors.onSurfaceVariant,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column {
                                GText(
                                    text = stringResource(R.string.api_external_access_title),
                                    style = GlassTheme.type.titleMedium,
                                    color = GlassTheme.colors.onSurface
                                )
                                GText(
                                    text = if (settings.isApiExternalAccessEnabled)
                                        stringResource(R.string.api_external_access_enabled)
                                    else
                                        stringResource(R.string.api_external_access_disabled),
                                    style = GlassTheme.type.bodySmall,
                                    color = if (settings.isApiExternalAccessEnabled) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                                )
                            }
                        }

                        GSwitch(
                            checked = settings.isApiExternalAccessEnabled,
                            onCheckedChange = { viewModel.setApiServerExternalAccess(it) },
                            activeColor = GlassTheme.colors.secondary
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    GText(
                        text = if (settings.isApiExternalAccessEnabled)
                            stringResource(R.string.api_external_access_desc_on, localIpAddress, apiPort)
                        else
                            stringResource(R.string.api_external_access_desc_off),
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }

            // 3. Server Status & Network Address Card
            GCard(
                cornerRadius = 16.dp,
                containerColor = GlassTheme.colors.surface.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GText(
                        text = stringResource(R.string.api_address_info),
                        style = GlassTheme.type.titleSmall,
                        color = GlassTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val loopbackCopiedMsg = stringResource(R.string.api_copied_loopback)
                    AddressRow(
                        label = stringResource(R.string.api_loopback_label),
                        url = loopbackUrl,
                        isEnabled = true,
                        statusBadge = stringResource(R.string.api_status_always_available),
                        onCopy = { copyToClipboard(context, loopbackUrl, loopbackCopiedMsg) }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val networkCopiedMsg = stringResource(R.string.api_copied_network)
                    val externalDisabledToast = stringResource(R.string.api_external_disabled_toast)
                    AddressRow(
                        label = stringResource(R.string.api_lan_label),
                        url = if (settings.isApiExternalAccessEnabled) baseUrl else stringResource(R.string.api_url_external_disabled, baseUrl),
                        isEnabled = settings.isApiExternalAccessEnabled,
                        statusBadge = if (settings.isApiExternalAccessEnabled) stringResource(R.string.api_status_allowed) else stringResource(R.string.api_status_blocked),
                        onCopy = {
                            if (settings.isApiExternalAccessEnabled) {
                                copyToClipboard(context, baseUrl, networkCopiedMsg)
                            } else {
                                Toast.makeText(context, externalDisabledToast, Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    val apiKeyCopiedMsg = stringResource(R.string.api_copied_key)
                    AddressRow(
                        label = stringResource(R.string.api_key_label),
                        url = if (apiKeyRevealed) apiKey else maskSecret(apiKey),
                        isEnabled = true,
                        revealed = apiKeyRevealed,
                        onToggleReveal = { apiKeyRevealed = !apiKeyRevealed },
                        onCopy = { copyToClipboard(context, apiKey, apiKeyCopiedMsg) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    GDivider(color = GlassTheme.colors.outline.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GText(
                            text = stringResource(R.string.api_connected_model),
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                        GText(
                            text = activeModel?.name ?: stringResource(R.string.api_no_model_selected),
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GText(
                            text = stringResource(R.string.api_processed_requests),
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                        GText(
                            text = stringResource(R.string.api_request_count, requestCount),
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.secondary
                        )
                    }
                }
            }

            // 3. Supported API Endpoints
            GCard(
                cornerRadius = 16.dp,
                containerColor = GlassTheme.colors.surface.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    GText(
                        text = stringResource(R.string.api_supported_endpoints),
                        style = GlassTheme.type.titleSmall,
                        color = GlassTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    EndpointRow(method = "GET", path = "/api/tags", desc = stringResource(R.string.api_endpoint_tags_desc))
                    EndpointRow(method = "POST", path = "/api/generate", desc = stringResource(R.string.api_endpoint_generate_desc))
                    EndpointRow(method = "POST", path = "/api/chat", desc = stringResource(R.string.api_endpoint_chat_desc))
                    EndpointRow(method = "POST", path = "/v1/chat/completions", desc = stringResource(R.string.api_endpoint_openai_desc))
                }
            }

            // 4. cURL Example
            GCard(
                cornerRadius = 16.dp,
                containerColor = GlassTheme.colors.surface.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val displayApiKey = if (apiKeyRevealed) apiKey else maskSecret(apiKey)
                    val curlCopiedMsg = stringResource(R.string.api_copied_curl)
                    val curlExamplePrompt = stringResource(R.string.api_curl_example_prompt)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GText(
                            text = stringResource(R.string.api_curl_example),
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.primary
                        )

                        val curlCmd = """curl -X POST $effectiveUrl/api/generate \
  -H "Authorization: Bearer $apiKey" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "$curlExamplePrompt", "stream": false}'"""

                        GIconButton(onClick = {
                            copyToClipboard(context, curlCmd, curlCopiedMsg)
                        }) {
                            GIcon(
                                imageVector = GIcons.ContentCopy,
                                contentDescription = stringResource(R.string.api_copy_command_desc),
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassTheme.colors.surface)
                            .padding(12.dp)
                    ) {
                        GText(
                            text = "curl -X POST $effectiveUrl/api/generate \\\n  -H \"Authorization: Bearer $displayApiKey\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"prompt\": \"$curlExamplePrompt\", \"stream\": false}'",
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = GlassTheme.colors.onSurface
                        )
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun AddressRow(
    label: String,
    url: String,
    isEnabled: Boolean = true,
    statusBadge: String? = null,
    revealed: Boolean = true,
    onToggleReveal: (() -> Unit)? = null,
    onCopy: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GText(
                text = label,
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.onSurfaceVariant
            )
            if (statusBadge != null) {
                GText(
                    text = statusBadge,
                    style = GlassTheme.type.labelSmall,
                    color = if (isEnabled) GlassTheme.colors.primary else GlassTheme.colors.error
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (isEnabled) GlassTheme.colors.surface
                    else GlassTheme.colors.surface.copy(alpha = 0.5f)
                )
                .clickable { onCopy() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GText(
                text = url,
                fontSize = 12.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                color = if (isEnabled) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggleReveal != null) {
                    GIconButton(onClick = onToggleReveal, modifier = Modifier.size(28.dp)) {
                        GIcon(
                            imageVector = if (revealed) GIcons.VisibilityOff else GIcons.Visibility,
                            contentDescription = if (revealed) stringResource(R.string.api_hide_key_desc) else stringResource(R.string.api_show_key_desc),
                            tint = GlassTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                GIcon(
                    imageVector = GIcons.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = if (isEnabled) GlassTheme.colors.onSurfaceVariant else GlassTheme.colors.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun EndpointRow(
    method: String,
    path: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (method == "POST") GlassTheme.colors.primaryContainer
                    else GlassTheme.colors.secondaryContainer
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            GText(
                text = method,
                fontSize = 10.sp,
                color = if (method == "POST") GlassTheme.colors.onPrimaryContainer
                else GlassTheme.colors.onSecondaryContainer,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        GText(
            text = path,
            fontSize = 12.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = GlassTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        GText(
            text = desc,
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
    }
}

private fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("API Address", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

/** Never render an API key in full: keep a short recognizable prefix and the last 4 chars. */
private fun maskSecret(secret: String): String {
    if (secret.isBlank()) return ""
    val last4 = secret.takeLast(4)
    val rawPrefix = secret.substringBeforeLast('-', "")
    val prefix = if (rawPrefix.isNotBlank() && rawPrefix.length <= 16) "$rawPrefix-" else ""
    return "$prefix••••••••$last4"
}
