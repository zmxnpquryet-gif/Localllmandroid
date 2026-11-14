package com.localllm.android.ui.api

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.ui.glass.GCard
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

@Composable
internal fun AddressRow(
    label: String,
    url: String,
    isEnabled: Boolean = true,
    statusBadge: String? = null,
    revealed: Boolean = true,
    onToggleReveal: (() -> Unit)? = null,
    onCopy: () -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GText(
                text = label,
                style = GlassTheme.type.labelSmall,
                color = GlassTheme.colors.onSurfaceVariant
            )
            if (statusBadge != null) {
                GText(
                    text = statusBadge,
                    style = GlassTheme.type.labelSmall,
                    color = if (isEnabled) GlassTheme.colors.primary else GlassTheme.colors.error
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(GlassTheme.colors.surfaceVariant)
                .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(8.dp))
                .clickable(onClick = onCopy)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GText(
                text = url,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (isEnabled) GlassTheme.colors.onSurface else GlassTheme.colors.onSurfaceVariant
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onToggleReveal != null) {
                    GIconButton(onClick = onToggleReveal, modifier = Modifier.size(28.dp)) {
                        GIcon(
                            imageVector = if (revealed) GIcons.VisibilityOff else GIcons.Visibility,
                            contentDescription = if (revealed) stringResource(R.string.api_hide_key_desc)
                            else stringResource(R.string.api_show_key_desc),
                            tint = GlassTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                }
                GIcon(
                    imageVector = GIcons.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = if (isEnabled) GlassTheme.colors.onSurfaceVariant else GlassTheme.colors.outline,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
internal fun EndpointRow(
    method: String,
    path: String,
    desc: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(GlassTheme.colors.surfaceVariant)
                .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            GText(
                text = method,
                fontSize = 10.sp,
                color = GlassTheme.colors.onSurface,
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        GText(
            text = path,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
            color = GlassTheme.colors.onSurface,
            modifier = Modifier.weight(1f)
        )
        GText(
            text = desc,
            style = GlassTheme.type.labelSmall,
            color = GlassTheme.colors.onSurfaceVariant
        )
    }
}

@Composable
internal fun CurlExampleCard(
    context: Context,
    effectiveUrl: String,
    apiKey: String,
    apiKeyRevealed: Boolean
) {
    GCard(cornerRadius = 14.dp, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            val displayApiKey = if (apiKeyRevealed) apiKey else maskSecret(apiKey)
            val curlCopiedMsg = stringResource(R.string.api_copied_curl)
            val curlExamplePrompt = stringResource(R.string.api_curl_example_prompt)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GText(
                    text = stringResource(R.string.api_curl_example),
                    style = GlassTheme.type.titleSmall,
                    color = GlassTheme.colors.onSurface
                )

                val curlCmd = """curl -X POST $effectiveUrl/api/generate \
  -H "Authorization: Bearer $apiKey" \
  -H "Content-Type: application/json" \
  -d '{"prompt": "$curlExamplePrompt", "stream": false}'"""

                GIconButton(onClick = { copyToClipboard(context, curlCmd, curlCopiedMsg) }) {
                    GIcon(
                        imageVector = GIcons.ContentCopy,
                        contentDescription = stringResource(R.string.api_copy_command_desc),
                        tint = GlassTheme.colors.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(GlassTheme.colors.surfaceVariant)
                    .border(1.dp, GlassTheme.colors.outline, RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                GText(
                    text = "curl -X POST $effectiveUrl/api/generate \\\n  -H \"Authorization: Bearer $displayApiKey\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"prompt\": \"$curlExamplePrompt\", \"stream\": false}'",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = GlassTheme.colors.onSurface
                )
            }
        }
    }
}

internal fun copyToClipboard(context: Context, text: String, toastMessage: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("API Address", text))
    Toast.makeText(context, toastMessage, Toast.LENGTH_SHORT).show()
}

internal fun maskSecret(secret: String): String {
    if (secret.isBlank()) return ""
    val last4 = secret.takeLast(4)
    val rawPrefix = secret.substringBeforeLast('-', "")
    val prefix = if (rawPrefix.isNotBlank() && rawPrefix.length <= 16) "$rawPrefix-" else ""
    return "$prefix••••••••$last4"
}
