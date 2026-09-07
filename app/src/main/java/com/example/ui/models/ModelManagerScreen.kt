package com.example.ui.models

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SdCard
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.engine.DownloadStatus
import com.example.model.LlmModel
import com.example.model.ModelRuntimeType
import com.example.ui.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("모델 관리")
                        Text(
                            text = "메인 모델, 비전 타워, 드래프터 관리",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.navigateTo("chat") }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "뒤로가기"
                        )
                    }
                },
                actions = {
                    OutlinedButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.SdCard,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("기기 GGUF 가져오기", fontSize = 12.sp)
                    }

                    Button(
                        onClick = { showFdmDialog = true },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("다운로드 링크 추가", fontSize = 13.sp)
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
        ) {
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
                FilterChip(
                    selected = selectedFilter == "ALL",
                    onClick = { selectedFilter = "ALL" },
                    label = { Text("전체 (${models.size})") }
                )
                FilterChip(
                    selected = selectedFilter == "DOWNLOADED",
                    onClick = { selectedFilter = "DOWNLOADED" },
                    label = { Text("다운로드 완료 (${models.count { it.isDownloaded }})") }
                )
                FilterChip(
                    selected = selectedFilter == "LLAMA_CPP",
                    onClick = { selectedFilter = "LLAMA_CPP" },
                    label = { Text("GGUF") }
                )
                FilterChip(
                    selected = selectedFilter == "LITE_RT",
                    onClick = { selectedFilter = "LITE_RT" },
                    label = { Text("LiteRT") }
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
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Storage,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "앱 모델 실제 사용 용량",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = storageUsedFormatted,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "실제 기기 저장소에 기록된 파일 크기",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "기기 여유: $availableStorageFormatted",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 6.dp),
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f)
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
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Speed,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "다운로드 중",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            IconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "취소",
                    tint = MaterialTheme.colorScheme.error,
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
            Text(
                text = "속도: ${status.speedText}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (status.etaSeconds > 0) "남은 시간: 약 ${status.etaSeconds}초" else "완료 중...",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val safeProgress = if (status.progress.isNaN() || status.progress < 0f) 0f else status.progress.coerceIn(0f, 1f)
            Text(
                text = String.format("%.0f%%", safeProgress * 100f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        val safeProgress = if (status.progress.isNaN() || status.progress < 0f) 0f else status.progress.coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { safeProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Multi-segment progress visualizer
        Text(
            text = "분할 다운로드 진행 상태",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(safeSeg)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.primary)
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
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
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
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(
                progress = { safeProgress },
                modifier = Modifier
                    .width(80.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = String.format("%.0f%%", safeProgress * 100f),
                style = MaterialTheme.typography.labelSmall,
                color = if (safeProgress >= 1f) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
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
    val borderColor = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
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
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "동시 마운트 활성화",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = model.repoId,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = model.runtimeBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = model.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        // 3-in-1 Unified Bundle Components Badges
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Main weights badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("메인 가중치", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface)
            }

            // Vision Tower badge
            if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (model.isVisionDownloaded) "비전 타워 포함" else "비전 타워 지원",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // MTP Drafter badge
            if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (model.isMtpDownloaded) "드래프터 포함" else "드래프터 가속 지원",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                }
            }

            // LiteRT Template badge
            if (model.runtimeType == ModelRuntimeType.LITE_RT || model.templateFileName != null || model.templateFileUrl.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Layers,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (model.isTemplateDownloaded || model.localTemplatePath != null) "템플릿 내장" else "LiteRT 템플릿",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
            }

            // Thinking / Reasoning badge
            if (model.supportsReasoning) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "Thinking 추론",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.primary
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
            FilterChip(
                selected = model.hasMmproj,
                onClick = onToggleVision,
                leadingIcon = {
                    Icon(
                        imageVector = if (model.hasMmproj) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (model.hasMmproj) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                label = {
                    Text(
                        text = if (model.hasMmproj) "비전 타워 [ON]" else "비전 타워 [OFF]",
                        fontSize = 11.sp
                    )
                }
            )

            FilterChip(
                selected = model.supportsMtp,
                onClick = onToggleDrafter,
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = if (model.supportsMtp) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                label = {
                    Text(
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
                Text(
                    text = "크기: ${model.displaySize}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    Text(
                        text = "디스크 점유: $actualDiskFormatted",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isDownloaded) {
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "삭제",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Button(
                        onClick = onMount,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isActive) "실행 중" else "실행")
                    }
                } else {
                    Button(
                        onClick = onDownload,
                        enabled = !model.isDownloading,
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (model.isDownloading) "다운로드 중..." else "다운로드")
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("커스텀 모델 링크 다운로드")
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "직접 파일명을 지정할 수 있으며, LiteRT 모델의 템플릿 파일 다운로드 및 띵킹(사고 과정) 기능을 설정할 수 있습니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Fast Presets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            modelNameInput = "DeepSeek R1 1.5B (Thinking)"
                            runtimeChoice = ModelRuntimeType.LLAMA_CPP
                            mainUrlInput = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
                            customFileNameInput = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = ""
                            supportsReasoningInput = true
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("DeepSeek R1", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            modelNameInput = "Phi-4 Mini LiteRT"
                            runtimeChoice = ModelRuntimeType.LITE_RT
                            mainUrlInput = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/phi-4-mini-instruct-gpu.bin"
                            customFileNameInput = "phi-4-mini-instruct-gpu.bin"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = "https://huggingface.co/litert-community/Phi-4-mini-instruct/raw/main/tokenizer_config.json"
                            supportsReasoningInput = true
                        },
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("LiteRT 템플릿", fontSize = 11.sp)
                    }
                }

                OutlinedTextField(
                    value = modelNameInput,
                    onValueChange = { modelNameInput = it },
                    label = { Text("모델 이름") },
                    placeholder = { Text("미입력 시 파일명 자동 사용") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("런타임 엔진", style = MaterialTheme.typography.bodyMedium)
                    Row {
                        FilterChip(
                            selected = runtimeChoice == ModelRuntimeType.LLAMA_CPP,
                            onClick = { runtimeChoice = ModelRuntimeType.LLAMA_CPP },
                            label = { Text("llama.cpp") }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        FilterChip(
                            selected = runtimeChoice == ModelRuntimeType.LITE_RT,
                            onClick = { runtimeChoice = ModelRuntimeType.LITE_RT },
                            label = { Text("LiteRT LM") }
                        )
                    }
                }

                // Custom filename input
                OutlinedTextField(
                    value = customFileNameInput,
                    onValueChange = { customFileNameInput = it },
                    label = { Text("저장 파일명 (선택)") },
                    placeholder = {
                        Text(if (runtimeChoice == ModelRuntimeType.LITE_RT) "custom_model.bin" else "custom_model.gguf")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 1. Main Model URL (Required)
                OutlinedTextField(
                    value = mainUrlInput,
                    onValueChange = {
                        mainUrlInput = it
                        if (customFileNameInput.isBlank() && it.contains('/')) {
                            val derived = it.trim().substringAfterLast('/')
                            if (derived.isNotBlank()) customFileNameInput = derived
                        }
                    },
                    label = { Text("메인 모델 다운로드 링크 [필수]") },
                    placeholder = { Text("https://.../model.gguf") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) {
                                mainUrlInput = clip
                                if (customFileNameInput.isBlank() && clip.contains('/')) {
                                    customFileNameInput = clip.trim().substringAfterLast('/')
                                }
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. Hugging Face Access Token (Optional for gated / private models)
                OutlinedTextField(
                    value = hfTokenInput,
                    onValueChange = { hfTokenInput = it },
                    label = { Text("Hugging Face 토큰 (선택: Gated/비공개 모델)") },
                    placeholder = { Text("hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                Icon(
                                    imageVector = if (isTokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (isTokenVisible) "숨기기" else "보기"
                                )
                            }
                            IconButton(onClick = {
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) hfTokenInput = clip.trim()
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "붙여넣기")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // LiteRT Template file URL (Jinja / JSON)
                OutlinedTextField(
                    value = templateUrlInput,
                    onValueChange = { templateUrlInput = it },
                    label = { Text("LiteRT Jinja/JSON 템플릿 파일 링크" + if (runtimeChoice == ModelRuntimeType.LITE_RT) " [권장]" else " (선택)") },
                    placeholder = { Text("https://.../chat_template.jinja 또는 tokenizer_config.json") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) templateUrlInput = clip
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Thinking / Reasoning mode toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Psychology,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "사고 과정 (Thinking/추론) 활성화",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "<think> 태그 또는 추론 단계 펼침/접기 UI 지원",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = supportsReasoningInput,
                        onCheckedChange = { supportsReasoningInput = it }
                    )
                }

                // Embedded Vision Tower toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "내장 비전 타워 (Vision Tower)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "단일 GGUF 파일 내부에 비전 가중치 내장 (Qwen2-VL, Llava 등)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hasEmbeddedVisionInput,
                        onCheckedChange = { hasEmbeddedVisionInput = it }
                    )
                }

                // Embedded Speculative Drafter (MTP) toggle
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "내장 추측 디코딩 드래프터 (MTP)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Text(
                            text = "단일 GGUF 파일 내부에 드래프터 헤드 내장 (DeepSeek-V3 MTP 등)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = hasEmbeddedDrafterInput,
                        onCheckedChange = { hasEmbeddedDrafterInput = it }
                    )
                }

                // 2. Vision Tower URL (Optional)
                OutlinedTextField(
                    value = visionUrlInput,
                    onValueChange = { visionUrlInput = it },
                    label = { Text("비전 타워 다운로드 링크 (선택)") },
                    placeholder = { Text("https://.../mmproj.gguf") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) visionUrlInput = clip
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. MTP Drafter URL (Optional)
                OutlinedTextField(
                    value = mtpUrlInput,
                    onValueChange = { mtpUrlInput = it },
                    label = { Text("드래프터 다운로드 링크 (선택)") },
                    placeholder = { Text("https://.../draft.gguf") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank()) mtpUrlInput = clip
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "붙여넣기")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
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
                Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("다운로드 시작")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기")
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
