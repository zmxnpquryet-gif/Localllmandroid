package com.localllm.android.ui

/**
 * Type-safe navigation destinations for LocalLLM Android.
 * Prevents silent routing errors caused by raw string typos.
 */
enum class AppScreen(val route: String) {
    CHAT("chat"),
    MODELS("models"),
    SETTINGS("settings"),
    VOICE_MODE("voice_mode"),
    API_MODE("api_mode");

    companion object {
        fun fromRoute(route: String): AppScreen {
            return entries.firstOrNull { it.route.equals(route, ignoreCase = true) } ?: CHAT
        }
    }
}
