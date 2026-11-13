package com.localllm.android.ui.chat

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme
import java.util.Locale

/**
 * Quiet instrument readout under an answer: one mono line, no chip, no icons.
 * The numbers are the product's local-runtime signature, so they stay visible
 * without competing with the prose above them.
 */
@Composable
fun PerformanceMetricsChip(
    tps: Float,
    promptSpeed: Float,
    contextTokens: Int,
    isMtpOn: Boolean,
    modifier: Modifier = Modifier
) {
    if (tps <= 0f && promptSpeed <= 0f && contextTokens <= 0) return

    val parts = buildList {
        if (tps > 0f) add(String.format(Locale.US, "%.1f t/s", tps))
        if (promptSpeed > 0f) add(String.format(Locale.US, "PP %.0f", promptSpeed))
        if (contextTokens > 0) add("$contextTokens tok")
        if (isMtpOn) add("MTP")
    }

    GText(
        text = parts.joinToString(" · "),
        modifier = modifier.padding(top = 2.dp),
        style = GlassTheme.type.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = GlassTheme.colors.onSurfaceVariant,
        fontSize = 11.sp
    )
}
