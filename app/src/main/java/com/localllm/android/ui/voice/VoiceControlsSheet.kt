package com.localllm.android.ui.voice

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.android.R
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GSlider
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.voice.LocalSttEngine
import com.localllm.android.voice.LocalTtsEngine
import com.localllm.android.voice.SttEngine
import java.util.Locale

@Composable
internal fun SttStatusRow(viewModel: MainViewModel) {
    val sttState by viewModel.voiceManager.localStt.modelState.collectAsStateWithLifecycle()
    val lastEngine by viewModel.voiceManager.lastSttEngine.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            GText(
                text = stringResource(R.string.voice_stt_ondevice),
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.onBackground
            )
            val engineNote = when (lastEngine) {
                SttEngine.LOCAL_WHISPER -> stringResource(R.string.voice_stt_last_local)
                SttEngine.SYSTEM -> stringResource(R.string.voice_stt_last_system)
                null -> null
            }
            GText(
                text = when (val s = sttState) {
                    is LocalSttEngine.ModelState.Missing ->
                        stringResource(R.string.voice_stt_using_system) + (engineNote?.let { " • $it" } ?: "")
                    is LocalSttEngine.ModelState.Downloading ->
                        stringResource(R.string.voice_stt_downloading, (s.progress * 100).toInt())
                    is LocalSttEngine.ModelState.Ready ->
                        stringResource(R.string.voice_stt_ready) + (engineNote?.let { " • $it" } ?: "")
                    is LocalSttEngine.ModelState.Failed ->
                        stringResource(R.string.voice_stt_failed, s.message)
                },
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.onSurfaceVariant
            )
        }
        when (sttState) {
            is LocalSttEngine.ModelState.Missing, is LocalSttEngine.ModelState.Failed -> {
                GTextButton(onClick = viewModel::downloadLocalStt) {
                    GText(
                        text = if (sttState is LocalSttEngine.ModelState.Failed) stringResource(R.string.voice_stt_retry)
                        else stringResource(R.string.voice_stt_get),
                        color = GlassTheme.colors.primary
                    )
                }
            }
            is LocalSttEngine.ModelState.Downloading, is LocalSttEngine.ModelState.Ready -> {}
        }
    }
}

@Composable
internal fun TtsEngineCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    installed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(GlassTheme.colors.surface)
            .border(
                width = 1.dp,
                color = if (selected) GlassTheme.colors.primary else GlassTheme.colors.outline,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GText(text = title, fontSize = 12.sp, color = GlassTheme.colors.onSurface, maxLines = 1)
            Spacer(modifier = Modifier.width(4.dp))
            when {
                selected -> GIcon(
                    imageVector = GIcons.Done,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(14.dp)
                )
                !installed -> GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        GText(
            text = subtitle,
            fontSize = 10.sp,
            color = GlassTheme.colors.onSurfaceVariant,
            maxLines = 2
        )
    }
}

@Composable
internal fun TtsVoiceControls(
    speakerId: Int,
    speed: Float,
    onSpeakerChange: (Int) -> Unit,
    onSpeedChange: (Float) -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GText(
                text = stringResource(R.string.voice_tts_speaker),
                fontSize = 11.sp,
                color = GlassTheme.colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items(LocalTtsEngine.SPEAKER_COUNT) { index ->
                    val active = index == speakerId
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .clip(CircleShape)
                            .background(if (active) GlassTheme.colors.primary else GlassTheme.colors.surfaceVariant)
                            .clickable { onSpeakerChange(index) },
                        contentAlignment = Alignment.Center
                    ) {
                        GText(
                            text = "${index + 1}",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = if (active) GlassTheme.colors.onPrimary else GlassTheme.colors.onSurface
                        )
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            GText(
                text = stringResource(R.string.voice_tts_speed, String.format(Locale.US, "%.1f", speed)),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = GlassTheme.colors.onSurfaceVariant
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                GSlider(
                    value = speed,
                    onValueChange = onSpeedChange,
                    valueRange = 0.5f..2.0f,
                    steps = 14
                )
            }
            GTextButton(onClick = onPreview) {
                GText(text = stringResource(R.string.voice_tts_preview), color = GlassTheme.colors.primary, fontSize = 12.sp)
            }
            GTextButton(onClick = onDelete) {
                GText(text = stringResource(R.string.voice_stt_delete), color = GlassTheme.colors.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
