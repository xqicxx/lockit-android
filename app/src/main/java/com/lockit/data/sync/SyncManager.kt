package com.lockit.data.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

/**
 * Default conflict resolution strategy.
 */
/** Result of a sync() call. */
enum class SyncOutcome {
    AlreadyUpToDate, Pushed, Pulled, LocalWon, CloudWon, Error,
}

enum class ResolveStrategy {
    /** Keep local version, overwrite cloud. */
    KeepLocal,
    /** Keep cloud version, overwrite local. */
    KeepCloud,
    /** Newer timestamp wins (default for auto-sync). */
    LastWriteWins,
}

/**
 * Core vault sync engine. Composes [SyncBackend] (cloud storage),
 * [SyncKeyManager] (keys), [SyncStateStore] (persistent state),
 * and [VaultFileProvider] (database file I/O).
 *
 * All dependencies are injected — no direct access to SharedPreferences
 * or LockitDatabase. Swap any component for testing or reuse.
 */
class VaultSyncEngine(
    private val backend: SyncBackend,
    private val keyManager: SyncKeyManager,
    private val stateStore: SyncStateStore,
    private val vaultFile: VaultFileProvider,
) {

    // --- Sync key (delegated) ---

    fun hasSyncKey(): Boolean = keyManager.hasSyncKey()
    fun getSyncKeyEncoded(): String? = keyManager.getSyncKeyEncoded()
    fun setSyncKey(encodedKey: String) = keyManager.setSyncKey(encodedKey)
    fun clearSyncKey() {
        keyManager.clearSyncKey()
        stateStore.clear()
    }
    fun getDeviceId(): String = keyManager.getDeviceId()

    // --- Sync status ---

    suspend fun getSyncStatus(): SyncStatus {
        return withContext(Dispatchers.IO) {
            when {
                !hasSyncKey() -> SyncStatus.NotConfigured
                !backend.isConfigured() -> SyncStatus.Error
                else -> {
                    val cloudManifest = backend.getManifest().getOrNull()
                    if (cloudManifest == null) SyncStatus.NeverSynced
                    else computeStatus(cloudManifest)
                }
            }
        }
    }

    private fun computeStatus(cloudManifest: SyncManifest): SyncStatus {
        val localChecksum = vaultFile.computeChecksum()
        val lastChecksum = stateStore.lastSyncChecksum

        return when {
            lastChecksum == null -> {
                if (cloudManifest.vaultChecksum == localChecksum) SyncStatus.UpToDate
                else SyncStatus.CloudAhead
            }
            cloudManifest.vaultChecksum == localChecksum -> SyncStatus.UpToDate
            cloudManifest.vaultChecksum == lastChecksum -> SyncStatus.LocalAhead
            localChecksum == lastChecksum -> SyncStatus.CloudAhead
            else -> SyncStatus.Conflict
        }
    }

    // --- Push ---

    suspend fun push(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val localChecksum = vaultFile.computeChecksum()
                val cloudManifest = backend.getManifest().getOrNull()

                val conflict = ConflictDetector.checkPushConflict(
                    cloudManifest = cloudManifest,
                    lastSyncChecksum = stateStore.lastSyncChecksum,
                    localChecksum = localChecksum,
                    localDevice = getDeviceId(),
                )

                if (conflict != null) {
                    Result.failure(ConflictDetector.toException(conflict))
                } else {
                    encryptAndUpload(localChecksum)
                }
            }
        }
    }

    private suspend fun encryptAndUpload(localChecksum: String): Result<Unit> {
        val syncKey = SyncCrypto.decodeSyncKey(getSyncKeyEncoded()!!)
        val plaintext = vaultFile.readVaultBytes()
        val encrypted = SyncCrypto.encrypt(plaintext, syncKey)

        val manifest = SyncManifest(
            vaultChecksum = localChecksum,
            updatedAt = Instant.now(),
            updatedBy = getDeviceId(),
            encryptedSize = encrypted.size.toLong(),
        )

        return backend.uploadVault(encrypted, manifest).onSuccess {
            stateStore.recordSync(manifest.vaultChecksum)
        }
    }

    // --- Pull ---

    suspend fun pull(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val cloudManifest = backend.getManifest().getOrNull()
                if (cloudManifest == null) {
                    Result.failure(IllegalStateException("No cloud vault exists"))
                } else {
                    val localChecksum = vaultFile.computeChecksum()

                    val conflict = ConflictDetector.checkPullConflict(
                        cloudManifest = cloudManifest,
                        lastSyncChecksum = stateStore.lastSyncChecksum,
                        localChecksum = localChecksum,
                        localDevice = getDeviceId(),
                    )

                    if (conflict != null) {
                        Result.failure(ConflictDetector.toException(conflict))
                    } else {
                        downloadAndDecrypt(cloudManifest)
                    }
                }
            }
        }
    }

    private suspend fun downloadAndDecrypt(cloudManifest: SyncManifest): Result<Unit> {
        val syncKey = SyncCrypto.decodeSyncKey(getSyncKeyEncoded()!!)
        val encrypted = backend.downloadVault().getOrThrow()
        val plaintext = SyncCrypto.decrypt(encrypted, syncKey)

        vaultFile.closeAndReplace(plaintext)

        stateStore.recordSync(cloudManifest.vaultChecksum)
        return Result.success(Unit)
    }

    // --- Force push (skip conflict check) ---

    suspend fun forcePush(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else {
                encryptAndUpload(vaultFile.computeChecksum())
            }
        }
    }

    // --- Force pull (skip conflict check) ---

    suspend fun forcePull(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val cloudManifest = backend.getManifest().getOrNull()
                if (cloudManifest == null) {
                    Result.failure(IllegalStateException("No cloud vault exists"))
                } else {
                    downloadAndDecrypt(cloudManifest)
                }
            }
        }
    }

    // --- Smart sync API ---

    fun getVaultFile(): java.io.File = vaultFile.getVaultFile()
    fun readVaultBytes(): ByteArray = vaultFile.readVaultBytes()

    suspend fun autoPush(): Result<Unit> = push(ResolveStrategy.LastWriteWins)

    suspend fun push(strategy: ResolveStrategy): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasSyncKey()) return@withContext Result.failure(IllegalStateException("No Sync Key configured"))
        if (!backend.isConfigured()) return@withContext Result.failure(IllegalStateException("Backend not configured"))

        val localChecksum = vaultFile.computeChecksum()
        val cloudManifest = backend.getManifest().getOrNull()
        val conflict = ConflictDetector.checkPushConflict(
            cloudManifest = cloudManifest,
            lastSyncChecksum = stateStore.lastSyncChecksum,
            localChecksum = localChecksum,
            localDevice = getDeviceId(),
        )
        when {
            conflict == null -> encryptAndUpload(localChecksum)
            strategy == ResolveStrategy.KeepLocal -> forcePush()
            strategy == ResolveStrategy.KeepCloud -> Result.success(Unit)
            strategy == ResolveStrategy.LastWriteWins -> Result.failure(ConflictDetector.toException(conflict))
            else -> Result.failure(IllegalStateException("Unknown strategy"))
        }
    }

    suspend fun pull(strategy: ResolveStrategy): Result<Unit> = withContext(Dispatchers.IO) {
        if (!hasSyncKey()) return@withContext Result.failure(IllegalStateException("No Sync Key configured"))
        if (!backend.isConfigured()) return@withContext Result.failure(IllegalStateException("Backend not configured"))

        val cloudManifest = backend.getManifest().getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("No cloud vault exists"))

        val localChecksum = vaultFile.computeChecksum()
        val conflict = ConflictDetector.checkPullConflict(
            cloudManifest = cloudManifest,
            lastSyncChecksum = stateStore.lastSyncChecksum,
            localChecksum = localChecksum,
            localDevice = getDeviceId(),
        )
        when {
            conflict == null -> downloadAndDecrypt(cloudManifest)
            strategy == ResolveStrategy.KeepCloud -> forcePull()
            strategy == ResolveStrategy.KeepLocal -> Result.success(Unit)
            strategy == ResolveStrategy.LastWriteWins -> Result.failure(ConflictDetector.toException(conflict))
            else -> Result.failure(IllegalStateException("Unknown strategy"))
        }
    }

    suspend fun sync(): Result<SyncOutcome> = withContext(Dispatchers.IO) {
        if (!hasSyncKey()) return@withContext Result.failure(IllegalStateException("No Sync Key configured"))
        if (!backend.isConfigured()) return@withContext Result.failure(IllegalStateException("Backend not configured"))

        val cloudManifest = backend.getManifest().getOrNull()
        if (cloudManifest == null) {
            return@withContext forcePush().fold(
                onSuccess = { Result.success(SyncOutcome.Pushed) },
                onFailure = { Result.failure(it) },
            )
        }
        val localChecksum = vaultFile.computeChecksum()
        val lastChecksum = stateStore.lastSyncChecksum

        when {
            cloudManifest.vaultChecksum == localChecksum ->
                Result.success(SyncOutcome.AlreadyUpToDate)
            lastChecksum == null -> {
                if (localChecksum == "sha256:empty") {
                    pull().fold(
                        onSuccess = { Result.success(SyncOutcome.Pulled) },
                        onFailure = { Result.failure(it) },
                    )
                } else {
                    Result.failure(ConflictDetector.toException(
                        ConflictDetector.ConflictInfo(
                            localChecksum = localChecksum,
                            cloudChecksum = cloudManifest.vaultChecksum,
                            localUpdated = Instant.now(),
                            cloudUpdated = cloudManifest.updatedAt,
                            localDevice = getDeviceId(),
                            cloudDevice = cloudManifest.updatedBy,
                        )
                    ))
                }
            }
            cloudManifest.vaultChecksum == lastChecksum && localChecksum != lastChecksum ->
                push().fold(
                    onSuccess = { Result.success(SyncOutcome.Pushed) },
                    onFailure = { Result.failure(it) },
                )
            localChecksum == lastChecksum && cloudManifest.vaultChecksum != lastChecksum ->
                pull().fold(
                    onSuccess = { Result.success(SyncOutcome.Pulled) },
                    onFailure = { Result.failure(it) },
                )
            else -> {
                Result.failure(ConflictDetector.toException(
                    ConflictDetector.ConflictInfo(
                        localChecksum = localChecksum,
                        cloudChecksum = cloudManifest.vaultChecksum,
                        localUpdated = Instant.now(),
                        cloudUpdated = cloudManifest.updatedAt,
                        localDevice = getDeviceId(),
                        cloudDevice = cloudManifest.updatedBy,
                    )
                ))
            }
        }
    }
}
