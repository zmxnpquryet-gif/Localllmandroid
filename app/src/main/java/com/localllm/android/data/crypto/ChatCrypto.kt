package com.localllm.android.data.crypto

import android.os.Build
import com.localllm.android.BuildConfig
import com.localllm.android.R
import com.localllm.android.i18n.AppStrings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the AES key actually lives, as reported by the platform (not as hoped).
 */
enum class KeyProtection {
    /** Dedicated secure element (StrongBox). */
    STRONGBOX,

    /** Trusted execution environment: key material never enters the app process. */
    TRUSTED_ENVIRONMENT,

    /** Keystore-managed but software-only (typical on emulators and some old devices). */
    SOFTWARE,

    /** The platform could not tell us. Treated as unverified, never as hardware. */
    UNKNOWN
}

/**
 * AES-256-GCM cipher utility backed by the Android Keystore.
 *
 * Format `v2:<base64(IV(12) + ciphertext + tag(16))>`. The caller supplies associated
 * data (AAD) that is authenticated but not stored, so a ciphertext copied into another
 * row or column fails authentication instead of decrypting as if it belonged there.
 * Payloads without the `v2:` prefix are the pre-AAD format; they are still readable so
 * existing data survives, and [isLegacyFormat] lets callers upgrade them.
 *
 * Hardware claim, precisely: the key is generated inside the Keystore and non-exportable.
 * Whether that Keystore is backed by a TEE/StrongBox is a property of the device, so
 * [keyProtection] reads it back through `KeyInfo` and callers can show it. StrongBox is
 * deliberately not requested: every AES operation is a round trip to the secure element
 * and rows are decrypted in bulk when the conversation list renders.
 *
 * Failure policy: there is intentionally NO fallback to a hardcoded key. A missing or
 * unusable Keystore fails closed with a SecurityException.
 */
object ChatCrypto {
    private const val TAG = "ChatCrypto"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "LocalLlm_ChatDb_MasterKey_v2"
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12

    /** Marks the AAD-bound format. Cannot collide with Base64 output, which has no ':'. */
    const val VERSION_PREFIX = "v2:"

    @Volatile
    private var cachedKey: SecretKey? = null

    @Volatile
    private var cachedProtection: KeyProtection? = null

    @Synchronized
    private fun getSecretKey(): SecretKey {
        cachedKey?.let { return it }

        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val key = if (!keyStore.containsAlias(KEY_ALIAS)) {
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
                keyGenerator.generateKey()
            } else {
                val keyEntry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                    ?: throw IllegalStateException("KeyStore entry is not a SecretKeyEntry")
                keyEntry.secretKey
            }
            cachedKey = key
            cachedProtection = readProtection(key)
            Log.i(TAG, "Master key protection: $cachedProtection")
            key
        } catch (e: Throwable) {
            Log.e(TAG, "KEYSTORE_KEY_UNAVAILABLE")
            throw SecurityException(AppStrings.get(R.string.cry_keystore_fail, e.localizedMessage), e)
        }
    }

    /** Reads the protection level back from the platform instead of assuming it. */
    private fun readProtection(key: SecretKey): KeyProtection {
        return try {
            val factory = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE)
            val info = factory.getKeySpec(key, KeyInfo::class.java) as KeyInfo
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                when (info.securityLevel) {
                    KeyProperties.SECURITY_LEVEL_STRONGBOX -> KeyProtection.STRONGBOX
                    KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT -> KeyProtection.TRUSTED_ENVIRONMENT
                    KeyProperties.SECURITY_LEVEL_SOFTWARE -> KeyProtection.SOFTWARE
                    else -> KeyProtection.UNKNOWN
                }
            } else {
                @Suppress("DEPRECATION")
                if (info.isInsideSecureHardware) KeyProtection.TRUSTED_ENVIRONMENT else KeyProtection.SOFTWARE
            }
        } catch (_: Throwable) {
            Log.w(TAG, "KEY_PROTECTION_UNKNOWN")
            KeyProtection.UNKNOWN
        }
    }

    /**
     * Protection level of the master key. Creates the key on first use. Returns
     * [KeyProtection.UNKNOWN] rather than throwing when the Keystore is unusable.
     */
    fun keyProtection(): KeyProtection {
        cachedProtection?.let { return it }
        return try {
            getSecretKey()
            cachedProtection ?: KeyProtection.UNKNOWN
        } catch (_: SecurityException) {
            KeyProtection.UNKNOWN
        }
    }

    /** True for payloads written before AAD binding existed (no `v2:` prefix). */
    fun isLegacyFormat(ciphertext: String?): Boolean =
        !ciphertext.isNullOrEmpty() && !ciphertext.startsWith(VERSION_PREFIX)

    /**
     * Encrypts [plaintext]. [aad] is REQUIRED and authenticated but not stored; the
     * identical value must be passed to [decrypt]. Use something that identifies the
     * slot the data lives in (e.g. table, row id and column). The requirement is
     * enforced (not just documented): an unbound ciphertext could otherwise be copied
     * into another slot protected by the same key.
     *
     * @throws IllegalArgumentException When [aad] is blank.
     * @throws SecurityException If encryption fails for any reason; never leaks plaintext.
     */
    fun encrypt(plaintext: String?, aad: String = ""): String {
        if (plaintext.isNullOrEmpty()) return ""
        require(aad.isNotBlank()) { "ChatCrypto.encrypt requires non-blank AAD" }

        try {
            val key = getSecretKey()
            val cipher = Cipher.getInstance(ALGORITHM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
            val iv = cipher.iv
            require(iv != null && iv.size == IV_LENGTH_BYTE) { "Invalid cipher-generated IV length" }

            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)

            return VERSION_PREFIX + Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "ENCRYPT_FAILED")
            throw SecurityException(AppStrings.get(R.string.cry_encrypt_err, e.localizedMessage), e)
        }
    }

    /**
     * Decrypts a payload produced by [encrypt]. For `v2:` payloads [aad] must match the
     * value used at encryption time. Pre-`v2:` payloads carry no AAD and ignore [aad];
     * upgrade them with [isLegacyFormat] + re-encrypt. There is no legacy-key retry: a
     * payload that cannot be authenticated with the device Keystore key is rejected.
     *
     * @throws SecurityException If decryption fails or data has been tampered with.
     */
    fun decrypt(ciphertext: String?, aad: String = ""): String {
        if (ciphertext.isNullOrEmpty()) return ""

        val bound = ciphertext.startsWith(VERSION_PREFIX)
        val body = if (bound) ciphertext.substring(VERSION_PREFIX.length) else ciphertext

        val combined = try {
            Base64.decode(body, Base64.NO_WRAP)
        } catch (e: Exception) {
            throw SecurityException(AppStrings.get(R.string.cry_corrupt), e)
        }

        val minLength = IV_LENGTH_BYTE + TAG_LENGTH_BIT / 8 + 1 // IV + tag + >=1 ciphertext byte
        if (combined.size < minLength) {
            throw SecurityException(AppStrings.get(R.string.cry_bad_length, minLength))
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
            if (bound && aad.isNotEmpty()) cipher.updateAAD(aad.toByteArray(StandardCharsets.UTF_8))
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "DECRYPT_FAILED")
            throw SecurityException(AppStrings.get(R.string.cry_decrypt_fail), e)
        }
    }

    /**
     * Test seam. Unit tests run on a JVM with no Keystore, so they install a throwaway
     * in-memory key here. Passing null restores the Keystore path. The key is reported
     * as [KeyProtection.SOFTWARE] because that is what it is.
     *
     * Hard-disabled in release builds: `internal` is not a runtime security boundary,
     * so the seam refuses to run where tests never run.
     */
    @Synchronized
    internal fun installKeyForTesting(key: SecretKey?) {
        if (!BuildConfig.DEBUG) throw SecurityException("Test key seam is disabled in release builds")
        cachedKey = key
        cachedProtection = if (key != null) KeyProtection.SOFTWARE else null
    }

    /** Resets cached key instance (useful for test environments). */
    internal fun resetCachedKeyForTesting() {
        cachedKey = null
        cachedProtection = null
    }
}
