package com.localllm.android.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtifactExtractorTest {

    private val code = "fun main() {\n    println(1)\n    println(2)\n    println(3)\n}"

    private fun extract(markdown: String, streaming: Boolean = false) =
        ArtifactExtractor.extract(MarkdownParser.parse(markdown), streaming)

    @Test
    fun `a trailing listing becomes an artifact and leaves the prose`() {
        val answer = extract("다음 코드를 사용하세요.\n\n```kotlin\n$code\n```")

        assertEquals(1, answer.artifacts.size)
        val artifact = answer.artifacts.single()
        assertEquals("kotlin", artifact.language)
        assertEquals(ArtifactKind.CODE, artifact.kind)
        assertEquals(code, artifact.code)
        assertEquals(5, artifact.lineCount)
        assertEquals(1, answer.body.size)
        assertTrue(answer.body.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `nothing is extracted while the answer is still streaming`() {
        val answer = extract("본문입니다.\n\n```kotlin\n$code\n```", streaming = true)

        assertTrue(answer.artifacts.isEmpty())
        assertEquals(2, answer.body.size)
    }

    @Test
    fun `a short snippet stays inline`() {
        val answer = extract("설명\n\n```kotlin\nval x = 1\n```")

        assertTrue(answer.artifacts.isEmpty())
        assertEquals(2, answer.body.size)
    }

    @Test
    fun `a long single line still counts as substantial`() {
        val longLine = "val x = \"" + "1".repeat(200) + "\""
        val answer = extract("설명\n\n```kotlin\n$longLine\n```")

        assertEquals(1, answer.artifacts.size)
    }

    @Test
    fun `an answer that is only code is left inline`() {
        val answer = extract("```kotlin\n$code\n```")

        assertTrue(answer.artifacts.isEmpty())
        assertEquals(1, answer.body.size)
    }

    @Test
    fun `markdown listings are documents`() {
        val answer = extract("설명\n\n```markdown\n# 제목\n\n- a\n- b\n- c\n```")

        assertEquals(ArtifactKind.DOCUMENT, answer.artifacts.single().kind)
    }

    @Test
    fun `the heading directly above a single listing becomes its title`() {
        val answer = extract("본문 설명입니다.\n\n## 예제\n\n```kotlin\n$code\n```")

        assertEquals("예제", answer.artifacts.single().title)
        assertEquals(1, answer.body.size)
        assertTrue(answer.body.single() is MarkdownBlock.Paragraph)
    }

    @Test
    fun `a heading separated from the listing by prose stays in the body`() {
        val answer = extract("## 예제\n\n본문 설명입니다.\n\n```kotlin\n$code\n```")

        assertEquals("", answer.artifacts.single().title)
        assertEquals(2, answer.body.size)
        assertEquals(MarkdownBlock.Heading::class.java, answer.body.first()::class.java)
    }

    @Test
    fun `several trailing listings keep their order`() {
        val answer = extract(
            "설명\n\n```kotlin\n$code\n```\n\n```python\nprint(1)\nprint(2)\nprint(3)\nprint(4)\n```"
        )

        assertEquals(listOf("kotlin", "python"), answer.artifacts.map { it.language })
        assertEquals(1, answer.body.size)
    }

    @Test
    fun `a listing in the middle of the answer stays inline`() {
        val answer = extract("```kotlin\n$code\n```\n\n그리고 이 설명이 이어집니다.")

        assertTrue(answer.artifacts.isEmpty())
        assertEquals(2, answer.body.size)
    }

    @Test
    fun `an unclosed fence is never extracted`() {
        val answer = extract("설명\n\n```kotlin\n$code")

        assertTrue(answer.artifacts.isEmpty())
    }

    @Test
    fun `a rule between prose and the listing is dropped`() {
        val answer = extract("설명\n\n---\n\n```kotlin\n$code\n```")

        assertEquals(1, answer.body.size)
        assertEquals(1, answer.artifacts.size)
    }
}
