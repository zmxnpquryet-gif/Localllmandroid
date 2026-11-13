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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GLinearProgress
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.glass.LiquidBackground
import com.localllm.android.ui.glass.OffconMark
import com.localllm.android.update.AppUpdateInfo
import com.localllm.android.update.EngineVersions
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ChatScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var drawerOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = drawerOpen) { drawerOpen = false }

    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentConvId by viewModel.currentConversationId.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()
    val allModels by viewModel.models.collectAsStateWithLifecycle()
    val isGenerating by viewModel.isGenerating.collectAsStateWithLifecycle()
    val streamingMessage by viewModel.streamingMessage.collectAsStateWithLifecycle()
    val pendingAttachment by viewModel.pendingAttachment.collectAsStateWithLifecycle()
    val voiceState by viewModel.voiceManager.voiceState.collectAsStateWithLifecycle()
    val engineStatus by viewModel.engineStatusMessage.collectAsStateWithLifecycle()
    val isModelLoading by viewModel.isModelLoading.collectAsStateWithLifecycle()
    val modelLoadingProgress by viewModel.modelLoadingProgress.collectAsStateWithLifecycle()
    val modelLoadingStage by viewModel.modelLoadingStage.collectAsStateWithLifecycle()
    val isApiModeEnabled by viewModel.isApiModeEnabled.collectAsStateWithLifecycle()
    val apiPort by viewModel.apiServerPort.collectAsStateWithLifecycle()
    val documentLibrary by viewModel.documentLibrary.collectAsStateWithLifecycle()
    val activeDocumentIds by viewModel.activeDocumentIds.collectAsStateWithLifecycle()
    val appUpdate by viewModel.appUpdate.collectAsStateWithLifecycle()

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

    // Respect user touch interactions: suspend autoScroll immediately on drag start
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> {
                    autoScrollEnabled = false
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> {
                    autoScrollEnabled = isScrolledToBottom
                }
            }
        }
    }

    // Automatically resume auto-scroll only if user voluntarily scrolled back to the very bottom
    LaunchedEffect(isScrolledToBottom) {
        if (isScrolledToBottom) {
            autoScrollEnabled = true
        }
    }

    // Smooth scroll following the active streaming message without jumping if user scrolled away
    LaunchedEffect(streamingMessage?.content?.length, autoScrollEnabled) {
        if (streamingMessage != null && autoScrollEnabled) {
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                listState.scrollToItem(totalCount - 1)
            }
        }
    }

    // Scroll to bottom when user sends a new message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty() && messages.last().role == com.localllm.android.model.MessageRole.USER) {
            autoScrollEnabled = true
            val totalCount = listState.layoutInfo.totalItemsCount
            if (totalCount > 0) {
                listState.scrollToItem(totalCount - 1)
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
                    onOpenApiMode = { viewModel.navigateTo(AppScreen.API_MODE) },
                    contextUsageTokens = streamingMessage?.contextTokens ?: messages.lastOrNull()?.contextTokens ?: 0
                )
            },
            bottomBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Box(modifier = Modifier.fillMaxWidth().widthIn(max = 680.dp)) {
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
                    onOpenVoiceMode = { viewModel.navigateTo(AppScreen.VOICE_MODE) },
                    onDocumentPicked = { uri, name -> viewModel.importDocument(uri, name) },
                    indexedDocuments = documentLibrary,
                    activeDocumentIds = activeDocumentIds,
                    onToggleDocument = { viewModel.toggleDocumentSelection(it) },
                    onDeleteDocument = { viewModel.deleteIndexedDocument(it) },
                    isModelLoading = isModelLoading,
                    modelLoadingProgress = modelLoadingProgress,
                    modelLoadingStage = modelLoadingStage,
                    currentTps = streamingMessage?.tps ?: 0f,
                    promptSpeed = streamingMessage?.promptSpeed ?: 0f,
                    contextTokens = streamingMessage?.contextTokens ?: messages.lastOrNull()?.contextTokens ?: 0
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier.fillMaxSize()
                    .background(GlassTheme.colors.background)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 680.dp)
                        .align(Alignment.TopCenter)
                        .fillMaxHeight()
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
                                            text = if (modelLoadingProgress > 0f) String.format(Locale.US, "%.0f%%", modelLoadingProgress * 100f) else "",
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
                                                tint = GlassTheme.colors.primary,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            GText(
                                                text = stringResource(R.string.chat_api_banner_active, apiPort),
                                                fontSize = 11.sp,
                                                color = GlassTheme.colors.onSurface
                                            )
                                        }
                                        GText(
                                            text = stringResource(R.string.chat_api_banner_details),
                                            fontSize = 11.sp,
                                            color = GlassTheme.colors.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // App update banner
                    appUpdate?.let { update ->
                        UpdateBanner(
                            update = update,
                            onDismiss = { viewModel.dismissAppUpdate() }
                        )
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
                                            fontSize = 12.sp,
                                            color = GlassTheme.colors.onSurface,
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

                    // MCP tool approval: every tool call waits for an explicit decision.
                    val pendingTool by viewModel.pendingToolApproval.collectAsStateWithLifecycle()
                    pendingTool?.let { call ->
                        GDialog(
                            onDismissRequest = { viewModel.resolveToolApproval(false) },
                            title = {
                                GText(
                                    text = stringResource(R.string.mcp_approval_title),
                                    style = GlassTheme.type.titleMedium
                                )
                            },
                            text = {
                                Column {
                                    GText(
                                        text = stringResource(R.string.mcp_approval_body, call.name),
                                        style = GlassTheme.type.bodyMedium
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    GText(
                                        text = call.argumentsJson.take(1500),
                                        style = GlassTheme.type.bodySmall,
                                        color = GlassTheme.colors.onSurfaceVariant
                                    )
                                }
                            },
                            confirmButton = {
                                GTextButton(onClick = { viewModel.resolveToolApproval(true) }) {
                                    GText(stringResource(R.string.mcp_approval_run))
                                }
                            },
                            dismissButton = {
                                GTextButton(onClick = { viewModel.resolveToolApproval(false) }) {
                                    GText(stringResource(R.string.mcp_approval_decline))
                                }
                            }
                        )
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
    // A load that succeeded on a fallback backend (GPU/OpenCL → CPU)
    // reports the failure that caused it but is a working state, so it stays a warning.
    val lower = status.lowercase()
    if (status.contains("대체 실행") || status.contains("回退") || status.contains("替代") ||
        lower.contains("fallback")
    ) return false
    return lower.contains("error") || lower.contains("failed") || lower.contains("exception") ||
        status.contains("오류") || status.contains("실패") || status.contains("예외") ||
        status.contains("错误") || status.contains("失败") || status.contains("异常")
}

@Composable
private fun UpdateBanner(
    update: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        GCard(cornerRadius = 16.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(update.releaseUrl) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    GText(
                        text = stringResource(R.string.update_banner_title, update.latestVersion),
                        style = GlassTheme.type.bodySmall,
                        color = GlassTheme.colors.onSurface
                    )
                    GText(
                        text = stringResource(
                            R.string.update_banner_engine,
                            EngineVersions.LLAMA_CPP,
                            EngineVersions.LITERT_LM
                        ),
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
                GText(
                    text = stringResource(R.string.update_banner_action),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.primary
                )
                GIconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    GIcon(
                        imageVector = GIcons.Close,
                        contentDescription = stringResource(R.string.update_banner_dismiss),
                        tint = GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
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
            .padding(horizontal = 20.dp)
            .padding(top = 64.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        OffconMark(
            modifier = Modifier.size(44.dp),
            contentDescription = "OffconAI"
        )

        Spacer(modifier = Modifier.height(22.dp))

        GText(
            text = "무엇이든 함께해볼까요?",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                fontSize = 26.sp,
                lineHeight = 34.sp,
                letterSpacing = (-0.5).sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            ),
            color = GlassTheme.colors.onBackground,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        GText(
            text = "모델은 기기 안에서 실행되고 대화는 암호화되어 저장됩니다",
            style = GlassTheme.type.bodyMedium,
            color = GlassTheme.colors.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        if (activeModel == null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(GlassTheme.colors.surface)
                    .clickable { onOpenModelManager() }
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GIcon(
                        imageVector = GIcons.CloudDownload,
                        contentDescription = null,
                        tint = GlassTheme.colors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        GText(
                            text = "모델 선택 및 다운로드",
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.onSurface
                        )
                        GText(
                            text = "시작하려면 로컬 모델이 필요합니다",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                }
                GIcon(
                    imageVector = GIcons.ArrowForward,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        val suggestions = listOf(
            Triple("파이썬으로 Hello World 출력하기", GIcons.Description, "코드 작성"),
            Triple("오늘 저녁 메뉴 추천해줘", GIcons.Chat, "일상 대화"),
            Triple("재미있는 사실 하나 알려줘", GIcons.Info, "지식 탐색")
        )

        suggestions.forEach { (prompt, icon, category) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(GlassTheme.colors.surface)
                    .clickable { onSampleClick(prompt) }
                    .padding(horizontal = 16.dp, vertical = 15.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(GlassTheme.colors.secondary),
                    contentAlignment = Alignment.Center
                ) {
                    GIcon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = GlassTheme.colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    GText(
                        text = prompt,
                        style = GlassTheme.type.bodyMedium.copy(
                            fontSize = 15.sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                        ),
                        color = GlassTheme.colors.onSurface
                    )
                    GText(
                        text = category,
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
                GIcon(
                    imageVector = GIcons.ArrowForward,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
