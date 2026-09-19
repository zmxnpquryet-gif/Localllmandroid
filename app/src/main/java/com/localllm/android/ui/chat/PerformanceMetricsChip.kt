package com.localllm.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun PerformanceMetricsChip(
    tps: Float,
    promptSpeed: Float,
    contextTokens: Int,
    isMtpOn: Boolean,
    modifier: Modifier = Modifier
) {
    if (tps <= 0f && promptSpeed <= 0f) return

    val chipBg = GlassTheme.colors.surfaceVariant.copy(alpha = 0.45f)
    val accent = GlassTheme.colors.primary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        GIcon(
            imageVector = GIcons.Speed,
            contentDescription = stringResource(R.string.chat_performance_desc),
            tint = accent,
            modifier = Modifier.size(14.dp)
        )

        GText(
            text = String.format("%.1f t/s", tps),
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.onSurface,
            fontSize = 11.sp
        )

        GText(
            text = "•",
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.outline,
            fontSize = 11.sp
        )

        GText(
            text = String.format("PP %.0f t/s", promptSpeed),
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.onSurfaceVariant,
            fontSize = 11.sp
        )

        if (contextTokens > 0) {
            GText(
                text = "•",
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.outline,
                fontSize = 11.sp
            )
            GText(
                text = "${contextTokens} tok",
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.onSurfaceVariant,
                fontSize = 11.sp
            )
        }

        if (isMtpOn) {
            Spacer(modifier = Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(accent.copy(alpha = 0.2f))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GIcon(
                        imageVector = GIcons.Bolt,
                        contentDescription = "MTP",
                        tint = accent,
                        modifier = Modifier.size(10.dp)
                    )
                    GText(
                        text = "MTP",
                        style = GlassTheme.type.labelSmall,
                        color = accent,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
