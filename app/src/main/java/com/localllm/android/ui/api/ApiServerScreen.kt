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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    val baseUrl = "http://$localIpAddress:$apiPort"
    val loopbackUrl = "http://127.0.0.1:$apiPort"
    val effectiveUrl = if (settings.isApiExternalAccessEnabled) baseUrl else loopbackUrl

    GScaffold(
        topBar = {
            GTopBar(
                title = {
                    Column {
                        GText("API 전용 모드", style = GlassTheme.type.titleLarge)
                        GText(
                            text = "포트 $apiPort • Ollama / OpenAI 호환 엔드포인트",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    GIconButton(onClick = { viewModel.navigateTo(AppScreen.CHAT) }) {
                        GIcon(
                            imageVector = GIcons.ArrowBack,
                            contentDescription = "뒤로 가기"
                        )
                    }
                },
                actions = {
                    GIconButton(onClick = { viewModel.refreshLocalIp() }) {
                        GIcon(
                            imageVector = GIcons.Refresh,
                            contentDescription = "IP 주소 새로고침"
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
                                    text = if (isApiModeEnabled) "API 모드 실행 중" else "API 모드 비활성화",
                                    style = GlassTheme.type.titleMedium,
                                    color = GlassTheme.colors.onSurface
                                )
                                GText(
                                    text = if (isApiModeEnabled) "포트 $apiPort 에서 외부 요청 대기 중" else "스위치를 켜서 API 서버를 구동하세요",
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
                                    text = "외부 네트워크(LAN) 접근 허용",
                                    style = GlassTheme.type.titleMedium,
                                    color = GlassTheme.colors.onSurface
                                )
                                GText(
                                    text = if (settings.isApiExternalAccessEnabled)
                                        "외부 접속 가능 (0.0.0.0 바인딩)"
                                    else
                                        "보안 격리 모드 (127.0.0.1 로컬 전용)",
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
                            "동일한 Wi-Fi 또는 로컬 네트워크(LAN)에 연결된 다른 PC나 기기에서 이 기기의 IP($localIpAddress:$apiPort)로 API를 직접 호출할 수 있습니다."
                        else
                            "보안을 위해 외부 기기의 API 접근이 차단되어 있습니다. 이 기기 내부(127.0.0.1)의 앱 및 프로세스에서만 접속할 수 있습니다.",
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
                        text = "접속 주소 정보",
                        style = GlassTheme.type.titleSmall,
                        color = GlassTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    AddressRow(
                        label = "기기 내 로컬 접속 (Loopback)",
                        url = loopbackUrl,
                        isEnabled = true,
                        statusBadge = "항상 접근 가능",
                        onCopy = { copyToClipboard(context, loopbackUrl, "루프백 주소가 복사되었습니다.") }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AddressRow(
                        label = "같은 Wi-Fi / 로컬 네트워크 접속",
                        url = if (settings.isApiExternalAccessEnabled) baseUrl else "$baseUrl (외부 접근 비활성화됨)",
                        isEnabled = settings.isApiExternalAccessEnabled,
                        statusBadge = if (settings.isApiExternalAccessEnabled) "외부 접속 허용됨" else "외부 접근 차단됨",
                        onCopy = {
                            if (settings.isApiExternalAccessEnabled) {
                                copyToClipboard(context, baseUrl, "네트워크 주소가 복사되었습니다.")
                            } else {
                                Toast.makeText(context, "외부 접근이 꺼져 있습니다. 위의 '외부 네트워크(LAN) 접근 허용' 스위치를 켜주세요.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    AddressRow(
                        label = "보안 API 인증 키 (Bearer Token)",
                        url = apiKey,
                        isEnabled = true,
                        onCopy = { copyToClipboard(context, apiKey, "API 키가 복사되었습니다.") }
                    )

                    Spacer(modifier = Modifier.height(12.dp))
                    GDivider(color = GlassTheme.colors.outline.copy(alpha = 0.15f))
                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GText(
                            text = "현재 연결 모델",
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                        GText(
                            text = activeModel?.name ?: "(선택된 모델 없음)",
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
                            text = "처리된 API 요청 수",
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                        GText(
                            text = "${requestCount}건",
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
                        text = "지원되는 API 규격 (포트 11434)",
                        style = GlassTheme.type.titleSmall,
                        color = GlassTheme.colors.primary
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    EndpointRow(method = "GET", path = "/api/tags", desc = "설치된 모델 목록 조회")
                    EndpointRow(method = "POST", path = "/api/generate", desc = "단일 텍스트 생성 (Ollama 규격)")
                    EndpointRow(method = "POST", path = "/api/chat", desc = "대화형 메시지 생성 (Ollama 규격)")
                    EndpointRow(method = "POST", path = "/v1/chat/completions", desc = "OpenAI 호환 대화 완성 엔드포인트")
                }
            }

            // 4. cURL Example
            GCard(
                cornerRadius = 16.dp,
                containerColor = GlassTheme.colors.surface.copy(alpha = 0.55f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GText(
                            text = "cURL 호출 예시 (Ollama 형식)",
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.primary
                        )

                        val curlCmd = """curl -X POST $effectiveUrl/api/generate \
  -H "Authorization: Bearer $apiKey" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "안녕하세요", "stream": false}'"""

                        GIconButton(onClick = {
                            copyToClipboard(context, curlCmd, "cURL 명령어가 복사되었습니다.")
                        }) {
                            GIcon(
                                imageVector = GIcons.ContentCopy,
                                contentDescription = "명령어 복사",
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
                            text = "curl -X POST $effectiveUrl/api/generate \\\n  -H \"Authorization: Bearer $apiKey\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"prompt\": \"안녕하세요\", \"stream\": false}'",
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
            GIcon(
                imageVector = GIcons.ContentCopy,
                contentDescription = "복사",
                tint = if (isEnabled) GlassTheme.colors.onSurfaceVariant else GlassTheme.colors.outline,
                modifier = Modifier.size(16.dp)
            )
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
