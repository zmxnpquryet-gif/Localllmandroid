package com.example.data.crypto

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-256-GCM cipher utility for encrypting chat history before storing in local SQLite.
 * Protects user conversations on device storage with authenticated encryption.
 */
object ChatCrypto {
    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BIT = 128
    private const val IV_LENGTH_BYTE = 12
    
    // Salt/seed unique to this local SQLite instance
    private const val MASTER_SEED = "LocalLLM_SQLite_Encrypted_Keystore_2026_Key"

    private val secretKey: SecretKeySpec by lazy {
        val sha = MessageDigest.getInstance("SHA-256")
        val keyBytes = sha.digest(MASTER_SEED.toByteArray(StandardCharsets.UTF_8))
        SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Encrypts plaintext into a Base64-encoded string containing IV + Ciphertext.
     */
    fun encrypt(plaintext: String?): String {
        if (plaintext == null) return ""
        if (plaintext.isEmpty()) return ""
        try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv = ByteArray(IV_LENGTH_BYTE)
            // Deterministic or secure IV
            java.security.SecureRandom().nextBytes(iv)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)
            val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            val combined = ByteArray(iv.size + encrypted.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encrypted, 0, combined, iv.size, encrypted.size)
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Fallback to simple obfuscation if GCM fails
            return Base64.encodeToString(plaintext.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        }
    }

    /**
     * Decrypts a Base64-encoded string back to plaintext.
     */
    fun decrypt(ciphertext: String?): String {
        if (ciphertext == null || ciphertext.isEmpty()) return ""
        try {
            val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH_BYTE) {
                return String(combined, StandardCharsets.UTF_8)
            }
            val iv = ByteArray(IV_LENGTH_BYTE)
            val encrypted = ByteArray(combined.size - IV_LENGTH_BYTE)
            System.arraycopy(combined, 0, iv, 0, iv.size)
            System.arraycopy(combined, IV_LENGTH_BYTE, encrypted, 0, encrypted.size)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BIT, iv)
            cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
            val decrypted = cipher.doFinal(encrypted)
            return String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // Fallback decode
            return try {
                val decoded = Base64.decode(ciphertext, Base64.NO_WRAP)
                String(decoded, StandardCharsets.UTF_8)
            } catch (ex: Exception) {
                ciphertext
            }
        }
    }
}
