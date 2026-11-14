package com.localllm.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.localllm.android.model.LlmModel
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.ui.glass.OffconMark

@Composable
internal fun EmptyChatPlaceholder(
    activeModel: LlmModel?,
    contextWindow: Int,
    onOpenModelManager: () -> Unit,
    onSampleClick: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 40.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OffconMark(
                modifier = Modifier.size(28.dp),
                contentDescription = "OffconAI"
            )
            Spacer(modifier = Modifier.width(8.dp))
            GText(
                text = "OffconAI",
                style = GlassTheme.type.titleSmall,
                color = GlassTheme.colors.onBackground
            )
            Spacer(modifier = Modifier.width(8.dp))
            GText(
                text = "local",
                style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.onSurfaceVariant
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            GText(
                text = stringResource(R.string.chat_empty_title),
                style = GlassTheme.type.headlineMedium.copy(fontSize = 24.sp, lineHeight = 32.sp),
                color = GlassTheme.colors.onBackground
            )
            GText(
                text = stringResource(R.string.chat_empty_body),
                style = GlassTheme.type.bodyMedium,
                color = GlassTheme.colors.onSurfaceVariant
            )
        }

        GCard(
            cornerRadius = 14.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(1.dp))
                            .background(
                                if (activeModel != null) GlassTheme.colors.tertiary
                                else GlassTheme.colors.onSurfaceVariant
                            )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    GText(
                        text = stringResource(R.string.chat_empty_engine),
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
                GText(
                    text = activeModel?.name ?: stringResource(R.string.chat_empty_model_required),
                    style = GlassTheme.type.titleSmall,
                    color = GlassTheme.colors.onSurface
                )
                if (activeModel != null) {
                    GText(
                        text = "${activeModel.runtimeBadge} · ${activeModel.quantization} · ${activeModel.displaySize} · $contextWindow ctx",
                        style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                } else {
                    GTextButton(onClick = onOpenModelManager) {
                        GText(
                            text = stringResource(R.string.chat_model_manage),
                            style = GlassTheme.type.labelMedium,
                            color = GlassTheme.colors.primary
                        )
                    }
                }
            }
        }

        Column {
            val suggestions = listOf(
                Triple(stringResource(R.string.chat_empty_suggest_docs), GIcons.Description, stringResource(R.string.chat_empty_suggest_docs)),
                Triple(stringResource(R.string.chat_empty_suggest_models), GIcons.Memory, stringResource(R.string.chat_empty_suggest_models)),
                Triple(stringResource(R.string.chat_empty_suggest_api), GIcons.Dns, stringResource(R.string.chat_empty_suggest_api))
            )
            suggestions.forEachIndexed { index, (label, icon, prompt) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSampleClick(prompt) }
                        .padding(horizontal = 4.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GIcon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    GText(
                        text = label,
                        style = GlassTheme.type.bodyMedium,
                        color = GlassTheme.colors.onSurface
                    )
                }
                if (index < suggestions.lastIndex) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(GlassTheme.colors.outline)
                    )
                }
            }
        }

        GText(
            text = stringResource(R.string.chat_empty_footer),
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
    }
}
