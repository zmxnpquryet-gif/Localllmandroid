package com.localllm.android

import android.util.Base64
import com.localllm.android.data.crypto.ChatCrypto
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ChatCryptoTest {

    @Before
    fun setUp() {
        ChatCrypto.resetCachedKeyForTesting()
    }

    @Test
    fun `encrypt and decrypt roundtrip succeeds`() {
        val original = "비밀 대화 메시지: DeepSeek-R1 로컬 온디바이스 추론 2026!"
        val encrypted = ChatCrypto.encrypt(original)

        assertNotEquals(original, encrypted)
        assertTrue(encrypted.isNotBlank())

        val decrypted = ChatCrypto.decrypt(encrypted)
        assertEquals(original, decrypted)
    }

    @Test
    fun `cipher generated IVs are unique and preserve the ciphertext format`() {
        val plaintext = "같은 메시지"
        val payloads = List(16) { Base64.decode(ChatCrypto.encrypt(plaintext), Base64.NO_WRAP) }
        val ivs = payloads.map { Base64.encodeToString(it.copyOfRange(0, 12), Base64.NO_WRAP) }

        assertEquals(payloads.size, ivs.toSet().size)
        payloads.forEach { payload ->
            assertEquals(12 + plaintext.toByteArray(Charsets.UTF_8).size + 16, payload.size)
        }
        // Every IV-varied payload must still decrypt back to the same plaintext.
        payloads.forEach { payload ->
            assertEquals(plaintext, ChatCrypto.decrypt(Base64.encodeToString(payload, Base64.NO_WRAP)))
        }
    }

    @Test
    fun `legacy hardcoded-key ciphertext is rejected instead of silently downgraded`() {
        val plaintext = "legacy conversation"
        val legacyKey = SecretKeySpec(
            MessageDigest.getInstance("SHA-256")
                .digest("LocalLLM_SQLite_Encrypted_Keystore_2026_Key".toByteArray(Charsets.UTF_8)),
            "AES"
        )
        val iv = ByteArray(12) { it.toByte() }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, legacyKey, GCMParameterSpec(128, iv))
        val ciphertext = Base64.encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)

        try {
            ChatCrypto.decrypt(ciphertext)
            fail("레거시 하드코딩 키로 만든 암호문은 복호화되지 않아야 합니다.")
        } catch (e: SecurityException) {
            // Expected: fail closed, no downgrade to a shipped key.
        }
    }

    @Test
    fun `encrypt empty or null returns empty string`() {
        assertEquals("", ChatCrypto.encrypt(""))
        assertEquals("", ChatCrypto.encrypt(null))
        assertEquals("", ChatCrypto.decrypt(""))
        assertEquals("", ChatCrypto.decrypt(null))
    }

    @Test
    fun `tampered ciphertext throws SecurityException`() {
        val original = "기밀 프롬프트"
        val encrypted = ChatCrypto.encrypt(original)

        val rawBytes = android.util.Base64.decode(encrypted, android.util.Base64.NO_WRAP)
        // Flip one bit in the ciphertext payload
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0x01).toByte()
        val tamperedBase64 = android.util.Base64.encodeToString(rawBytes, android.util.Base64.NO_WRAP)

        try {
            ChatCrypto.decrypt(tamperedBase64)
            fail("변조된 암호문에 대해 SecurityException이 발생해야 합니다.")
        } catch (e: SecurityException) {
            // Expected
            assertTrue(e.message?.contains("복호화 실패") == true || e.message?.contains("손상") == true)
        }
    }

    @Test
    fun `corrupted non-base64 input throws SecurityException`() {
        try {
            ChatCrypto.decrypt("This is definitely not a valid AES-GCM ciphertext payload!")
            fail("유효하지 않은 데이터에 대해 SecurityException이 발생해야 합니다.")
        } catch (e: SecurityException) {
            // Expected
        }
    }

    @Test
    fun `short ciphertext throws SecurityException`() {
        val tooShort = android.util.Base64.encodeToString(ByteArray(5), android.util.Base64.NO_WRAP)
        try {
            ChatCrypto.decrypt(tooShort)
            fail("길이가 부족한 데이터에 대해 SecurityException이 발생해야 합니다.")
        } catch (e: SecurityException) {
            // Expected
        }
    }
}
