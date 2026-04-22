package com.lockit.data.vault

import android.content.Context
import androidx.core.content.edit
import androidx.room.withTransaction
import com.lockit.data.audit.AuditLogger
import com.lockit.data.audit.AuditSeverity
import com.lockit.data.crypto.Argon2Params
import com.lockit.data.crypto.KeyManager
import com.lockit.data.crypto.LockitCrypto
import com.lockit.data.database.CredentialDao
import com.lockit.data.database.CredentialEntity
import com.lockit.data.database.LockitDatabase
import com.lockit.domain.model.Credential
import com.lockit.domain.model.CredentialType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import com.lockit.utils.SearchMatcher
import java.time.Instant

class VaultManager(
    private val context: Context,
    private val dao: CredentialDao,
    private val keyManager: KeyManager,
    private val auditLogger: AuditLogger,
) {

    private val crypto = LockitCrypto()

    /**
     * Initialize a new vault with a master password.
     * Uses OWASP-recommended Argon2 parameters (64MB, 3 iterations, 4 parallelism).
     */
    fun initVault(masterPassword: String) {
        if (keyManager.isVaultInitialized()) {
            throw IllegalStateException("Vault already initialized")
        }
        val salt = crypto.generateSalt()
        val (memory, iterations, parallelism) = Argon2Params.DEFAULT
        val masterKey = crypto.deriveKey(masterPassword, salt, memory, iterations, parallelism)
        keyManager.initVault(salt, masterKey)
        auditLogger.log("VAULT_INITIALIZED", "New vault created with Argon2id OWASP params", AuditSeverity.Warning)
    }

    /**
     * Unlock the vault with the master password.
     */
    fun unlockVault(masterPassword: String): Result<Unit> {
        if (!keyManager.isVaultInitialized()) {
            return Result.failure(IllegalStateException("Vault not initialized. Create one first."))
        }
        val result = keyManager.unlockVault(masterPassword)
        if (result.isSuccess) {
            auditLogger.log("VAULT_UNLOCKED", "PIN authentication successful", AuditSeverity.Info)
        } else {
            auditLogger.log("VAULT_UNLOCK_FAILED", "Invalid PIN attempt", AuditSeverity.Warning)
        }
        return result
    }

    /**
     * Lock the vault (clear master key from memory).
     */
    fun lockVault() {
        keyManager.lockVault()
        auditLogger.log("VAULT_LOCKED", "Master key cleared from memory", AuditSeverity.Warning)
    }

    /**
     * Check if the vault is currently unlocked.
     */
    fun isUnlocked(): Boolean = keyManager.isUnlocked()

    /**
     * Check if the vault has been initialized.
     */
    fun isInitialized(): Boolean = keyManager.isVaultInitialized()

    /**
     * Change the master password.
     * Decrypts all credentials with the old key, re-encrypts with the new key.
     * Uses stored Argon2 params for derivation.
     * Uses transaction to ensure atomicity - all updates succeed or none.
     */
    suspend fun changePassword(oldPassword: String, newPassword: String): Result<Unit> = runCatching {
        val salt = keyManager.getSalt()
            ?: throw IllegalStateException("Vault not initialized")
        val (memory, iterations, parallelism) = keyManager.getArgon2Params()

        // Derive keys for re-encryption
        val oldKey = crypto.deriveKey(oldPassword, salt, memory, iterations, parallelism)
        val newKey = crypto.deriveKey(newPassword, salt, memory, iterations, parallelism)

        // Verify old password by checking key hash
        val oldKeyHash = java.security.MessageDigest.getInstance("SHA-256").digest(oldKey)
        val storedHashStr = context.getSharedPreferences("lockit_vault", Context.MODE_PRIVATE)
            .getString("vault_key_hash", null)
            ?: throw IllegalStateException("Vault corrupted: no key hash")
        val storedHash = java.util.Base64.getDecoder().decode(storedHashStr)
        if (!oldKeyHash.contentEquals(storedHash)) {
            oldKey.fill(0)
            newKey.fill(0)
            throw IllegalStateException("Invalid old password")
        }

        // Use transaction to ensure atomicity - re-encrypt all credentials
        LockitDatabase.getInstance(context).withTransaction {
            val allEntities = dao.getAllEntities()
            for (entity in allEntities) {
                val decrypted = crypto.decrypt(entity.value, oldKey)
                val reEncrypted = crypto.encrypt(decrypted, newKey)
                dao.update(entity.copy(value = reEncrypted, updatedAt = Instant.now().toEpochMilli()))
            }
        }

        // Update stored key hash AFTER successful re-encryption
        keyManager.updateMasterKey(newKey)

        // Zero out sensitive keys after use
        oldKey.fill(0)
        newKey.fill(0)

        auditLogger.log("PASSWORD_CHANGED", "Master password updated", AuditSeverity.Danger)
    }

    /**
     * Get the master key (throws if not unlocked).
     */
    private fun requireMasterKey(): ByteArray {
        return keyManager.getMasterKey()
            ?: throw IllegalStateException("Vault is locked")
    }

    // --- Credential operations ---

    fun getAllCredentials(): Flow<List<Credential>> {
        return dao.getAll().map { entities ->
            val masterKey = requireMasterKey()
            entities.map { decryptCredential(it, masterKey) }
        }
    }

    suspend fun getCredentialById(id: String): Credential? {
        val masterKey = requireMasterKey()
        val entity = dao.getById(id) ?: return null
        val credential = decryptCredential(entity, masterKey)
        auditLogger.log("CREDENTIAL_VIEWED", "${credential.name} - details accessed", AuditSeverity.Info)
        return credential
    }

    fun searchCredentials(query: String): Flow<List<Credential>> {
        // Fetch all credentials for fuzzy matching (SQL LIKE defeats spelling tolerance)
        // Use flowOn(Dispatchers.IO) to move decryption off main thread
        return dao.getAll().map { entities ->
            val masterKey = requireMasterKey()
            val credentials = entities.map { decryptCredential(it, masterKey) }
            // Apply fuzzy matching scoring and sort by match score
            SearchMatcher.sortByMatchScore(credentials, query)
        }.flowOn(Dispatchers.IO)
    }

    fun getCredentialsByService(service: String): Flow<List<Credential>> {
        return dao.getByService(service).map { entities ->
            val masterKey = requireMasterKey()
            entities.map { decryptCredential(it, masterKey) }
        }
    }

    suspend fun addCredential(
        name: String,
        type: CredentialType,
        service: String,
        key: String,
        value: String,
        metadata: String = "{}",
    ) {
        val masterKey = requireMasterKey()
        val encryptedValue = crypto.encrypt(value.toByteArray(Charsets.UTF_8), masterKey)
        val now = Instant.now().toEpochMilli()

        val entity = CredentialEntity(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            type = type.name,
            service = service,
            key = key,
            value = encryptedValue,
            metadata = metadata,
            createdAt = now,
            updatedAt = now,
        )
        dao.insert(entity)
        auditLogger.log("CREDENTIAL_CREATED", "$name - service: $service", AuditSeverity.Info)
    }

    suspend fun updateCredential(
        id: String,
        name: String,
        type: CredentialType,
        service: String,
        key: String,
        value: String,
        metadata: String? = null,
    ) {
        val masterKey = requireMasterKey()
        val encryptedValue = crypto.encrypt(value.toByteArray(Charsets.UTF_8), masterKey)
        val existing = dao.getById(id)
        val now = Instant.now().toEpochMilli()

        // Use explicit query to ensure updatedAt is correctly set
        dao.updateById(
            id = id,
            name = name,
            type = type.name,
            service = service,
            key = key,
            value = encryptedValue,
            metadata = metadata ?: existing?.metadata ?: "{}",
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        auditLogger.log("CREDENTIAL_UPDATED", "$name - value modified", AuditSeverity.Warning)
    }

    suspend fun deleteCredential(credential: Credential) {
        dao.delete(
            CredentialEntity(
                id = credential.id,
                name = credential.name,
                type = credential.type.name,
                service = credential.service,
                key = credential.key,
                value = credential.value.toByteArray(Charsets.UTF_8),
                metadata = "{}",
                createdAt = credential.createdAt.toEpochMilli(),
                updatedAt = credential.updatedAt.toEpochMilli(),
            ),
        )
        auditLogger.log("CREDENTIAL_DELETED", "${credential.name} - permanent removal", AuditSeverity.Danger)
    }

    /**
     * Copy credential value to clipboard (audit log only).
     */
    fun logCredentialCopied(name: String) {
        auditLogger.log("CREDENTIAL_COPIED", "$name - clipboard copy", AuditSeverity.Info)
    }

    /**
     * Log credential viewed/revealed action.
     */
    fun logCredentialViewed(name: String) {
        auditLogger.log("CREDENTIAL_VIEWED", "$name - value revealed", AuditSeverity.Info)
    }

    fun getCredentialCount(): Flow<Int> = dao.getCount()
    fun getServiceCount(): Flow<Int> = dao.getServiceCount()

    /**
     * Get the Argon2 parameters used for this vault.
     * Returns OWASP params for new vaults, legacy params for old vaults.
     */
    fun getArgon2ParamsInfo(): Triple<Int, Int, Int> = keyManager.getArgon2Params()

    /**
     * Get crypto constants for UI display.
     */
    fun getCryptoConstants(): com.lockit.data.crypto.CryptoConstants = com.lockit.data.crypto.CryptoConstants

    /**
     * Check if vault needs Argon2 upgrade.
     */
    fun needsArgon2Upgrade(): Boolean = keyManager.needsArgon2Upgrade()

    /**
     * Check if vault might need recovery from failed upgrade.
     * Returns true if OWASP params are stored (indicating upgrade was attempted)
     * but vault is unlocked and credentials can't be decrypted.
     */
    suspend fun needsRecovery(): Boolean {
        // If vault params are OWASP but vault is unlocked with potentially wrong key
        val (memory, iterations, parallelism) = keyManager.getArgon2Params()
        val isOwaspParams = memory == Argon2Params.MEMORY_OWASP &&
            iterations == Argon2Params.ITERATIONS_OWASP &&
            parallelism == Argon2Params.PARALLELISM_OWASP

        // If OWASP params but vault is unlocked, check if we can decrypt
        if (isOwaspParams && keyManager.isUnlocked()) {
            // Try to get credentials - if decryption fails, need recovery
            try {
                val masterKey = keyManager.getMasterKey() ?: return false
                val entities = dao.getAllEntities()
                if (entities.isNotEmpty()) {
                    // Try decrypt first credential
                    crypto.decrypt(entities.first().value, masterKey)
                    return false // Decrypt succeeded, no recovery needed
                }
            } catch (e: Exception) {
                return true // Decrypt failed, need recovery
            }
        }
        return false
    }

    /**
     * Upgrade Argon2 parameters to OWASP recommended values.
     * CRITICAL: Re-encrypts all credentials with the new key derived from OWASP params.
     * This is necessary because different Argon2 params produce different keys
     * even with the same password and salt.
     */
    suspend fun upgradeArgon2Params(password: String): Result<Unit> = runCatching {
        if (!keyManager.isVaultInitialized()) {
            throw IllegalStateException("Vault not initialized")
        }

        val salt = keyManager.getSalt() ?: throw IllegalStateException("Vault not initialized")
        val (currentMemory, currentIterations, currentParallelism) = keyManager.getArgon2Params()

        // Derive old key with current params
        val oldKey = crypto.deriveKey(password, salt, currentMemory, currentIterations, currentParallelism)

        // Verify old key by checking hash
        val oldKeyHash = java.security.MessageDigest.getInstance("SHA-256").digest(oldKey)
        val storedHashStr = context.getSharedPreferences("lockit_vault", Context.MODE_PRIVATE)
            .getString("vault_key_hash", null)
            ?: throw IllegalStateException("Vault corrupted: no key hash")
        val storedHash = java.util.Base64.getDecoder().decode(storedHashStr)
        if (!oldKeyHash.contentEquals(storedHash)) {
            oldKey.fill(0)
            throw IllegalArgumentException("WRONG_PIN")
        }

        // Derive new key with OWASP params
        val newKey = crypto.deriveKey(
            password,
            salt,
            Argon2Params.MEMORY_OWASP,
            Argon2Params.ITERATIONS_OWASP,
            Argon2Params.PARALLELISM_OWASP
        )

        // CRITICAL: Re-encrypt all credentials with new key
        LockitDatabase.getInstance(context).withTransaction {
            val allEntities = dao.getAllEntities()
            for (entity in allEntities) {
                val decrypted = crypto.decrypt(entity.value, oldKey)
                val reEncrypted = crypto.encrypt(decrypted, newKey)
                dao.update(entity.copy(value = reEncrypted))
            }
        }

        // Update stored params and hash AFTER successful re-encryption
        val newKeyHash = java.security.MessageDigest.getInstance("SHA-256").digest(newKey)
        context.getSharedPreferences("lockit_vault", Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString("vault_key_hash", java.util.Base64.getEncoder().encodeToString(newKeyHash))
                putInt("argon2_memory", Argon2Params.MEMORY_OWASP)
                putInt("argon2_iterations", Argon2Params.ITERATIONS_OWASP)
                putInt("argon2_parallelism", Argon2Params.PARALLELISM_OWASP)
            }

        // Update in-memory master key
        keyManager.updateMasterKey(newKey)

        // Zero out sensitive keys
        oldKey.fill(0)
        newKey.fill(0)

        auditLogger.log("ARGON2_UPGRADED", "Argon2 params upgraded to OWASP, credentials re-encrypted", AuditSeverity.Warning)
    }

    /**
     * Recover vault after failed Argon2 upgrade.
     * Uses when vault params show OWASP but credentials are still encrypted with legacy key.
     */
    suspend fun recoverFromFailedUpgrade(password: String): Result<Unit> = runCatching {
        android.util.Log.d("VaultManager", "Starting recovery with password length: ${password.length}")
        if (!keyManager.isVaultInitialized()) {
            throw IllegalStateException("Vault not initialized")
        }

        val salt = keyManager.getSalt() ?: throw IllegalStateException("Vault not initialized")
        android.util.Log.d("VaultManager", "Salt: ${java.util.Base64.getEncoder().encodeToString(salt)}")

        // Try to derive legacy key and decrypt first credential to verify
        val legacyKey = crypto.deriveKey(
            password,
            salt,
            Argon2Params.MEMORY_LEGACY,
            Argon2Params.ITERATIONS_LEGACY,
            Argon2Params.PARALLELISM_LEGACY
        )

        // Verify by trying to decrypt first credential
        val firstEntity = dao.getAllEntities().firstOrNull()
            ?: throw IllegalStateException("No credentials to recover")

        try {
            crypto.decrypt(firstEntity.value, legacyKey)
        } catch (e: Exception) {
            legacyKey.fill(0)
            throw IllegalArgumentException("WRONG_PIN")
        }

        // Derive new key with OWASP params
        val newKey = crypto.deriveKey(
            password,
            salt,
            Argon2Params.MEMORY_OWASP,
            Argon2Params.ITERATIONS_OWASP,
            Argon2Params.PARALLELISM_OWASP
        )

        // Re-encrypt all credentials with new key
        LockitDatabase.getInstance(context).withTransaction {
            val allEntities = dao.getAllEntities()
            for (entity in allEntities) {
                val decrypted = crypto.decrypt(entity.value, legacyKey)
                val reEncrypted = crypto.encrypt(decrypted, newKey)
                dao.update(entity.copy(value = reEncrypted))
            }
        }

        // Update stored params and hash
        val newKeyHash = java.security.MessageDigest.getInstance("SHA-256").digest(newKey)
        context.getSharedPreferences("lockit_vault", Context.MODE_PRIVATE)
            .edit(commit = true) {
                putString("vault_key_hash", java.util.Base64.getEncoder().encodeToString(newKeyHash))
                putInt("argon2_memory", Argon2Params.MEMORY_OWASP)
                putInt("argon2_iterations", Argon2Params.ITERATIONS_OWASP)
                putInt("argon2_parallelism", Argon2Params.PARALLELISM_OWASP)
            }

        // Update in-memory master key
        keyManager.updateMasterKey(newKey)

        // Zero out sensitive keys
        legacyKey.fill(0)
        newKey.fill(0)

        auditLogger.log("VAULT_RECOVERED", "Vault recovered from failed Argon2 upgrade", AuditSeverity.Warning)
    }

    private fun decryptCredential(entity: CredentialEntity, masterKey: ByteArray): Credential {
        val decryptedValue = try {
            crypto.decrypt(entity.value, masterKey).decodeToString()
        } catch (e: Exception) {
            android.util.Log.e("VaultManager", "Decrypt failed for ${entity.name}: ${e.message}", e)
            throw e
        }

        val metadataMap = try {
            val json = org.json.JSONObject(entity.metadata)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                map[key] = json.getString(key)
            }
            map
        } catch (_: Exception) {
            emptyMap<String, String>()
        }

        return Credential(
            id = entity.id,
            name = entity.name,
            type = CredentialType.fromString(entity.type),
            service = entity.service,
            key = entity.key,
            value = decryptedValue,
            metadata = metadataMap,
            createdAt = Instant.ofEpochMilli(entity.createdAt),
            updatedAt = Instant.ofEpochMilli(entity.updatedAt),
        )
    }
}
