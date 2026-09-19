package com.localllm.android.ui.chat

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.model.LlmModel
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.glass.GDivider
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GMenu
import com.localllm.android.ui.glass.GMenuItem
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

@Composable
fun ChatTopBar(
    activeModel: LlmModel?,
    allModels: List<LlmModel>,
    currentRuntime: ModelRuntimeType,
    isMtpOn: Boolean,
    onOpenDrawer: () -> Unit,
    onNewChat: () -> Unit,
    onSelectModel: (LlmModel) -> Unit,
    onOpenSettings: () -> Unit,
    onSwitchRuntime: (ModelRuntimeType) -> Unit,
    onOpenModelManager: () -> Unit = {},
    onOpenApiMode: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var isModelMenuExpanded by remember { mutableStateOf(false) }
    var isMoreMenuExpanded by remember { mutableStateOf(false) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Drawer Hamburger Menu
        GIconButton(onClick = onOpenDrawer) {
            GIcon(
                imageVector = GIcons.Menu,
                contentDescription = stringResource(R.string.conversations),
                tint = GlassTheme.colors.onSurface
            )
        }

        // Center Model Selector Pill (liquid glass)
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(GlassTheme.colors.surface.copy(alpha = 0.55f))
                .border(1.dp, GlassTheme.colors.primary.copy(alpha = 0.35f), RoundedCornerShape(22.dp))
                .clickable { isModelMenuExpanded = true }
                .padding(horizontal = 14.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (activeModel != null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GText(
                                text = activeModel.name,
                                style = GlassTheme.type.titleSmall,
                                color = GlassTheme.colors.onSurface
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            GIcon(
                                imageVector = GIcons.ArrowDown,
                                contentDescription = null,
                                tint = GlassTheme.colors.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GText(
                                text = activeModel.runtimeBadge,
                                style = GlassTheme.type.labelSmall,
                                color = GlassTheme.colors.primary,
                                fontSize = 10.sp
                            )
                            if (activeModel.isVisionDownloaded || activeModel.hasMmproj) {
                                GText(
                                    text = " • Vision",
                                    style = GlassTheme.type.labelSmall,
                                    color = GlassTheme.colors.tertiary,
                                    fontSize = 10.sp
                                )
                            }
                            if (activeModel.isMtpDownloaded || (isMtpOn && activeModel.supportsMtp)) {
                                GText(
                                    text = " • MTP 2x",
                                    style = GlassTheme.type.labelSmall,
                                    color = GlassTheme.colors.secondary,
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GIcon(
                            imageVector = GIcons.CloudDownload,
                            contentDescription = null,
                            tint = GlassTheme.colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GText(
                            text = "모델 다운로드 필요",
                            style = GlassTheme.type.titleSmall,
                            color = GlassTheme.colors.primary
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        GIcon(
                            imageVector = GIcons.ArrowDown,
                            contentDescription = null,
                            tint = GlassTheme.colors.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // Model Dropdown
            GMenu(
                expanded = isModelMenuExpanded,
                onDismissRequest = { isModelMenuExpanded = false }
            ) {
                GMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIcon(
                                imageVector = GIcons.CloudDownload,
                                contentDescription = null,
                                tint = GlassTheme.colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            GText(
                                text = "모델 다운로드 및 관리",
                                style = GlassTheme.type.labelLarge,
                                color = GlassTheme.colors.primary
                            )
                        }
                    },
                    onClick = {
                        isModelMenuExpanded = false
                        onOpenModelManager()
                    }
                )

                GDivider(modifier = Modifier.padding(vertical = 4.dp))

                GText(
                    text = "로컬 모델 목록",
                    style = GlassTheme.type.labelMedium,
                    color = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )

                allModels.forEach { model ->
                    val isSelected = model.id == activeModel?.id
                    GMenuItem(
                        text = {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    GText(
                                        text = model.name,
                                        style = GlassTheme.type.bodyMedium,
                                        color = if (isSelected) GlassTheme.colors.primary else GlassTheme.colors.onSurface
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    if (model.supportsMtp) {
                                        GIcon(
                                            imageVector = GIcons.Bolt,
                                            contentDescription = "MTP 지원",
                                            tint = GlassTheme.colors.primary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                    }
                                    if (!model.isDownloaded) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(GlassTheme.colors.errorContainer)
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            GText(
                                                text = "미다운로드",
                                                style = GlassTheme.type.labelSmall,
                                                color = GlassTheme.colors.onErrorContainer,
                                                fontSize = 9.sp
                                            )
                                        }
                                    }
                                }
                                GText(
                                    text = "${model.runtimeBadge} • ${model.displaySize} • ${model.quantization}",
                                    style = GlassTheme.type.labelSmall,
                                    color = GlassTheme.colors.onSurfaceVariant
                                )
                            }
                        },
                        trailingIcon = if (isSelected) {
                            {
                                GIcon(
                                    imageVector = GIcons.Check,
                                    contentDescription = null,
                                    tint = GlassTheme.colors.primary
                                )
                            }
                        } else null,
                        onClick = {
                            if (model.isDownloaded) {
                                onSelectModel(model)
                            } else {
                                onOpenModelManager()
                            }
                            isModelMenuExpanded = false
                        }
                    )
                }
            }
        }

        // Right Actions: New Chat & More
        Row(verticalAlignment = Alignment.CenterVertically) {
            GIconButton(onClick = onNewChat) {
                GIcon(
                    imageVector = GIcons.Add,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = GlassTheme.colors.onSurface
                )
            }

            Box {
                GIconButton(onClick = { isMoreMenuExpanded = true }) {
                    GIcon(
                        imageVector = GIcons.MoreVert,
                        contentDescription = stringResource(R.string.details),
                        tint = GlassTheme.colors.onSurface
                    )
                }

                GMenu(
                    expanded = isMoreMenuExpanded,
                    onDismissRequest = { isMoreMenuExpanded = false }
                ) {
                    GMenuItem(
                        text = {
                            GText(stringResource(R.string.nav_models))
                        },
                        leadingIcon = {
                            GIcon(GIcons.CloudDownload, contentDescription = null)
                        },
                        onClick = {
                            onOpenModelManager()
                            isMoreMenuExpanded = false
                        }
                    )

                    GMenuItem(
                        text = {
                            GText("런타임: ${if (currentRuntime == ModelRuntimeType.LLAMA_CPP) "llama.cpp" else "LiteRT LM"}")
                        },
                        leadingIcon = {
                            GIcon(GIcons.Tune, contentDescription = null)
                        },
                        onClick = {
                            val next = if (currentRuntime == ModelRuntimeType.LLAMA_CPP) ModelRuntimeType.LITE_RT else ModelRuntimeType.LLAMA_CPP
                            onSwitchRuntime(next)
                            isMoreMenuExpanded = false
                        }
                    )

                    GMenuItem(
                        text = {
                            GText(stringResource(R.string.nav_api_mode))
                        },
                        leadingIcon = {
                            GIcon(GIcons.Dns, contentDescription = null, tint = GlassTheme.colors.primary)
                        },
                        onClick = {
                            onOpenApiMode()
                            isMoreMenuExpanded = false
                        }
                    )

                    GMenuItem(
                        text = { GText(stringResource(R.string.nav_settings)) },
                        leadingIcon = {
                            GIcon(GIcons.Tune, contentDescription = null)
                        },
                        onClick = {
                            onOpenSettings()
                            isMoreMenuExpanded = false
                        }
                    )
                }
            }
        }
    }
}
