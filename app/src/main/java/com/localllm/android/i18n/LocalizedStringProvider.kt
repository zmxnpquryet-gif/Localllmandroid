package com.localllm.android.i18n

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

/**
 * Resolves localized Android string resources against a cached locale [Configuration]
 * context without rebuilding configuration contexts on every streaming update.
 */
class LocalizedStringProvider(
    private val application: Application,
    private val languagePreference: () -> String
) {
    private var cacheKey: String? = null
    @SuppressLint("StaticFieldLeak") // Application-scoped configuration context.
    private var cachedContext: Context? = null

    fun get(@StringRes id: Int, vararg args: Any?): String {
        val pref = languagePreference()
        val key = "$pref|${Locale.getDefault().toLanguageTag()}"
        var ctx = if (cacheKey == key) cachedContext else null
        if (ctx == null) {
            val locale = when (pref) {
                "ko" -> Locale.KOREAN
                "en" -> Locale.ENGLISH
                "zh" -> Locale.SIMPLIFIED_CHINESE
                else -> Locale.getDefault()
            }
            val config = Configuration(application.resources.configuration).apply { setLocale(locale) }
            ctx = application.createConfigurationContext(config)
            cacheKey = key
            cachedContext = ctx
        }
        @Suppress("UNCHECKED_CAST")
        return ctx.getString(id, *(args as Array<Any>))
    }
}
