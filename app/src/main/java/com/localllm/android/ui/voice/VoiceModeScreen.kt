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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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

    // Audio recording permission launcher
    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            viewModel.startInteractiveVoiceSession()
        } else {
            Toast.makeText(context, "대화형 음성 모드를 위해 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
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
        InteractiveVoiceState.LISTENING -> "음성을 듣고 있습니다..."
        InteractiveVoiceState.PROCESSING -> "로컬 모델이 생각 중입니다..."
        InteractiveVoiceState.SPEAKING -> "답변을 말하는 중입니다..."
        InteractiveVoiceState.IDLE -> "중앙 오브를 눌러 대화를 시작하세요"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F17))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Top Bar: Exit button & Mode indicator
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    viewModel.voiceManager.stopListening()
                    viewModel.voiceManager.stopSpeaking()
                    viewModel.navigateTo(AppScreen.CHAT)
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = Color.White
                )
            }

            Text(
                text = "음성 대화 모드",
                style = MaterialTheme.typography.titleSmall,
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
                Icon(
                    imageVector = if (voiceState == InteractiveVoiceState.SPEAKING) Icons.Default.VolumeUp else Icons.Default.Mic,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(54.dp)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.9f)
            )

            if (recognizedText.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"$recognizedText\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
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
            Text(
                text = "한국어 음성 모델 템플릿 (STT / TTS)",
                style = MaterialTheme.typography.labelSmall,
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
                    VoiceTemplateCard(
                        template = template,
                        onToggle = {
                            viewModel.voiceManager.toggleVoiceModelInstall(template.id)
                            Toast.makeText(context, "${template.name} 설정이 갱신되었습니다.", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom action buttons: Mic Mute, Orb Trigger
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        isMicMuted = !isMicMuted
                        if (isMicMuted) viewModel.voiceManager.stopListening()
                    },
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E293B))
                ) {
                    Icon(
                        imageVector = if (isMicMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        contentDescription = "마이크",
                        tint = if (isMicMuted) Color.Red else Color.White
                    )
                }

                Button(
                    onClick = {
                        val hasMic = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED
                        if (hasMic) {
                            viewModel.startInteractiveVoiceSession()
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Icon(imageVector = Icons.Default.RecordVoiceOver, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = if (voiceState == InteractiveVoiceState.IDLE) "대화 시작" else "대화 중지")
                }
            }
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
                color = if (template.isInstalled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.1f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onToggle() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = template.name,
                    fontSize = 12.sp,
                    color = Color.White
                )
                Spacer(modifier = Modifier.width(4.dp))
                if (template.koreanSupport) {
                    Text(
                        text = "KR",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Text(
                text = "${template.type} • ${template.sizeText}",
                fontSize = 10.sp,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
            imageVector = if (template.isInstalled) Icons.Default.Done else Icons.Default.CloudDownload,
            contentDescription = null,
            tint = if (template.isInstalled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(16.dp)
        )
    }
}
