package com.localllm.android.ui.chat

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.localllm.android.R
import com.localllm.android.model.LlmModel
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.drawer.ChatDrawer
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GDrawer
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GLinearProgress
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.glass.LiquidBackground
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var drawerOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    val conversations by viewModel.conversations.collectAsState()
    val currentConvId by viewModel.currentConversationId.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()
    val allModels by viewModel.models.collectAsState()
    val isGenerating by viewModel.isGenerating.collectAsState()
    val streamingMessage by viewModel.streamingMessage.collectAsState()
    val pendingAttachment by viewModel.pendingAttachment.collectAsState()
    val voiceState by viewModel.voiceManager.voiceState.collectAsState()
    val engineStatus by viewModel.engineStatusMessage.collectAsState()
    val isModelLoading by viewModel.isModelLoading.collectAsState()
    val modelLoadingProgress by viewModel.modelLoadingProgress.collectAsState()
    val modelLoadingStage by viewModel.modelLoadingStage.collectAsState()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsState()
    val apiPort by viewModel.apiServerPort.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var showStatusDetailDialog by remember { mutableStateOf(false) }
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
    val micPermissionDeniedMsg = stringResource(R.string.chat_voice_mic_permission_required)
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
            Toast.makeText(context, micPermissionDeniedMsg, Toast.LENGTH_SHORT).show()
        }
    }

    GDrawer(
        open = drawerOpen,
        onClose = { drawerOpen = false },
        drawerContent = {
            ChatDrawer(
                conversations = conversations,
                currentConversationId = currentConvId,
                onSelectConversation = { id ->
                    viewModel.selectConversation(id)
                    drawerOpen = false
                },
                onNewChat = {
                    viewModel.createNewConversation()
                    drawerOpen = false
                },
                onDeleteConversation = { id -> viewModel.deleteConversation(id) },
                onRenameConversation = { id, title -> viewModel.renameConversation(id, title) },
                onOpenModelManager = {
                    viewModel.navigateTo(AppScreen.MODELS)
                    drawerOpen = false
                },
                onOpenVoiceMode = {
                    viewModel.navigateTo(AppScreen.VOICE_MODE)
                    drawerOpen = false
                },
                onOpenApiMode = {
                    viewModel.navigateTo(AppScreen.API_MODE)
                    drawerOpen = false
                },
                onOpenSettings = {
                    viewModel.navigateTo(AppScreen.SETTINGS)
                    drawerOpen = false
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) {
        GScaffold(
            topBar = {
                ChatTopBar(
                    activeModel = activeModel,
                    allModels = allModels,
                    currentRuntime = settings.runtime,
                    isMtpOn = settings.enableMtp,
                    onOpenDrawer = { drawerOpen = true },
                    onNewChat = { viewModel.createNewConversation() },
                    onSelectModel = { viewModel.selectModel(it) },
                    onOpenSettings = { viewModel.navigateTo(AppScreen.SETTINGS) },
                    onSwitchRuntime = { viewModel.switchRuntime(it) },
                    onOpenModelManager = { viewModel.navigateTo(AppScreen.MODELS) },
                    onOpenApiMode = { viewModel.navigateTo(AppScreen.API_MODE) }
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
                        // Local recording toggles: tap again to stop & transcribe on-device.
                        if (voiceState == com.localllm.android.voice.InteractiveVoiceState.LISTENING) {
                            viewModel.voiceManager.stopListening()
                        } else {
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
                        }
                    },
                    onOpenVoiceMode = { viewModel.navigateTo(AppScreen.VOICE_MODE) }
                )
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
            ) {
                LiquidBackground()
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Model Loading Progress Bar (Visible during loading, disappears when loading finishes)
                    AnimatedVisibility(visible = isModelLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            GCard(cornerRadius = 16.dp) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            GIcon(
                                                imageVector = GIcons.Memory,
                                                contentDescription = null,
                                                tint = GlassTheme.colors.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            GText(
                                                text = if (modelLoadingStage.isNotBlank()) stringResource(R.string.model_loading, modelLoadingStage) else stringResource(R.string.model_loading_default),
                                                style = GlassTheme.type.labelSmall,
                                                color = GlassTheme.colors.primary
                                            )
                                        }
                                        GText(
                                            text = if (modelLoadingProgress > 0f) String.format("%.0f%%", modelLoadingProgress * 100f) else "",
                                            style = GlassTheme.type.labelSmall,
                                            color = GlassTheme.colors.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    if (modelLoadingProgress <= 0f) {
                                        GLinearProgress(
                                            progress = null,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = GlassTheme.colors.primary,
                                            trackColor = GlassTheme.colors.surfaceVariant
                                        )
                                    } else {
                                        GLinearProgress(
                                            progress = modelLoadingProgress,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp)),
                                            color = GlassTheme.colors.primary,
                                            trackColor = GlassTheme.colors.surfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // API Dedicated Mode Active Banner
                    AnimatedVisibility(visible = isApiModeEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            GCard(cornerRadius = 16.dp) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { viewModel.navigateTo(AppScreen.API_MODE) }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            GIcon(
                                                imageVector = GIcons.Dns,
                                                contentDescription = null,
                                                tint = GlassTheme.colors.secondary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            GText(
                                                text = stringResource(R.string.chat_api_banner_active, apiPort),
                                                fontSize = 11.sp,
                                                color = GlassTheme.colors.onSecondaryContainer
                                            )
                                        }
                                        GText(
                                            text = stringResource(R.string.chat_api_banner_details),
                                            fontSize = 11.sp,
                                            color = GlassTheme.colors.secondary
                                        )
                                    }
                                }
                            }
                        }
                    }

                // SDengine TEST Banner (shows whenever the experimental engine is selected)
                if (settings.runtime == com.localllm.android.model.ModelRuntimeType.SD_ENGINE) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        GCard(cornerRadius = 16.dp) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(GlassTheme.colors.errorContainer.copy(alpha = 0.45f))
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    GIcon(
                                        imageVector = GIcons.ErrorOutline,
                                        contentDescription = null,
                                        tint = GlassTheme.colors.error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    GText(
                                        text = stringResource(R.string.chat_sdengine_banner_prefix) +
                                                com.localllm.engine.SDEngine.advisoryText(),
                                        fontSize = 11.sp,
                                        color = GlassTheme.colors.onErrorContainer,
                                        maxLines = 5,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                        }
                    }
                }

                // Engine Status Pill Banner
                if (!engineStatus.isNullOrBlank()) {
                        val isErrorStatus = isEngineErrorStatus(engineStatus)

                        val bannerTextColor = if (isErrorStatus) {
                            GlassTheme.colors.onErrorContainer
                        } else {
                            GlassTheme.colors.onSurfaceVariant
                        }

                        val bannerIcon = if (isErrorStatus) GIcons.ErrorOutline else GIcons.Info
                        val bannerIconTint = if (isErrorStatus) GlassTheme.colors.error else GlassTheme.colors.primary

                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            GCard(cornerRadius = 16.dp) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            if (isErrorStatus) GlassTheme.colors.errorContainer.copy(alpha = 0.35f)
                                            else Color.Transparent
                                        )
                                        .clickable { showStatusDetailDialog = true }
                                        .padding(horizontal = 16.dp, vertical = 8.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        GIcon(
                                            imageVector = bannerIcon,
                                            contentDescription = null,
                                            tint = bannerIconTint,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        GText(
                                            text = engineStatus ?: "",
                                            fontSize = 11.sp,
                                            color = bannerTextColor,
                                            maxLines = 3,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        GText(
                                            text = stringResource(R.string.chat_status_details_short),
                                            fontSize = 10.sp,
                                            color = bannerIconTint,
                                            style = GlassTheme.type.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Full Engine Status & Diagnostics Dialog
                    if (showStatusDetailDialog && !engineStatus.isNullOrBlank()) {
                        GDialog(
                            onDismissRequest = { showStatusDetailDialog = false },
                            title = {
                                GText(
                                    text = if (isEngineErrorStatus(engineStatus)) stringResource(R.string.engine_error_details) else stringResource(R.string.engine_status),
                                    style = GlassTheme.type.titleMedium
                                )
                            },
                            text = {
                                GText(
                                    text = engineStatus ?: "",
                                    style = GlassTheme.type.bodySmall,
                                    color = GlassTheme.colors.onSurface
                                )
                            },
                            confirmButton = {
                                val copiedToast = stringResource(R.string.copied)
                                GTextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                        val clip = ClipData.newPlainText("Engine Status", engineStatus ?: "")
                                        clipboard?.setPrimaryClip(clip)
                                        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    GText(stringResource(R.string.copy))
                                }
                            },
                            dismissButton = {
                                GTextButton(onClick = { showStatusDetailDialog = false }) {
                                    GText(stringResource(R.string.close))
                                }
                            }
                        )
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
                                        onOpenModelManager = { viewModel.navigateTo(AppScreen.MODELS) },
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
                        val jumpVisible = !autoScrollEnabled && (streamingMessage != null || isGenerating)
                        val jumpAlpha by androidx.compose.animation.core.animateFloatAsState(
                            targetValue = if (jumpVisible) 1f else 0f,
                            label = "jumpAlpha"
                        )
                        if (jumpAlpha > 0.01f) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                                    .alpha(jumpAlpha)
                            ) {
                                JumpToLatestButton(
                                    onClick = {
                                        autoScrollEnabled = true
                                        scope.launch {
                                            val totalCount = listState.layoutInfo.totalItemsCount
                                            if (totalCount > 0) {
                                                listState.scrollToItem(totalCount - 1, scrollOffset = 100000)
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Language-independent error detection for the engine status banner. */
private fun isEngineErrorStatus(status: String?): Boolean {
    if (status.isNullOrBlank()) return false
    val lower = status.lowercase()
    return lower.contains("error") || lower.contains("failed") || lower.contains("exception") ||
        status.contains("오류") || status.contains("실패") || status.contains("예외")
}

@Composable
private fun JumpToLatestButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassTheme.colors.primaryContainer)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GIcon(
                imageVector = GIcons.ArrowDown,
                contentDescription = stringResource(R.string.chat_jump_latest_desc),
                tint = GlassTheme.colors.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            GText(
                text = stringResource(R.string.chat_jump_latest),
                style = GlassTheme.type.labelMedium,
                color = GlassTheme.colors.onPrimaryContainer
            )
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
                                GlassTheme.colors.primary.copy(alpha = 0.35f),
                                GlassTheme.colors.surfaceVariant.copy(alpha = 0.8f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        color = GlassTheme.colors.primary.copy(alpha = 0.6f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            GText(
                text = stringResource(R.string.chat_empty_no_model_title),
                style = GlassTheme.type.headlineMedium,
                color = GlassTheme.colors.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            GText(
                text = stringResource(R.string.chat_empty_no_model_desc),
                style = GlassTheme.type.bodyMedium,
                color = GlassTheme.colors.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(20.dp))

            GButton(
                onClick = onOpenModelManager,
                modifier = Modifier.clip(RoundedCornerShape(16.dp))
            ) {
                GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                GText(
                    text = stringResource(R.string.chat_empty_download_model),
                    color = GlassTheme.colors.onPrimary,
                    style = GlassTheme.type.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(
                    text = stringResource(R.string.chat_empty_features),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
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
                                GlassTheme.colors.primary.copy(alpha = 0.35f),
                                GlassTheme.colors.surfaceVariant.copy(alpha = 0.8f),
                                Color.Transparent
                            )
                        )
                    )
                    .border(
                        width = 1.5.dp,
                        color = GlassTheme.colors.primary.copy(alpha = 0.6f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                GIcon(
                    imageVector = GIcons.Info,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            GText(
                text = stringResource(R.string.app_name),
                style = GlassTheme.type.headlineMedium,
                color = GlassTheme.colors.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassTheme.colors.primary.copy(alpha = 0.15f))
                        .border(
                            1.dp,
                            GlassTheme.colors.primary.copy(alpha = 0.4f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    GText(
                        text = "${activeModel.name} • ${activeModel.runtimeBadge}",
                        style = GlassTheme.type.labelMedium,
                        color = GlassTheme.colors.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Feature chips
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                GText(
                    text = stringResource(R.string.app_tagline),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Quick Suggestion buttons
            val suggestions = listOf(
                stringResource(R.string.prompt_suggestion_1),
                stringResource(R.string.prompt_suggestion_2),
                stringResource(R.string.prompt_suggestion_3)
            )

            suggestions.forEach { prompt ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(GlassTheme.colors.surface.copy(alpha = 0.55f))
                        .border(
                            1.dp,
                            GlassTheme.colors.primary.copy(alpha = 0.25f),
                            RoundedCornerShape(16.dp)
                        )
                        .clickable { onSampleClick(prompt) }
                        .padding(horizontal = 16.dp, vertical = 14.dp)
                ) {
                    GText(
                        text = prompt,
                        style = GlassTheme.type.bodyMedium,
                        color = GlassTheme.colors.onSurface
                    )
                }
            }
        }
    }
}
