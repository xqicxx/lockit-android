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
        // Use commit() for synchronous write to prevent data loss during system crashes
        prefs.edit(commit = true) {
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
     * Check if vault needs Argon2 upgrade (legacy params -> OWASP params).
     */
    fun needsArgon2Upgrade(): Boolean {
        val (memory, iterations, parallelism) = getArgon2Params()
        return memory != Argon2Params.MEMORY_OWASP ||
               iterations != Argon2Params.ITERATIONS_OWASP ||
               parallelism != Argon2Params.PARALLELISM_OWASP
    }

    /**
     * Upgrade Argon2 parameters to OWASP recommended values.
     * Requires vault to be unlocked and user password to re-derive key.
     */
    fun upgradeArgon2Params(password: String): Result<Unit> = runCatching {
        if (!isUnlocked()) {
            throw IllegalStateException("Vault must be unlocked before upgrade")
        }

        val salt = getSalt() ?: throw IllegalStateException("Vault not initialized")

        // First verify password with CURRENT params (not new params!)
        val (currentMemory, currentIterations, currentParallelism) = getArgon2Params()
        val verifyKey = crypto.deriveKey(password, salt, currentMemory, currentIterations, currentParallelism)

        // Verify password is correct by comparing with current master key
        if (!verifyKey.contentEquals(masterKey!!)) {
            verifyKey.fill(0)
            throw IllegalArgumentException("WRONG_PIN")
        }
        verifyKey.fill(0)

        // Now re-derive master key with OWASP params (same password, same salt, new params)
        val newKey = crypto.deriveKey(
            password,
            salt,
            Argon2Params.MEMORY_OWASP,
            Argon2Params.ITERATIONS_OWASP,
            Argon2Params.PARALLELISM_OWASP
        )

        // Update stored params and hash
        val keyHash = hashKey(newKey)
        prefs.edit(commit = true) {
            putString("vault_key_hash", Base64.getEncoder().encodeToString(keyHash))
            putInt("argon2_memory", Argon2Params.MEMORY_OWASP)
            putInt("argon2_iterations", Argon2Params.ITERATIONS_OWASP)
            putInt("argon2_parallelism", Argon2Params.PARALLELISM_OWASP)
        }

        // Update the in-memory master key reference to the new derived key
        masterKey = newKey
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
     * Updates stored key hash after successful password change.
     */
    fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val salt = getSalt() ?: throw IllegalStateException("Vault not initialized")
        val (memory, iterations, parallelism) = getArgon2Params()
        val oldKey = crypto.deriveKey(oldPassword, salt, memory, iterations, parallelism)

        // Verify old password is correct by checking key hash
        val oldKeyHash = hashKey(oldKey)
        val storedHashStr = prefs.getString("vault_key_hash", null)
            ?: throw IllegalStateException("Vault corrupted: no key hash")
        val storedHash = Base64.getDecoder().decode(storedHashStr)
        if (!oldKeyHash.contentEquals(storedHash)) {
            oldKey.fill(0)  // Zero out before throwing
            throw IllegalStateException("Invalid old password")
        }

        val newKey = crypto.deriveKey(newPassword, salt, memory, iterations, parallelism)

        // Update stored key hash for future verification (synchronous write)
        val newKeyHash = hashKey(newKey)
        prefs.edit(commit = true) {
            putString("vault_key_hash", Base64.getEncoder().encodeToString(newKeyHash))
        }

        // Update in-memory master key
        masterKey?.fill(0)
        masterKey = newKey.copyOf()

        // Zero out sensitive keys
        oldKey.fill(0)
        newKey.fill(0)
    }

    /**
     * Update the in-memory master key (used after password change).
     * Uses synchronous write to prevent data loss.
     */
    fun updateMasterKey(newKey: ByteArray) {
        masterKey?.fill(0)
        masterKey = newKey.copyOf()
        // Update stored key hash for future verification (synchronous write)
        val keyHash = hashKey(newKey)
        prefs.edit(commit = true) {
            putString("vault_key_hash", Base64.getEncoder().encodeToString(keyHash))
        }
    }
}
