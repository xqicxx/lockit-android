package com.lockit.data.sync

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Sync Key encryption/decryption for vault synchronization.
 * Uses AES-256-GCM with a shared Sync Key (independent of device PIN).
 *
 * Format: Version(1 byte) + Nonce(12 bytes) + AES-256-GCM ciphertext
 * - Version: 0x01 (current)
 * - Nonce: 12 bytes random IV
 * - Ciphertext: includes 16-byte GCM authentication tag
 */
object SyncCrypto {

    const val VERSION: Byte = 0x01
    const val NONCE_SIZE = 12
    const val TAG_SIZE = 16
    const val KEY_SIZE = 32 // 256-bit
    const val ALGORITHM = "AES/GCM/NoPadding"

    /**
     * Generate a new 256-bit Sync Key.
     * Should be done once per vault, shared across all devices.
     */
    fun generateSyncKey(): ByteArray {
        val key = ByteArray(KEY_SIZE)
        SecureRandom().nextBytes(key)
        return key
    }

    /**
     * Encode Sync Key to Base64 for QR code / manual input.
     */
    fun encodeSyncKey(key: ByteArray): String {
        require(key.size == KEY_SIZE) { "Sync Key must be $KEY_SIZE bytes" }
        return java.util.Base64.getEncoder().encodeToString(key)
    }

    /**
     * Decode Sync Key from Base64 (from QR scan or manual input).
     */
    fun decodeSyncKey(encoded: String): ByteArray {
        val key = java.util.Base64.getDecoder().decode(encoded)
        require(key.size == KEY_SIZE) { "Decoded key must be $KEY_SIZE bytes" }
        return key
    }

    /**
     * Encrypt vault.db bytes with Sync Key.
     * Returns formatted blob: Version + Nonce + Ciphertext.
     */
    fun encrypt(plaintext: ByteArray, syncKey: ByteArray): ByteArray {
        require(syncKey.size == KEY_SIZE) { "Sync Key must be $KEY_SIZE bytes" }

        val nonce = ByteArray(NONCE_SIZE)
        SecureRandom().nextBytes(nonce)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(syncKey, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE * 8, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)

        // Format: Version(1) + Nonce(12) + Ciphertext(N+16)
        val output = ByteArray(1 + NONCE_SIZE + ciphertext.size)
        output[0] = VERSION
        System.arraycopy(nonce, 0, output, 1, NONCE_SIZE)
        System.arraycopy(ciphertext, 0, output, 1 + NONCE_SIZE, ciphertext.size)

        return output
    }

    /**
     * Decrypt vault.enc blob with Sync Key.
     * Returns original vault.db bytes.
     */
    fun decrypt(blob: ByteArray, syncKey: ByteArray): ByteArray {
        require(blob.size > 1 + NONCE_SIZE + TAG_SIZE) { "Blob too short" }
        require(syncKey.size == KEY_SIZE) { "Sync Key must be $KEY_SIZE bytes" }

        // Parse format
        val version = blob[0]
        if (version != VERSION) {
            throw IllegalArgumentException("Unsupported vault.enc version: $version")
        }

        val nonce = blob.sliceArray(1 until 1 + NONCE_SIZE)
        val ciphertext = blob.sliceArray(1 + NONCE_SIZE until blob.size)

        val cipher = Cipher.getInstance(ALGORITHM)
        val keySpec = SecretKeySpec(syncKey, "AES")
        val gcmSpec = GCMParameterSpec(TAG_SIZE * 8, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }

    /**
     * Check if blob is valid vault.enc format.
     */
    fun isValidEncryptedBlob(blob: ByteArray): Boolean {
        return blob.size > 1 + NONCE_SIZE + TAG_SIZE && blob[0] == VERSION
    }
}