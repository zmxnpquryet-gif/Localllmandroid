package com.example.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.model.LlmModel
import com.example.ui.MainViewModel
import com.example.ui.drawer.ChatDrawer
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val conversations by viewModel.conversations.collectAsState()
    val currentConvId by viewModel.currentConversationId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()
    val allModels by viewModel.models.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val pendingAttachment by viewModel.pendingAttachment.collectAsState()
    val engineStatus by viewModel.engineStatusMessage.collectAsState()
    val isModelLoading by viewModel.isModelLoading.collectAsState()
    val modelLoadingProgress by viewModel.modelLoadingProgress.collectAsState()
    val modelLoadingStage by viewModel.modelLoadingStage.collectAsState()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsState()
    val apiPort by viewModel.apiServerPort.collectAsState()

    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // Auto-scroll control: follows answer during streaming, releases when user interacts
    var autoScrollEnabled by remember { mutableStateOf(true) }

    // Check whether the user is viewing the bottom of the list
    val isScrolledToBottom by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val visibleItems = layoutInfo.visibleItemsInfo
            if (visibleItems.isEmpty()) return@derivedStateOf true
            val lastVisible = visibleItems.last()
            val totalCount = layoutInfo.totalItemsCount
            val isLastItem = lastVisible.index >= totalCount - 1
            val isBottomReached = (lastVisible.offset + lastVisible.size) <= (layoutInfo.viewportEndOffset + 100)
            isLastItem && isBottomReached
        }
    }

    // Monitor drag interactions: disable autoScroll only when user scrolls away from bottom
    LaunchedEffect(listState.interactionSource) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    // User started dragging
                }
                is DragInteraction.Stop -> {
                    // Finger lifted: if at bottom, keep auto-scroll on; else allow manual browsing
                    autoScrollEnabled = isScrolledToBottom
                }
                is DragInteraction.Cancel -> {
                    autoScrollEnabled = isScrolledToBottom
                }
            }
        }
    }

    // When scrolling reaches the bottom, automatically resume following
    LaunchedEffect(isScrolledToBottom) {
        if (isScrolledToBottom) {
            autoScrollEnabled = true
        }
    }

    // Scroll to bottom when sending/receiving new messages
    LaunchedEffect(messages.size) {
        autoScrollEnabled = true
        val totalCount = listState.layoutInfo.totalItemsCount
        if (totalCount > 0) {
            listState.scrollToItem(totalCount - 1, scrollOffset = 100000)
        }
    }

    // When streaming message appears, guarantee auto-scroll is enabled
    LaunchedEffect(streamingMessage != null) {
        if (streamingMessage != null) {
            autoScrollEnabled = true
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                listState.scrollToItem(totalCount - 1, scrollOffset = 100000)
            }
        }
    }

    // Follow answer stream as tokens arrive in real-time with full scroll offset
    LaunchedEffect(streamingMessage?.content) {
        if (streamingMessage != null && autoScrollEnabled) {
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                listState.scrollToItem(totalCount - 1, scrollOffset = 100000)
            }
        }
    }

    // Audio recording permission launcher for STT
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.voiceManager.startListening(
                onResult = { recognizedText ->
                    inputText = recognizedText
                },
                onError = { err ->
                    Toast.makeText(context, err, Toast.LENGTH_SHORT).show()
                }
            )
        } else {
            Toast.makeText(context, "음성 입력을 위해 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                conversations = conversations,
                currentConversationId = currentConvId,
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    scope.launch { drawerState.close() }
                },
                onNewChat = {
                    viewModel.createNewConversation()
                    scope.launch { drawerState.close() }
                },
                onDeleteConversation = { id -> viewModel.deleteConversation(id) },
                onRenameConversation = { id, title -> viewModel.renameConversation(id, title) },
                onOpenModelManager = {
                    viewModel.navigateTo("models")
                    scope.launch { drawerState.close() }
                },
                onOpenVoiceMode = {
                    viewModel.navigateTo("voice_mode")
                    scope.launch { drawerState.close() }
                },
                onOpenApiMode = {
                    viewModel.navigateTo("api_mode")
                    scope.launch { drawerState.close() }
                },
                onOpenSettings = {
                    viewModel.navigateTo("settings")
                    scope.launch { drawerState.close() }
                }
            )
        }
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    activeModel = activeModel,
                    allModels = allModels,
                    currentRuntime = settings.runtime,
                    isMtpOn = settings.enableMtp,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onNewChat = { viewModel.createNewConversation() },
                    onSelectModel = { viewModel.selectModel(it) },
                    onOpenSettings = { viewModel.navigateTo("settings") },
                    onSwitchRuntime = { viewModel.switchRuntime(it) },
                    onOpenModelManager = { viewModel.navigateTo("models") },
                    onOpenApiMode = { viewModel.navigateTo("api_mode") }
                )
            },
            bottomBar = {
                ChatInputBar(
                    inputText = inputText,
                    onInputTextChanged = { inputText = it },
                    onSendMessage = {
                        val text = inputText
                        inputText = ""
                        autoScrollEnabled = true
                        viewModel.sendMessage(text)
                        scope.launch {
                            val totalCount = listState.layoutInfo.totalItemsCount
                            if (totalCount > 0) {
                                listState.scrollToItem(totalCount - 1, scrollOffset = 100000)
                            }
                        }
                    },
                    onStopGeneration = { viewModel.stopGeneration() },
                    isGenerating = isGenerating,
                    activeModel = activeModel,
                    pendingAttachment = pendingAttachment,
                    onSetAttachment = { viewModel.setPendingAttachment(it) },
                    reasoningEffort = settings.reasoningEffort,
                    onReasoningEffortChanged = { viewModel.setReasoningEffort(it) },
                    onStartVoiceInput = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            viewModel.voiceManager.startListening(
                                onResult = { text -> inputText = text },
                                onError = { err -> Toast.makeText(context, err, Toast.LENGTH_SHORT).show() }
                            )
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onOpenVoiceMode = { viewModel.navigateTo("voice_mode") }
                )
            },
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            modifier = modifier.fillMaxSize()
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                // Model Loading Progress Bar (Visible during loading, disappears when loading finishes)
                AnimatedVisibility(visible = isModelLoading) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Memory,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (modelLoadingStage.isNotBlank()) "모델 로딩 중: $modelLoadingStage" else "모델 로딩 중...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = if (modelLoadingProgress > 0f) String.format("%.0f%%", modelLoadingProgress * 100f) else "",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        if (modelLoadingProgress <= 0f) {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                progress = { modelLoadingProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        }
                    }
                }

                // API Dedicated Mode Active Banner
                AnimatedVisibility(visible = isApiModeEnabled) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                            .clickable { viewModel.navigateTo("api_mode") }
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Dns,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "API 모드 실행 중 (포트 $apiPort) • 탭하여 API 정보 확인",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                            Text(
                                text = "상세보기 >",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }

                // Engine Status Pill Banner
                if (!engineStatus.isNullOrBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = engineStatus ?: "",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1
                            )
                        }
                    }
                }

                // Chat Messages Container
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        if (messages.isEmpty() && streamingMessage == null) {
                            item {
                                EmptyChatPlaceholder(
                                    activeModel = activeModel,
                                    onOpenModelManager = { viewModel.navigateTo("models") },
                                    onSampleClick = { sampleText ->
                                        viewModel.sendMessage(sampleText)
                                    }
                                )
                            }
                        } else {
                            items(messages, key = { it.id }) { msg ->
                                ChatMessageItem(
                                    message = msg,
                                    showMetrics = settings.showPerformanceMetrics,
                                    isMtpOn = settings.enableMtp && (activeModel?.supportsMtp == true),
                                    reasoningEffortLabel = settings.reasoningEffortLabel,
                                    onSpeak = { text -> viewModel.voiceManager.speak(text) }
                                )
                            }

                            // Ongoing streaming message
                            if (streamingMessage != null) {
                                item(key = "streaming_current_message") {
                                    ChatMessageItem(
                                        message = streamingMessage!!,
                                        showMetrics = settings.showPerformanceMetrics,
                                        isMtpOn = settings.enableMtp && (activeModel?.supportsMtp == true),
                                        reasoningEffortLabel = settings.reasoningEffortLabel,
                                        onSpeak = { text -> viewModel.voiceManager.speak(text) }
                                    )
                                }
                            }

                            // Dedicated bottom anchor to guarantee the bottom of the answer is always followed and never cut off
                            item(key = "chat_bottom_anchor") {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(28.dp)
                                )
                            }
                        }
                    }

                    // Floating Button: Jump to latest / re-enable auto-scroll when user manually scrolled up
                    androidx.compose.animation.AnimatedVisibility(
                        visible = !autoScrollEnabled && (streamingMessage != null || isGenerating),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                    ) {
                        Surface(
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shadowElevation = 6.dp,
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
                                .clickable {
                                    autoScrollEnabled = true
                                    scope.launch {
                                        val totalCount = listState.layoutInfo.totalItemsCount
                                        if (totalCount > 0) {
                                            listState.scrollToItem(totalCount - 1, scrollOffset = 100000)
                                        }
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.KeyboardArrowDown,
                                    contentDescription = "최신 답변으로 이동",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "답변 따라가기",
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatPlaceholder(
    activeModel: LlmModel?,
    onOpenModelManager: () -> Unit,
    onSampleClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (activeModel == null) {
            // No downloaded model state
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "로컬 LLM 다운로드 필요",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "기본 모델이 없습니다. 모델 관리에서 모델을 다운로드하여 기기에서 바로 실행할 수 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onOpenModelManager,
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("모델 다운로드")
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "GGUF / LiteRT 지원 • 비전 및 가속 지원",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Downloaded Model ready state
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Local LLM",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    letterSpacing = 0.5.sp
                ),
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.4f),
                            androidx.compose.foundation.shape.RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "${activeModel.name} • ${activeModel.runtimeBadge}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Feature chips
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "로컬 실행 • 기기 내 저장",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Suggestion buttons
            val suggestions = listOf(
                "오늘 저녁 메뉴 추천해줘",
                "주말에 읽을 만한 책 추천해줘",
                "하루 일정 정리하는 방법 알려줘"
            )

            suggestions.forEach { prompt ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .border(
                            1.dp,
                            MaterialTheme.colorScheme.outline.copy(alpha = 0.2f),
                            androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
                        )
                        .clickable { onSampleClick(prompt) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    Text(
                        text = prompt,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}
