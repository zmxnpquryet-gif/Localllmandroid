package com.localllm.android.ui.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp

/** Ambient content color (filled buttons override it; explicit colors always win). */
val LocalGContentColor = staticCompositionLocalOf { Color.Black }

/**
 * Glass sheen / hairline color. White reads as a highlight on dark canvases but
 * disappears on light ones, so light themes fall back to ink at reduced alpha.
 */
@Composable
fun glassHighlight(alpha: Float): Color {
    val darkCanvas = GlassTheme.colors.background.luminance() < 0.5f
    return if (darkCanvas) Color.White.copy(alpha = alpha) else Color.Black.copy(alpha = alpha * 0.7f)
}

/** Foundation-only text. Accepts the same styling knobs screens already use. */
@Composable
fun GText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassTheme.type.bodyMedium,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip
) {
    val resolved = if (color == Color.Unspecified) LocalGContentColor.current else color
    BasicText(
        text = text,
        modifier = modifier,
        style = style.copy(
            color = resolved,
            fontSize = if (fontSize != TextUnit.Unspecified) fontSize else style.fontSize,
            fontWeight = fontWeight ?: style.fontWeight,
            fontFamily = fontFamily ?: style.fontFamily,
            lineHeight = if (lineHeight != TextUnit.Unspecified) lineHeight else style.lineHeight,
            textAlign = textAlign ?: style.textAlign ?: TextAlign.Start
        ),
        maxLines = maxLines,
        overflow = overflow
    )
}

/** Frosted card: translucent surface + hairline gradient border + top sheen. */
@Composable
fun GCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 20.dp,
    containerColor: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)
    val borderBrush = Brush.linearGradient(
        colors = listOf(
            glassHighlight(0.35f),
            GlassTheme.colors.primary.copy(alpha = 0.25f),
            glassHighlight(0.08f)
        )
    )
    Box(
        modifier = modifier
            .clip(shape)
            .background(containerColor ?: GlassTheme.colors.surface.copy(alpha = 0.55f))
            .border(1.dp, borderBrush, shape),
        content = {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(shape)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(glassHighlight(0.10f), Color.Transparent)
                        )
                    )
            )
            content()
        }
    )
}

/** Full-screen aurora backdrop drawn under content. */
@Composable
fun LiquidBackground(modifier: Modifier = Modifier) {
    val scheme = GlassTheme.colors
    Box(modifier = modifier.fillMaxSize().background(scheme.background)) {
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(scheme.primary.copy(alpha = 0.16f), Color.Transparent),
                radius = 900f
            )
        ))
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.radialGradient(
                colors = listOf(scheme.secondary.copy(alpha = 0.12f), Color.Transparent),
                radius = 1100f
            )
        ))
        Box(modifier = Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                colors = listOf(
                    glassHighlight(0.05f), Color.Transparent, Color.Black.copy(alpha = 0.08f)
                )
            )
        ))
    }
}

@Composable
fun GDivider(
    modifier: Modifier = Modifier,
    color: Color = GlassTheme.colors.outline.copy(alpha = 0.15f),
    thickness: Dp = 1.dp
) {
    Box(modifier = modifier.fillMaxWidth().height(thickness).background(color))
}
