package com.localllm.android.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * OffconAI's open-connection mark. The glyph is drawn from theme tokens so a
 * palette change remains visible on every brand surface.
 */
@Composable
fun OffconMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    backgroundColor: Color = GlassTheme.colors.primary,
    markColor: Color = GlassTheme.colors.onPrimary
) {
    val accessibleModifier = if (contentDescription == null) {
        modifier
    } else {
        modifier.semantics { this.contentDescription = contentDescription }
    }

    Canvas(accessibleModifier) {
        val extent = size.minDimension
        drawRoundRect(
            color = backgroundColor,
            cornerRadius = CornerRadius(extent * 0.28f)
        )

        val stroke = extent * 0.10f
        val loop = Path().apply {
            moveTo(extent * 0.72f, extent * 0.25f)
            lineTo(extent * 0.43f, extent * 0.25f)
            cubicTo(
                extent * 0.30f, extent * 0.25f,
                extent * 0.21f, extent * 0.35f,
                extent * 0.21f, extent * 0.49f
            )
            lineTo(extent * 0.21f, extent * 0.51f)
            cubicTo(
                extent * 0.21f, extent * 0.65f,
                extent * 0.30f, extent * 0.75f,
                extent * 0.43f, extent * 0.75f
            )
            lineTo(extent * 0.71f, extent * 0.75f)
            cubicTo(
                extent * 0.81f, extent * 0.75f,
                extent * 0.88f, extent * 0.67f,
                extent * 0.88f, extent * 0.59f
            )
            lineTo(extent * 0.88f, extent * 0.53f)
        }
        drawPath(
            path = loop,
            color = markColor,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )

        drawLine(
            color = markColor,
            start = Offset(extent * 0.54f, extent * 0.50f),
            end = Offset(extent * 0.76f, extent * 0.50f),
            strokeWidth = stroke * 0.48f,
            cap = StrokeCap.Round
        )
        drawCircle(
            color = markColor,
            radius = extent * 0.035f,
            center = Offset(extent * 0.76f, extent * 0.50f)
        )
    }
}
