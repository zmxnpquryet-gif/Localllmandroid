package com.localllm.android.ui.models

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.model.ModelRuntimeType
import com.localllm.android.ui.glass.GButton
import com.localllm.android.ui.glass.GDialog
import com.localllm.android.ui.glass.GFilterChip
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GOutlineButton
import com.localllm.android.ui.glass.GSwitch
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GTextButton
import com.localllm.android.ui.glass.GTextField
import com.localllm.android.ui.glass.GlassTheme

@Composable
internal fun FdmAddBundleDialog(
    context: Context,
    initialHfToken: String = "",
    onDismiss: () -> Unit,
    onStartDownload: (
        name: String,
        runtime: ModelRuntimeType,
        mainUrl: String,
        customFileName: String,
        visionUrl: String,
        mtpUrl: String,
        templateUrl: String,
        supportsReasoning: Boolean,
        hfToken: String,
        hasEmbeddedVision: Boolean,
        hasEmbeddedDrafter: Boolean
    ) -> Unit
) {
    val mmDefaultName = stringResource(R.string.mm_default_name)
    var modelNameInput by remember { mutableStateOf("") }
    var runtimeChoice by remember { mutableStateOf(ModelRuntimeType.LLAMA_CPP) }
    var mainUrlInput by remember { mutableStateOf("") }
    var customFileNameInput by remember { mutableStateOf("") }
    var visionUrlInput by remember { mutableStateOf("") }
    var mtpUrlInput by remember { mutableStateOf("") }
    var templateUrlInput by remember { mutableStateOf("") }
    var supportsReasoningInput by remember { mutableStateOf(false) }
    var hasEmbeddedVisionInput by remember { mutableStateOf(false) }
    var hasEmbeddedDrafterInput by remember { mutableStateOf(false) }
    var hfTokenInput by remember { mutableStateOf(initialHfToken) }
    var isTokenVisible by remember { mutableStateOf(false) }

    val clipboard = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }

    GDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = GIcons.CloudDownload,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                GText(stringResource(R.string.model_manager_custom_dialog_title))
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GText(
                    text = stringResource(R.string.model_manager_custom_dialog_desc),
                    style = GlassTheme.type.bodySmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    GOutlineButton(
                        onClick = {
                            modelNameInput = "DeepSeek R1 (GGUF)"
                            runtimeChoice = ModelRuntimeType.LLAMA_CPP
                            mainUrlInput = "https://huggingface.co/unsloth/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
                            customFileNameInput = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = ""
                            hasEmbeddedVisionInput = false
                            supportsReasoningInput = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        GText("DeepSeek R1", fontSize = 11.sp)
                    }
                    GOutlineButton(
                        onClick = {
                            modelNameInput = mmDefaultName
                            runtimeChoice = ModelRuntimeType.LITE_RT
                            mainUrlInput = "https://huggingface.co/lotapa/gemma3-1b-it-int4.litertlm/resolve/main/gemma3-1b-it-int4.litertlm"
                            customFileNameInput = "gemma3-1b-it-int4.litertlm"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = ""
                            hasEmbeddedVisionInput = true
                            supportsReasoningInput = false
                        },
                        modifier = Modifier.weight(1.1f)
                    ) {
                        GText(stringResource(R.string.model_manager_litert_vision), fontSize = 11.sp)
                    }
                    GOutlineButton(
                        onClick = {
                            modelNameInput = "Phi-4 Mini (LiteRT)"
                            runtimeChoice = ModelRuntimeType.LITE_RT
                            mainUrlInput = "https://huggingface.co/litert-community/Phi-4-mini-instruct/resolve/main/Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"
                            customFileNameInput = "Phi-4-mini-instruct_multi-prefill-seq_q8_ekv4096.litertlm"
                            visionUrlInput = ""
                            mtpUrlInput = ""
                            templateUrlInput = ""
                            hasEmbeddedVisionInput = false
                            supportsReasoningInput = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        GText(stringResource(R.string.model_manager_template_litert), fontSize = 11.sp)
                    }
                }

                GTextField(
                    value = modelNameInput,
                    onValueChange = { modelNameInput = it },
                    label = { GText(stringResource(R.string.model_manager_field_model_name)) },
                    placeholder = { GText(stringResource(R.string.model_manager_field_model_name_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GText(stringResource(R.string.model_manager_field_runtime), style = GlassTheme.type.bodyMedium)
                    Row {
                        GFilterChip(
                            selected = runtimeChoice == ModelRuntimeType.LLAMA_CPP,
                            onClick = { runtimeChoice = ModelRuntimeType.LLAMA_CPP },
                            label = { GText("llama.cpp") }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        GFilterChip(
                            selected = runtimeChoice == ModelRuntimeType.LITE_RT,
                            onClick = { runtimeChoice = ModelRuntimeType.LITE_RT },
                            label = { GText("LiteRT LM") }
                        )
                    }
                }

                if (runtimeChoice == ModelRuntimeType.LITE_RT) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(GlassTheme.colors.surfaceVariant)
                            .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        GText(
                            text = stringResource(R.string.model_manager_litert_hint),
                            style = GlassTheme.type.labelSmall,
                            color = GlassTheme.colors.onSurfaceVariant
                        )
                    }
                }

                GTextField(
                    value = customFileNameInput,
                    onValueChange = { customFileNameInput = it },
                    label = { GText(stringResource(R.string.model_manager_field_filename)) },
                    placeholder = {
                        GText(if (runtimeChoice == ModelRuntimeType.LITE_RT) "custom_model.bin" else "custom_model.gguf")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                PasteableUrlField(
                    value = mainUrlInput,
                    onValueChange = {
                        mainUrlInput = it
                        if (customFileNameInput.isBlank() && it.contains('/')) {
                            val derived = it.trim().substringAfterLast('/')
                            if (derived.isNotBlank()) customFileNameInput = derived
                        }
                    },
                    label = stringResource(R.string.model_manager_field_main_url),
                    placeholder = "https://.../model.gguf",
                    clipboard = clipboard
                )

                GText(
                    text = stringResource(R.string.model_manager_mirror_hint),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )

                GTextField(
                    value = hfTokenInput,
                    onValueChange = { hfTokenInput = it },
                    label = { GText(stringResource(R.string.model_manager_field_hf_token)) },
                    placeholder = { GText("hf_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx") },
                    singleLine = true,
                    visualTransformation = if (isTokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            GIconButton(onClick = { isTokenVisible = !isTokenVisible }) {
                                GIcon(
                                    imageVector = if (isTokenVisible) GIcons.VisibilityOff else GIcons.Visibility,
                                    contentDescription = if (isTokenVisible) stringResource(R.string.settings_hide) else stringResource(R.string.settings_show)
                                )
                            }
                            GIconButton(onClick = {
                                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                if (!clip.isNullOrBlank()) hfTokenInput = clip.trim()
                            }) {
                                GIcon(GIcons.ContentPaste, contentDescription = stringResource(R.string.model_manager_paste_desc))
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                PasteableUrlField(
                    value = templateUrlInput,
                    onValueChange = { templateUrlInput = it },
                    label = if (runtimeChoice == ModelRuntimeType.LITE_RT) {
                        stringResource(R.string.model_manager_field_template_url_recommended)
                    } else {
                        stringResource(R.string.model_manager_field_template_url_optional)
                    },
                    placeholder = stringResource(R.string.model_manager_field_template_url_hint),
                    clipboard = clipboard
                )

                BundleToggleRow(
                    icon = GIcons.Psychology,
                    title = stringResource(R.string.model_manager_reasoning_toggle),
                    description = stringResource(R.string.model_manager_reasoning_toggle_desc),
                    checked = supportsReasoningInput,
                    onCheckedChange = { supportsReasoningInput = it }
                )

                BundleToggleRow(
                    icon = GIcons.Visibility,
                    title = stringResource(R.string.model_manager_embedded_vision),
                    description = stringResource(R.string.model_manager_embedded_vision_desc),
                    checked = hasEmbeddedVisionInput,
                    onCheckedChange = { hasEmbeddedVisionInput = it }
                )

                BundleToggleRow(
                    icon = GIcons.Bolt,
                    title = stringResource(R.string.model_manager_embedded_drafter),
                    description = stringResource(R.string.model_manager_embedded_drafter_desc),
                    checked = hasEmbeddedDrafterInput,
                    onCheckedChange = { hasEmbeddedDrafterInput = it }
                )

                PasteableUrlField(
                    value = visionUrlInput,
                    onValueChange = { visionUrlInput = it },
                    label = stringResource(R.string.model_manager_field_vision_url),
                    placeholder = "https://.../mmproj.gguf",
                    clipboard = clipboard
                )

                PasteableUrlField(
                    value = mtpUrlInput,
                    onValueChange = { mtpUrlInput = it },
                    label = stringResource(R.string.model_manager_field_mtp_url),
                    placeholder = "https://.../draft.gguf",
                    clipboard = clipboard
                )
            }
        },
        confirmButton = {
            GButton(
                onClick = {
                    if (mainUrlInput.isNotBlank()) {
                        onStartDownload(
                            modelNameInput.trim(),
                            runtimeChoice,
                            mainUrlInput.trim(),
                            customFileNameInput.trim(),
                            visionUrlInput.trim(),
                            mtpUrlInput.trim(),
                            templateUrlInput.trim(),
                            supportsReasoningInput,
                            hfTokenInput.trim(),
                            hasEmbeddedVisionInput,
                            hasEmbeddedDrafterInput
                        )
                    }
                },
                enabled = mainUrlInput.isNotBlank()
            ) {
                GIcon(GIcons.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                GText(stringResource(R.string.model_manager_download_start))
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
private fun PasteableUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    clipboard: ClipboardManager
) {
    GTextField(
        value = value,
        onValueChange = onValueChange,
        label = { GText(label) },
        placeholder = { GText(placeholder) },
        trailingIcon = {
            GIconButton(onClick = {
                val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                if (!clip.isNullOrBlank()) onValueChange(clip)
            }) {
                GIcon(GIcons.ContentPaste, contentDescription = stringResource(R.string.model_manager_paste_desc))
            }
        },
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun BundleToggleRow(
    icon: ImageVector,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(GlassTheme.colors.surfaceVariant)
            .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GIcon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                GText(text = title, style = GlassTheme.type.bodyMedium)
            }
            GText(
                text = description,
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.onSurfaceVariant
            )
        }
        GSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

internal fun queryFileName(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) result = cursor.getString(index)
            }
        }
    }
    if (result == null) {
        result = uri.path?.let { p ->
            val cut = p.lastIndexOf('/')
            if (cut != -1) p.substring(cut + 1) else p
        }
    }
    return result
}
