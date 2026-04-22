package com.lockit.data.sync

/**
 * Interface for cloud sync backends.
 * Supports Google Drive, S3, WebDAV, etc.
 */
interface SyncBackend {

    /**
     * Backend name for UI display.
     */
    val name: String

    /**
     * Check if backend is configured (has auth credentials).
     */
    suspend fun isConfigured(): Boolean

    /**
     * Configure backend with credentials.
     * For Google Drive: uses account email.
     * For S3: uses access key + secret.
     */
    suspend fun configure(credentials: Map<String, String>): Result<Unit>

    /**
     * Upload vault.enc to cloud.
     * Atomic operation: upload vault.enc, then update manifest.
     */
    suspend fun uploadVault(
        encryptedData: ByteArray,
        manifest: SyncManifest
    ): Result<Unit>

    /**
     * Download vault.enc from cloud.
     * Returns encrypted blob.
     */
    suspend fun downloadVault(): Result<ByteArray>

    /**
     * Get manifest from cloud.
     * Returns null if no cloud data exists.
     */
    suspend fun getManifest(): Result<SyncManifest?>

    /**
     * Delete all sync data from cloud.
     * Used when Sync Key is changed.
     */
    suspend fun deleteSyncData(): Result<Unit>

    /**
     * Release backend resources.
     */
    suspend fun disconnect()
}