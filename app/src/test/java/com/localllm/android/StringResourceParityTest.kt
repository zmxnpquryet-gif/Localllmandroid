package com.localllm.android

import android.content.res.Configuration
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards the bilingual UI: every declared string must resolve in BOTH the default
 * (English) and the Korean resource set, so the in-app language switcher can never
 * fall back to a missing key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StringResourceParityTest {

    @Test
    fun everyStringResolvesInEnglishAndKorean() {
        val app = RuntimeEnvironment.getApplication()
        val packageName = app.packageName
        val koreanContext = app.createConfigurationContext(
            Configuration(app.resources.configuration).apply { setLocale(Locale.KOREAN) }
        )

        val missing = mutableListOf<String>()
        R.string::class.java.fields.forEach { field ->
            val name = field.name
            if (app.resources.getIdentifier(name, "string", packageName) == 0) missing += "en:$name"
            if (koreanContext.resources.getIdentifier(name, "string", packageName) == 0) missing += "ko:$name"
        }

        assertTrue("string keys missing on one side: $missing", missing.isEmpty())
        assertTrue(
            "expected the bilingual catalog to be substantial",
            R.string::class.java.fields.size > 300
        )
    }
}
