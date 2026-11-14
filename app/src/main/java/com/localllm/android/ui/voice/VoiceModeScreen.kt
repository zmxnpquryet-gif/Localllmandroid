package com.localllm.android.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.localllm.android.R
import com.localllm.android.ui.AppScreen
import com.localllm.android.ui.MainViewModel
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.voice.InteractiveVoiceState
import com.localllm.android.voice.LocalTtsEngine
import com.localllm.android.voice.TtsEngineMode
import com.localllm.android.voice.VoiceAudioReason

@Composable
fun VoiceModeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val voiceState by viewModel.voiceManager.voiceState.collectAsStateWithLifecycle()
    val amplitude by viewModel.voiceManager.audioAmplitude.collectAsStateWithLifecycle()
    val recognizedText by viewModel.voiceManager.recognizedText.collectAsStateWithLifecycle()
    val ttsMode by viewModel.voiceManager.ttsMode.collectAsStateWithLifecycle()
    val localTtsState by viewModel.voiceManager.localTts.modelState.collectAsStateWithLifecycle()
    val ttsSpeaker by viewModel.voiceManager.ttsSpeakerId.collectAsStateWithLifecycle()
    val ttsSpeed by viewModel.voiceManager.ttsSpeed.collectAsStateWithLifecycle()
    val activeModel by viewModel.activeModel.collectAsStateWithLifecycle()
    val nativeOmniActive by viewModel.nativeOmniActive.collectAsStateWithLifecycle()
    val nativeOmniTranscript by viewModel.nativeOmniTranscript.collectAsStateWithLifecycle()
    val audioDecision by viewModel.voiceAudioDecision.collectAsStateWithLifecycle()
    val isNativeOmni = activeModel?.supportsNativeAudioInput == true &&
        activeModel?.supportsNativeAudioOutput == true

    var isMicMuted by remember { mutableStateOf(false) }
    var nativePermissionPending by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopNativeOmniSession()
            viewModel.voiceManager.stopListening()
            viewModel.voiceManager.stopSpeaking()
        }
    }

    val micPermissionRequiredMsg = stringResource(R.string.voice_interactive_permission_required)
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            if (nativePermissionPending) {
                nativePermissionPending = false
                viewModel.startNativeOmniTurn()
            } else {
                viewModel.startInteractiveVoiceSession()
            }
        } else {
            Toast.makeText(context, micPermissionRequiredMsg, Toast.LENGTH_SHORT).show()
        }
    }

    fun triggerVoiceAction() {
        if (isNativeOmni) {
            if (nativeOmniActive) {
                viewModel.stopNativeOmniTurn()
                return
            }
            val hasMic = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                viewModel.startNativeOmniTurn()
            } else {
                nativePermissionPending = true
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else if (voiceState == InteractiveVoiceState.IDLE) {
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
    }

    val targetScale = when (voiceState) {
        InteractiveVoiceState.LISTENING -> (1.0f + amplitude * 0.25f).coerceIn(1.0f, 1.25f)
        InteractiveVoiceState.PROCESSING -> 1.04f
        InteractiveVoiceState.SPEAKING -> 1.08f
        InteractiveVoiceState.IDLE -> 1.0f
    }
    val currentScale by animateFloatAsState(
        targetValue = targetScale,
        animationSpec = tween(durationMillis = 160),
        label = "voiceDialScale"
    )

    val isActiveVoice = voiceState != InteractiveVoiceState.IDLE
    val dialBackground = if (isActiveVoice) GlassTheme.colors.primary else GlassTheme.colors.surface
    val dialBorder = if (isActiveVoice) GlassTheme.colors.primary else GlassTheme.colors.outline
    val dialIconTint = if (isActiveVoice) GlassTheme.colors.onPrimary else GlassTheme.colors.onSurface

    val statusText = when (voiceState) {
        InteractiveVoiceState.LISTENING -> stringResource(R.string.voice_state_listening)
        InteractiveVoiceState.PROCESSING -> stringResource(R.string.voice_state_local_thinking)
        InteractiveVoiceState.SPEAKING -> stringResource(R.string.voice_state_speaking_answer)
        InteractiveVoiceState.IDLE -> stringResource(R.string.voice_state_idle_hint)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(GlassTheme.colors.background)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
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
                        tint = GlassTheme.colors.onBackground
                    )
                }

                GText(
                    text = stringResource(R.string.voice_mode_title),
                    style = GlassTheme.type.titleSmall,
                    color = GlassTheme.colors.onBackground
                )

                Spacer(modifier = Modifier.width(36.dp))
            }

            GText(
                text = if (isNativeOmni) {
                    (activeModel?.name ?: "LFM3 Omni") + " · native audio in → audio out · one tap per turn"
                } else {
                    stringResource(R.string.voice_native_audio_unavailable)
                },
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isNativeOmni) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant
            )

            audioDecision?.let { decision ->
                GText(
                    text = stringResource(
                        when (decision.reason) {
                            VoiceAudioReason.MODEL_ACCEPTS_AUDIO -> R.string.voice_route_direct
                            VoiceAudioReason.MODEL_NEEDS_STT -> R.string.voice_route_stt
                            VoiceAudioReason.RUNTIME_CANNOT_DECODE_AUDIO -> R.string.voice_route_runtime_stt
                        }
                    ),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(144.dp)
                            .scale(currentScale)
                            .clip(CircleShape)
                            .background(dialBackground)
                            .border(1.dp, dialBorder, CircleShape)
                            .clickable(onClick = ::triggerVoiceAction),
                        contentAlignment = Alignment.Center
                    ) {
                        GIcon(
                            imageVector = if (voiceState == InteractiveVoiceState.SPEAKING) GIcons.VolumeUp else GIcons.Mic,
                            contentDescription = null,
                            tint = dialIconTint,
                            modifier = Modifier.size(40.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    GText(
                        text = statusText,
                        style = GlassTheme.type.titleMedium,
                        color = GlassTheme.colors.onBackground
                    )

                    val liveTranscript = if (isNativeOmni) nativeOmniTranscript else recognizedText
                    if (liveTranscript.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        GText(
                            text = "\"$liveTranscript\"",
                            style = GlassTheme.type.bodyMedium,
                            color = GlassTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 330.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 12.dp)
            ) {
                if (isNativeOmni) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 6.dp)
                    ) {
                        GText(
                            text = "Native Omni session",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.primary
                        )
                        GText(
                            text = "16 kHz PCM is fed directly to the model; text and 24 kHz speech arrive together.",
                            fontSize = 11.sp,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                        if (nativeOmniTranscript.isNotBlank()) {
                            GText(
                                text = nativeOmniTranscript.takeLast(240),
                                fontSize = 12.sp,
                                color = GlassTheme.colors.onBackground,
                                maxLines = 3
                            )
                        }
                    }
                } else {
                    GText(
                        text = stringResource(R.string.voice_tts_section),
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TtsEngineCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.voice_tts_system_title),
                            subtitle = stringResource(R.string.voice_tts_system_desc),
                            selected = ttsMode == TtsEngineMode.SYSTEM,
                            installed = true,
                            onClick = { viewModel.selectTtsMode(TtsEngineMode.SYSTEM) }
                        )
                        TtsEngineCard(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.voice_tts_local_title),
                            subtitle = when (val state = localTtsState) {
                                is LocalTtsEngine.ModelState.Missing -> stringResource(R.string.voice_tts_local_missing)
                                is LocalTtsEngine.ModelState.Downloading ->
                                    stringResource(R.string.voice_stt_downloading, (state.progress * 100).toInt())
                                is LocalTtsEngine.ModelState.Ready -> stringResource(R.string.voice_tts_local_ready)
                                is LocalTtsEngine.ModelState.Failed -> stringResource(R.string.voice_stt_failed, state.message)
                            },
                            selected = ttsMode == TtsEngineMode.LOCAL_NEURAL,
                            installed = localTtsState is LocalTtsEngine.ModelState.Ready,
                            onClick = {
                                when (localTtsState) {
                                    is LocalTtsEngine.ModelState.Ready -> viewModel.selectTtsMode(TtsEngineMode.LOCAL_NEURAL)
                                    is LocalTtsEngine.ModelState.Downloading -> Unit
                                    else -> viewModel.downloadLocalTts()
                                }
                            }
                        )
                    }

                    if (ttsMode == TtsEngineMode.LOCAL_NEURAL && localTtsState is LocalTtsEngine.ModelState.Ready) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TtsVoiceControls(
                            speakerId = ttsSpeaker,
                            speed = ttsSpeed,
                            onSpeakerChange = viewModel::setTtsSpeaker,
                            onSpeedChange = viewModel::setTtsSpeed,
                            onPreview = viewModel::previewTts,
                            onDelete = viewModel::deleteLocalTts
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    SttStatusRow(viewModel = viewModel)
                }

                Spacer(modifier = Modifier.height(16.dp))

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
                            if (isMicMuted) {
                                if (isNativeOmni) viewModel.stopNativeOmniSession()
                                else viewModel.voiceManager.stopListening()
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(GlassTheme.colors.surface)
                            .border(1.dp, GlassTheme.colors.outline, CircleShape)
                    ) {
                        GIcon(
                            imageVector = if (isMicMuted) GIcons.MicOff else GIcons.Mic,
                            contentDescription = stringResource(R.string.voice_mic_desc),
                            tint = if (isMicMuted) GlassTheme.colors.error else GlassTheme.colors.onSurface
                        )
                    }

                    GButton(
                        onClick = ::triggerVoiceAction,
                        modifier = Modifier.height(44.dp)
                    ) {
                        GIcon(imageVector = GIcons.RecordVoiceOver, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        GText(
                            text = if (voiceState == InteractiveVoiceState.IDLE) stringResource(R.string.voice_dialogue_start)
                            else stringResource(R.string.voice_stop_dialogue)
                        )
                    }
                }
            }
        }
    }
}
