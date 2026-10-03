package com.localllm.android

import android.util.Base64
import com.localllm.android.data.crypto.ChatCrypto
import com.localllm.android.data.crypto.KeyProtection
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        TestCrypto.install()
    }

    @After
    fun tearDown() {
        TestCrypto.uninstall()
    }

    private fun assertRejected(message: String, block: () -> Unit) {
        try {
            block()
            fail(message)
        } catch (_: SecurityException) {
            // Expected: fail closed.
        }
    }

    @Test
    fun `encrypt and decrypt roundtrip succeeds`() {
        val original = "비밀 대화 메시지: DeepSeek-R1 로컬 온디바이스 추론 2026!"
        val encrypted = ChatCrypto.encrypt(original, "slot-a")

        assertNotEquals(original, encrypted)
        assertTrue(encrypted.startsWith(ChatCrypto.VERSION_PREFIX))
        assertEquals(original, ChatCrypto.decrypt(encrypted, "slot-a"))
    }

    @Test
    fun `cipher generated IVs are unique and preserve the ciphertext format`() {
        val plaintext = "같은 메시지"
        val payloads = List(16) {
            Base64.decode(ChatCrypto.encrypt(plaintext, "slot").removePrefix(ChatCrypto.VERSION_PREFIX), Base64.NO_WRAP)
        }
        val ivs = payloads.map { Base64.encodeToString(it.copyOfRange(0, 12), Base64.NO_WRAP) }

        assertEquals(payloads.size, ivs.toSet().size)
        payloads.forEach { payload ->
            assertEquals(12 + plaintext.toByteArray(Charsets.UTF_8).size + 16, payload.size)
        }
        payloads.forEach { payload ->
            val wire = ChatCrypto.VERSION_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
            assertEquals(plaintext, ChatCrypto.decrypt(wire, "slot"))
        }
    }

    @Test
    fun `ciphertext moved to a different slot fails authentication`() {
        val encrypted = ChatCrypto.encrypt("row one secret", "message:1:content")

        assertEquals("row one secret", ChatCrypto.decrypt(encrypted, "message:1:content"))
        // Same ciphertext, another row or column: must not decrypt as if it belonged there.
        assertRejected("ciphertext swapped into another row must be rejected") {
            ChatCrypto.decrypt(encrypted, "message:2:content")
        }
        assertRejected("ciphertext swapped into another column must be rejected") {
            ChatCrypto.decrypt(encrypted, "message:1:reasoning")
        }
        assertRejected("missing AAD must be rejected") { ChatCrypto.decrypt(encrypted) }
    }

    @Test
    fun `encrypt refuses blank AAD`() {
        try {
            ChatCrypto.encrypt("unbound secret", "")
            fail("encrypt without AAD must be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: every ciphertext is bound to its slot.
        }
    }

    @Test
    fun `pre-AAD payloads stay readable and are flagged as legacy`() {
        val legacy = TestCrypto.legacyPayload("old row")

        assertTrue(ChatCrypto.isLegacyFormat(legacy))
        assertEquals("old row", ChatCrypto.decrypt(legacy, "any-slot-ignored"))
        assertFalse(ChatCrypto.isLegacyFormat(""))
        assertFalse(ChatCrypto.isLegacyFormat(null))
    }

    @Test
    fun `stripping the version prefix off a bound payload does not downgrade it`() {
        val bound = ChatCrypto.encrypt("row one secret", "message:1:content")
        val stripped = bound.removePrefix(ChatCrypto.VERSION_PREFIX)

        assertRejected("a bound payload must not decrypt through the legacy path") {
            ChatCrypto.decrypt(stripped, "message:1:content")
        }
        assertRejected("a bound payload must not decrypt through the legacy path either") {
            ChatCrypto.decrypt(stripped, "anything-else")
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

        assertRejected("레거시 하드코딩 키로 만든 암호문은 복호화되지 않아야 합니다.") {
            ChatCrypto.decrypt(ciphertext)
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
        val encrypted = ChatCrypto.encrypt("기밀 프롬프트", "slot")

        val rawBytes = Base64.decode(encrypted.removePrefix(ChatCrypto.VERSION_PREFIX), Base64.NO_WRAP)
        rawBytes[rawBytes.size - 1] = (rawBytes[rawBytes.size - 1].toInt() xor 0x01).toByte()
        val tampered = ChatCrypto.VERSION_PREFIX + Base64.encodeToString(rawBytes, Base64.NO_WRAP)

        assertRejected("변조된 암호문에 대해 SecurityException이 발생해야 합니다.") {
            ChatCrypto.decrypt(tampered, "slot")
        }
    }

    @Test
    fun `corrupted non-base64 input throws SecurityException`() {
        assertRejected("유효하지 않은 데이터에 대해 SecurityException이 발생해야 합니다.") {
            ChatCrypto.decrypt("This is definitely not a valid AES-GCM ciphertext payload!")
        }
    }

    @Test
    fun `short ciphertext throws SecurityException`() {
        val tooShort = Base64.encodeToString(ByteArray(5), Base64.NO_WRAP)
        assertRejected("길이가 부족한 데이터에 대해 SecurityException이 발생해야 합니다.") {
            ChatCrypto.decrypt(tooShort)
        }
    }

    @Test
    fun `an injected test key is never reported as hardware protected`() {
        assertEquals(KeyProtection.SOFTWARE, ChatCrypto.keyProtection())
    }
}
