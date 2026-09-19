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
                title = { GText("설정 및 커스텀 테마") },
                navigationIcon = {
                    GIconButton(onClick = { viewModel.navigateTo(AppScreen.CHAT) }) {
                        GIcon(
                            imageVector = GIcons.ArrowBack,
                            contentDescription = "뒤로가기"
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
                title = "추론 백엔드 런타임",
                icon = GIcons.Memory
            ) {
                GText(
                    text = "추론 엔진 런타임을 선택하세요.",
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
                    label = { GText("⚠ SDengine (TEST) — 자체 엔진 실험체") },
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
                title = "인덱싱 및 성능 가속 옵션",
                icon = GIcons.Bolt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText("MTP (Multi-Token Prediction) 자동 적용", style = GlassTheme.type.bodyMedium)
                        GText("모델이 MTP를 지원할 때 스펙큘레이티브 2x 토큰 디코딩 가속", fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
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
                        GText("KV-Cache 및 프롬프트 인덱싱 가속", style = GlassTheme.type.bodyMedium)
                        GText("Flash-Attention v2 및 프롬프트 캐시 최적화", fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
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
                        GText("실시간 TPS & PP 속도 표시", style = GlassTheme.type.bodyMedium)
                        GText("메시지 하단에 토큰 생성 속도 및 프롬프트 처리 지표 표시", fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
                    }
                    GSwitch(
                        checked = settings.showPerformanceMetrics,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(showPerformanceMetrics = it)) }
                    )
                }
            }

            // 3. Generation Hyperparameters (Context 4096 default, Temp, Top-P, Top-K)
            SettingsCard(
                title = "세부 추론 파라미터",
                icon = GIcons.Tune
            ) {
                // Context Window
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    GText("컨텍스트 창 크기 (Context Window)")
                    GText("${settings.contextWindow} 토큰", color = GlassTheme.colors.primary)
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
                    GText("온도 (Temperature)")
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
                    GText("Top-P 샘플링")
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
                title = "사용자 지정 시스템 프롬프트",
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
                    label = { GText("시스템 프롬프트 (System Instruction)") }
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
                        GText("초기화")
                    }
                }
            }

            // 5. MCP (Model Context Protocol) URL Integration
            SettingsCard(
                title = "MCP (Model Context Protocol) 도구 연동",
                icon = GIcons.Hub
            ) {
                GText(
                    text = "MCP 서버에 JSON-RPC로 연결해 도구 목록을 조회하고, 그 정의를 채팅 프롬프트에 전달합니다. 모델의 자동 도구 실행(에이전틱 호출)은 아직 지원하지 않습니다.",
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
                        text = "상태: $mcpStatusText",
                        fontSize = 12.sp,
                        color = GlassTheme.colors.primary,
                        modifier = Modifier.weight(1f)
                    )
                    GButton(onClick = { viewModel.connectMcp(mcpUrlInput) }) {
                        GText("연결 및 적용")
                    }
                }
                if (mcpTools.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    GText(
                        text = "연결된 도구: " + mcpTools.joinToString(", ") { it.name },
                        fontSize = 12.sp,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }

            // 6. Hugging Face Personal Access Token (Optional)
            SettingsCard(
                title = "Hugging Face 액세스 토큰 (선택)",
                icon = GIcons.Key
            ) {
                GText(
                    text = "Gated 모델(Gemma, Meta Llama 등) 또는 비공개 저장소 모델을 다운로드할 때 인증 헤더로 전송됩니다. 공개 모델 다운로드 시에는 비워두셔도 정상 동작합니다.",
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
                    label = { GText("Hugging Face 토큰 (hf_...)") },
                    placeholder = { GText("hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        GIconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                            GIcon(
                                imageVector = if (isTokenVisible) GIcons.VisibilityOff else GIcons.Visibility,
                                contentDescription = if (isTokenVisible) "숨기기" else "보기"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                GText(
                    text = if (settings.hfToken.isNotBlank()) "✓ 토큰 설정됨 (다운로드 요청 시 Authorization 헤더 자동 포함)" else "미설정 (공개 모델 전용)",
                    fontSize = 12.sp,
                    color = if (settings.hfToken.isNotBlank()) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                )
            }

            // 7. Theme & Dark/Light Mode Personalization
            SettingsCard(
                title = "UI 테마 및 다크/화이트 모드",
                icon = GIcons.Palette
            ) {
                GText("화면 모드 (Dark / Light Mode)", style = GlassTheme.type.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    GFilterChip(
                        selected = settings.darkModePreference == "dark",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "dark")) },
                        label = { GText("다크 모드") },
                        leadingIcon = { GIcon(GIcons.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    GFilterChip(
                        selected = settings.darkModePreference == "light",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "light")) },
                        label = { GText("화이트 모드") },
                        leadingIcon = { GIcon(GIcons.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    GFilterChip(
                        selected = settings.darkModePreference == "system",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "system")) },
                        label = { GText("시스템 설정") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                GText("테마 컬러 팔레트 선택", style = GlassTheme.type.bodyMedium)
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
                title = "API 전용 모드 (포트 11434)",
                icon = GIcons.Dns
            ) {
                GText(
                    text = "기기를 독립적인 Ollama 호환 LLM 서버로 구동합니다. 로컬 네트워크의 다른 장치나 앱에서 HTTP API로 연결할 수 있습니다.",
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
                            text = if (isApiModeEnabled) "API 서버 구동 중" else "API 서버 꺼짐",
                            style = GlassTheme.type.bodyMedium,
                            color = if (isApiModeEnabled) GlassTheme.colors.primary else GlassTheme.colors.onSurface
                        )
                        GText(
                            text = if (isApiModeEnabled) {
                                if (settings.isApiExternalAccessEnabled) "http://$localIpAddress:$apiPort (외부 허용)"
                                else "http://127.0.0.1:$apiPort (로컬 전용)"
                            } else "포트 11434 수신 대기",
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
                        GText("외부 네트워크(LAN) 접근 허용", style = GlassTheme.type.bodyMedium)
                        GText(
                            text = if (settings.isApiExternalAccessEnabled)
                                "전체 인터페이스(0.0.0.0) 바인딩 활성화"
                            else
                                "기기 내부 루프백(127.0.0.1) 격리",
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
                    GText("API 모드 상세 화면 및 cURL 가이드 열기")
                }
            }

            // 8. Encrypted SQLite Storage Management
            SettingsCard(
                title = "로컬 SQLite 암호화 저장소",
                icon = GIcons.Lock
            ) {
                GText(
                    text = "모든 대화 기록 및 메타데이터는 AES-256-GCM 알고리즘으로 기기 내 안전하게 암호화되어 보관됩니다.",
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
                    GText("모든 암호화 대화 데이터 영구 삭제")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    if (showClearDialog) {
        GDialog(
            onDismissRequest = { showClearDialog = false },
            title = { GText("대화 내역 전체 삭제") },
            text = { GText("로컬 SQLite에 암호화 저장된 모든 대화 기록을 완전히 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                GTextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearDialog = false
                        Toast.makeText(context, "대화 기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    GText("삭제", color = GlassTheme.colors.error)
                }
            },
            dismissButton = {
                GTextButton(onClick = { showClearDialog = false }) {
                    GText("취소")
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
