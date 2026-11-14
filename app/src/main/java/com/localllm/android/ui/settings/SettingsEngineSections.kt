package com.localllm.android.ui.settings

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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.memory.MemoryGuardStore
import com.localllm.android.model.GenerationSettings
import com.localllm.android.model.KvCacheQuantization
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GSlider
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import java.util.Locale

@Composable
internal fun SettingsCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(GlassTheme.colors.surface)
            .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                GText(
                    text = title,
                    style = GlassTheme.type.titleMedium,
                    color = GlassTheme.colors.onSurface
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    extraNote: String? = null,
    extraNoteHighlighted: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            GText(title, style = GlassTheme.type.bodyMedium)
            GText(subtitle, fontSize = 12.sp, color = GlassTheme.colors.onSurfaceVariant)
            if (extraNote != null) {
                GText(
                    text = extraNote,
                    fontSize = 11.sp,
                    color = if (extraNoteHighlighted) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
                )
            }
        }
        GSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
internal fun RuntimeSettingsSection(
    runtime: ModelRuntimeType,
    onSwitchRuntime: (ModelRuntimeType) -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_runtime_title),
        icon = GIcons.Memory
    ) {
        GText(
            text = stringResource(R.string.settings_runtime_desc),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            GFilterChip(
                selected = runtime == ModelRuntimeType.LLAMA_CPP,
                onClick = { onSwitchRuntime(ModelRuntimeType.LLAMA_CPP) },
                label = { GText("llama.cpp (GGUF)") },
                modifier = Modifier.weight(1f)
            )
            GFilterChip(
                selected = runtime == ModelRuntimeType.LITE_RT,
                onClick = { onSwitchRuntime(ModelRuntimeType.LITE_RT) },
                label = { GText("LiteRT LM (NPU)") },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun HardwareSettingsSection(
    settings: GenerationSettings,
    llamaGpuOffloadAvailable: Boolean,
    onUpdateSettings: (GenerationSettings) -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_performance_title),
        icon = GIcons.Bolt
    ) {
        SettingToggleRow(
            title = stringResource(R.string.settings_mtp_title),
            subtitle = stringResource(R.string.settings_mtp_desc),
            checked = settings.enableMtp,
            onCheckedChange = { onUpdateSettings(settings.copy(enableMtp = it)) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = stringResource(R.string.settings_indexing_title),
            subtitle = stringResource(R.string.settings_indexing_desc),
            checked = settings.enableIndexingAcceleration,
            onCheckedChange = { onUpdateSettings(settings.copy(enableIndexingAcceleration = it)) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = stringResource(R.string.settings_metrics_title),
            subtitle = stringResource(R.string.settings_metrics_desc),
            checked = settings.showPerformanceMetrics,
            onCheckedChange = { onUpdateSettings(settings.copy(showPerformanceMetrics = it)) }
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = stringResource(R.string.settings_gpu_title),
            subtitle = stringResource(R.string.settings_gpu_desc),
            extraNote = stringResource(
                if (llamaGpuOffloadAvailable) R.string.settings_llama_gpu_available
                else R.string.settings_llama_gpu_unavailable
            ),
            extraNoteHighlighted = llamaGpuOffloadAvailable,
            checked = settings.enableGpuAcceleration,
            onCheckedChange = { onUpdateSettings(settings.copy(enableGpuAcceleration = it)) }
        )
    }
}

@Composable
internal fun MemoryGuardSettingsSection(
    memoryGuardState: MainViewModel.MemoryGuardUiState,
    onSetEnabled: (Boolean) -> Unit,
    onViewLog: () -> Unit,
    onReset: () -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_memory_title),
        icon = GIcons.Info
    ) {
        GText(
            text = stringResource(R.string.settings_memory_desc),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        SettingToggleRow(
            title = stringResource(
                R.string.settings_memory_level,
                memoryGuardState.level,
                MemoryGuardStore.MAX_LEVEL
            ),
            subtitle = memoryLevelDescription(memoryGuardState.level),
            checked = memoryGuardState.enabled,
            onCheckedChange = onSetEnabled
        )

        Spacer(modifier = Modifier.height(10.dp))
        GText(stringResource(R.string.settings_memory_incidents), style = GlassTheme.type.bodyMedium)
        val incidents = memoryGuardState.incidents
        if (incidents.isEmpty()) {
            GText(
                text = stringResource(R.string.settings_memory_no_incidents),
                fontSize = 12.sp,
                color = GlassTheme.colors.onSurfaceVariant
            )
        } else {
            incidents.takeLast(3).reversed().forEach { incident ->
                GText(
                    text = "• ${incident.cause} — ${incident.detail}",
                    fontSize = 11.sp,
                    color = GlassTheme.colors.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GTextButton(onClick = onViewLog) {
                GText(stringResource(R.string.settings_memory_view_log), color = GlassTheme.colors.primary)
            }
            GTextButton(onClick = onReset) {
                GText(stringResource(R.string.settings_memory_reset), color = GlassTheme.colors.primary)
            }
        }
    }
}

@Composable
internal fun KvCacheSettingsSection(
    settings: GenerationSettings,
    onUpdateSettings: (GenerationSettings) -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_kv_title),
        icon = GIcons.Tune
    ) {
        GText(
            text = stringResource(R.string.settings_kv_desc),
            style = GlassTheme.type.bodySmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KvCacheQuantization.entries.forEach { option ->
                GFilterChip(
                    selected = settings.kvCacheQuantization == option,
                    onClick = { onUpdateSettings(settings.copy(kvCacheQuantization = option)) },
                    label = { GText(option.label) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        SettingToggleRow(
            title = stringResource(R.string.settings_oom_bypass_title),
            subtitle = stringResource(R.string.settings_oom_bypass_desc),
            checked = settings.ignoreMemoryLimits,
            onCheckedChange = { onUpdateSettings(settings.copy(ignoreMemoryLimits = it)) }
        )
        if (settings.ignoreMemoryLimits) {
            Spacer(modifier = Modifier.height(6.dp))
            GText(
                text = stringResource(R.string.settings_oom_bypass_warning),
                fontSize = 11.sp,
                color = GlassTheme.colors.error
            )
        }
    }
}

@Composable
internal fun SamplingSettingsSection(
    settings: GenerationSettings,
    onUpdateSettings: (GenerationSettings) -> Unit
) {
    SettingsCard(
        title = stringResource(R.string.settings_params_title),
        icon = GIcons.Tune
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            GText(stringResource(R.string.settings_context_window))
            GText(
                text = stringResource(R.string.settings_context_window_value, settings.contextWindow),
                style = GlassTheme.type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.primary
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(2048, 4096, 8192, 16384).forEach { ctx ->
                GFilterChip(
                    selected = settings.contextWindow == ctx,
                    onClick = { onUpdateSettings(settings.copy(contextWindow = ctx)) },
                    label = { GText("$ctx") }
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GText(stringResource(R.string.settings_temperature))
            GText(
                text = String.format(Locale.US, "%.2f", settings.temperature),
                style = GlassTheme.type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.primary
            )
        }
        GSlider(
            value = settings.temperature,
            onValueChange = { onUpdateSettings(settings.copy(temperature = it)) },
            valueRange = 0.0f..1.5f,
            steps = 14
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GText(stringResource(R.string.settings_top_p_sampling))
            GText(
                text = String.format(Locale.US, "%.2f", settings.topP),
                style = GlassTheme.type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.primary
            )
        }
        GSlider(
            value = settings.topP,
            onValueChange = { onUpdateSettings(settings.copy(topP = it)) },
            valueRange = 0.1f..1.0f,
            steps = 9
        )

        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            GText("Top-K")
            GText(
                text = "${settings.topK}",
                style = GlassTheme.type.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = GlassTheme.colors.primary
            )
        }
        GSlider(
            value = settings.topK.toFloat(),
            onValueChange = { onUpdateSettings(settings.copy(topK = it.toInt())) },
            valueRange = 10f..100f,
            steps = 9
        )
    }
}

@Composable
private fun memoryLevelDescription(level: Int): String = stringResource(
    when (level) {
        0 -> R.string.settings_memory_level_0
        1 -> R.string.settings_memory_level_1
        2 -> R.string.settings_memory_level_2
        3 -> R.string.settings_memory_level_3
        else -> R.string.settings_memory_level_4
    }
)
