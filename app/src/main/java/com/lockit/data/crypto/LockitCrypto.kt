package com.lockit.data.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val NONCE_LENGTH = 12
private const val GCM_TAG_LENGTH = 128  // bits
private const val KEY_LENGTH = 32       // 256 bits
private const val SALT_LENGTH = 16

// Argon2id parameters (optimized for mobile: ~0.5s unlock)
private const val ARGON2_MEMORY = 16384     // 16 MB in KB
private const val ARGON2_ITERATIONS = 2
private const val ARGON2_PARALLELISM = 1

class LockitCrypto {

    private val secureRandom = SecureRandom()

    /**
     * Derive a 256-bit key from password and salt using Argon2id.
     * Compatible with CLI crypto.rs (argon2 default params).
     */
    fun deriveKey(password: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(ARGON2_MEMORY)
            .withIterations(ARGON2_ITERATIONS)
            .withParallelism(ARGON2_PARALLELISM)
            .build()

        val generator = Argon2BytesGenerator()
        generator.init(params)
        val key = ByteArray(KEY_LENGTH)
        generator.generateBytes(password.toCharArray(), key, 0, key.size)
        return key
    }

    /**
     * Generate a random salt for key derivation.
     */
    fun generateSalt(): ByteArray {
        val salt = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(salt)
        return salt
    }

    /**
     * Encrypt plaintext using AES-256-GCM.
     * Returns: [12-byte nonce][ciphertext + 16-byte GCM tag]
     * Compatible with CLI crypto.rs encrypt().
     */
    fun encrypt(plaintext: ByteArray, masterKey: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(masterKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /**
     * Decrypt data in format: [12-byte nonce][ciphertext + 16-byte GCM tag]
     * Compatible with CLI crypto.rs decrypt().
     */
    fun decrypt(data: ByteArray, masterKey: ByteArray): ByteArray {
        require(data.size > NONCE_LENGTH) { "Data too short: ${data.size} bytes" }

        val nonce = data.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = data.copyOfRange(NONCE_LENGTH, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(masterKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }
}
