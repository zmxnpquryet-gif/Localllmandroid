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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
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
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme
import java.util.Locale

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
    onDocumentPicked: (android.net.Uri, String) -> Unit = { _, _ -> },
    indexedDocuments: List<com.localllm.android.document.DocumentSummary> = emptyList(),
    activeDocumentIds: Set<String> = emptySet(),
    onToggleDocument: (String) -> Unit = {},
    onDeleteDocument: (String) -> Unit = {},
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
    val isDarkCanvas = GlassTheme.colors.background.luminance() < 0.5f

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

    // Document Picker contract: extracts and indexes the file for retrieval.
    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val displayName = runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: uri.lastPathSegment ?: "document"
            onDocumentPicked(uri, displayName)
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
                    .background(GlassTheme.colors.secondary)
                    .clickable { showReasoningSlider = !showReasoningSlider }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Psychology,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
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
                        .background(GlassTheme.colors.surface)
                        .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(12.dp))
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
                    .background(GlassTheme.colors.surface)
                    .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(12.dp))
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
                            tint = GlassTheme.colors.onSurfaceVariant,
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
                            color = GlassTheme.colors.onSurfaceVariant
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

        // 3. Quiet composer capsule
        val capsuleShape = RoundedCornerShape(20.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = if (isDarkCanvas) 0.dp else 1.dp,
                    shape = capsuleShape,
                    ambientColor = Color.Black.copy(alpha = 0.04f),
                    spotColor = Color.Black.copy(alpha = 0.06f)
                )
                .clip(capsuleShape)
                .background(GlassTheme.colors.surface)
                .border(1.dp, GlassTheme.colors.outline, capsuleShape)
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Attachment Icon Button
            GIconButton(
                onClick = { showAttachmentSheet = true },
                modifier = Modifier.size(38.dp)
            ) {
                GIcon(
                    imageVector = GIcons.Add,
                    contentDescription = stringResource(R.string.attach_file),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }

            // Hands-free (live) voice mode trigger
            GIconButton(
                onClick = onOpenVoiceMode,
                modifier = Modifier.size(38.dp)
            ) {
                GIcon(
                    imageVector = GIcons.VoiceMode,
                    contentDescription = stringResource(R.string.nav_voice_mode),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }

            // Main Text Input Field
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (inputText.isEmpty()) {
                    GText(
                        text = stringResource(R.string.chat_input_hint),
                        style = GlassTheme.type.bodyLarge,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = inputText,
                    onValueChange = onInputTextChanged,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = GlassTheme.type.bodyLarge.copy(color = GlassTheme.colors.onSurface),
                    cursorBrush = SolidColor(GlassTheme.colors.primary),
                    maxLines = 5
                )
            }

            // Mic Icon Button (dictation)
            GIconButton(
                onClick = onStartVoiceInput,
                modifier = Modifier.size(38.dp)
            ) {
                GIcon(
                    imageVector = GIcons.Mic,
                    contentDescription = stringResource(R.string.voice_input),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(21.dp)
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Send / Stop Action Button
            val canSend = inputText.isNotBlank() || pendingAttachment != null
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            isGenerating -> GlassTheme.colors.primary
                            canSend -> GlassTheme.colors.primary
                            else -> GlassTheme.colors.surfaceVariant
                        }
                    )
                    .clickable(enabled = isGenerating || canSend) {
                        if (isGenerating) onStopGeneration() else onSendMessage()
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isGenerating) {
                    GIcon(
                        imageVector = GIcons.Stop,
                        contentDescription = stringResource(R.string.stop),
                        tint = GlassTheme.colors.onPrimary,
                        modifier = Modifier.size(15.dp)
                    )
                } else {
                    GIcon(
                        imageVector = GIcons.ArrowUp,
                        contentDescription = stringResource(R.string.send),
                        tint = if (canSend) GlassTheme.colors.onPrimary else GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        // Streaming / loading status strip below the capsule
        val status = when {
            isModelLoading -> modelLoadingStage.ifBlank { stringResource(R.string.model_loading_default) }
            isGenerating && currentTps > 0f -> String.format(Locale.US, "%.1f tok/s · %d ctx", currentTps, contextTokens)
            isGenerating && promptSpeed > 0f -> String.format(Locale.US, "프롬프트 처리 중 · %.1f tok/s", promptSpeed)
            isGenerating -> "생성 중…"
            else -> null
        }
        if (status != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(GlassTheme.colors.onSurfaceVariant)
                )
                Spacer(modifier = Modifier.width(8.dp))
                GText(
                    text = status,
                    style = GlassTheme.type.labelSmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    ),
                    color = GlassTheme.colors.onSurfaceVariant,
                    fontSize = 10.sp
                )
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
                tint = GlassTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                GText(
                    text = stringResource(R.string.chat_attach_image),
                    style = GlassTheme.type.bodyLarge,
                    color = GlassTheme.colors.onSurface
                )
                GText(
                    text = stringResource(R.string.chat_attach_image_mmproj_hint),
                    style = GlassTheme.type.bodySmall,
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
                tint = GlassTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column {
                GText(
                    text = stringResource(R.string.chat_attach_document),
                    style = GlassTheme.type.bodyLarge,
                    color = GlassTheme.colors.onSurface
                )
                GText(
                    text = stringResource(R.string.chat_attach_document_hint),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }
        }

        if (indexedDocuments.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            GText(
                text = stringResource(R.string.chat_library_title),
                style = GlassTheme.type.titleSmall,
                color = GlassTheme.colors.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            GText(
                text = stringResource(R.string.chat_library_hint),
                style = GlassTheme.type.bodySmall,
                color = GlassTheme.colors.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            indexedDocuments.forEach { document ->
                val included = document.id in activeDocumentIds
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onToggleDocument(document.id) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        GText(
                            text = document.name,
                            style = GlassTheme.type.bodyMedium,
                            color = GlassTheme.colors.onSurface,
                            maxLines = 1
                        )
                        GText(
                            text = stringResource(R.string.chat_library_chunks, document.chunks),
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                    GIconButton(
                        onClick = { onDeleteDocument(document.id) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        GIcon(
                            imageVector = GIcons.Close,
                            contentDescription = stringResource(R.string.chat_library_delete),
                            tint = GlassTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                    GSwitch(checked = included, onCheckedChange = { onToggleDocument(document.id) })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}
