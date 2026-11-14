package com.localllm.android.ui.api

import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.android.R
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GDivider
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTopBar
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun ApiServerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsStateWithLifecycle()
    val apiServerStatusMessage by viewModel.apiServerStatusMessage.collectAsStateWithLifecycle()
    val apiPort by viewModel.apiServerPort.collectAsStateWithLifecycle()
    val localIpAddress by viewModel.localIpAddress.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()
    val requestCount by viewModel.apiRequestCount.collectAsStateWithLifecycle()
    val apiKey by viewModel.apiServerApiKey.collectAsStateWithLifecycle()
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
                            style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
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
                    GIconButton(onClick = viewModel::refreshLocalIp) {
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
            modifier = Modifier
                .fillMaxSize()
                .background(GlassTheme.colors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                GCard(cornerRadius = 14.dp, modifier = Modifier.fillMaxWidth()) {
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
                                        .size(6.dp)
                                        .clip(RoundedCornerShape(1.dp))
                                        .background(
                                            if (isApiModeEnabled) GlassTheme.colors.tertiary
                                            else GlassTheme.colors.onSurfaceVariant
                                        )
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    GText(
                                        text = if (isApiModeEnabled) stringResource(R.string.api_server_running)
                                        else stringResource(R.string.api_server_stopped),
                                        style = GlassTheme.type.titleMedium,
                                        color = GlassTheme.colors.onSurface
                                    )
                                    GText(
                                        text = if (isApiModeEnabled) stringResource(R.string.api_server_waiting, apiPort)
                                        else stringResource(R.string.api_server_switch_hint),
                                        style = GlassTheme.type.bodySmall,
                                        color = GlassTheme.colors.onSurfaceVariant
                                    )
                                }
                            }
                            GSwitch(
                                checked = isApiModeEnabled,
                                onCheckedChange = viewModel::setApiModeEnabled
                            )
                        }

                        if (!apiServerStatusMessage.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            GText(
                                text = apiServerStatusMessage ?: "",
                                style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = if (isApiModeEnabled) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                            )
                        }
                    }
                }

                GCard(cornerRadius = 14.dp, modifier = Modifier.fillMaxWidth()) {
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
                                GIcon(
                                    imageVector = GIcons.Lan,
                                    contentDescription = null,
                                    tint = GlassTheme.colors.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    GText(
                                        text = stringResource(R.string.api_external_access_title),
                                        style = GlassTheme.type.titleMedium,
                                        color = GlassTheme.colors.onSurface
                                    )
                                    GText(
                                        text = if (settings.isApiExternalAccessEnabled) stringResource(R.string.api_external_access_enabled)
                                        else stringResource(R.string.api_external_access_disabled),
                                        style = GlassTheme.type.bodySmall,
                                        color = GlassTheme.colors.onSurfaceVariant
                                    )
                                }
                            }
                            GSwitch(
                                checked = settings.isApiExternalAccessEnabled,
                                onCheckedChange = viewModel::setApiServerExternalAccess
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        GText(
                            text = if (settings.isApiExternalAccessEnabled) {
                                stringResource(R.string.api_external_access_desc_on, localIpAddress, apiPort)
                            } else {
                                stringResource(R.string.api_external_access_desc_off)
                            },
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                }

                GCard(cornerRadius = 14.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        GText(
                            text = stringResource(R.string.api_address_info),
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.onSurface
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
                            url = if (settings.isApiExternalAccessEnabled) baseUrl
                            else stringResource(R.string.api_url_external_disabled, baseUrl),
                            isEnabled = settings.isApiExternalAccessEnabled,
                            statusBadge = if (settings.isApiExternalAccessEnabled) stringResource(R.string.api_status_allowed)
                            else stringResource(R.string.api_status_blocked),
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
                        GDivider(color = GlassTheme.colors.outline)
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
                                style = GlassTheme.type.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = GlassTheme.colors.onSurface
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
                                style = GlassTheme.type.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                color = GlassTheme.colors.primary
                            )
                        }
                    }
                }

                GCard(cornerRadius = 14.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        GText(
                            text = stringResource(R.string.api_supported_endpoints),
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.onSurface
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        EndpointRow(method = "GET", path = "/api/tags", desc = stringResource(R.string.api_endpoint_tags_desc))
                        EndpointRow(method = "POST", path = "/api/generate", desc = stringResource(R.string.api_endpoint_generate_desc))
                        EndpointRow(method = "POST", path = "/api/chat", desc = stringResource(R.string.api_endpoint_chat_desc))
                        EndpointRow(method = "POST", path = "/v1/chat/completions", desc = stringResource(R.string.api_endpoint_openai_desc))
                    }
                }

                CurlExampleCard(
                    context = context,
                    effectiveUrl = effectiveUrl,
                    apiKey = apiKey,
                    apiKeyRevealed = apiKeyRevealed
                )
            }
        }
    }
}
