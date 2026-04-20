package com.lockit.data.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Crypto constants - exposed for UI display
 */
object CryptoConstants {
    // AES-GCM parameters
    const val NONCE_LENGTH = 12          // bytes
    const val GCM_TAG_LENGTH = 128       // bits
    const val KEY_LENGTH = 32            // bytes (256 bits)
    const val SALT_LENGTH = 16           // bytes

    // Algorithm names for display
    const val ENCRYPTION_ALGORITHM = "AES-256-GCM"
    const val KEY_DERIVATION_ALGORITHM = "ARGON2ID"
    const val STORAGE_TYPE = "LOCAL_SQLITE"
}

private const val NONCE_LENGTH = CryptoConstants.NONCE_LENGTH
private const val GCM_TAG_LENGTH = CryptoConstants.GCM_TAG_LENGTH
private const val KEY_LENGTH = CryptoConstants.KEY_LENGTH
private const val SALT_LENGTH = CryptoConstants.SALT_LENGTH

/**
 * Argon2id parameters - OWASP recommended (2024)
 * memory=64MB, iterations=3, parallelism=4
 * Approx ~1-2s unlock time on modern devices
 */
object Argon2Params {
    // OWASP recommended params for new vaults
    const val MEMORY_OWASP = 65536       // 64 MB in KB
    const val ITERATIONS_OWASP = 3
    const val PARALLELISM_OWASP = 4

    // Legacy params (for existing vaults created before this update)
    const val MEMORY_LEGACY = 16384      // 16 MB in KB
    const val ITERATIONS_LEGACY = 2
    const val PARALLELISM_LEGACY = 1

    // Default for new vaults: OWASP params
    val DEFAULT = Triple(MEMORY_OWASP, ITERATIONS_OWASP, PARALLELISM_OWASP)
    val LEGACY = Triple(MEMORY_LEGACY, ITERATIONS_LEGACY, PARALLELISM_LEGACY)
}

class LockitCrypto {

    private val secureRandom = SecureRandom()

    /**
     * Derive a 256-bit key from password and salt using Argon2id.
     * @param memoryKB Memory in KB (OWASP recommends 64MB = 65536KB)
     * @param iterations Number of passes (OWASP recommends 3)
     * @param parallelism Number of threads (OWASP recommends 4)
     */
    fun deriveKey(
        password: String,
        salt: ByteArray,
        memoryKB: Int = Argon2Params.MEMORY_OWASP,
        iterations: Int = Argon2Params.ITERATIONS_OWASP,
        parallelism: Int = Argon2Params.PARALLELISM_OWASP
    ): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withMemoryAsKB(memoryKB)
            .withIterations(iterations)
            .withParallelism(parallelism)
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
