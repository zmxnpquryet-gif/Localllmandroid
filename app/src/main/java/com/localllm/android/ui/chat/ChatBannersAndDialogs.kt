package com.localllm.android.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.engine.McpToolCall
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GLinearProgress
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GlassTheme
import com.localllm.android.update.AppUpdateInfo
import com.localllm.android.update.EngineVersions
import java.util.Locale

@Composable
internal fun ModelLoadingBanner(
    visible: Boolean,
    stage: String,
    progress: Float
) {
    AnimatedVisibility(visible = visible) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            GCard(cornerRadius = 14.dp) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(
                                imageVector = GIcons.Memory,
                                contentDescription = null,
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            GText(
                                text = if (stage.isNotBlank()) stringResource(R.string.model_loading, stage)
                                else stringResource(R.string.model_loading_default),
                                style = GlassTheme.type.labelSmall,
                                color = GlassTheme.colors.primary
                            )
                        }
                        GText(
                            text = if (progress > 0f) String.format(Locale.US, "%.0f%%", progress * 100f) else "",
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    GLinearProgress(
                        progress = if (progress <= 0f) null else progress,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        color = GlassTheme.colors.primary,
                        trackColor = GlassTheme.colors.surfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
internal fun ApiModeBanner(
    visible: Boolean,
    apiPort: Int,
    onOpenApiMode: () -> Unit
) {
    AnimatedVisibility(visible = visible) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
        ) {
            GCard(cornerRadius = 14.dp) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenApiMode)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(
                                imageVector = GIcons.Dns,
                                contentDescription = null,
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            GText(
                                text = stringResource(R.string.chat_api_banner_active, apiPort),
                                fontSize = 11.sp,
                                color = GlassTheme.colors.onSurface
                            )
                        }
                        GText(
                            text = stringResource(R.string.chat_api_banner_details),
                            fontSize = 11.sp,
                            color = GlassTheme.colors.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun UpdateBanner(
    update: AppUpdateInfo,
    onDismiss: () -> Unit
) {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        GCard(cornerRadius = 14.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri(update.releaseUrl) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    GText(
                        text = stringResource(R.string.update_banner_title, update.latestVersion),
                        style = GlassTheme.type.bodySmall,
                        color = GlassTheme.colors.onSurface
                    )
                    GText(
                        text = stringResource(
                            R.string.update_banner_engine,
                            EngineVersions.LLAMA_CPP,
                            EngineVersions.LITERT_LM
                        ),
                        style = GlassTheme.type.labelSmall,
                        color = GlassTheme.colors.onSurfaceVariant
                    )
                }
                GText(
                    text = stringResource(R.string.update_banner_action),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.primary
                )
                GIconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                    GIcon(
                        imageVector = GIcons.Close,
                        contentDescription = stringResource(R.string.update_banner_dismiss),
                        tint = GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun EngineStatusStrip(
    status: String?,
    onShowDetails: () -> Unit
) {
    if (status.isNullOrBlank()) return
    val isErrorStatus = isEngineErrorStatus(status)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isErrorStatus) GlassTheme.colors.errorContainer.copy(alpha = 0.3f)
                else Color.Transparent
            )
            .clickable(onClick = onShowDetails)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        GText(
            text = status,
            style = GlassTheme.type.bodySmall,
            color = if (isErrorStatus) GlassTheme.colors.onErrorContainer else GlassTheme.colors.onSurfaceVariant,
            maxLines = 2,
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        GText(
            text = stringResource(R.string.chat_status_details_short),
            style = GlassTheme.type.labelSmall,
            color = if (isErrorStatus) GlassTheme.colors.error else GlassTheme.colors.onSurfaceVariant
        )
    }
}

@Composable
internal fun McpToolApprovalDialog(
    call: McpToolCall,
    onResolve: (Boolean) -> Unit
) {
    GDialog(
        onDismissRequest = { onResolve(false) },
        title = {
            GText(
                text = stringResource(R.string.mcp_approval_title),
                style = GlassTheme.type.titleMedium
            )
        },
        text = {
            Column {
                GText(
                    text = stringResource(R.string.mcp_approval_body, call.name),
                    style = GlassTheme.type.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                GText(
                    text = call.argumentsJson.take(1500),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            GTextButton(onClick = { onResolve(true) }) {
                GText(stringResource(R.string.mcp_approval_run))
            }
        },
        dismissButton = {
            GTextButton(onClick = { onResolve(false) }) {
                GText(stringResource(R.string.mcp_approval_decline))
            }
        }
    )
}

@Composable
internal fun EngineStatusDetailDialog(
    context: Context,
    status: String,
    onDismiss: () -> Unit
) {
    GDialog(
        onDismissRequest = onDismiss,
        title = {
            GText(
                text = if (isEngineErrorStatus(status)) stringResource(R.string.engine_error_details)
                else stringResource(R.string.engine_status),
                style = GlassTheme.type.titleMedium
            )
        },
        text = {
            GText(
                text = status,
                style = GlassTheme.type.bodySmall,
                color = GlassTheme.colors.onSurface
            )
        },
        confirmButton = {
            val copiedToast = stringResource(R.string.copied)
            GTextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    clipboard?.setPrimaryClip(ClipData.newPlainText("Engine Status", status))
                    Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                }
            ) {
                GText(stringResource(R.string.copy))
            }
        },
        dismissButton = {
            GTextButton(onClick = onDismiss) {
                GText(stringResource(R.string.close))
            }
        }
    )
}

@Composable
internal fun JumpToLatestButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(GlassTheme.colors.primaryContainer)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GIcon(
                imageVector = GIcons.ArrowDown,
                contentDescription = stringResource(R.string.chat_jump_latest_desc),
                tint = GlassTheme.colors.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            GText(
                text = stringResource(R.string.chat_jump_latest),
                style = GlassTheme.type.labelMedium,
                color = GlassTheme.colors.onPrimaryContainer
            )
        }
    }
}

internal fun isEngineErrorStatus(status: String?): Boolean {
    if (status.isNullOrBlank()) return false
    val lower = status.lowercase()
    if (status.contains("대체 실행") || status.contains("回退") || status.contains("替代") ||
        lower.contains("fallback")
    ) return false
    return lower.contains("error") || lower.contains("failed") || lower.contains("exception") ||
        status.contains("오류") || status.contains("실패") || status.contains("예외") ||
        status.contains("错误") || status.contains("失败") || status.contains("异常")
}
