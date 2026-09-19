package com.localllm.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.voice.LocalSttEngine
import com.localllm.android.voice.SttEngine
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.localllm.android.R
import com.localllm.android.model.VoiceModelTemplate
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.voice.InteractiveVoiceState

@Composable
fun VoiceModeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val voiceState by viewModel.voiceManager.voiceState.collectAsState()
    val amplitude by viewModel.voiceManager.audioAmplitude.collectAsState()
    val recognizedText by viewModel.voiceManager.recognizedText.collectAsState()
    val voiceTemplates by viewModel.voiceManager.installedVoiceTemplates.collectAsState()

    var isMicMuted by remember { mutableStateOf(false) }

    // Leaving this screen (back gesture included) must break the hands-free loop:
    // otherwise the TTS completion callback would restart listening after exit.
    DisposableEffect(Unit) {
        onDispose {
            viewModel.voiceManager.stopListening()
            viewModel.voiceManager.stopSpeaking()
        }
    }

    // Audio recording permission launcher
    val micPermissionRequiredMsg = stringResource(R.string.voice_interactive_permission_required)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startInteractiveVoiceSession()
        } else {
            Toast.makeText(context, micPermissionRequiredMsg, Toast.LENGTH_SHORT).show()
        }
    }

    // Pulsing animation for ChatGPT-like Voice Orb
    val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
    val idlePulse by infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "idleScale"
    )

    val currentScale = when (voiceState) {
        InteractiveVoiceState.LISTENING -> (1.0f + amplitude * 0.4f).coerceIn(1.0f, 1.4f)
        InteractiveVoiceState.PROCESSING -> idlePulse * 1.08f
        InteractiveVoiceState.SPEAKING -> idlePulse * 1.15f
        InteractiveVoiceState.IDLE -> idlePulse
    }

    val orbGradient = when (voiceState) {
        InteractiveVoiceState.LISTENING -> Brush.radialGradient(
            listOf(Color(0xFF38BDF8), Color(0xFF0284C7), Color(0xFF0C4A6E))
        )
        InteractiveVoiceState.PROCESSING -> Brush.radialGradient(
            listOf(Color(0xFFA855F7), Color(0xFF7E22CE), Color(0xFF3B0764))
        )
        InteractiveVoiceState.SPEAKING -> Brush.radialGradient(
            listOf(Color(0xFF10A37F), Color(0xFF059669), Color(0xFF064E3B))
        )
        InteractiveVoiceState.IDLE -> Brush.radialGradient(
            listOf(Color(0xFF64748B), Color(0xFF334155), Color(0xFF0F172A))
        )
    }

    val statusText = when (voiceState) {
        InteractiveVoiceState.LISTENING -> stringResource(R.string.voice_state_listening)
        InteractiveVoiceState.PROCESSING -> stringResource(R.string.voice_state_local_thinking)
        InteractiveVoiceState.SPEAKING -> stringResource(R.string.voice_state_speaking_answer)
        InteractiveVoiceState.IDLE -> stringResource(R.string.voice_state_idle_hint)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Aurora depth washes (theme-tinted, dark immersive base preserved)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            GlassTheme.colors.primary.copy(alpha = 0.14f),
                            Color.Transparent
                        ),
                        radius = 800f
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            GlassTheme.colors.secondary.copy(alpha = 0.10f),
                            Color.Transparent
                        ),
                        radius = 1000f
                    )
                )
        )
        // Top Bar: Exit button & Mode indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GIconButton(
                onClick = {
                    viewModel.voiceManager.stopListening()
                    viewModel.voiceManager.stopSpeaking()
                    viewModel.navigateTo(AppScreen.CHAT)
                }
            ) {
                GIcon(
                    imageVector = GIcons.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = Color.White
                )
            }

            GText(
                text = stringResource(R.string.voice_mode_title),
                style = GlassTheme.type.titleSmall,
                color = Color.White
            )

            Spacer(modifier = Modifier.width(36.dp))
        }

        // Center: Interactive Voice Orb
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 60.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(190.dp)
                    .scale(currentScale)
                    .clip(CircleShape)
                    .background(orbGradient)
                    .border(2.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                    .clickable {
                        if (voiceState == InteractiveVoiceState.IDLE) {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                viewModel.startInteractiveVoiceSession()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        } else {
                            viewModel.voiceManager.stopListening()
                            viewModel.voiceManager.stopSpeaking()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                GIcon(
                    imageVector = if (voiceState == InteractiveVoiceState.SPEAKING) GIcons.VolumeUp else GIcons.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            GText(
                text = statusText,
                style = GlassTheme.type.titleMedium,
                color = Color.White.copy(alpha = 0.9f)
            )

            if (recognizedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                GText(
                    text = "\"$recognizedText\"",
                    style = GlassTheme.type.bodyMedium,
                    color = GlassTheme.colors.primary,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        // Bottom: Korean Voice Model Templates & Control Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            GText(
                text = stringResource(R.string.voice_template_section),
                style = GlassTheme.type.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(voiceTemplates) { template ->
                    val templateUpdatedToast = stringResource(R.string.voice_template_updated, template.name)
                    VoiceTemplateCard(
                        template = template,
                        onToggle = {
                            viewModel.voiceManager.toggleVoiceModelInstall(template.id)
                            Toast.makeText(context, templateUpdatedToast, Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // On-device STT status (Whisper tiny multilingual, ~75MB one-time)
            SttStatusRow(viewModel = viewModel)

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom action buttons: Mic Mute, Orb Trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIconButton(
                    onClick = {
                        isMicMuted = !isMicMuted
                        if (isMicMuted) viewModel.voiceManager.stopListening()
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    GIcon(
                        imageVector = if (isMicMuted) GIcons.MicOff else GIcons.Mic,
                        contentDescription = stringResource(R.string.voice_mic_desc),
                        tint = if (isMicMuted) Color.Red else Color.White
                    )
                }

                GButton(
                    onClick = {
                        if (voiceState != InteractiveVoiceState.IDLE) {
                            viewModel.voiceManager.stopListening()
                            viewModel.voiceManager.stopSpeaking()
                        } else {
                            val hasMic = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (hasMic) {
                                viewModel.startInteractiveVoiceSession()
                            } else {
                                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    },
                    modifier = Modifier.height(48.dp)
                ) {
                    GIcon(imageVector = GIcons.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    GText(text = if (voiceState == InteractiveVoiceState.IDLE) stringResource(R.string.voice_dialogue_start) else stringResource(R.string.voice_stop_dialogue))
                }
            }
        }
    }
}

@Composable
private fun SttStatusRow(viewModel: MainViewModel) {
    val sttState by viewModel.voiceManager.localStt.modelState.collectAsState()
    val lastEngine by viewModel.voiceManager.lastSttEngine.collectAsState()
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
                color = Color.White.copy(alpha = 0.9f)
            )
            val engineNote = when (lastEngine) {
                SttEngine.LOCAL_WHISPER -> stringResource(R.string.voice_stt_last_local)
                SttEngine.SYSTEM -> stringResource(R.string.voice_stt_last_system)
                null -> null
            }
            GText(
                text = when (val s = sttState) {
                    is LocalSttEngine.ModelState.Missing -> stringResource(R.string.voice_stt_using_system) + (engineNote?.let { " • $it" } ?: "")
                    is LocalSttEngine.ModelState.Downloading -> stringResource(R.string.voice_stt_downloading, (s.progress * 100).toInt())
                    is LocalSttEngine.ModelState.Ready -> stringResource(R.string.voice_stt_ready) + (engineNote?.let { " • $it" } ?: "")
                    is LocalSttEngine.ModelState.Failed -> stringResource(R.string.voice_stt_failed, s.message)
                },
                style = GlassTheme.type.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        when (sttState) {
            is LocalSttEngine.ModelState.Missing, is LocalSttEngine.ModelState.Failed -> {
                GTextButton(onClick = { viewModel.downloadLocalStt() }) {
                    GText(
                        text = if (sttState is LocalSttEngine.ModelState.Failed) stringResource(R.string.voice_stt_retry) else stringResource(R.string.voice_stt_get),
                        color = Color.White
                    )
                }
            }
            is LocalSttEngine.ModelState.Downloading, is LocalSttEngine.ModelState.Ready -> {}
        }
    }
}

@Composable
private fun VoiceTemplateCard(
    template: VoiceModelTemplate,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1E293B))
            .border(
                width = 1.dp,
                color = if (template.isInstalled) GlassTheme.colors.primary else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GText(
                    text = template.name,
                    fontSize = 12.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (template.koreanSupport) {
                    GText(
                        text = "KR",
                        fontSize = 10.sp,
                        color = GlassTheme.colors.primary
                    )
                }
            }
            GText(
                text = "${template.type} • ${template.sizeText}",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        GIcon(
            imageVector = if (template.isInstalled) GIcons.Done else GIcons.CloudDownload,
            contentDescription = null,
            tint = if (template.isInstalled) GlassTheme.colors.primary else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}
