package com.localllm.android.markdown

/**
 * A block that was pulled out of the answer body because it is a self-contained
 * code or document listing, so the chat can show it collapsed at the bottom.
 */
data class MarkdownArtifact(
    /** Optional title taken from the heading directly above the block. */
    val title: String,
    val language: String,
    val kind: ArtifactKind,
    val code: String
) {
    val lineCount: Int get() = if (code.isEmpty()) 0 else code.lines().size
}

enum class ArtifactKind {
    /** Source code: previewed as monospace text. */
    CODE,

    /** Prose/document markup: previewed as rendered markdown. */
    DOCUMENT
}

data class MarkdownAnswer(
    val body: List<MarkdownBlock>,
    val artifacts: List<MarkdownArtifact>
)

/**
 * Splits a finished answer into the prose body and the listings that sit at its end.
 *
 * Deliberately conservative, so an ordinary chat reply never gets chopped up:
 *  - only the *trailing* run of fenced blocks is considered;
 *  - the fence must be closed (a streaming fence keeps rendering inline);
 *  - the listing must be substantial (4+ non-blank lines or 160+ characters);
 *  - nothing is extracted while the answer is still streaming;
 *  - if the body would become empty the answer is returned untouched.
 */
object ArtifactExtractor {

    private val documentLanguages = setOf("md", "markdown", "txt", "text", "rst")
    private const val MIN_NON_BLANK_LINES = 4
    private const val MIN_CHARACTERS = 160
    private const val MAX_TITLE_LENGTH = 60

    fun extract(blocks: List<MarkdownBlock>, isStreaming: Boolean = false): MarkdownAnswer {
        if (isStreaming || blocks.isEmpty()) return MarkdownAnswer(blocks, emptyList())

        val artifacts = ArrayDeque<MarkdownArtifact>()
        var index = blocks.lastIndex
        while (index >= 0) {
            val block = blocks[index]
            if (block is MarkdownBlock.Code && block.closed && isSubstantial(block.code)) {
                artifacts.addFirst(block.toArtifact(heading = ""))
                index--
            } else {
                break
            }
        }
        if (artifacts.isEmpty()) return MarkdownAnswer(blocks, emptyList())

        var body: List<MarkdownBlock> = trimTrailingRules(blocks.subList(0, index + 1).toList())
        // A single listing can inherit the heading directly above it as its title.
        if (artifacts.size == 1 && body.size >= 2) {
            val heading = body.lastOrNull() as? MarkdownBlock.Heading
            val title = heading?.spans?.joinToString("") { it.text }?.trim().orEmpty()
            if (heading != null && title.isNotEmpty() && title.length <= MAX_TITLE_LENGTH) {
                artifacts[0] = artifacts[0].copy(title = title)
                body = trimTrailingRules(body.dropLast(1))
            }
        }
        if (body.isEmpty()) return MarkdownAnswer(blocks, emptyList())

        return MarkdownAnswer(body, artifacts.toList())
    }

    private fun trimTrailingRules(blocks: List<MarkdownBlock>): List<MarkdownBlock> {
        var end = blocks.size
        while (end > 0 && blocks[end - 1] == MarkdownBlock.Rule) end--
        return if (end == blocks.size) blocks else blocks.subList(0, end)
    }

    private fun isSubstantial(code: String): Boolean {
        val trimmed = code.trim()
        if (trimmed.isEmpty()) return false
        val nonBlankLines = trimmed.lines().count { it.isNotBlank() }
        return nonBlankLines >= MIN_NON_BLANK_LINES || trimmed.length >= MIN_CHARACTERS
    }

    private fun MarkdownBlock.Code.toArtifact(heading: String): MarkdownArtifact {
        val normalized = language.trim().lowercase()
        return MarkdownArtifact(
            title = heading,
            language = normalized,
            kind = if (normalized in documentLanguages) ArtifactKind.DOCUMENT else ArtifactKind.CODE,
            code = code
        )
    }
}
