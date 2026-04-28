package com.lockit.data.sync

import android.content.Context
import com.lockit.data.database.LockitDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Result of a sync() call. */
enum class SyncOutcome {
    AlreadyUpToDate,
    Pushed,
    Pulled,
    LocalWon,
    CloudWon,
    Error,
}

/**
 * Manages vault synchronization between devices.
 * Uses Sync Key for encryption (independent of device PIN).
 */
class SyncManager(
    private val context: Context,
    private val backend: SyncBackend,
) {

    private val prefs = context.getSharedPreferences("lockit_sync", Context.MODE_PRIVATE)

    companion object {
        const val KEY_SYNC_KEY = "sync_key"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_LAST_SYNC_CHECKSUM = "last_sync_checksum"
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
    }

    /**
     * Check if Sync Key is configured.
     */
    fun hasSyncKey(): Boolean {
        return prefs.contains(KEY_SYNC_KEY)
    }

    /**
     * Get the Sync Key (Base64 encoded).
     */
    fun getSyncKeyEncoded(): String? {
        return prefs.getString(KEY_SYNC_KEY, null)
    }

    /**
     * Set a new Sync Key (from generation or QR scan).
     */
    fun setSyncKey(encodedKey: String) {
        // Validate key format
        SyncCrypto.decodeSyncKey(encodedKey)
        prefs.edit().putString(KEY_SYNC_KEY, encodedKey).apply()
    }

    /**
     * Clear Sync Key (used when resetting sync).
     */
    fun clearSyncKey() {
        prefs.edit()
            .remove(KEY_SYNC_KEY)
            .remove(KEY_LAST_SYNC_CHECKSUM)
            .remove(KEY_LAST_SYNC_TIME)
            .apply()
    }

    /**
     * Get device identifier.
     */
    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = "${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    /**
     * Get current sync status.
     */
    suspend fun getSyncStatus(): SyncStatus {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) SyncStatus.NotConfigured

            else if (!backend.isConfigured()) SyncStatus.Error

            else {
                val cloudManifest = backend.getManifest().getOrNull()
                if (cloudManifest == null) SyncStatus.NeverSynced

                else {
                    val lastSyncChecksum = prefs.getString(KEY_LAST_SYNC_CHECKSUM, null)
                    val localChecksum = computeLocalChecksum()

                    if (lastSyncChecksum == null) {
                        // Never synced before, compare with cloud
                        if (cloudManifest.vaultChecksum == localChecksum) SyncStatus.UpToDate
                        else SyncStatus.CloudAhead
                    } else if (cloudManifest.vaultChecksum == localChecksum) SyncStatus.UpToDate

                    else if (cloudManifest.vaultChecksum == lastSyncChecksum) {
                        // Cloud hasn't changed, local has
                        SyncStatus.LocalAhead
                    } else if (localChecksum == lastSyncChecksum) {
                        // Cloud changed, local hasn't
                        SyncStatus.CloudAhead
                    } else {
                        // Both changed
                        SyncStatus.Conflict
                    }
                }
            }
        }
    }

    /**
     * Push local vault to cloud.
     * Returns conflict info if detected.
     */
    suspend fun push(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val syncKey = SyncCrypto.decodeSyncKey(getSyncKeyEncoded()!!)

                // Check for conflict
                val cloudManifest = backend.getManifest().getOrNull()
                val lastSyncChecksum = prefs.getString(KEY_LAST_SYNC_CHECKSUM, null)
                val localChecksum = computeLocalChecksum()

                // Conflict detection: cloud modified since our last sync OR first sync with different data
                val isConflict = cloudManifest != null && (lastSyncChecksum?.let {
                    cloudManifest.vaultChecksum != it
                } ?: (cloudManifest.vaultChecksum != localChecksum))

                if (isConflict) {
                    // Cloud has been modified since our last sync - conflict!
                    Result.failure(SyncConflictException(
                        localChecksum = localChecksum,
                        cloudChecksum = cloudManifest!!.vaultChecksum,
                        localUpdated = Instant.now(),
                        cloudUpdated = cloudManifest.updatedAt,
                        localDevice = getDeviceId(),
                        cloudDevice = cloudManifest.updatedBy,
                    ))
                } else {
                    // Encrypt and upload
                    val dbFile = getDatabaseFile()
                    val plaintext = dbFile.readBytes()
                    val encrypted = SyncCrypto.encrypt(plaintext, syncKey)

                    val manifest = SyncManifest(
                        vaultChecksum = computeLocalChecksum(),
                        updatedAt = Instant.now(),
                        updatedBy = getDeviceId(),
                        encryptedSize = encrypted.size.toLong(),
                    )

                    backend.uploadVault(encrypted, manifest).onSuccess {
                        // Update local sync state
                        prefs.edit()
                            .putString(KEY_LAST_SYNC_CHECKSUM, manifest.vaultChecksum)
                            .putLong(KEY_LAST_SYNC_TIME, manifest.updatedAt.toEpochMilli())
                            .apply()
                    }
                }
            }
        }
    }

    /**
     * Pull cloud vault to local.
     * Returns conflict info if detected.
     */
    suspend fun pull(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val syncKey = SyncCrypto.decodeSyncKey(getSyncKeyEncoded()!!)

                val cloudManifest = backend.getManifest().getOrNull()
                if (cloudManifest == null) {
                    Result.failure(IllegalStateException("No cloud vault exists"))
                } else {
                    // Check for conflict
                    val lastSyncChecksum = prefs.getString(KEY_LAST_SYNC_CHECKSUM, null)
                    val localChecksum = computeLocalChecksum()

                    // Conflict detection: local modified since our last sync OR first sync with non-empty local different from cloud
                    val isConflict = if (lastSyncChecksum != null) {
                        localChecksum != lastSyncChecksum
                    } else {
                        // First sync: conflict if local is non-empty and differs from cloud
                        localChecksum != "sha256:empty" && localChecksum != cloudManifest.vaultChecksum
                    }

                    if (isConflict) {
                        // Local has been modified since our last sync - conflict!
                        Result.failure(SyncConflictException(
                            localChecksum = localChecksum,
                            cloudChecksum = cloudManifest.vaultChecksum,
                            localUpdated = Instant.now(),
                            cloudUpdated = cloudManifest.updatedAt,
                            localDevice = getDeviceId(),
                            cloudDevice = cloudManifest.updatedBy,
                        ))
                    } else {
                        // Download and decrypt
                        val encrypted = backend.downloadVault().getOrThrow()
                        val plaintext = SyncCrypto.decrypt(encrypted, syncKey)

                        // Close database before replacing file to avoid corruption
                        LockitDatabase.closeAndReset(context)

                        val dbFile = getDatabaseFile()
                        val backupFile = File(dbFile.parent, "vault.db.backup")
                        if (dbFile.exists()) {
                            dbFile.copyTo(backupFile, overwrite = true)
                        }

                        // Replace local vault
                        dbFile.writeBytes(plaintext)

                        // Update local sync state
                        prefs.edit()
                            .putString(KEY_LAST_SYNC_CHECKSUM, cloudManifest.vaultChecksum)
                            .putLong(KEY_LAST_SYNC_TIME, cloudManifest.updatedAt.toEpochMilli())
                            .apply()

                        Result.success(Unit)
                    }
                }
            }
        }
    }

    /**
     * Force push, ignoring conflicts.
     * Use after conflict resolution (user chose "keep local").
     */
    suspend fun forcePush(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else {
                val syncKey = SyncCrypto.decodeSyncKey(getSyncKeyEncoded()!!)

                val dbFile = getDatabaseFile()
                val plaintext = dbFile.readBytes()
                val encrypted = SyncCrypto.encrypt(plaintext, syncKey)

                val manifest = SyncManifest(
                    vaultChecksum = computeLocalChecksum(),
                    updatedAt = Instant.now(),
                    updatedBy = getDeviceId(),
                    encryptedSize = encrypted.size.toLong(),
                )

                backend.uploadVault(encrypted, manifest).onSuccess {
                    prefs.edit()
                        .putString(KEY_LAST_SYNC_CHECKSUM, manifest.vaultChecksum)
                        .putLong(KEY_LAST_SYNC_TIME, manifest.updatedAt.toEpochMilli())
                        .apply()
                }
            }
        }
    }

    /**
     * Force pull, ignoring conflicts.
     * Use after conflict resolution (user chose "keep cloud").
     */
    suspend fun forcePull(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val syncKey = SyncCrypto.decodeSyncKey(getSyncKeyEncoded()!!)
                val cloudManifest = backend.getManifest().getOrNull()
                if (cloudManifest == null) {
                    Result.failure(IllegalStateException("No cloud vault exists"))
                } else {
                    // Download and decrypt, skipping conflict check
                    val encrypted = backend.downloadVault().getOrThrow()
                    val plaintext = SyncCrypto.decrypt(encrypted, syncKey)

                    LockitDatabase.closeAndReset(context)

                    val dbFile = getDatabaseFile()
                    val backupFile = File(dbFile.parent, "vault.db.backup")
                    if (dbFile.exists()) {
                        dbFile.copyTo(backupFile, overwrite = true)
                    }

                    dbFile.writeBytes(plaintext)

                    prefs.edit()
                        .putString(KEY_LAST_SYNC_CHECKSUM, cloudManifest.vaultChecksum)
                        .putLong(KEY_LAST_SYNC_TIME, cloudManifest.updatedAt.toEpochMilli())
                        .apply()

                    Result.success(Unit)
                }
            }
        }
    }

    // --- Private ---

    private fun getDatabaseFile(): File {
        return LockitDatabase.getDatabaseFile(context)
    }

    private fun computeLocalChecksum(): String {
        val dbFile = getDatabaseFile()
        if (!dbFile.exists()) return "sha256:empty"

        val digest = MessageDigest.getInstance("SHA-256")
        // Use stream to avoid OOM for large databases
        val hash = dbFile.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
            digest.digest()
        }
        return "sha256:" + hash.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Exception thrown when sync conflict is detected.
 */
class SyncConflictException(
    val localChecksum: String,
    val cloudChecksum: String,
    val localUpdated: Instant,
    val cloudUpdated: Instant,
    val localDevice: String,
    val cloudDevice: String,
) : Exception("Sync conflict: local and cloud both modified") {

    fun toSyncConflict(): SyncConflict {
        return SyncConflict(
            localChecksum = localChecksum,
            cloudChecksum = cloudChecksum,
            localUpdated = localUpdated,
            cloudUpdated = cloudUpdated,
            localDevice = localDevice,
            cloudDevice = cloudDevice,
        )
    }
}