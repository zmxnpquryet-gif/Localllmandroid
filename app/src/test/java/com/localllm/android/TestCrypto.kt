package com.localllm.android

import android.util.Base64
import com.localllm.android.data.crypto.ChatCrypto
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Unit tests run on a JVM with no Android Keystore. They install a throwaway random
 * in-memory key explicitly, instead of production code sniffing for "am I a test".
 * The real Keystore path is covered by ChatCryptoInstrumentedTest.
 */
object TestCrypto {
    lateinit var key: SecretKey
        private set

    fun install() {
        key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        ChatCrypto.installKeyForTesting(key)
    }

    fun uninstall() {
        ChatCrypto.installKeyForTesting(null)
    }

    /**
     * Builds a TRUE pre-AAD payload with JCA directly: Base64(IV + ciphertext + tag),
     * no version prefix, no AAD — byte-for-byte what older builds wrote. Stripping the
     * prefix off a new [ChatCrypto.encrypt] call is NOT equivalent (that keeps the AAD
     * binding and must fail to decrypt, which is asserted elsewhere).
     */
    fun legacyPayload(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == 12) { "unexpected IV length" }
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
    }
}
