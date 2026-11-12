package com.localllm.android.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun `newer versions are detected`() {
        assertTrue(UpdateChecker.isNewer("v1.7.0", "1.6.0"))
        assertTrue(UpdateChecker.isNewer("1.6.1", "1.6.0"))
        assertTrue(UpdateChecker.isNewer("2.0", "1.9.9"))
        assertTrue(UpdateChecker.isNewer("v1.6.0.1", "1.6.0"))
    }

    @Test
    fun `same and older versions are ignored`() {
        assertFalse(UpdateChecker.isNewer("v1.6.0", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("v1.5.9", "1.6.0"))
        assertFalse(UpdateChecker.isNewer("1.6", "1.6.0"))
    }

    @Test
    fun `pre-release tags never prompt stable users`() {
        assertFalse(UpdateChecker.isNewer("v1.7.0-beta1", "1.6.0"))
    }
}
