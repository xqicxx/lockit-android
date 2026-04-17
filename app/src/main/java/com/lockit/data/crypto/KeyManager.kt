package com.lockit.data.crypto

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.Base64

/**
 * Manages the in-memory master key for the current session.
 * The master key is derived from the user's password via Argon2id
 * and stored in memory only - never persisted to disk.
 *
 * Argon2 parameters are stored alongside the salt to ensure
 * compatibility when parameters change over versions.
 */
class KeyManager(private val context: Context) {

    private val prefs by lazy {
        context.getSharedPreferences("lockit_vault", Context.MODE_PRIVATE)
    }

    @Volatile
    private var masterKey: ByteArray? = null

    private val crypto = LockitCrypto()

    /**
     * Check if vault has been initialized (salt exists).
     */
    fun isVaultInitialized(): Boolean = prefs.contains("vault_salt")

    /**
     * Initialize a new vault: store the salt, Argon2 params, and a hash of the derived master key
     * for future password verification. Also sets the master key in memory.
     * Uses OWASP-recommended Argon2 parameters for new vaults.
     */
    fun initVault(salt: ByteArray, masterKey: ByteArray) {
        this.masterKey = masterKey.copyOf()
        val keyHash = hashKey(masterKey)
        prefs.edit {
            putString("vault_salt", Base64.getEncoder().encodeToString(salt))
            putString("vault_key_hash", Base64.getEncoder().encodeToString(keyHash))
            // Store OWASP params for new vaults
            putInt("argon2_memory", Argon2Params.MEMORY_OWASP)
            putInt("argon2_iterations", Argon2Params.ITERATIONS_OWASP)
            putInt("argon2_parallelism", Argon2Params.PARALLELISM_OWASP)
        }
    }

    /**
     * Get the stored salt.
     */
    fun getSalt(): ByteArray? {
        val saltStr = prefs.getString("vault_salt", null) ?: return null
        return Base64.getDecoder().decode(saltStr)
    }

    /**
     * Get stored Argon2 parameters, or fallback to legacy params for old vaults.
     */
    fun getArgon2Params(): Triple<Int, Int, Int> {
        val memory = prefs.getInt("argon2_memory", Argon2Params.MEMORY_LEGACY)
        val iterations = prefs.getInt("argon2_iterations", Argon2Params.ITERATIONS_LEGACY)
        val parallelism = prefs.getInt("argon2_parallelism", Argon2Params.PARALLELISM_LEGACY)
        return Triple(memory, iterations, parallelism)
    }

    /**
     * Unlock the vault: derive master key from password and salt using stored Argon2 params,
     * then verify against the stored key hash.
     */
    fun unlockVault(password: String): Result<Unit> = runCatching {
        val salt = getSalt() ?: throw IllegalStateException("Vault not initialized")
        val storedHashStr = prefs.getString("vault_key_hash", null)
            ?: throw IllegalStateException("Vault corrupted: no key hash")
        val storedHash = Base64.getDecoder().decode(storedHashStr)

        // Use stored Argon2 params (or legacy fallback for old vaults)
        val (memory, iterations, parallelism) = getArgon2Params()
        val derivedKey = crypto.deriveKey(password, salt, memory, iterations, parallelism)
        val derivedHash = hashKey(derivedKey)

        if (!derivedHash.contentEquals(storedHash)) {
            throw IllegalStateException("Invalid password")
        }

        masterKey = derivedKey
    }

    /**
     * SHA-256 hash of the master key for verification.
     */
    private fun hashKey(key: ByteArray): ByteArray {
        return MessageDigest.getInstance("SHA-256").digest(key)
    }

    /**
     * Get the current master key (null if vault is locked).
     */
    fun getMasterKey(): ByteArray? = masterKey

    /**
     * Lock the vault: clear master key from memory.
     */
    fun lockVault() {
        masterKey?.fill(0)
        masterKey = null
    }

    /**
     * Check if vault is currently unlocked.
     */
    fun isUnlocked(): Boolean = masterKey != null

    /**
     * Change the master password.
     * Uses stored Argon2 params for derivation.
     */
    fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val salt = getSalt() ?: throw IllegalStateException("Vault not initialized")
        val (memory, iterations, parallelism) = getArgon2Params()
        val oldKey = crypto.deriveKey(oldPassword, salt, memory, iterations, parallelism)

        // Decrypt all values with old key, re-encrypt with new key
        // This is handled by the use case layer
        val newKey = crypto.deriveKey(newPassword, salt, memory, iterations, parallelism)
        masterKey = newKey
    }

    /**
     * Update the in-memory master key (used after password change).
     */
    fun updateMasterKey(newKey: ByteArray) {
        masterKey?.fill(0)
        masterKey = newKey.copyOf()
        // Update stored key hash for future verification
        val keyHash = hashKey(newKey)
        prefs.edit {
            putString("vault_key_hash", Base64.getEncoder().encodeToString(keyHash))
        }
    }
}
