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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PerformanceMetricsChip(
    tps: Float,
    promptSpeed: Float,
    contextTokens: Int,
    isMtpOn: Boolean,
    modifier: Modifier = Modifier
) {
    if (tps <= 0f && promptSpeed <= 0f) return

    val chipBg = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    val accent = MaterialTheme.colorScheme.primary

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(chipBg)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Speed,
            contentDescription = "성능",
            tint = accent,
            modifier = Modifier.size(14.dp)
        )

        Text(
            text = String.format("%.1f t/s", tps),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 11.sp
        )

        Text(
            text = "•",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            fontSize = 11.sp
        )

        Text(
            text = String.format("PP %.0f t/s", promptSpeed),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )

        if (contextTokens > 0) {
            Text(
                text = "•",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                fontSize = 11.sp
            )
            Text(
                text = "${contextTokens} tok",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    Icon(
                        imageVector = Icons.Default.Bolt,
                        contentDescription = "MTP",
                        tint = accent,
                        modifier = Modifier.size(10.dp)
                    )
                    Text(
                        text = "MTP",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
