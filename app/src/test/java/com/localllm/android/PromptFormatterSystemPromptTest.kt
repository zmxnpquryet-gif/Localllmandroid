package com.localllm.android

import com.localllm.android.engine.PromptFormatter
import com.localllm.android.i18n.AppStrings
import com.localllm.android.model.PromptTemplateType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The bundled instruction prompt is a resource string, so this needs real resources:
 * with the low-end toggle off the system turn must not carry it, while the tools block
 * (which the user may still want) must survive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PromptFormatterSystemPromptTest {

    @Before
    fun attachStrings() {
        AppStrings.attach(RuntimeEnvironment.getApplication().resources)
    }

    private fun format(includeBuiltInSystemPrompt: Boolean, toolsContext: String?): String =
        PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.CHATML,
            templateJsonPath = null,
            systemPrompt = "",
            history = emptyList(),
            userPrompt = "hi",
            toolsContext = toolsContext,
            supportsReasoning = false,
            includeBuiltInSystemPrompt = includeBuiltInSystemPrompt
        )

    @Test
    fun `built-in prompt is sent by default`() {
        val builtIn = AppStrings.get(R.string.sys_prompt)
        assertTrue("test needs a non-empty bundled prompt", builtIn.isNotBlank())
        assertTrue(format(includeBuiltInSystemPrompt = true, toolsContext = null).contains(builtIn))
    }

    @Test
    fun `built-in prompt can be disabled without dropping tools`() {
        val builtIn = AppStrings.get(R.string.sys_prompt)
        val without = format(includeBuiltInSystemPrompt = false, toolsContext = "TOOL_BLOCK")

        assertFalse("the bundled prompt must be gone", without.contains(builtIn))
        assertTrue("the tools block must still be sent", without.contains("TOOL_BLOCK"))
        assertTrue(without.contains("hi"))
    }

    @Test
    fun `a caller supplied system prompt is never suppressed`() {
        val out = PromptFormatter.formatPrompt(
            templateType = PromptTemplateType.CHATML,
            templateJsonPath = null,
            systemPrompt = "CUSTOM INSTRUCTION",
            history = emptyList(),
            userPrompt = "hi",
            toolsContext = null,
            supportsReasoning = false,
            includeBuiltInSystemPrompt = false
        )
        assertTrue(out.contains("CUSTOM INSTRUCTION"))
    }
}
