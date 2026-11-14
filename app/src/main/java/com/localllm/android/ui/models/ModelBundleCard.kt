package com.localllm.android.ui.models

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme
import java.io.File
import java.util.Locale

@Composable
internal fun ModelBundleCardItem(
    model: LlmModel,
    isActive: Boolean,
    onMount: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
    onToggleVision: () -> Unit,
    onToggleDrafter: () -> Unit,
    @Suppress("UNUSED_PARAMETER") onRuntimeChange: (ModelRuntimeType) -> Unit
) {
    val borderColor = if (isActive) GlassTheme.colors.primary else GlassTheme.colors.outline

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTheme.colors.surface)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isActive) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(GlassTheme.colors.tertiary)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    GText(
                        text = model.name,
                        style = GlassTheme.type.titleMedium,
                        color = GlassTheme.colors.onSurface
                    )
                }
                GText(
                    text = model.repoId,
                    style = GlassTheme.type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(GlassTheme.colors.surfaceVariant)
                    .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                GText(
                    text = model.runtimeBadge,
                    style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    color = GlassTheme.colors.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        GText(
            text = modelDescriptionText(model),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            CapabilityTag(
                label = if (model.runtimeType == ModelRuntimeType.LITE_RT) {
                    stringResource(R.string.model_manager_all_in_one)
                } else {
                    stringResource(R.string.model_manager_main_weights)
                }
            )

            if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
                CapabilityTag(
                    icon = GIcons.Visibility,
                    label = when {
                        model.supportsNativeAudioInput ->
                            if (model.isVisionDownloaded) "Omni projector ready" else "Omni projector"
                        model.runtimeType == ModelRuntimeType.LITE_RT ->
                            stringResource(R.string.model_manager_litert_vision)
                        model.isVisionDownloaded ->
                            stringResource(R.string.model_manager_vision_included)
                        else ->
                            stringResource(R.string.model_manager_vision_supported)
                    }
                )
            }

            if (model.supportsNativeAudioInput && model.supportsNativeAudioOutput) {
                CapabilityTag(icon = GIcons.RecordVoiceOver, label = "Native Voice")
            }

            if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
                CapabilityTag(
                    icon = GIcons.Bolt,
                    label = if (model.isMtpDownloaded) stringResource(R.string.model_manager_drafter_included)
                    else stringResource(R.string.model_manager_drafter_supported)
                )
            }

            if (model.supportsReasoning) {
                CapabilityTag(
                    icon = GIcons.Psychology,
                    label = stringResource(R.string.model_manager_thinking)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                        tint = GlassTheme.colors.onSurfaceVariant
                    )
                },
                label = {
                    GText(
                        text = if (model.hasMmproj) stringResource(R.string.model_manager_vision_on)
                        else stringResource(R.string.model_manager_vision_off),
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
                        tint = GlassTheme.colors.onSurfaceVariant
                    )
                },
                label = {
                    GText(
                        text = if (model.supportsMtp) stringResource(R.string.model_manager_drafter_on)
                        else stringResource(R.string.model_manager_drafter_off),
                        fontSize = 11.sp
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                GText(
                    text = stringResource(R.string.model_manager_size, model.displaySize),
                    style = GlassTheme.type.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = GlassTheme.colors.onSurfaceVariant
                )
                if (model.isDownloaded) {
                    val localBytes = remember(model.localFilePath, model.localMmprojPath, model.localMtpDrafterPath, model.localTemplatePath) {
                        listOfNotNull(model.localFilePath, model.localMmprojPath, model.localMtpDrafterPath, model.localTemplatePath)
                            .sumOf { path -> File(path).takeIf { it.exists() }?.length() ?: 0L }
                    }
                    val actualDiskFormatted = remember(localBytes) { formatDiskBytes(localBytes) }
                    GText(
                        text = stringResource(R.string.model_manager_disk_usage, actualDiskFormatted),
                        style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = GlassTheme.colors.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (model.isDownloaded) {
                    GIconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(36.dp)
                    ) {
                        GIcon(
                            imageVector = GIcons.Delete,
                            contentDescription = stringResource(R.string.delete),
                            tint = GlassTheme.colors.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    GButton(
                        onClick = onMount,
                        containerColor = if (isActive) GlassTheme.colors.surfaceVariant else GlassTheme.colors.primary,
                        contentColor = if (isActive) GlassTheme.colors.onSurface else GlassTheme.colors.onPrimary
                    ) {
                        GIcon(
                            imageVector = GIcons.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GText(if (isActive) stringResource(R.string.model_manager_running) else stringResource(R.string.run_model))
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
                        GText(if (model.isDownloading) stringResource(R.string.downloading) else stringResource(R.string.download))
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityTag(
    label: String,
    icon: ImageVector? = null
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(GlassTheme.colors.surfaceVariant)
            .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                GIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            GText(
                text = label,
                fontSize = 10.sp,
                color = GlassTheme.colors.onSurfaceVariant
            )
        }
    }
}

internal fun formatDiskBytes(bytes: Long): String {
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format(Locale.US, "%.2f GB", gb)
    else String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

private val MODEL_DESC_RES: Map<String, Int> = mapOf(
    "smollm2-360m-instruct-gguf" to R.string.model_desc_smollm2_360m,
    "qwen2.5-0.5b-instruct-gguf" to R.string.model_desc_qwen05b,
    "llama-3.2-1b-instruct-gguf" to R.string.model_desc_llama1b,
    "deepseek-r1-distill-qwen-1.5b-gguf" to R.string.model_desc_ds_r1_15b,
    "qwen2.5-1.5b-instruct-gguf" to R.string.model_desc_qwen15b,
    "smollm2-1.7b-instruct-gguf" to R.string.model_desc_smollm2_17b,
    "gemma-2-2b-it-gguf" to R.string.model_desc_gemma2_2b,
    "qwen2-vl-2b-instruct-gguf" to R.string.model_desc_qwen2vl,
    "qwen2.5-coder-1.5b-litert" to R.string.model_desc_coder_lr,
    "gemma-3-1b-it-litert" to R.string.model_desc_gemma3_lr,
    "functiongemma-mobile-actions-litert" to R.string.model_desc_funcgemma_lr
)

@Composable
internal fun modelDescriptionText(model: LlmModel): String {
    val resId = MODEL_DESC_RES[model.id]
    return if (resId != null) stringResource(resId) else model.description
}
