package com.lockit.data.vault

import android.content.Context
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
        return dao.search(query).map { entities ->
            val masterKey = requireMasterKey()
            entities.map { decryptCredential(it, masterKey) }
        }
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

        val entity = CredentialEntity(
            id = id,
            name = name,
            type = type.name,
            service = service,
            key = key,
            value = encryptedValue,
            metadata = metadata ?: existing?.metadata ?: "{}",
            createdAt = existing?.createdAt ?: Instant.now().toEpochMilli(),
            updatedAt = Instant.now().toEpochMilli(),
        )
        dao.update(entity)
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

    fun getCredentialCount(): Flow<Int> = dao.getCount()
    fun getServiceCount(): Flow<Int> = dao.getServiceCount()

    /**
     * Get the Argon2 parameters used for this vault.
     * Returns OWASP params for new vaults, legacy params for old vaults.
     */
    fun getArgon2ParamsInfo(): Triple<Int, Int, Int> = keyManager.getArgon2Params()

    private fun decryptCredential(entity: CredentialEntity, masterKey: ByteArray): Credential {
        val decryptedValue = crypto.decrypt(entity.value, masterKey)
            .decodeToString()

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
