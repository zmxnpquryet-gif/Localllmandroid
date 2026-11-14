package com.localllm.android.ui.models

import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.android.R
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GDivider
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GOutlineButton
import com.localllm.android.ui.glass.GScaffold
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTopBar
import com.localllm.android.ui.glass.GlassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun ModelManagerScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val models by viewModel.models.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()
    val activeDownloadStatus by viewModel.activeDownloadStatus.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    var showFdmDialog by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("ALL") }

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
                        GText(stringResource(R.string.model_manager_title))
                        GText(
                            text = stringResource(R.string.model_manager_subtitle),
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
                }
            )
        },
        modifier = modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(GlassTheme.colors.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                        GIcon(imageVector = GIcons.SdCard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        GText(stringResource(R.string.import_device_model), fontSize = 12.sp)
                    }
                    GButton(
                        onClick = { showFdmDialog = true },
                        modifier = Modifier.weight(1f)
                    ) {
                        GIcon(imageVector = GIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        GText(stringResource(R.string.add_custom_download_url), fontSize = 13.sp)
                    }
                }

                val downloadingModel = models.firstOrNull { it.isDownloading }
                if (downloadingModel != null && activeDownloadStatus != null) {
                    FdmActiveDownloadCard(
                        model = downloadingModel,
                        status = activeDownloadStatus!!,
                        onCancel = { viewModel.cancelDownload(downloadingModel.id) }
                    )
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GFilterChip(
                        selected = selectedFilter == "ALL",
                        onClick = { selectedFilter = "ALL" },
                        label = { GText(stringResource(R.string.model_manager_tab_all_count, models.size)) }
                    )
                    GFilterChip(
                        selected = selectedFilter == "DOWNLOADED",
                        onClick = { selectedFilter = "DOWNLOADED" },
                        label = { GText(stringResource(R.string.model_manager_tab_downloaded_count, models.count { it.isDownloaded })) }
                    )
                    GFilterChip(
                        selected = selectedFilter == "LLAMA_CPP",
                        onClick = { selectedFilter = "LLAMA_CPP" },
                        label = { GText(stringResource(R.string.tab_gguf)) }
                    )
                    GFilterChip(
                        selected = selectedFilter == "LITE_RT",
                        onClick = { selectedFilter = "LITE_RT" },
                        label = { GText(stringResource(R.string.tab_litert)) }
                    )
                }

                StorageSummaryStrip(
                    filesDir = context.filesDir,
                    modelsKey = models,
                    completedSignal = activeDownloadStatus?.isCompleted == true
                )

                GDivider(
                    modifier = Modifier.padding(vertical = 6.dp),
                    color = GlassTheme.colors.outline
                )

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        ModelBundleCardItem(
                            model = model,
                            isActive = model.id == activeModel?.id,
                            onMount = { viewModel.selectModel(model) },
                            onDownload = { viewModel.downloadModel(model.id) },
                            onDelete = { viewModel.deleteModel(model.id) },
                            onToggleVision = { viewModel.toggleModelVision(model.id) },
                            onToggleDrafter = { viewModel.toggleModelDrafter(model.id) },
                            onRuntimeChange = { viewModel.setModelRuntime(model.id, it) }
                        )
                    }
                    item { Spacer(modifier = Modifier.height(24.dp)) }
                }
            }
        }
    }

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

@Composable
private fun StorageSummaryStrip(
    filesDir: File,
    modelsKey: Any,
    completedSignal: Boolean
) {
    val modelsDir = remember(filesDir) { File(filesDir, "models") }
    var storageTick by remember { mutableStateOf(0) }
    LaunchedEffect(completedSignal) {
        if (completedSignal) storageTick++
    }
    val actualStorageUsedBytes by produceState(0L, modelsKey, storageTick) {
        value = withContext(Dispatchers.IO) {
            if (modelsDir.exists()) modelsDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        }
    }
    val availableDeviceStorageBytes = remember(actualStorageUsedBytes) {
        try {
            StatFs(filesDir.path).availableBytes
        } catch (_: Exception) {
            32_000_000_000L
        }
    }

    val storageUsedFormatted = remember(actualStorageUsedBytes) { formatDiskBytes(actualStorageUsedBytes) }
    val availableStorageFormatted = remember(availableDeviceStorageBytes) {
        String.format(Locale.US, "%.1f GB", availableDeviceStorageBytes / (1024.0 * 1024.0 * 1024.0))
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(GlassTheme.colors.surface)
            .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GIcon(
            imageVector = GIcons.Storage,
            contentDescription = null,
            tint = GlassTheme.colors.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GText(
                    text = stringResource(R.string.storage_usage),
                    style = GlassTheme.type.titleSmall,
                    color = GlassTheme.colors.onSurface
                )
                GText(
                    text = storageUsedFormatted,
                    style = GlassTheme.type.titleSmall.copy(fontFamily = FontFamily.Monospace),
                    color = GlassTheme.colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                GText(
                    text = stringResource(R.string.model_manager_storage_detail),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
                GText(
                    text = stringResource(R.string.model_manager_device_free, availableStorageFormatted),
                    style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }
        }
    }
}
