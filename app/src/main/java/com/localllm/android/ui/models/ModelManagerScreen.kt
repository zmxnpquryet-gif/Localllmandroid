package com.localllm.android.ui.models

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.engine.DownloadStatus
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GDivider
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GLinearProgress
import com.localllm.android.ui.glass.GOutlineButton
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTextField
import com.localllm.android.ui.glass.GTopBar
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.glass.LiquidBackground

@Composable
fun ModelManagerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsState()
    val activeModel by viewModel.activeModel.collectAsState()
    val activeDownloadStatus by viewModel.activeDownloadStatus.collectAsState()
    val settings by viewModel.settings.collectAsState()

    var showFdmDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("ALL") } // "ALL", "DOWNLOADED", "LLAMA_CPP", "LITE_RT"

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val fileName = queryFileName(context, uri) ?: "imported_model.gguf"
            viewModel.importLocalModel(uri, fileName)
        }
    }

    val filteredModels = remember(models, selectedFilter) {
        when (selectedFilter) {
            "DOWNLOADED" -> models.filter { it.isDownloaded }
            "LLAMA_CPP" -> models.filter { it.runtimeType == ModelRuntimeType.LLAMA_CPP }
            "LITE_RT" -> models.filter { it.runtimeType == ModelRuntimeType.LITE_RT }
            else -> models
        }
    }

    GScaffold(
        topBar = {
            GTopBar(
                title = {
                    Column {
                        GText("모델 관리")
                        GText(
                            text = "메인 모델, 비전 타워, 드래프터 관리",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                },
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
            modifier = Modifier
                .fillMaxSize()
        ) {
            LiquidBackground()
            Column(modifier = Modifier.fillMaxSize()) {
            // Quick actions live here (not in the top bar) so the title never squeezes
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GOutlineButton(
                    onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.weight(1f)
                ) {
                    GIcon(
                        imageVector = GIcons.SdCard,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    GText("기기 모델 가져오기", fontSize = 12.sp)
                }
                GButton(
                    onClick = { showFdmDialog = true },
                    modifier = Modifier.weight(1f)
                ) {
                    GIcon(
                        imageVector = GIcons.Add,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    GText("다운로드 링크 추가", fontSize = 13.sp)
                }
            }
            // Active FDM Download Monitor Widget (if downloading)
            val downloadingModel = models.firstOrNull { it.isDownloading }
            if (downloadingModel != null && activeDownloadStatus != null) {
                FdmActiveDownloadCard(
                    model = downloadingModel,
                    status = activeDownloadStatus!!,
                    onCancel = { viewModel.cancelDownload(downloadingModel.id) }
                )
            }

            // Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                GFilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { GText("전체 (${models.size})") }
                )
                GFilterChip(
                    selected = selectedFilter == "DOWNLOADED",
                    onClick = { selectedFilter = "DOWNLOADED" },
                    label = { GText("다운로드 완료 (${models.count { it.isDownloaded }})") }
                )
                GFilterChip(
                    selected = selectedFilter == "LLAMA_CPP",
                    onClick = { selectedFilter = "LLAMA_CPP" },
                    label = { GText("GGUF") }
                )
                GFilterChip(
                    selected = selectedFilter == "LITE_RT",
                    onClick = { selectedFilter = "LITE_RT" },
                    label = { GText("LiteRT") }
                )
            }

            // Device Storage & Model Footprint Monitor Card
            val modelsDir = remember { java.io.File(context.filesDir, "models") }
            val actualStorageUsedBytes = remember(models, activeDownloadStatus) {
                if (modelsDir.exists()) {
                    modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                } else 0L
            }
            val totalDeviceStorageBytes = remember {
                try {
                    val stat = android.os.StatFs(context.filesDir.path)
                    stat.totalBytes
                } catch (_: Exception) {
                    64_000_000_000L // 64 GB default fallback
                }
            }
            val availableDeviceStorageBytes = remember(actualStorageUsedBytes) {
                try {
                    val stat = android.os.StatFs(context.filesDir.path)
                    stat.availableBytes
                } catch (_: Exception) {
                    32_000_000_000L
                }
            }

            val storageUsedFormatted = remember(actualStorageUsedBytes) {
                val gb = actualStorageUsedBytes / (1024.0 * 1024.0 * 1024.0)
                if (gb >= 1.0) String.format("%.2f GB", gb)
                else String.format("%.1f MB", actualStorageUsedBytes / (1024.0 * 1024.0))
            }
            val availableStorageFormatted = remember(availableDeviceStorageBytes) {
                val gb = availableDeviceStorageBytes / (1024.0 * 1024.0 * 1024.0)
                String.format("%.1f GB", gb)
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Storage,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        GText(
                            text = "앱 모델 실제 사용 용량",
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.onSurface
                        )
                        GText(
                            text = storageUsedFormatted,
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        GText(
                            text = "실제 기기 저장소에 기록된 파일 크기",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                        GText(
                            text = "기기 여유: $availableStorageFormatted",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                }
            }

            GDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = GlassTheme.colors.outline.copy(alpha = 0.15f)
            )

            // Models List
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(filteredModels, key = { it.id }) { model ->
                    val isActive = model.id == activeModel?.id
                    ModelBundleCardItem(
                        model = model,
                        isActive = isActive,
                        onMount = { viewModel.selectModel(model) },
                        onDownload = { viewModel.downloadModel(model.id) },
                        onDelete = { viewModel.deleteModel(model.id) },
                        onToggleVision = { viewModel.toggleModelVision(model.id) },
                        onToggleDrafter = { viewModel.toggleModelDrafter(model.id) }
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            }
        }
    }

    // FDM Multi-Link Downloader Dialog (Supports Filename, LiteRT Template, Thinking/Reasoning, and Embedded Vision/Drafter)
    if (showFdmDialog) {
        FdmAddBundleDialog(
            context = context,
            initialHfToken = settings.hfToken,
            onDismiss = { showFdmDialog = false },
            onStartDownload = { name, runtime, mainUrl, customFileName, visionUrl, mtpUrl, templateUrl, supportsReasoning, hfToken, hasEmbeddedVision, hasEmbeddedDrafter ->
                viewModel.addCustomFdmBundle(
                    name = name,
                    runtime = runtime,
                    mainUrl = mainUrl,
                    customFileName = customFileName,
                    visionUrl = visionUrl,
                    mtpUrl = mtpUrl,
                    templateUrl = templateUrl,
                    supportsReasoning = supportsReasoning,
                    autoStartDownload = true,
                    hfToken = hfToken,
                    hasEmbeddedVision = hasEmbeddedVision,
                    hasEmbeddedDrafter = hasEmbeddedDrafter
                )
                showFdmDialog = false
            }
        )
    }
}

/**
 * FDM (Free Download Manager) Active Download Monitor
 * Displays transfer speed, ETA, unified progress, 8 multi-thread segments,
 * and individual component progress (Main, Vision Tower, MTP Drafter).
 */
@Composable
private fun FdmActiveDownloadCard(
    model: LlmModel,
    status: DownloadStatus,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTheme.colors.surfaceVariant)
            .border(1.dp, GlassTheme.colors.primary, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = GIcons.Speed,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    GText(
                        text = "다운로드 중",
                        style = GlassTheme.type.titleSmall,
                        color = GlassTheme.colors.primary
                    )
                    GText(
                        text = model.name,
                        style = GlassTheme.type.bodySmall
                    )
                }
            }
            GIconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                GIcon(
                    imageVector = GIcons.Close,
                    contentDescription = "취소",
                    tint = GlassTheme.colors.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Metrics: Speed, ETA, Total Progress %
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GText(
                text = "속도: ${status.speedText}",
                style = GlassTheme.type.labelMedium,
                color = GlassTheme.colors.primary
            )
            GText(
                text = if (status.etaSeconds > 0) "남은 시간: 약 ${status.etaSeconds}초" else "완료 중...",
                style = GlassTheme.type.labelMedium,
                color = GlassTheme.colors.onSurfaceVariant
            )
            val safeProgress = if (status.progress.isNaN() || status.progress < 0f) 0f else status.progress.coerceIn(0f, 1f)
            GText(
                text = String.format("%.0f%%", safeProgress * 100f),
                style = GlassTheme.type.titleSmall,
                color = GlassTheme.colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        val safeProgress = if (status.progress.isNaN() || status.progress < 0f) 0f else status.progress.coerceIn(0f, 1f)
        GLinearProgress(
            progress = safeProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Multi-segment progress visualizer
        GText(
            text = "분할 다운로드 진행 상태",
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.onSurfaceVariant,
            fontSize = 11.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            status.segments.forEach { segProgress ->
                val safeSeg = if (segProgress.isNaN() || segProgress < 0f) 0f else segProgress.coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassTheme.colors.outline.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(safeSeg)
                            .height(10.dp)
                            .background(GlassTheme.colors.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // 3-in-1 Components breakdown
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(GlassTheme.colors.surface.copy(alpha = 0.6f))
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Main weights
            ComponentProgressRow(
                title = "1. 메인 가중치 (${model.fileName.take(20)})",
                progress = status.mainProgress
            )
            // Vision Tower
            if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
                ComponentProgressRow(
                    title = "2. 비전 타워 (${model.mmprojFileName ?: "mmproj.gguf"})",
                    progress = status.visionProgress
                )
            }
            // MTP Drafter
            if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
                ComponentProgressRow(
                    title = "3. MTP 드래프터 (${model.mtpDrafterFileName ?: "drafter.gguf"})",
                    progress = status.mtpProgress
                )
            }
            // LiteRT Template
            if (model.templateFileName != null || model.templateFileUrl.isNotBlank() || model.runtimeType == ModelRuntimeType.LITE_RT) {
                ComponentProgressRow(
                    title = "4. 프롬프트 템플릿 (${model.templateFileName ?: "template.json"})",
                    progress = status.templateProgress
                )
            }
        }
    }
}

@Composable
private fun ComponentProgressRow(title: String, progress: Float) {
    val safeProgress = if (progress.isNaN() || progress < 0f) 0f else progress.coerceIn(0f, 1f)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        GText(
            text = title,
            style = GlassTheme.type.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            GLinearProgress(
                progress = safeProgress,
                modifier = Modifier
                    .width(80.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            GText(
                text = String.format("%.0f%%", safeProgress * 100f),
                style = GlassTheme.type.labelSmall,
                color = if (safeProgress >= 1f) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
            )
        }
    }
}

/**
 * Model Card item representing a unified bundled model.
 * When mounted, Main Model, mmproj Vision Tower, and MTP Drafter are loaded together!
 */
@Composable
private fun ModelBundleCardItem(
    model: LlmModel,
    isActive: Boolean,
    onMount: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onToggleVision: () -> Unit,
    onToggleDrafter: () -> Unit
) {
    val borderColor = if (isActive) GlassTheme.colors.primary else GlassTheme.colors.outline.copy(alpha = 0.2f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.4f))
            .border(if (isActive) 2.dp else 1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GText(
                        text = model.name,
                        style = GlassTheme.type.titleMedium,
                        color = GlassTheme.colors.onSurface
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        GIcon(
                            imageVector = GIcons.CheckCircle,
                            contentDescription = "동시 마운트 활성화",
                            tint = GlassTheme.colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                GText(
                    text = model.repoId,
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GlassTheme.colors.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                GText(
                    text = model.runtimeBadge,
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.onPrimaryContainer,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        GText(
            text = model.description,
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3-in-1 Unified Bundle Components Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Main weights / Unified package badge
            if (model.runtimeType == ModelRuntimeType.LITE_RT) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(GlassTheme.colors.tertiaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    GText("올인원 통합 모델", fontSize = 10.sp, color = GlassTheme.colors.onTertiaryContainer)
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(GlassTheme.colors.surface)
                        .border(0.5.dp, GlassTheme.colors.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    GText("메인 가중치", fontSize = 10.sp, color = GlassTheme.colors.onSurface)
                }
            }

            // Vision Tower badge
            if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(GlassTheme.colors.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIcon(
                            imageVector = GIcons.Visibility,
                            contentDescription = null,
                            tint = GlassTheme.colors.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        GText(
                            text = if (model.runtimeType == ModelRuntimeType.LITE_RT) "LiteRT 통합 비전"
                            else if (model.isVisionDownloaded) "비전 타워 포함" else "비전 타워 지원",
                            fontSize = 10.sp,
                            color = GlassTheme.colors.primary
                        )
                    }
                }
            }

            // MTP Drafter badge
            if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(GlassTheme.colors.secondary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIcon(
                            imageVector = GIcons.Bolt,
                            contentDescription = null,
                            tint = GlassTheme.colors.secondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        GText(
                            text = if (model.isMtpDownloaded) "드래프터 포함" else "드래프터 가속 지원",
                            fontSize = 10.sp,
                            color = GlassTheme.colors.secondary
                        )
                    }
                }
            }

            // LiteRT Template badge
            if (model.runtimeType == ModelRuntimeType.LITE_RT || model.templateFileName != null || model.templateFileUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(GlassTheme.colors.tertiary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIcon(
                            imageVector = GIcons.Layers,
                            contentDescription = null,
                            tint = GlassTheme.colors.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        GText(
                            text = if (model.isTemplateDownloaded || model.localTemplatePath != null) "템플릿 내장" else "LiteRT 템플릿",
                            fontSize = 10.sp,
                            color = GlassTheme.colors.tertiary
                        )
                    }
                }
            }

            // Thinking / Reasoning badge
            if (model.supportsReasoning) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(GlassTheme.colors.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIcon(
                            imageVector = GIcons.Psychology,
                            contentDescription = null,
                            tint = GlassTheme.colors.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        GText(
                            text = "Thinking 추론",
                            fontSize = 10.sp,
                            color = GlassTheme.colors.primary
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Manual Feature Toggles (Vision Tower & Speculative Drafter)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GFilterChip(
                selected = model.hasMmproj,
                onClick = onToggleVision,
                leadingIcon = {
                    GIcon(
                        imageVector = if (model.hasMmproj) GIcons.Visibility else GIcons.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (model.hasMmproj) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                    )
                },
                label = {
                    GText(
                        text = if (model.hasMmproj) "비전 타워 [ON]" else "비전 타워 [OFF]",
                        fontSize = 11.sp
                    )
                }
            )

            GFilterChip(
                selected = model.supportsMtp,
                onClick = onToggleDrafter,
                leadingIcon = {
                    GIcon(
                        imageVector = GIcons.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (model.supportsMtp) GlassTheme.colors.secondary else GlassTheme.colors.onSurfaceVariant
                    )
                },
                label = {
                    GText(
                        text = if (model.supportsMtp) "드래프터 [ON]" else "드래프터 [OFF]",
                        fontSize = 11.sp
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                GText(
                    text = "크기: ${model.displaySize}",
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                if (model.isDownloaded) {
                    val localBytes = remember(model.localFilePath, model.localMmprojPath, model.localMtpDrafterPath) {
                        var sum = 0L
                        model.localFilePath?.let { p -> val f = java.io.File(p); if (f.exists()) sum += f.length() }
                        model.localMmprojPath?.let { p -> val f = java.io.File(p); if (f.exists()) sum += f.length() }
                        model.localMtpDrafterPath?.let { p -> val f = java.io.File(p); if (f.exists()) sum += f.length() }
                        model.localTemplatePath?.let { p -> val f = java.io.File(p); if (f.exists()) sum += f.length() }
                        sum
                    }
                    val actualDiskFormatted = remember(localBytes) {
                        val gb = localBytes / (1024.0 * 1024.0 * 1024.0)
                        if (gb >= 1.0) String.format("%.2f GB", gb)
                        else String.format("%.1f MB", localBytes / (1024.0 * 1024.0))
                    }
                    GText(
                        text = "디스크 점유: $actualDiskFormatted",
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.primary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isDownloaded) {
                    GIconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        GIcon(
                            imageVector = GIcons.Delete,
                            contentDescription = "삭제",
                            tint = GlassTheme.colors.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    GButton(
                        onClick = onMount,
                        containerColor = if (isActive) GlassTheme.colors.secondary else GlassTheme.colors.primary,
                        contentColor = if (isActive) GlassTheme.colors.onSecondary else GlassTheme.colors.onPrimary
                    ) {
                        GIcon(
                            imageVector = GIcons.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GText(if (isActive) "실행 중" else "실행")
                    }
                } else {
                    GButton(
                        onClick = onDownload,
                        enabled = !model.isDownloading
                    ) {
                        GIcon(
                            imageVector = GIcons.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GText(if (model.isDownloading) "다운로드 중..." else "다운로드")
                    }
                }
            }
        }
    }
}

/**
 * FDM (Free Download Manager) Style Direct Link 3-in-1 Bundle Dialog
 * Lets the user input:
 * 1. Main model download link
 * 2. Vision tower mmproj download link
 * 3. MTP Drafter download link
 * and download them all together as a single unified bundle!
 */
@Composable
private fun FdmAddBundleDialog(
    context: Context,
    initialHfToken: String = "",
    onDismiss: () -> Unit,
    onStartDownload: (
        name: String,
        runtime: ModelRuntimeType,
        mainUrl: String,
        customFileName: String,
        visionUrl: String,
        mtpUrl: String,
        templateUrl: String,
        supportsReasoning: Boolean,
        hfToken: String,
        hasEmbeddedVision: Boolean,
        hasEmbeddedDrafter: Boolean
    ) -> Unit
) {
    var modelNameInput by remember { mutableStateOf("") }
    var runtimeChoice by remember { mutableStateOf(ModelRuntimeType.LLAMA_CPP) }
    var mainUrlInput by remember { mutableStateOf("") }
    var customFileNameInput by remember { mutableStateOf("") }
    var visionUrlInput by remember { mutableStateOf("") }
    var mtpUrlInput by remember { mutableStateOf("") }
    var templateUrlInput by remember { mutableStateOf("") }
    var supportsReasoningInput by remember { mutableStateOf(false) }
    var hasEmbeddedVisionInput by remember { mutableStateOf(false) }
    var hasEmbeddedDrafterInput by remember { mutableStateOf(false) }
    var hfTokenInput by remember { mutableStateOf(initialHfToken) }
    var isTokenVisible by remember { mutableStateOf(false) }

    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    GDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                GText("커스텀 모델 링크 다운로드")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GText(
                    text = "직접 파일명을 지정할 수 있으며, LiteRT 모델의 템플릿 파일 다운로드 및 띵킹(사고 과정) 기능을 설정할 수 있습니다.",
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )

                // Fast Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GOutlineButton(
                        onClick = {
                            modelNameInput = "DeepSeek R1 (GGUF)"
                            runtimeChoice = ModelRuntimeType.LLAMA_CPP
                            mainUrlInput = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
                            customFileNameInput = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = ""
                            hasEmbeddedVisionInput = false
                            supportsReasoningInput = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        GText("DeepSeek R1", fontSize = 11.sp)
                    }
                    GOutlineButton(
                        onClick = {
                            modelNameInput = "Gemma 3 1B (LiteRT 통합 비전)"
                            runtimeChoice = ModelRuntimeType.LITE_RT
                            mainUrlInput = "https://huggingface.co/lotapa/gemma3-1b-it-int4.litertlm/resolve/main/gemma3-1b-it-int4.litertlm"
                            customFileNameInput = "gemma3-1b-it-int4.litertlm"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = ""
                            hasEmbeddedVisionInput = true
                            supportsReasoningInput = false
                        },
                        modifier = Modifier.weight(1.1f)
                    ) {
                        GText("LiteRT 통합 비전", fontSize = 11.sp)
                    }
                    GOutlineButton(
                        onClick = {
                            modelNameInput = "Phi-4 Mini (LiteRT)"
                            runtimeChoice = ModelRuntimeType.LITE_RT
                            mainUrlInput = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/phi-4-mini-instruct-gpu.bin"
                            customFileNameInput = "phi-4-mini-instruct-gpu.bin"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = "https://huggingface.co/litert-community/Phi-4-mini-instruct/raw/main/tokenizer_config.json"
                            hasEmbeddedVisionInput = false
                            supportsReasoningInput = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        GText("LiteRT 템플릿", fontSize = 11.sp)
                    }
                }

                GTextField(
                    value = modelNameInput,
                    onValueChange = { modelNameInput = it },
                    label = { GText("모델 이름") },
                    placeholder = { GText("미입력 시 파일명 자동 사용") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GText("런타임 엔진", style = GlassTheme.type.bodyMedium)
                    Row {
                        GFilterChip(
                            selected = runtimeChoice == ModelRuntimeType.LLAMA_CPP,
                            onClick = { runtimeChoice = ModelRuntimeType.LLAMA_CPP },
                            label = { GText("llama.cpp") }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GFilterChip(
                            selected = runtimeChoice == ModelRuntimeType.LITE_RT,
                            onClick = { runtimeChoice = ModelRuntimeType.LITE_RT },
                            label = { GText("LiteRT LM") }
                        )
                    }
                }

                if (runtimeChoice == ModelRuntimeType.LITE_RT) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassTheme.colors.tertiaryContainer.copy(alpha = 0.4f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        GText(
                            text = "💡 Google LiteRT 모델은 비전 타워(Vision Encoder), 토크나이저, 프롬프트 템플릿이 단일 바이너리(.litertlm / .bin)에 통합 패키징된 올인원 구조입니다.",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onTertiaryContainer
                        )
                    }
                }

                // Custom filename input
                GTextField(
                    value = customFileNameInput,
                    onValueChange = { customFileNameInput = it },
                    label = { GText("저장 파일명 (선택)") },
                    placeholder = {
                        GText(if (runtimeChoice == ModelRuntimeType.LITE_RT) "custom_model.bin" else "custom_model.gguf")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 1. Main Model URL (Required)
                GTextField(
                    value = mainUrlInput,
                    onValueChange = {
                        mainUrlInput = it
                        if (customFileNameInput.isBlank() && it.contains('/')) {
                            val derived = it.trim().substringAfterLast('/')
                            if (derived.isNotBlank()) customFileNameInput = derived
                        }
                    },
                    label = { GText("메인 모델 다운로드 링크 [필수]") },
                    placeholder = { GText("https://.../model.gguf") },
                    trailingIcon = {
                        GIconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) {
                                mainUrlInput = clip
                                if (customFileNameInput.isBlank() && clip.contains('/')) {
                                    customFileNameInput = clip.trim().substringAfterLast('/')
                                }
                            }
                        }) {
                            GIcon(GIcons.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Hugging Face Access Token (Optional for gated / private models)
                GTextField(
                    value = hfTokenInput,
                    onValueChange = { hfTokenInput = it },
                    label = { GText("Hugging Face 토큰 (선택: Gated/비공개 모델)") },
                    placeholder = { GText("hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                GIcon(
                                    imageVector = if (isTokenVisible) GIcons.VisibilityOff else GIcons.Visibility,
                                    contentDescription = if (isTokenVisible) "숨기기" else "보기"
                                )
                            }
                            GIconButton(onClick = {
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) hfTokenInput = clip.trim()
                            }) {
                                GIcon(GIcons.ContentPaste, contentDescription = "붙여넣기")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // LiteRT Template file URL (Jinja / JSON)
                GTextField(
                    value = templateUrlInput,
                    onValueChange = { templateUrlInput = it },
                    label = { GText("LiteRT Jinja/JSON 템플릿 파일 링크" + if (runtimeChoice == ModelRuntimeType.LITE_RT) " [권장]" else " (선택)") },
                    placeholder = { GText("https://.../chat_template.jinja 또는 tokenizer_config.json") },
                    trailingIcon = {
                        GIconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) templateUrlInput = clip
                        }) {
                            GIcon(GIcons.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Thinking / Reasoning mode toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(
                                imageVector = GIcons.Psychology,
                                contentDescription = null,
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            GText(
                                text = "사고 과정 (Thinking/추론) 활성화",
                                style = GlassTheme.type.bodyMedium
                            )
                        }
                        GText(
                            text = "<think> 태그 또는 추론 단계 펼침/접기 UI 지원",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                    GSwitch(
                        checked = supportsReasoningInput,
                        onCheckedChange = { supportsReasoningInput = it }
                    )
                }

                // Embedded Vision Tower toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(
                                imageVector = GIcons.Visibility,
                                contentDescription = null,
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            GText(
                                text = "내장 비전 타워 (Vision Tower)",
                                style = GlassTheme.type.bodyMedium
                            )
                        }
                        GText(
                            text = "단일 GGUF 파일 내부에 비전 가중치 내장 (Qwen2-VL, Llava 등)",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                    GSwitch(
                        checked = hasEmbeddedVisionInput,
                        onCheckedChange = { hasEmbeddedVisionInput = it }
                    )
                }

                // Embedded Speculative Drafter (MTP) toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(
                                imageVector = GIcons.Bolt,
                                contentDescription = null,
                                tint = GlassTheme.colors.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            GText(
                                text = "내장 추측 디코딩 드래프터 (MTP)",
                                style = GlassTheme.type.bodyMedium
                            )
                        }
                        GText(
                            text = "단일 GGUF 파일 내부에 드래프터 헤드 내장 (DeepSeek-V3 MTP 등)",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                    GSwitch(
                        checked = hasEmbeddedDrafterInput,
                        onCheckedChange = { hasEmbeddedDrafterInput = it }
                    )
                }

                // 2. Vision Tower URL (Optional)
                GTextField(
                    value = visionUrlInput,
                    onValueChange = { visionUrlInput = it },
                    label = { GText("비전 타워 다운로드 링크 (선택)") },
                    placeholder = { GText("https://.../mmproj.gguf") },
                    trailingIcon = {
                        GIconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) visionUrlInput = clip
                        }) {
                            GIcon(GIcons.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. MTP Drafter URL (Optional)
                GTextField(
                    value = mtpUrlInput,
                    onValueChange = { mtpUrlInput = it },
                    label = { GText("드래프터 다운로드 링크 (선택)") },
                    placeholder = { GText("https://.../draft.gguf") },
                    trailingIcon = {
                        GIconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) mtpUrlInput = clip
                        }) {
                            GIcon(GIcons.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            GButton(
                onClick = {
                    if (mainUrlInput.isNotBlank()) {
                        onStartDownload(
                            modelNameInput.trim(),
                            runtimeChoice,
                            mainUrlInput.trim(),
                            customFileNameInput.trim(),
                            visionUrlInput.trim(),
                            mtpUrlInput.trim(),
                            templateUrlInput.trim(),
                            supportsReasoningInput,
                            hfTokenInput.trim(),
                            hasEmbeddedVisionInput,
                            hasEmbeddedDrafterInput
                        )
                    }
                },
                enabled = mainUrlInput.isNotBlank()
            ) {
                GIcon(GIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                GText("다운로드 시작")
            }
        },
        dismissButton = {
            GTextButton(onClick = onDismiss) {
                GText("닫기")
            }
        }
    )
}

private fun queryFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) {
                    result = cursor.getString(index)
                }
            }
        }
    }
    if (result == null) {
        result = uri.path?.let { p ->
            val cut = p.lastIndexOf('/')
            if (cut != -1) p.substring(cut + 1) else p
        }
    }
    return result
}
