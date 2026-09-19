package com.localllm.android.data.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Enterprise-grade AES-256-GCM cipher utility backed by Android Keystore.
 * Generates and securely stores unique cryptographic keys in hardware-backed/secure OS Keystore.
 *
 * Failure policy: there is intentionally NO fallback to a hardcoded key. A missing or
 * unusable Keystore fails closed with a SecurityException instead of silently degrading
 * to a key that ships in the APK.
 */
object ChatCrypto {
    private const val TAG = "ChatCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "LocalLlm_ChatDb_MasterKey_v2"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    @Volatile
    private var cachedKey: SecretKey? = null

    /**
     * True only under Robolectric unit tests. A missing Keystore provider on a real
     * device must NOT be mistaken for "running a test" — that confusion silently
     * downgraded release builds to a hard-coded key. Fail closed instead.
     */
    private fun isRunningInUnitTest(): Boolean {
        return try {
            android.os.Build.FINGERPRINT.contains("robolectric", ignoreCase = true)
        } catch (_: Throwable) {
            false
        }
    }

    @Synchronized
    private fun getSecretKey(): SecretKey {
        cachedKey?.let { return it }

        if (isRunningInUnitTest()) {
            // Random, per-process key. Never derived from a constant, so a unit-test
            // build can never produce ciphertext readable by a shipped app.
            val testKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
            cachedKey = testKey
            return testKey
        }

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                val keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    ANDROID_KEYSTORE
                )
                val spec = KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .build()
                keyGenerator.init(spec)
                val newKey = keyGenerator.generateKey()
                cachedKey = newKey
                newKey
            } else {
                val keyEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                    ?: throw IllegalStateException("KeyStore entry is not a SecretKeyEntry")
                cachedKey = keyEntry.secretKey
                keyEntry.secretKey
            }
        } catch (e: Throwable) {
            Log.e(TAG, "AndroidKeyStore 키 생성/접근 실패: ${e.message}", e)
            throw SecurityException("Android Keystore 하드웨어 보안 키 접근에 실패했습니다: ${e.localizedMessage}", e)
        }
    }

    /**
     * Encrypts plaintext using AES-256-GCM authenticated encryption.
     * Returns Base64-encoded [IV (12 bytes) + Ciphertext + GCM Tag (16 bytes)].
     *
     * @throws SecurityException If encryption fails for any reason; never leaks plaintext.
     */
    fun encrypt(plaintext: String?): String {
        if (plaintext.isNullOrEmpty()) return ""

        try {
            val key = getSecretKey()
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv != null && iv.size == IV_LENGTH_BYTE) { "Invalid cipher-generated IV length" }

            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed securely: ${e.message}", e)
            throw SecurityException("암호화 처리 중 보안 오류가 발생했습니다: ${e.localizedMessage}", e)
        }
    }

    /**
     * Decrypts Base64-encoded [IV + Ciphertext + GCM Tag] using the Keystore key.
     * There is no legacy-key retry: a payload that cannot be authenticated with the
     * device Keystore key is rejected rather than decrypted with a shipped constant.
     *
     * @throws SecurityException If decryption fails or data has been tampered with.
     */
    fun decrypt(ciphertext: String?): String {
        if (ciphertext.isNullOrEmpty()) return ""

        val combined = try {
            Base64.decode(ciphertext, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw SecurityException("손상된 Base64 암호문 데이터입니다.", e)
        }

        if (combined.size <= IV_LENGTH_BYTE) {
            throw SecurityException("암호문 데이터 길이가 유효하지 않습니다 (최소 ${IV_LENGTH_BYTE + 1}바이트 필요).")
        }

        val iv = ByteArray(IV_LENGTH_BYTE)
        val encrypted = ByteArray(combined.size - IV_LENGTH_BYTE)
        System.arraycopy(combined, 0, iv, 0, iv.size)
        System.arraycopy(combined, IV_LENGTH_BYTE, encrypted, 0, encrypted.size)

        try {
            val key = getSecretKey()
            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed: ${e.message}", e)
            throw SecurityException("암호문 복호화 실패: 무결성 검증에 실패했거나 키가 일치하지 않습니다.", e)
        }
    }

    /**
     * Resets cached key instance (useful for test environments).
     */
    internal fun resetCachedKeyForTesting() {
        cachedKey = null
    }
}
