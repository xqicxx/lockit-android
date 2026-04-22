package com.lockit.data.sync

import org.json.JSONObject
import java.time.Instant

/**
 * Manifest for vault.enc in cloud storage.
 * Contains metadata for conflict detection and sync status.
 */
data class SyncManifest(
    val version: Int = 1,
    val vaultChecksum: String, // sha256:abc123...
    val updatedAt: Instant,
    val updatedBy: String, // device identifier
    val encryptedSize: Long,
) {

    /**
     * Serialize to JSON for cloud storage.
     */
    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("vaultChecksum", vaultChecksum)
        obj.put("updatedAt", updatedAt.toString())
        obj.put("updatedBy", updatedBy)
        obj.put("encryptedSize", encryptedSize)
        return obj.toString()
    }

    companion object {
        /**
         * Parse manifest from JSON.
         */
        fun fromJson(json: String): SyncManifest {
            val obj = JSONObject(json)
            return SyncManifest(
                version = obj.getInt("version"),
                vaultChecksum = obj.getString("vaultChecksum"),
                updatedAt = Instant.parse(obj.getString("updatedAt")),
                updatedBy = obj.getString("updatedBy"),
                encryptedSize = obj.getLong("encryptedSize"),
            )
        }
    }
}

/**
 * Sync status for UI display.
 */
enum class SyncStatus {
    NotConfigured,  // No Sync Key set
    NeverSynced,    // Sync Key set, but no cloud data
    UpToDate,       // Local matches cloud
    LocalAhead,     // Local newer than cloud (need push)
    CloudAhead,     // Cloud newer than local (need pull)
    Conflict,       // Both changed, need resolution
    Error,          // Sync failed
}

/**
 * Sync conflict info for resolution UI.
 */
data class SyncConflict(
    val localChecksum: String,
    val cloudChecksum: String,
    val localUpdated: Instant,
    val cloudUpdated: Instant,
    val localDevice: String,
    val cloudDevice: String,
)