package com.lockit.data.team

import android.util.Base64
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Team crypto operations for shared credentials.
 * Uses AES-256-GCM with team-shared key (independent of device PIN).
 */
object TeamCrypto {
    private const val NONCE_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128
    private const val KEY_LENGTH = 32  // 256 bits
    private const val INVITE_CODE_LENGTH = 16  // bytes for random invite code

    private val secureRandom = SecureRandom()

    /**
     * Generate a new AES-256 team key.
     */
    fun generateTeamKey(): ByteArray {
        val key = ByteArray(KEY_LENGTH)
        secureRandom.nextBytes(key)
        return key
    }

    /**
     * Generate a random invite code (for joining team).
     * Returns Base64-encoded string for easy sharing.
     */
    fun generateInviteCode(): String {
        val code = ByteArray(INVITE_CODE_LENGTH)
        secureRandom.nextBytes(code)
        return Base64.encodeToString(code, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    /**
     * Encode team key + invite code into a shareable string.
     * Format: base64(teamKey) + ":" + inviteCode
     */
    fun encodeTeamInvite(teamKey: ByteArray, inviteCode: String): String {
        val keyBase64 = Base64.encodeToString(teamKey, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return "$keyBase64:$inviteCode"
    }

    /**
     * Decode team invite string back to key + code.
     */
    fun decodeTeamInvite(inviteString: String): Pair<ByteArray, String>? {
        val parts = inviteString.split(":")
        if (parts.size != 2) return null
        return try {
            val key = Base64.decode(parts[0], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            val code = parts[1]
            if (key.size != KEY_LENGTH) null else Pair(key, code)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Encrypt shared credential value with team key.
     * Format: [12-byte nonce][ciphertext + 16-byte GCM tag]
     */
    fun encrypt(plaintext: ByteArray, teamKey: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_LENGTH)
        secureRandom.nextBytes(nonce)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(teamKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec)

        val ciphertext = cipher.doFinal(plaintext)
        return nonce + ciphertext
    }

    /**
     * Decrypt shared credential value with team key.
     */
    fun decrypt(data: ByteArray, teamKey: ByteArray): ByteArray {
        require(data.size > NONCE_LENGTH) { "Data too short: ${data.size} bytes" }

        val nonce = data.copyOfRange(0, NONCE_LENGTH)
        val ciphertext = data.copyOfRange(NONCE_LENGTH, data.size)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val keySpec = SecretKeySpec(teamKey, "AES")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH, nonce)
        cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)

        return cipher.doFinal(ciphertext)
    }
}