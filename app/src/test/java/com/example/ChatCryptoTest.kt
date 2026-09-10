package com.example

import com.example.data.crypto.ChatCrypto
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
