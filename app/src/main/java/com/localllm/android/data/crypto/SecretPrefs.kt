package com.localllm.android.data.crypto

import android.content.SharedPreferences
import android.util.Log

/**
 * Credentials (Hugging Face token, API server key, MCP bearer token) kept in
 * SharedPreferences, but only as [ChatCrypto] ciphertext bound to the secret's name
 * via AAD.
 *
 * Storage layout: the secret `name` lives under the preference key `name + "_enc"`.
 * The bare `name` key is the pre-encryption plaintext location; it is migrated on
 * first read and then removed, so an upgraded install does not keep a plaintext copy.
 *
 * Failure policy: a value that cannot be decrypted reads as absent. It is never
 * returned as if it were valid. The ciphertext is kept (a SecurityException can
 * also be a transient Keystore failure, and deleting would lose the only copy);
 * a re-prompted value overwrites it via [put]. When a stale plaintext copy is
 * still around (crashed migration), it is re-encrypted atomically and returned.
 */
class SecretPrefs(private val prefs: SharedPreferences) {

    /**
     * Returns the secret, or null when unset/undecryptable. Migrates legacy plaintext:
     * the ciphertext and the plaintext removal are committed together, so a crash can
     * never leave the migration half-done in the committed state.
     */
    fun get(name: String): String? {
        val stored = prefs.getString(encKey(name), null)
        if (!stored.isNullOrEmpty()) {
            try {
                val value = ChatCrypto.decrypt(stored, aad(name)).takeIf { it.isNotBlank() }
                if (value != null) {
                    // A plaintext copy left by a crashed migration has no business
                    // staying around once the ciphertext reads fine.
                    if (prefs.contains(name)) prefs.edit().remove(name).apply()
                    return value
                }
            } catch (_: SecurityException) {
                Log.w(TAG, "SECRET_UNREADABLE")
                // Keep the ciphertext: ChatCrypto wraps transient Keystore
                // failures (locked device, daemon hiccup) in SecurityException
                // too, and deleting here would destroy the only copy on a
                // temporary error. Fall through to the legacy plaintext below.
            }
        }

        val legacy = prefs.getString(name, null)?.takeIf { it.isNotBlank() } ?: return null
        val encrypted = try {
            ChatCrypto.encrypt(legacy, aad(name))
        } catch (_: SecurityException) {
            // Keystore unusable: the secret stays where it was rather than being lost.
            Log.w(TAG, "SECRET_MIGRATION_DEFERRED")
            return legacy
        }
        // Single commit: the plaintext is gone exactly when the ciphertext lands.
        if (!prefs.edit().putString(encKey(name), encrypted).remove(name).commit()) {
            Log.w(TAG, "SECRET_MIGRATION_COMMIT_FAILED")
        }
        return legacy
    }

    /**
     * Stores [value] encrypted. A blank value clears the secret (both slots). Returns
     * false when encryption was impossible; nothing is written in that case (no
     * plaintext fallback).
     */
    fun put(name: String, value: String?): Boolean {
        if (value.isNullOrBlank()) {
            remove(name)
            return true
        }
        return try {
            val encrypted = ChatCrypto.encrypt(value, aad(name))
            prefs.edit().putString(encKey(name), encrypted).remove(name).commit()
        } catch (_: SecurityException) {
            Log.e(TAG, "SECRET_STORE_FAILED")
            false
        }
    }

    fun remove(name: String) {
        prefs.edit().remove(encKey(name)).remove(name).apply()
    }

    private fun encKey(name: String) = name + ENC_SUFFIX

    private fun aad(name: String) = "secret-prefs:$name"

    companion object {
        private const val TAG = "SecretPrefs"
        internal const val ENC_SUFFIX = "_enc"

        const val HF_TOKEN = "hf_token"
        const val API_SERVER_KEY = "api_server_key"
        const val MCP_TOKEN = "mcp_bearer_token"
    }
}
