package com.localllm.android.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GSpinner
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun ReasoningCard(
    reasoningText: String,
    isStreaming: Boolean,
    reasoningEffortLabel: String,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(isStreaming) }

    LaunchedEffect(isStreaming) {
        if (!isStreaming) {
            isExpanded = false
        }
    }

    val containerColor = GlassTheme.colors.surface.copy(alpha = 0.55f)
    val accentColor = GlassTheme.colors.primary

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.30f),
                        accentColor.copy(alpha = 0.30f)
                    )
                ),
                RoundedCornerShape(12.dp)
            )
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isStreaming) {
                    GSpinner(
                        modifier = Modifier.size(16.dp),
                        color = accentColor
                    )
                } else {
                    GIcon(
                        imageVector = GIcons.Psychology,
                        contentDescription = "추론",
                        tint = accentColor,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                GText(
                    text = if (isStreaming) "추론 과정 처리 중..." else "추론 과정 완료",
                    style = GlassTheme.type.labelLarge,
                    color = GlassTheme.colors.onSurface
                )
                Spacer(modifier = Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    GText(
                        text = reasoningEffortLabel,
                        fontSize = 11.sp,
                        color = accentColor,
                        style = GlassTheme.type.labelSmall
                    )
                }
            }

            GIcon(
                imageVector = if (isExpanded) GIcons.ArrowUp else GIcons.ArrowDown,
                contentDescription = if (isExpanded) "접기" else "펼치기",
                tint = GlassTheme.colors.onSurfaceVariant
            )
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(modifier = Modifier.padding(top = 8.dp)) {
                GText(
                    text = reasoningText,
                    style = GlassTheme.type.bodySmall.copy(
                        lineHeight = 18.sp,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                )
            }
        }
    }
}
