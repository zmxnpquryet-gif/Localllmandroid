package com.localllm.android.ui.chat

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.localllm.android.R
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.LlmModel
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GOptionRow
import com.localllm.android.ui.glass.GSheet
import com.localllm.android.ui.glass.GSlider
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun ChatInputBar(
    inputText: String,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onStopGeneration: () -> Unit,
    isGenerating: Boolean,
    activeModel: LlmModel?,
    pendingAttachment: ChatAttachment?,
    onSetAttachment: (ChatAttachment?) -> Unit,
    reasoningEffort: Float,
    onReasoningEffortChanged: (Float) -> Unit,
    onStartVoiceInput: () -> Unit,
    onOpenVoiceMode: () -> Unit,
    isModelLoading: Boolean = false,
    modelLoadingProgress: Float = 0f,
    modelLoadingStage: String = "",
    currentTps: Float = 0f,
    promptSpeed: Float = 0f,
    contextTokens: Int = 0,
    modifier: Modifier = Modifier
) {
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showReasoningSlider by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSetAttachment(
                ChatAttachment(
                    uriString = uri.toString(),
                    mimeType = "image/*",
                    fileName = uri.lastPathSegment ?: "image.jpg",
                    isImage = true
                )
            )
        }
        showAttachmentSheet = false
    }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            onSetAttachment(
                ChatAttachment(
                    uriString = uri.toString(),
                    mimeType = "application/octet-stream",
                    fileName = uri.lastPathSegment ?: "document",
                    isImage = false
                )
            )
        }
        showAttachmentSheet = false
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // 1. Reasoning Effort quick pill if model supports reasoning (e.g. DeepSeek-R1)
        if (activeModel?.supportsReasoning == true) {
            Row(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE6EBFF))
                    .clickable { showReasoningSlider = !showReasoningSlider }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Psychology,
                    contentDescription = null,
                    tint = Color(0xFF687CFF),
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                val effortLabel = when {
                    reasoningEffort <= 0.25f -> "Low (0.2)"
                    reasoningEffort <= 0.6f -> "Medium (0.5)"
                    reasoningEffort <= 0.85f -> "High (0.8)"
                    else -> "Max (1.0)"
                }
                GText(
                    text = stringResource(R.string.chat_reasoning_effort_label, effortLabel),
                    style = GlassTheme.type.labelSmall,
                    color = Color(0xFF1E293B)
                )
            }

            AnimatedVisibility(visible = showReasoningSlider) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassTheme.colors.surface)
                        .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    GText(
                        text = stringResource(R.string.chat_reasoning_slider_title),
                        style = GlassTheme.type.labelSmall,
                        color = Color(0xFF64748B)
                    )
                    GSlider(
                        value = reasoningEffort,
                        onValueChange = onReasoningEffortChanged,
                        valueRange = 0f..1f,
                        steps = 3
                    )
                }
            }
        }

        // 2. Pending Attachment Preview
        if (pendingAttachment != null) {
            Box(
                modifier = Modifier
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassTheme.colors.surface)
                    .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pendingAttachment.isImage) {
                        AsyncImage(
                            model = pendingAttachment.uriString,
                            contentDescription = null,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        GIcon(
                            imageVector = GIcons.Description,
                            contentDescription = null,
                            tint = Color(0xFF687CFF),
                            modifier = Modifier.size(32.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        GText(
                            text = pendingAttachment.fileName,
                            style = GlassTheme.type.bodySmall,
                            maxLines = 1
                        )
                        GText(
                            text = if (pendingAttachment.isImage) "이미지" else "파일",
                            fontSize = 11.sp,
                            color = Color(0xFF64748B)
                        )
                    }

                    GIconButton(
                        onClick = { onSetAttachment(null) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        GIcon(
                            imageVector = GIcons.Close,
                            contentDescription = "삭제",
                            tint = GlassTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }

        // 3. Exact Style Guide Single Capsule Input Bar (19390.png)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(26.dp))
                .background(GlassTheme.colors.surface)
                
                .padding(start = 8.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment Icon Button
            GIconButton(
                onClick = { showAttachmentSheet = true },
                modifier = Modifier.size(36.dp)
            ) {
                GIcon(
                    imageVector = GIcons.Add,
                    contentDescription = stringResource(R.string.attach_file),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Mic Icon Button
            GIconButton(
                onClick = onStartVoiceInput,
                modifier = Modifier.size(36.dp)
            ) {
                GIcon(
                    imageVector = GIcons.Mic,
                    contentDescription = stringResource(R.string.voice_input),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Sparkle Voice Mode Quick Trigger
            GIconButton(
                onClick = onOpenVoiceMode,
                modifier = Modifier.size(36.dp)
            ) {
                GIcon(
                    imageVector = GIcons.AutoAwesome,
                    contentDescription = stringResource(R.string.nav_voice_mode),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Main Text Input Field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    GText(
                        text = "메시지를 입력하세요...",
                        style = GlassTheme.type.bodyMedium,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = GlassTheme.type.bodyMedium.copy(color = GlassTheme.colors.onSurface),
                    cursorBrush = SolidColor(Color(0xFF687CFF)),
                    maxLines = 4
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Black Circular Send / Stop Action Button (Matches 19390.png)
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isGenerating) Color(0xFFEF4444) else GlassTheme.colors.primary)
                    .clickable {
                        if (isGenerating) onStopGeneration()
                        else if (inputText.isNotBlank() || pendingAttachment != null) onSendMessage()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isGenerating) {
                    GIcon(
                        imageVector = GIcons.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                } else {
                    GIcon(
                        imageVector = GIcons.ArrowForward,
                        contentDescription = stringResource(R.string.send),
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    // Attachment Bottom Sheet
    GSheet(
        visible = showAttachmentSheet,
        onDismissRequest = { showAttachmentSheet = false }
    ) {
        GText(
            text = stringResource(R.string.chat_attach_sheet_title),
            style = GlassTheme.type.titleMedium,
            color = Color(0xFF1E293B)
        )

        Spacer(modifier = Modifier.height(16.dp))

        GOptionRow(
            onClick = {
                imagePicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.ImageOnly
                    )
                )
            }
        ) {
            GIcon(
                imageVector = GIcons.Add,
                contentDescription = null,
                tint = Color(0xFF687CFF),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                GText(
                    text = stringResource(R.string.chat_attach_image),
                    style = GlassTheme.type.bodyLarge,
                    color = Color(0xFF1E293B)
                )
                GText(
                    text = stringResource(R.string.chat_attach_image_mmproj_hint),
                    style = GlassTheme.type.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        GOptionRow(onClick = { docPicker.launch("*/*") }) {
            GIcon(
                imageVector = GIcons.Description,
                contentDescription = null,
                tint = Color(0xFF687CFF),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                GText(
                    text = stringResource(R.string.chat_attach_document),
                    style = GlassTheme.type.bodyLarge,
                    color = Color(0xFF1E293B)
                )
                GText(
                    text = stringResource(R.string.chat_attach_document_hint),
                    style = GlassTheme.type.bodySmall,
                    color = Color(0xFF64748B)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
