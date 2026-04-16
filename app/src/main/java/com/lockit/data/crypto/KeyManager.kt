package com.lockit.data.crypto

import android.content.Context
import androidx.core.content.edit
import java.security.MessageDigest
import java.util.Base64

/**
 * Manages the in-memory master key for the current session.
 * The master key is derived from the user's password via Argon2id
 * and stored in memory only - never persisted to disk.
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
     * Initialize a new vault: store the salt and a hash of the derived master key
     * for future password verification. Also sets the master key in memory.
     */
    fun initVault(salt: ByteArray, masterKey: ByteArray) {
        this.masterKey = masterKey.copyOf()
        val keyHash = hashKey(masterKey)
        prefs.edit {
            putString("vault_salt", Base64.getEncoder().encodeToString(salt))
            putString("vault_key_hash", Base64.getEncoder().encodeToString(keyHash))
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
     * Unlock the vault: derive master key from password and salt, then verify
     * against the stored key hash.
     */
    fun unlockVault(password: String): Result<Unit> = runCatching {
        val salt = getSalt() ?: throw IllegalStateException("Vault not initialized")
        val storedHashStr = prefs.getString("vault_key_hash", null)
            ?: throw IllegalStateException("Vault corrupted: no key hash")
        val storedHash = Base64.getDecoder().decode(storedHashStr)

        val derivedKey = crypto.deriveKey(password, salt)
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
     * Requires re-encrypting all stored values.
     */
    fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val salt = getSalt() ?: throw IllegalStateException("Vault not initialized")
        val oldKey = crypto.deriveKey(oldPassword, salt)

        // Decrypt all values with old key, re-encrypt with new key
        // This is handled by the use case layer
        val newKey = crypto.deriveKey(newPassword, salt)
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
