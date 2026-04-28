package com.lockit.data.sync

import java.time.Duration
import java.time.Instant

data class CloudBackupMeta(
    val id: String,
    val timestamp: Instant,
    val sizeBytes: Long,
)

/**
 * Cloud backup storage interface.
 * Implementations: GoogleDriveBackend, WebDavBackend, S3, etc.
 *
 * Cloud backups use the same encryption as vault sync (SyncCrypto).
 * Retention: 90 days by default.
 */
interface CloudBackupStore {
    suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit>
    suspend fun listBackups(): Result<List<CloudBackupMeta>>
    suspend fun deleteBackup(backupId: String): Result<Unit>
    suspend fun cleanupOld(maxAge: Duration = Duration.ofDays(90)): Result<Unit>
}
