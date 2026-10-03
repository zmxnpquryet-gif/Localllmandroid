package com.localllm.android

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.localllm.android.data.crypto.ChatCrypto
import com.localllm.android.data.crypto.KeyProtection
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the *production* path: a real Android Keystore key on a device/emulator.
 * The Robolectric unit tests install an in-memory key instead (there is no Keystore on
 * the JVM), so this is the only place the Keystore-backed crypto is proven.
 */
@RunWith(AndroidJUnit4::class)
class ChatCryptoInstrumentedTest {

    @Before
    fun setUp() {
        ChatCrypto.resetCachedKeyForTesting()
    }

    @Test
    fun roundTripThroughAndroidKeystore() {
        val original = "기기 Keystore 왕복 테스트: DeepSeek-R1 온디바이스 2026"
        val encrypted = ChatCrypto.encrypt(original, "instrumented:roundtrip")

        assertNotEquals(original, encrypted)
        assertTrue(encrypted.isNotBlank())
        assertEquals(original, ChatCrypto.decrypt(encrypted, "instrumented:roundtrip"))
    }

    @Test
    fun keystoreKeyIsCreatedUnderTheExpectedAlias() {
        ChatCrypto.encrypt("warm up the key", "instrumented:warmup")
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue(
            "ChatCrypto must persist its key in the Android Keystore",
            keyStore.containsAlias("LocalLlm_ChatDb_MasterKey_v2")
        )
    }

    @Test
    fun randomIvMakesCiphertextUnique() {
        val payloads = List(8) { ChatCrypto.encrypt("같은 평문", "instrumented:iv") }
        assertEquals(8, payloads.toSet().size)
        payloads.forEach { assertEquals("같은 평문", ChatCrypto.decrypt(it, "instrumented:iv")) }
    }

    @Test
    fun tamperedCiphertextIsRejected() {
        val encrypted = ChatCrypto.encrypt("변조 감지 대상", "slot")
        val raw = Base64.decode(encrypted.removePrefix(ChatCrypto.VERSION_PREFIX), Base64.NO_WRAP)
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 0x01).toByte()

        try {
            ChatCrypto.decrypt(ChatCrypto.VERSION_PREFIX + Base64.encodeToString(raw, Base64.NO_WRAP), "slot")
            fail("변조된 암호문은 복호화되면 안 됩니다.")
        } catch (e: SecurityException) {
            // Expected: AEAD integrity check fails closed.
        }
    }

    @Test
    fun ciphertextMovedToAnotherSlotIsRejectedByTheRealKeystore() {
        val encrypted = ChatCrypto.encrypt("행 1의 비밀", "message:1:content")
        assertEquals("행 1의 비밀", ChatCrypto.decrypt(encrypted, "message:1:content"))
        try {
            ChatCrypto.decrypt(encrypted, "message:2:content")
            fail("다른 행으로 옮긴 암호문은 인증에 실패해야 합니다.")
        } catch (e: SecurityException) {
            // Expected: AAD mismatch.
        }
    }

    /**
     * The README makes a hardware claim only as strong as what the platform reports.
     * This asserts the level is actually read back (not UNKNOWN) and logs it, so a
     * device farm run shows which devices are TEE/StrongBox and which are software.
     */
    @Test
    fun keyProtectionIsReadBackFromThePlatform() {
        ChatCrypto.encrypt("warm up the key")
        val level = ChatCrypto.keyProtection()
        android.util.Log.i("ChatCryptoInstrumentedTest", "Keystore key protection on this device: $level")
        assertNotEquals("KeyInfo should be readable for a Keystore key", KeyProtection.UNKNOWN, level)
    }

    /**
     * Regression guard for the removed hardcoded legacy key: on a real device, data
     * encrypted with the old shipped constant must NOT be silently decrypted.
     */
    @Test
    fun legacyHardcodedKeyCiphertextIsRejected() {
        val legacyKey = SecretKeySpec(
            MessageDigest.getInstance("SHA-256")
                .digest("LocalLLM_SQLite_Encrypted_Keystore_2026_Key".toByteArray(Charsets.UTF_8)),
            "AES"
        )
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey, GCMParameterSpec(128, iv))
        val ciphertext = Base64.encodeToString(
            iv + cipher.doFinal("legacy row".toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )

        try {
            ChatCrypto.decrypt(ciphertext)
            fail("하드코딩 레거시 키 암호문은 복호화되지 않아야 합니다(무음 다운그레이드 금지).")
        } catch (e: SecurityException) {
            // Expected: fail closed, no downgrade to a shipped key.
        }
    }
}
