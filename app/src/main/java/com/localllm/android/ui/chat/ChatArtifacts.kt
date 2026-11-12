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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.localllm.android.R
import com.localllm.android.markdown.ArtifactExtractor
import com.localllm.android.markdown.ArtifactKind
import com.localllm.android.markdown.MarkdownArtifact
import com.localllm.android.markdown.MarkdownParser
import com.localllm.android.ui.glass.GIcon
import com.localllm.android.ui.glass.GIconButton
import com.localllm.android.ui.glass.GIcons
import com.localllm.android.ui.glass.GText
import com.localllm.android.ui.glass.GlassTheme

/**
 * Assistant answer: markdown body plus the code/document listings that were moved
 * to collapsible artifact cards at the end.
 */
@Composable
fun AssistantMarkdown(
    markdown: String,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = GlassTheme.type.bodyLarge,
    color: Color = GlassTheme.colors.onBackground
) {
    val answer = remember(markdown, isStreaming) {
        ArtifactExtractor.extract(MarkdownParser.parse(markdown), isStreaming)
    }
    Column(modifier = modifier.fillMaxWidth()) {
        MarkdownBlocks(blocks = answer.body, style = style, color = color)
        if (answer.artifacts.isNotEmpty()) {
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                answer.artifacts.forEach { artifact ->
                    ArtifactCard(artifact = artifact)
                }
            }
        }
    }
}

@Composable
fun ArtifactCard(artifact: MarkdownArtifact, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val copiedText = stringResource(R.string.copied)
    var expanded by rememberSaveable(artifact.code) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(GlassTheme.colors.surfaceVariant.copy(alpha = 0.5f))
            .border(1.dp, GlassTheme.colors.outline.copy(alpha = 0.35f), shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            GIcon(
                imageVector = GIcons.Description,
                contentDescription = null,
                tint = GlassTheme.colors.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                GText(
                    text = artifact.title.ifBlank { stringResource(R.string.chat_artifact_title) },
                    style = GlassTheme.type.bodySmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                GText(
                    text = stringResource(R.string.chat_artifact_lines, artifact.lineCount),
                    style = GlassTheme.type.labelSmall,
                    color = GlassTheme.colors.onSurfaceVariant
                )
            }
            GIconButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Artifact", artifact.code))
                    Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.size(36.dp)
            ) {
                GIcon(
                    imageVector = GIcons.ContentCopy,
                    contentDescription = stringResource(R.string.copy),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(15.dp)
                )
            }
            GIconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(36.dp)
            ) {
                GIcon(
                    imageVector = if (expanded) GIcons.ArrowUp else GIcons.ArrowDown,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_artifact_collapse else R.string.chat_artifact_expand
                    ),
                    tint = GlassTheme.colors.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 12.dp, end = 12.dp, bottom = 12.dp)
            ) {
                if (artifact.kind == ArtifactKind.DOCUMENT) {
                    MarkdownMessage(
                        markdown = artifact.code,
                        style = GlassTheme.type.bodyMedium,
                        color = GlassTheme.colors.onBackground
                    )
                } else {
                    BasicText(
                        text = AnnotatedString(artifact.code),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.5.sp,
                            lineHeight = 18.sp,
                            color = GlassTheme.colors.onBackground
                        )
                    )
                }
            }
        }
    }
}
