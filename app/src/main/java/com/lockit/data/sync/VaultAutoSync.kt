package com.lockit.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VaultAutoSync(
    private val backupManager: VaultBackupManager,
    private val syncEngine: VaultSyncEngine,
    private val cloudBackupStore: CloudBackupStore? = null,
) {
    companion object {
        private const val TAG = "VaultAutoSync"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun onCredentialChanged() {
        scope.launch {
            backupManager.snapshot()
                .onSuccess { Log.d(TAG, "Auto-backup: ${it.id}") }
                .onFailure { Log.e(TAG, "Auto-backup failed: ${it.message}") }

            runCatching { backupManager.cleanup() }

            if (syncEngine.hasSyncKey()) {
                syncEngine.autoPush()
                    .onSuccess { Log.d(TAG, "Auto-sync pushed") }
                    .onFailure { Log.e(TAG, "Auto-sync failed: ${it.message}") }

                uploadCloudBackup()

                cloudBackupStore?.let {
                    runCatching { it.cleanupOld() }
                }
            }
        }
    }

    private suspend fun uploadCloudBackup() {
        try {
            val encoded = syncEngine.getSyncKeyEncoded() ?: return
            val key = SyncCrypto.decodeSyncKey(encoded)
            val dbFile = syncEngine.getVaultFile()
            if (!dbFile.exists()) return
            val plaintext = dbFile.readBytes()
            val encrypted = SyncCrypto.encrypt(plaintext, key)
            cloudBackupStore?.uploadBackup(encrypted, java.time.Instant.now())
        } catch (e: Exception) {
            Log.e(TAG, "Cloud backup upload failed: ${e.message}")
        }
    }
}
