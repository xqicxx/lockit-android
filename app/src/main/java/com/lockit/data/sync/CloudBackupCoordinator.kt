package com.lockit.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

class CloudBackupCoordinator(
    private val syncKeyProvider: () -> String?,
    private val vaultFile: VaultFileProvider,
    private val now: () -> Instant = { Instant.now() },
) {

    suspend fun uploadCurrentVault(store: CloudBackupStore): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val syncKey = syncKey()
            val file = vaultFile.getVaultFile()
            if (!file.exists()) {
                throw IllegalStateException("No vault database to back up")
            }
            val encrypted = SyncCrypto.encrypt(vaultFile.readVaultBytes(), syncKey)
            store.uploadBackup(encrypted, now()).getOrThrow()
        }
    }

    suspend fun restoreBackup(store: CloudBackupStore, backupId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val encrypted = store.downloadBackup(backupId).getOrThrow()
            val plaintext = SyncCrypto.decrypt(encrypted, syncKey())
            vaultFile.closeAndReplace(plaintext)
        }
    }

    private fun syncKey(): ByteArray {
        val encoded = syncKeyProvider() ?: throw IllegalStateException("No Sync Key configured")
        return SyncCrypto.decodeSyncKey(encoded)
    }
}
