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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.localllm.android.R
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.localllm.android.model.ChatAttachment
import com.localllm.android.model.LlmModel
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GOptionRow
import com.localllm.android.ui.glass.GSheet
import com.localllm.android.ui.glass.GSlider
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextField
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
    modifier: Modifier = Modifier
) {
    var showAttachmentSheet by remember { mutableStateOf(false) }
    var showReasoningSlider by remember { mutableStateOf(false) }

    // Photo Picker contract (Zero permission required)
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            onSetAttachment(
                ChatAttachment(
                    uriString = uri.toString(),
                    mimeType = "image/*",
                    fileName = "photo_${System.currentTimeMillis()}.jpg",
                    isImage = true
                )
            )
        }
        showAttachmentSheet = false
    }

    // Document Picker contract
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val fileName = uri.path?.substringAfterLast('/') ?: "document.txt"
            onSetAttachment(
                ChatAttachment(
                    uriString = uri.toString(),
                    mimeType = "application/octet-stream",
                    fileName = fileName,
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
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        // 1. Reasoning Effort quick pill if model supports reasoning (e.g. DeepSeek-R1)
        if (activeModel?.supportsReasoning == true) {
            Row(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
                    .clickable { showReasoningSlider = !showReasoningSlider }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Psychology,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
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
                    color = GlassTheme.colors.onSurface
                )
            }

            AnimatedVisibility(visible = showReasoningSlider) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(GlassTheme.colors.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    GText(
                        text = stringResource(R.string.chat_reasoning_slider_title),
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
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
                    .background(GlassTheme.colors.surfaceVariant)
                    .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                    .padding(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (pendingAttachment.isImage) {
                        AsyncImage(
                            model = pendingAttachment.uriString,
                            contentDescription = null,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(6.dp))
                        )
                    } else {
                        GIcon(
                            imageVector = GIcons.Description,
                            contentDescription = null,
                            tint = GlassTheme.colors.primary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    GText(
                        text = pendingAttachment.fileName,
                        style = GlassTheme.type.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1
                    )
                    GIconButton(
                        onClick = { onSetAttachment(null) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        GIcon(
                            imageVector = GIcons.Close,
                            contentDescription = stringResource(R.string.delete),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 3. Liquid-glass floating input pill (translucent; aurora shows through)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(GlassTheme.colors.surface.copy(alpha = 0.60f))
                .border(1.dp, GlassTheme.colors.primary.copy(alpha = 0.35f), RoundedCornerShape(28.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment "+" Button
            GIconButton(
                onClick = { showAttachmentSheet = true },
                modifier = Modifier.size(38.dp)
            ) {
                GIcon(
                    imageVector = GIcons.Add,
                    contentDescription = stringResource(R.string.attach_file),
                    tint = GlassTheme.colors.primary
                )
            }

            // Input Text Field
            GTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                placeholder = {
                    GText(
                        text = if (activeModel == null) stringResource(R.string.no_model_downloaded) else stringResource(R.string.input_placeholder),
                        style = GlassTheme.type.bodyLarge,
                        color = GlassTheme.colors.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                },
                modifier = Modifier.weight(1f),
                maxLines = 4
            )

            // Right side buttons
            if (isGenerating) {
                // Stop Generation button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(GlassTheme.colors.primary)
                        .clickable { onStopGeneration() },
                    contentAlignment = Alignment.Center
                ) {
                    GIcon(
                        imageVector = GIcons.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = GlassTheme.colors.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else if (inputText.isNotBlank() || pendingAttachment != null) {
                // Send button
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(GlassTheme.colors.primary)
                        .clickable { onSendMessage() },
                    contentAlignment = Alignment.Center
                ) {
                    GIcon(
                        imageVector = GIcons.ArrowForward,
                        contentDescription = stringResource(R.string.send),
                        tint = GlassTheme.colors.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            } else {
                // Speech-To-Text mic button
                GIconButton(
                    onClick = onStartVoiceInput,
                    modifier = Modifier.size(36.dp)
                ) {
                    GIcon(
                        imageVector = GIcons.Mic,
                        contentDescription = stringResource(R.string.voice_input),
                        tint = GlassTheme.colors.primary
                    )
                }

                // Interactive Voice Mode Headphone button
                GIconButton(
                    onClick = onOpenVoiceMode,
                    modifier = Modifier.size(36.dp)
                ) {
                    GIcon(
                        imageVector = GIcons.Headphones,
                        contentDescription = stringResource(R.string.nav_voice_mode),
                        tint = GlassTheme.colors.primary
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
            color = GlassTheme.colors.onSurface
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
                tint = GlassTheme.colors.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                GText(
                    text = stringResource(R.string.chat_attach_image),
                    style = GlassTheme.type.bodyMedium
                )
                GText(
                    text = if (activeModel?.hasMmproj == true) stringResource(R.string.chat_attach_image_mmproj_hint) else stringResource(R.string.chat_attach_text_only),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        GOptionRow(
            onClick = {
                documentPicker.launch(arrayOf("text/*", "application/pdf", "*/*"))
            }
        ) {
            GIcon(
                imageVector = GIcons.Description,
                contentDescription = null,
                tint = GlassTheme.colors.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                GText(
                    text = stringResource(R.string.chat_attach_document),
                    style = GlassTheme.type.bodyMedium
                )
                GText(
                    text = stringResource(R.string.chat_attach_document_hint),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
