package com.lockit.data.recovery

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.lockit.domain.model.RecoveryConfig
import com.lockit.domain.model.RecoveryMethod
import com.lockit.LockitApp
import com.lockit.data.crypto.Argon2Params
import com.lockit.data.crypto.LockitCrypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Base64

/**
 * Manages account recovery flow using Google email verification.
 *
 * Recovery mechanism:
 * 1. Bind (vault must be unlocked): Store encrypted master key with recovery key derived from email
 * 2. Recovery: Verify email, derive recovery key, decrypt stored master key, unlock vault
 * 3. Reset PIN: Re-encrypt vault with new PIN
 *
 * Security:
 * - Recovery key is derived from (email_hash + device_id) - tied to both account and device
 * - Master key is encrypted with this recovery key and stored separately
 * - During recovery, email is verified against stored hash, then recovery key decrypts master key
 */
class AccountRecoveryManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "lockit_recovery_prefs"
        private const val KEY_EMAIL_HASH = "recovery_email_hash"
        private const val KEY_CREATED_AT = "recovery_created_at"
        private const val KEY_METHOD = "recovery_method"
        private const val KEY_ENCRYPTED_MASTER_KEY = "recovery_encrypted_master_key"
        private const val KEY_RECOVERY_SALT = "recovery_salt"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val crypto = LockitCrypto()

    private val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    fun getSignInIntent(): Intent = signInClient.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun signOut() {
        signInClient.signOut()
    }

    /**
     * Get device-unique salt for recovery key derivation.
     */
    private fun getDeviceSalt(): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: "default_salt"
        return androidId.take(16)
    }

    /**
     * Derive recovery key from email + device salt + stored random salt.
     * Uses Argon2id (memory-hard) for secure key derivation.
     * This key is used to encrypt/decrypt the stored master key.
     */
    private fun deriveRecoveryKey(email: String, salt: ByteArray): ByteArray {
        val deviceSalt = getDeviceSalt()
        // Combine email + device salt to form the "password" for Argon2id
        val combined = "${email.lowercase()}:${deviceSalt}"
        // Use OWASP Argon2id parameters for memory-hard key derivation
        val (memory, iterations, parallelism) = Argon2Params.DEFAULT
        return crypto.deriveKey(combined, salt, memory, iterations, parallelism)
    }

    /**
     * Check if recovery is configured.
     */
    fun hasRecoveryConfigured(): Boolean {
        return prefs.contains(KEY_EMAIL_HASH) && prefs.contains(KEY_ENCRYPTED_MASTER_KEY)
    }

    /**
     * Get current recovery configuration.
     */
    fun getRecoveryConfig(): RecoveryConfig? {
        val emailHash = prefs.getString(KEY_EMAIL_HASH, null)
        if (emailHash == null) return null

        val createdAt = prefs.getLong(KEY_CREATED_AT, System.currentTimeMillis())
        val methodStr = prefs.getString(KEY_METHOD, RecoveryMethod.GOOGLE_EMAIL.name)
        val method = RecoveryMethod.values().find { it.name == methodStr } ?: RecoveryMethod.GOOGLE_EMAIL

        return RecoveryConfig(
            emailHash = emailHash,
            createdAt = createdAt,
            recoveryMethod = method,
        )
    }

    /**
     * Bind Google account for recovery.
     * Encrypts current master key with recovery key derived from email.
     *
     * REQUIREMENT: Vault must be unlocked before calling this.
     */
    fun bindRecoveryAccount(account: GoogleSignInAccount, masterKey: ByteArray): Result<Unit> {
        val email = account.email
        if (email == null) {
            return Result.failure(Exception("No email associated with account"))
        }

        // Store email hash
        val emailHash = RecoveryConfig.hashEmail(email)

        // Generate recovery salt for Argon2id
        val recoverySalt = crypto.generateSalt()

        // Derive recovery key using Argon2id with the random salt
        val recoveryKey = deriveRecoveryKey(email, recoverySalt)

        // Encrypt master key with recovery key
        val encryptedMasterKey = crypto.encrypt(masterKey, recoveryKey)

        // Zero out recovery key after use
        recoveryKey.fill(0)

        // Store encrypted master key and salt
        prefs.edit()
            .putString(KEY_EMAIL_HASH, emailHash)
            .putLong(KEY_CREATED_AT, System.currentTimeMillis())
            .putString(KEY_METHOD, RecoveryMethod.GOOGLE_EMAIL.name)
            .putString(KEY_ENCRYPTED_MASTER_KEY, java.util.Base64.getEncoder().encodeToString(encryptedMasterKey))
            .putString(KEY_RECOVERY_SALT, java.util.Base64.getEncoder().encodeToString(recoverySalt))
            .apply()

        return Result.success(Unit)
    }

    /**
     * Verify recovery account and decrypt stored master key.
     */
    fun verifyAndDecryptMasterKey(account: GoogleSignInAccount): RecoveryVerificationResult {
        val email = account.email
        if (email == null) {
            return RecoveryVerificationResult.Failure("No email associated with account")
        }

        val storedConfig = getRecoveryConfig()
        if (storedConfig == null || !prefs.contains(KEY_ENCRYPTED_MASTER_KEY)) {
            return RecoveryVerificationResult.NotConfigured
        }

        val emailHash = RecoveryConfig.hashEmail(email)
        if (emailHash != storedConfig.emailHash) {
            return RecoveryVerificationResult.Mismatch
        }

        // Get stored recovery salt
        val recoverySaltStr = prefs.getString(KEY_RECOVERY_SALT, null)
        if (recoverySaltStr == null) {
            return RecoveryVerificationResult.Failure("No stored recovery salt")
        }
        val recoverySalt = java.util.Base64.getDecoder().decode(recoverySaltStr)

        // Derive recovery key using Argon2id with stored salt
        val recoveryKey = deriveRecoveryKey(email, recoverySalt)

        // Decrypt stored master key
        try {
            val encryptedMasterKeyStr = prefs.getString(KEY_ENCRYPTED_MASTER_KEY, null)
            if (encryptedMasterKeyStr == null) {
                recoveryKey.fill(0)
                return RecoveryVerificationResult.Failure("No stored master key")
            }
            val encryptedMasterKey = java.util.Base64.getDecoder().decode(encryptedMasterKeyStr)
            val decryptedMasterKey = crypto.decrypt(encryptedMasterKey, recoveryKey)
            // Zero out recovery key after use
            recoveryKey.fill(0)
            return RecoveryVerificationResult.VerifiedWithKey(account, decryptedMasterKey)
        } catch (e: Exception) {
            recoveryKey.fill(0)
            return RecoveryVerificationResult.Failure("Failed to decrypt: ${e.message}")
        }
    }

    /**
     * Remove recovery configuration.
     */
    fun removeRecoveryConfig() {
        prefs.edit()
            .remove(KEY_EMAIL_HASH)
            .remove(KEY_CREATED_AT)
            .remove(KEY_METHOD)
            .remove(KEY_ENCRYPTED_MASTER_KEY)
            .remove(KEY_RECOVERY_SALT)
            .apply()
    }
}

sealed class RecoveryVerificationResult {
    data class VerifiedWithKey(val account: GoogleSignInAccount, val masterKey: ByteArray) : RecoveryVerificationResult()
    data class Failure(val message: String) : RecoveryVerificationResult()
    object Mismatch : RecoveryVerificationResult()
    object NotConfigured : RecoveryVerificationResult()
}