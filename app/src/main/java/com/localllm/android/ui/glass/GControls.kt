package com.localllm.android.ui.glass

import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Glass toggle replacing M3 Switch (48dp touch target, switch semantics). */
@Composable
fun GSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    activeColor: Color = GlassTheme.colors.primary
) {
    val colors = GlassTheme.colors
    val thumbOffset by animateFloatAsState(
        targetValue = if (checked) 1f else 0f, label = "switch"
    )
    Box(
        modifier = modifier
            .size(width = 52.dp, height = 48.dp)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(15.dp))
                .background(
                    if (checked) activeColor.copy(alpha = 0.85f)
                    else colors.surfaceVariant.copy(alpha = 0.7f)
                )
                .border(1.dp, glassHighlight(0.2f), RoundedCornerShape(15.dp))
                .padding(3.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(((52 - 6 - 24) * thumbOffset).dp.roundToPx(), 0) }
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.95f))
            )
        }
    }
}

/** Glass slider replacing M3 Slider (supports discrete [steps]). */
@Composable
fun GSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0
) {
    val colors = GlassTheme.colors
    var dragging by remember { mutableStateOf(false) }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(
                    current = value,
                    range = valueRange,
                    steps = steps.coerceAtLeast(0)
                )
                setProgress { target ->
                    onValueChange(
                        snap(target.coerceIn(valueRange.start, valueRange.endInclusive), valueRange, steps)
                    )
                    true
                }
            }
            .pointerInput(valueRange, steps) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false }
                ) { change, _ ->
                    change.consume()
                    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                    onValueChange(snap(fraction, valueRange, steps))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        val fraction = ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        val width = maxWidth
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.surfaceVariant.copy(alpha = 0.8f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceAtLeast(0.001f))
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(colors.primary)
        )
        if (steps > 0) {
            for (i in 0..steps + 1) {
                val fx = i.toFloat() / (steps + 1)
                Box(
                    modifier = Modifier
                        .offset { IntOffset((width.toPx() * fx).roundToInt() - 2.dp.roundToPx(), 0) }
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(colors.onSurface.copy(alpha = 0.35f)),
                )
            }
        }
        Box(
            modifier = Modifier
                .offset {
                    val px = (width.toPx() * fraction).roundToInt()
                    IntOffset(px - 11.dp.roundToPx(), 0)
                }
                .size(22.dp)
                .clip(CircleShape)
                .background(colors.primary)
                .border(2.dp, glassHighlight(0.7f), CircleShape)
        )
    }
}

private fun snap(fraction: Float, range: ClosedFloatingPointRange<Float>, steps: Int): Float {
    if (steps <= 0) return range.start + fraction * (range.endInclusive - range.start)
    val discrete = (fraction * (steps + 1)).roundToInt().coerceIn(0, steps + 1).toFloat() / (steps + 1)
    return range.start + discrete * (range.endInclusive - range.start)
}

/** Selectable filter chip replacing M3 FilterChip (same call signature). */
@Composable
fun GFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null
) {
    val colors = GlassTheme.colors
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) colors.primaryContainer.copy(alpha = 0.75f)
                else colors.surface.copy(alpha = 0.5f)
            )
            .border(
                1.dp,
                if (selected) colors.primary.copy(alpha = 0.6f)
                else colors.outline.copy(alpha = 0.25f),
                RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            leadingIcon?.let {
                it()
                Spacer(modifier = Modifier.width(6.dp))
            }
            label()
        }
    )
}

/** Linear progress replacing M3 LinearProgressIndicator. Null = indeterminate. */
@Composable
fun GLinearProgress(
    progress: Float?,
    modifier: Modifier = Modifier,
    color: Color = GlassTheme.colors.primary,
    trackColor: Color = GlassTheme.colors.surfaceVariant,
    height: Dp = 4.dp
) {
    if (progress == null) {
        val t = rememberInfiniteTransition(label = "indeterminate")
        val slide by t.animateFloat(
            initialValue = -0.4f, targetValue = 1f,
            animationSpec = InfiniteRepeatableSpec(tween(1400, easing = LinearEasing), RepeatMode.Restart),
            label = "slide"
        )
        Canvas(modifier = modifier.fillMaxWidth().height(height)) {
            drawRoundRect(trackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            val w = size.width * 0.4f
            val x = slide * size.width
            drawRoundRect(color, topLeft = androidx.compose.ui.geometry.Offset(x, 0f),
                size = androidx.compose.ui.geometry.Size(w, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
    } else {
        val f = progress.coerceIn(0f, 1f)
        Canvas(modifier = modifier.fillMaxWidth().height(height)) {
            drawRoundRect(trackColor, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
            drawRoundRect(color, size = androidx.compose.ui.geometry.Size(size.width * f, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
        }
    }
}

/** Spinning arc replacing M3 CircularProgressIndicator. */
@Composable
fun GSpinner(
    modifier: Modifier = Modifier,
    color: Color = GlassTheme.colors.primary,
    strokeWidth: Dp = 3.dp
) {
    val t = rememberInfiniteTransition(label = "spin")
    val angle by t.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = InfiniteRepeatableSpec(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "angle"
    )
    Canvas(modifier = modifier.rotate(angle)) {
        drawArc(
            color = color, startAngle = 0f, sweepAngle = 270f, useCenter = false,
            style = Stroke(width = strokeWidth.toPx(), cap = StrokeCap.Round)
        )
    }
}
