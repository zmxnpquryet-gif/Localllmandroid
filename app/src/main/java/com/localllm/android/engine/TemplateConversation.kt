package com.localllm.android.engine

enum class TemplateRole { SYSTEM, USER, ASSISTANT, TOOL }

sealed interface TemplateContent {
    data class Text(val value: String) : TemplateContent
    data class ImageRef(val path: String) : TemplateContent
    data class AudioRef(val path: String, val mediaType: String? = null) : TemplateContent
}

/** A model-neutral turn. Backend adapters decide how its metadata is serialized. */
data class TemplateMessage(
    val role: TemplateRole,
    val parts: List<TemplateContent>,
    val reasoning: String? = null,
    val channel: String? = null,
    val name: String? = null,
    val toolCallId: String? = null
) {
    fun plainText(): String = buildString {
        if (!channel.isNullOrBlank()) append("<channel>").append(channel).append("</channel>\n")
        if (!reasoning.isNullOrBlank()) {
            append("<reasoning>\n").append(reasoning.trim()).append("\n</reasoning>\n")
        }
        append(parts.joinToString("\n") { part ->
            when (part) {
                is TemplateContent.Text -> part.value
                // The native llama.cpp path uses its own media marker. Template-only
                // calls must not turn an arbitrary local attachment URI into prompt text.
                is TemplateContent.ImageRef -> "[image attachment]"
                is TemplateContent.AudioRef -> "[audio attachment]"
            }
        })
    }.trim()
}

object TemplateConversation {
    fun text(role: TemplateRole, value: String, channel: String? = null): TemplateMessage =
        TemplateMessage(role, listOf(TemplateContent.Text(value)), channel = channel)

    fun user(value: String, imagePath: String? = null, audioPath: String? = null): TemplateMessage {
        val parts = buildList {
            add(TemplateContent.Text(value))
            if (imagePath != null) add(TemplateContent.ImageRef(imagePath))
            if (audioPath != null) add(TemplateContent.AudioRef(audioPath))
        }
        return TemplateMessage(TemplateRole.USER, parts)
    }
}
