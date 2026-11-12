package com.localllm.android.update

import com.localllm.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Versions of the native/runtime engines bundled in this build. */
object EngineVersions {
    const val LLAMA_CPP = "v0.9.0"
    const val LITERT_LM = "0.19.1"
}

data class AppUpdateInfo(
    val latestVersion: String,
    val releaseUrl: String
)

/**
 * Checks the project's GitHub releases for a newer app build.
 *
 * The engines are compiled into the APK, so an engine upgrade is an app upgrade:
 * there is no safe way to hot-swap `libllama_jni.so` or the LiteRT AAR at runtime.
 * This checker only surfaces the release; installing it stays with the user.
 *
 * A failed check (offline, rate limit, 404) is silent: an update prompt must never
 * be the reason a chat fails to open.
 */
class UpdateChecker(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build(),
    private val currentVersion: String = BuildConfig.VERSION_NAME
) {

    suspend fun latestRelease(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.github.com/repos/zmxnpquryet-gif/Localllmandroid/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "LocalLLM-Android/$currentVersion")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                val body = response.body?.string() ?: return@withContext null
                val json = JSONObject(body)
                if (json.optBoolean("draft") || json.optBoolean("prerelease")) {
                    return@withContext null
                }
                val tag = json.optString("tag_name").trim()
                val url = json.optString("html_url").trim()
                if (tag.isEmpty() || url.isEmpty() || !isNewer(tag, currentVersion)) {
                    return@withContext null
                }
                AppUpdateInfo(latestVersion = tag, releaseUrl = url)
            }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        /**
         * Numeric comparison, tolerant of a `v` prefix and missing components.
         * Pre-release tags are ignored so a beta never prompts stable users.
         */
        fun isNewer(latest: String, current: String): Boolean {
            if (latest.trim().contains('-')) return false
            val latestParts = parse(latest)
            val currentParts = parse(current)
            val size = maxOf(latestParts.size, currentParts.size)
            for (index in 0 until size) {
                val latestPart = latestParts.getOrElse(index) { 0 }
                val currentPart = currentParts.getOrElse(index) { 0 }
                if (latestPart != currentPart) return latestPart > currentPart
            }
            return false
        }

        private fun parse(version: String): List<Int> = version.trim()
            .removePrefix("v")
            .substringBefore('+')
            .split('.')
            .map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
    }
}
