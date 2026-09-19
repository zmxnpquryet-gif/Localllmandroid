package com.localllm.android.ui.drawer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.model.Conversation
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GDivider
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTextField
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun ChatDrawer(
    conversations: List<Conversation>,
    currentConversationId: String?,
    onSelectConversation: (String) -> Unit,
    onNewChat: () -> Unit,
    onDeleteConversation: (String) -> Unit,
    onRenameConversation: (String, String) -> Unit,
    onOpenModelManager: () -> Unit,
    onOpenVoiceMode: () -> Unit,
    onOpenApiMode: () -> Unit = {},
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    var renameTargetConv by remember { mutableStateOf<Conversation?>(null) }
    var newTitleInput by remember { mutableStateOf("") }

    val filtered = remember(conversations, searchQuery) {
        if (searchQuery.isBlank()) conversations
        else conversations.filter { it.title.contains(searchQuery, ignoreCase = true) }
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .width(310.dp)
            .background(GlassTheme.colors.surface.copy(alpha = 0.82f))
            .statusBarsPadding()
            .padding(vertical = 12.dp)
    ) {
        // 1. New Chat Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(GlassTheme.colors.surfaceVariant)
                .border(
                    1.dp,
                    GlassTheme.colors.primary.copy(alpha = 0.25f),
                    RoundedCornerShape(12.dp)
                )
                .clickable { onNewChat() }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = GIcons.Add,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                GText(
                    text = stringResource(R.string.new_chat),
                    style = GlassTheme.type.titleSmall,
                    color = GlassTheme.colors.onSurface
                )
            }
            GIcon(
                imageVector = GIcons.Edit,
                contentDescription = null,
                tint = GlassTheme.colors.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }

        // 2. Search Conversations
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Search,
                    contentDescription = "검색",
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                GTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        GText(
                            text = "대화 검색...",
                            fontSize = 13.sp,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        // SQLite Encryption Notice
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GIcon(
                imageVector = GIcons.Lock,
                contentDescription = "암호화",
                tint = GlassTheme.colors.primary,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            GText(
                text = "로컬 SQLite AES-256 암호화 저장됨",
                fontSize = 11.sp,
                color = GlassTheme.colors.onSurfaceVariant
            )
        }

        GDivider(modifier = Modifier.padding(vertical = 8.dp))

        // 3. Conversation List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    GText(
                        text = "대화 기록이 없습니다.",
                        style = GlassTheme.type.bodySmall,
                        color = GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                items(filtered, key = { it.id }) { conv ->
                    val isSelected = conv.id == currentConversationId
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) GlassTheme.colors.primaryContainer.copy(alpha = 0.8f)
                                else Color.Transparent
                            )
                            .clickable { onSelectConversation(conv.id) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            GIcon(
                                imageVector = GIcons.Chat,
                                contentDescription = null,
                                tint = if (isSelected) GlassTheme.colors.primary else GlassTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            GText(
                                text = conv.title,
                                style = GlassTheme.type.bodyMedium,
                                color = if (isSelected) GlassTheme.colors.onPrimaryContainer else GlassTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }

                        if (isSelected) {
                            Row {
                                GIconButton(
                                    onClick = {
                                        renameTargetConv = conv
                                        newTitleInput = conv.title
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    GIcon(
                                        imageVector = GIcons.Edit,
                                        contentDescription = "수정",
                                        tint = GlassTheme.colors.onSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                GIconButton(
                                    onClick = { onDeleteConversation(conv.id) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    GIcon(
                                        imageVector = GIcons.Delete,
                                        contentDescription = "삭제",
                                        tint = GlassTheme.colors.error,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        GDivider()

        // 4. Bottom Navigation actions
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            // Model Management
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenModelManager() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Storage,
                    contentDescription = stringResource(R.string.nav_models),
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    GText(
                        text = stringResource(R.string.nav_models),
                        style = GlassTheme.type.bodyMedium
                    )
                    GText(
                        text = "GGUF / LiteRT LM",
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }

            // Interactive Voice Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenVoiceMode() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Headphones,
                    contentDescription = stringResource(R.string.nav_voice_mode),
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    GText(
                        text = stringResource(R.string.nav_voice_mode),
                        style = GlassTheme.type.bodyMedium
                    )
                    GText(
                        text = "STT + TTS",
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }

            // API Dedicated Mode (Port 11434)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenApiMode() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Dns,
                    contentDescription = stringResource(R.string.nav_api_mode),
                    tint = GlassTheme.colors.secondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    GText(
                        text = stringResource(R.string.nav_api_mode),
                        style = GlassTheme.type.bodyMedium
                    )
                    GText(
                        text = "Port 11434 Ollama/OpenAI",
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
            }

            // Settings
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onOpenSettings() }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.Settings,
                    contentDescription = stringResource(R.string.nav_settings),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                GText(
                    text = stringResource(R.string.nav_settings),
                    style = GlassTheme.type.bodyMedium
                )
            }
        }
    }

    // Rename Dialog
    if (renameTargetConv != null) {
        GDialog(
            onDismissRequest = { renameTargetConv = null },
            title = { GText("대화 제목 변경") },
            text = {
                GTextField(
                    value = newTitleInput,
                    onValueChange = { newTitleInput = it },
                    label = { GText("대화 제목") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                GTextButton(
                    onClick = {
                        val conv = renameTargetConv
                        if (conv != null && newTitleInput.isNotBlank()) {
                            onRenameConversation(conv.id, newTitleInput.trim())
                        }
                        renameTargetConv = null
                    }
                ) {
                    GText("변경")
                }
            },
            dismissButton = {
                GTextButton(onClick = { renameTargetConv = null }) {
                    GText("취소")
                }
            }
        )
    }
}
