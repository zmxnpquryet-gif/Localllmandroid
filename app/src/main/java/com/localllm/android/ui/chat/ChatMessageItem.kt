package com.localllm.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.localllm.android.R
import com.localllm.android.model.ChatMessage
import com.localllm.android.model.MessageRole
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun ChatMessageItem(
    message: ChatMessage,
    showMetrics: Boolean,
    isMtpOn: Boolean,
    reasoningEffortLabel: String,
    onSpeak: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isUser = message.role == MessageRole.USER
    val copiedText = stringResource(R.string.copied)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        if (isUser) {
            // User Message (Right-aligned artistic bubble)
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp))
                    .background(GlassTheme.colors.primaryContainer)
                    .border(
                        1.dp,
                        GlassTheme.colors.primary.copy(alpha = 0.25f),
                        RoundedCornerShape(topStart = 20.dp, topEnd = 6.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
                    )
                    .padding(14.dp)
            ) {
                // Attached file preview
                if (message.attachment != null) {
                    val att = message.attachment
                    if (att.isImage) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                        ) {
                            AsyncImage(
                                model = att.uriString,
                                contentDescription = stringResource(R.string.chat_attached_image_desc),
                                modifier = Modifier.matchParentSize()
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(GlassTheme.colors.surface.copy(alpha = 0.5f))
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            GIcon(
                                imageVector = GIcons.Description,
                                contentDescription = null,
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            GText(
                                text = att.fileName,
                                style = GlassTheme.type.bodySmall,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }

                if (message.content.isNotBlank()) {
                    GText(
                        text = message.content,
                        style = GlassTheme.type.bodyLarge,
                        color = GlassTheme.colors.onPrimaryContainer
                    )
                }
            }
        } else {
            // Assistant Message (ChatGPT style left aligned layout)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                // ChatGPT spark avatar
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(GlassTheme.colors.primary)
                        .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    GIcon(
                        imageVector = GIcons.AutoAwesome,
                        contentDescription = "AI",
                        tint = GlassTheme.colors.onPrimary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    // Reasoning Card (if DeepSeek/Qwen thinking model or reasoning exists)
                    if (!message.reasoning.isNullOrBlank() || message.isReasoningStreaming) {
                        ReasoningCard(
                            reasoningText = message.reasoning ?: "",
                            isStreaming = message.isReasoningStreaming,
                            reasoningEffortLabel = reasoningEffortLabel,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    // Main Text Content
                    if (message.content.isNotBlank()) {
                        GText(
                            text = message.content,
                            style = GlassTheme.type.bodyLarge.copy(
                                lineHeight = 24.sp,
                                color = GlassTheme.colors.onBackground
                            )
                        )
                    } else if (message.isStreaming && !message.isReasoningStreaming) {
                        // Pulsing cursor
                        GText(
                            text = "●",
                            style = GlassTheme.type.bodyLarge,
                            color = GlassTheme.colors.primary
                        )
                    }

                    // Performance stats & action buttons
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        if (showMetrics && (message.tps > 0f || message.promptSpeed > 0f)) {
                            PerformanceMetricsChip(
                                tps = message.tps,
                                promptSpeed = message.promptSpeed,
                                contextTokens = message.contextTokens,
                                isMtpOn = isMtpOn
                            )
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        // Action Icons: Copy, Speak
                        if (!message.isStreaming && message.content.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GIconButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("AI Response", message.content))
                                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    GIcon(
                                        imageVector = GIcons.ContentCopy,
                                        contentDescription = stringResource(R.string.copy),
                                        tint = GlassTheme.colors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                GIconButton(
                                    onClick = { onSpeak(message.content) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    GIcon(
                                        imageVector = GIcons.VolumeUp,
                                        contentDescription = stringResource(R.string.chat_speak_desc),
                                        tint = GlassTheme.colors.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
