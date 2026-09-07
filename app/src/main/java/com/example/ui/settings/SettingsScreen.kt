package com.example.ui.settings

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import com.example.model.GenerationSettings
import com.example.model.ModelRuntimeType
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val mcpStatusText by viewModel.mcpStatusText.collectAsState()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsState()
    val apiPort by viewModel.apiServerPort.collectAsState()
    val localIpAddress by viewModel.localIpAddress.collectAsState()

    var showClearDialog by remember { mutableStateOf(false) }
    var mcpUrlInput by remember { mutableStateOf(settings.mcpServerUrl) }
    var systemPromptInput by remember { mutableStateOf(settings.systemPrompt) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("설정 및 커스텀 테마") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo("chat") }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier.statusBarsPadding()
            )
        },
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Runtime Selection (LiteRT LM vs llama.cpp)
            SettingsCard(
                title = "추론 백엔드 런타임",
                icon = Icons.Default.Memory
            ) {
                Text(
                    text = "추론 엔진 런타임을 선택하세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FilterChip(
                        selected = settings.runtime == ModelRuntimeType.LLAMA_CPP,
                        onClick = { viewModel.switchRuntime(ModelRuntimeType.LLAMA_CPP) },
                        label = { Text("llama.cpp (GGUF)") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = settings.runtime == ModelRuntimeType.LITE_RT,
                        onClick = { viewModel.switchRuntime(ModelRuntimeType.LITE_RT) },
                        label = { Text("LiteRT LM (NPU)") },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // 2. Hardware Acceleration & MTP Options
            SettingsCard(
                title = "인덱싱 및 성능 가속 옵션",
                icon = Icons.Default.Bolt
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MTP (Multi-Token Prediction) 자동 적용", style = MaterialTheme.typography.bodyMedium)
                        Text("모델이 MTP를 지원할 때 스펙큘레이티브 2x 토큰 디코딩 가속", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
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
                        Text("KV-Cache 및 프롬프트 인덱싱 가속", style = MaterialTheme.typography.bodyMedium)
                        Text("Flash-Attention v2 및 프롬프트 캐시 최적화", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
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
                        Text("실시간 TPS & PP 속도 표시", style = MaterialTheme.typography.bodyMedium)
                        Text("메시지 하단에 토큰 생성 속도 및 프롬프트 처리 지표 표시", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = settings.showPerformanceMetrics,
                        onCheckedChange = { viewModel.updateSettings(settings.copy(showPerformanceMetrics = it)) }
                    )
                }
            }

            // 3. Generation Hyperparameters (Context 4096 default, Temp, Top-P, Top-K)
            SettingsCard(
                title = "세부 추론 파라미터",
                icon = Icons.Default.Tune
            ) {
                // Context Window
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("컨텍스트 창 크기 (Context Window)")
                    Text("${settings.contextWindow} 토큰", color = MaterialTheme.colorScheme.primary)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(2048, 4096, 8192, 16384).forEach { ctx ->
                        FilterChip(
                            selected = settings.contextWindow == ctx,
                            onClick = { viewModel.updateSettings(settings.copy(contextWindow = ctx)) },
                            label = { Text("$ctx") }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Temperature
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("온도 (Temperature)")
                    Text(String.format("%.2f", settings.temperature), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
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
                    Text("Top-P 샘플링")
                    Text(String.format("%.2f", settings.topP), color = MaterialTheme.colorScheme.primary)
                }
                Slider(
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
                    Text("Top-K")
                    Text("${settings.topK}", color = MaterialTheme.colorScheme.primary)
                }
                Slider(
                    value = settings.topK.toFloat(),
                    onValueChange = { viewModel.updateSettings(settings.copy(topK = it.toInt())) },
                    valueRange = 10f..100f,
                    steps = 9
                )
            }

            // 4. Custom System Prompt
            SettingsCard(
                title = "사용자 지정 시스템 프롬프트",
                icon = Icons.Default.Tune
            ) {
                OutlinedTextField(
                    value = systemPromptInput,
                    onValueChange = {
                        systemPromptInput = it
                        viewModel.updateSettings(settings.copy(systemPrompt = it))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    label = { Text("시스템 프롬프트 (System Instruction)") }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val defaultPrompt = ""
                            systemPromptInput = defaultPrompt
                            viewModel.updateSettings(settings.copy(systemPrompt = defaultPrompt))
                        }
                    ) {
                        Text("초기화")
                    }
                }
            }

            // 5. MCP (Model Context Protocol) URL Integration
            SettingsCard(
                title = "MCP (Model Context Protocol) 연동",
                icon = Icons.Default.Hub
            ) {
                Text(
                    text = "MCP 서버 URL(SSE 또는 HTTP)을 입력하면 실시간 외부 도구가 즉시 적용됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = mcpUrlInput,
                    onValueChange = { mcpUrlInput = it },
                    label = { Text("MCP Server URL") },
                    placeholder = { Text("https://mcp.weather.dev/sse") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "상태: $mcpStatusText",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { viewModel.connectMcp(mcpUrlInput) }) {
                        Text("연결 및 적용")
                    }
                }
            }

            // 6. Theme & Dark/Light Mode Personalization
            SettingsCard(
                title = "UI 테마 및 다크/화이트 모드",
                icon = Icons.Default.Palette
            ) {
                Text("화면 모드 (Dark / Light Mode)", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = settings.darkModePreference == "dark",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "dark")) },
                        label = { Text("다크 모드") },
                        leadingIcon = { Icon(Icons.Default.DarkMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = settings.darkModePreference == "light",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "light")) },
                        label = { Text("화이트 모드") },
                        leadingIcon = { Icon(Icons.Default.LightMode, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = settings.darkModePreference == "system",
                        onClick = { viewModel.updateSettings(settings.copy(darkModePreference = "system")) },
                        label = { Text("시스템 설정") },
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text("테마 컬러 팔레트 선택", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                val themes = listOf(
                    Triple("artistic", "Artistic Purple", Color(0xFFD0BCFF)),
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
                                        color = if (isSelected) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = name.split(" ").first(),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 7. API Dedicated Mode (Port 11434 Ollama / OpenAI Server)
            SettingsCard(
                title = "API 전용 모드 (포트 11434)",
                icon = Icons.Default.Dns
            ) {
                Text(
                    text = "기기를 독립적인 Ollama 호환 LLM 서버로 구동합니다. 로컬 네트워크의 다른 장치나 앱에서 HTTP API로 연결할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isApiModeEnabled) "API 서버 구동 중" else "API 서버 꺼짐",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (isApiModeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isApiModeEnabled) "http://$localIpAddress:$apiPort" else "포트 11434 수신 대기",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    androidx.compose.material3.Switch(
                        checked = isApiModeEnabled,
                        onCheckedChange = { viewModel.setApiModeEnabled(it) }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { viewModel.navigateTo("api_mode") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.Dns,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("API 모드 상세 화면 및 cURL 가이드 열기")
                }
            }

            // 8. Encrypted SQLite Storage Management
            SettingsCard(
                title = "로컬 SQLite 암호화 저장소",
                icon = Icons.Default.Lock
            ) {
                Text(
                    text = "모든 대화 기록 및 메타데이터는 AES-256-GCM 알고리즘으로 기기 내 안전하게 암호화되어 보관됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { showClearDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("모든 암호화 대화 데이터 영구 삭제")
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("대화 내역 전체 삭제") },
            text = { Text("로컬 SQLite에 암호화 저장된 모든 대화 기록을 완전히 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearAllHistory()
                        showClearDialog = false
                        Toast.makeText(context, "대화 기록이 삭제되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Text("삭제", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text("취소")
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f), RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}
