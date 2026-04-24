package com.lockit.domain.model

import java.security.MessageDigest

/**
 * Recovery configuration for Google account-based vault recovery.
 * Stores hashed email for verification without storing the actual email.
 */
data class RecoveryConfig(
    val emailHash: String,
    val createdAt: Long = System.currentTimeMillis(),
    val recoveryMethod: RecoveryMethod = RecoveryMethod.GOOGLE_EMAIL,
) {
    companion object {
        /**
         * Hash email using SHA-256 for secure storage.
         */
        fun hashEmail(email: String): String {
            val bytes = MessageDigest.getInstance("SHA-256").digest(email.lowercase().toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /**
         * Verify if provided email matches stored hash.
         */
        fun verifyEmail(email: String, storedHash: String): Boolean {
            return hashEmail(email) == storedHash
        }
    }
}

enum class RecoveryMethod {
    GOOGLE_EMAIL,
    RECOVERY_CODE,
}