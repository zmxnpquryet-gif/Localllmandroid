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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.engine.DownloadStatus
import com.localllm.android.engine.IntegrityNote
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GLinearProgress
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme
import java.util.Locale

@Composable
internal fun FdmActiveDownloadCard(
    model: LlmModel,
    status: DownloadStatus,
    onCancel: () -> Unit
) {
    val safeProgress = if (status.progress.isNaN() || status.progress < 0f) 0f else status.progress.coerceIn(0f, 1f)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTheme.colors.surface)
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
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    GText(
                        text = stringResource(R.string.model_manager_downloading_title),
                        style = GlassTheme.type.titleSmall,
                        color = GlassTheme.colors.onSurface
                    )
                    GText(
                        text = model.name,
                        style = GlassTheme.type.bodySmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }
            GIconButton(onClick = onCancel, modifier = Modifier.size(28.dp)) {
                GIcon(
                    imageVector = GIcons.Close,
                    contentDescription = stringResource(R.string.cancel),
                    tint = GlassTheme.colors.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GText(
                text = stringResource(R.string.model_manager_speed, status.speedText),
                style = GlassTheme.type.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.primary
            )
            GText(
                text = if (status.etaSeconds > 0) stringResource(R.string.model_manager_eta, status.etaSeconds)
                else stringResource(R.string.model_manager_finishing),
                style = GlassTheme.type.labelMedium.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.onSurfaceVariant
            )
            GText(
                text = String.format(Locale.US, "%.0f%%", safeProgress * 100f),
                style = GlassTheme.type.titleSmall.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.onSurface
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        GLinearProgress(
            progress = safeProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
        )

        Spacer(modifier = Modifier.height(8.dp))

        GText(
            text = stringResource(R.string.model_manager_segments),
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
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(GlassTheme.colors.surfaceVariant)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(safeSeg)
                            .height(8.dp)
                            .background(GlassTheme.colors.primary)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(GlassTheme.colors.surfaceVariant)
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ComponentProgressRow(
                title = stringResource(R.string.model_manager_comp_main, model.fileName.take(20)),
                progress = status.mainProgress
            )
            if (model.hasMmproj || model.visionTowerUrl.isNotBlank()) {
                ComponentProgressRow(
                    title = stringResource(R.string.model_manager_comp_vision, model.mmprojFileName ?: "mmproj.gguf"),
                    progress = status.visionProgress
                )
            }
            if (model.supportsMtp || model.mtpDrafterUrl.isNotBlank()) {
                ComponentProgressRow(
                    title = stringResource(R.string.model_manager_comp_mtp, model.mtpDrafterFileName ?: "drafter.gguf"),
                    progress = status.mtpProgress
                )
            }
            if (model.templateFileName != null || model.templateFileUrl.isNotBlank() || model.runtimeType == ModelRuntimeType.LITE_RT) {
                ComponentProgressRow(
                    title = stringResource(R.string.model_manager_comp_template, model.templateFileName ?: "template.json"),
                    progress = status.templateProgress
                )
            }
        }

        if (status.integrityNote != IntegrityNote.NONE) {
            Spacer(modifier = Modifier.height(8.dp))
            GText(
                text = stringResource(
                    if (status.integrityNote == IntegrityNote.VERIFIED) R.string.model_manager_integrity_verified
                    else R.string.model_manager_integrity_unverified
                ),
                style = GlassTheme.type.labelSmall,
                color = if (status.integrityNote == IntegrityNote.VERIFIED) GlassTheme.colors.primary
                else GlassTheme.colors.onSurfaceVariant,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
internal fun ComponentProgressRow(title: String, progress: Float) {
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
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
            )
            Spacer(modifier = Modifier.width(6.dp))
            GText(
                text = String.format(Locale.US, "%.0f%%", safeProgress * 100f),
                style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (safeProgress >= 1f) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
            )
        }
    }
}
