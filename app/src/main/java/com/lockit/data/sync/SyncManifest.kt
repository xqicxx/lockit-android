package com.lockit.data.sync

import org.json.JSONObject
import java.time.Instant

/**
 * Manifest for vault.enc in cloud storage.
 * Contains metadata for conflict detection and sync status.
 */
data class SyncManifest(
    val version: Int = 2,
    val vaultChecksum: String,
    val updatedAt: Instant,
    val updatedBy: String,
    val encryptedSize: Long,
    val schemaVersion: Int = 2,
) {

    fun toJson(): String {
        val obj = JSONObject()
        obj.put("version", version)
        obj.put("vault_checksum", vaultChecksum)
        obj.put("updated_at", updatedAt.toString())
        obj.put("updated_by", updatedBy)
        obj.put("encrypted_size", encryptedSize)
        obj.put("schema_version", schemaVersion)
        return obj.toString()
    }

    companion object {
        private fun parseInstantSafely(iso: String): Instant {
            return try {
                Instant.parse(iso)
            } catch (_: Exception) {
                Instant.EPOCH
            }
        }

        fun fromJson(json: String): SyncManifest {
            val obj = JSONObject(json)
            return SyncManifest(
                version = obj.optInt("version", 2),
                vaultChecksum = obj.optString("vault_checksum", obj.optString("vaultChecksum", "")),
                updatedAt = parseInstantSafely(
                    obj.optString("updated_at", obj.optString("updatedAt", Instant.EPOCH.toString()))
                ),
                updatedBy = obj.optString("updated_by", obj.optString("updatedBy", "")),
                encryptedSize = obj.optLong("encrypted_size", obj.optLong("encryptedSize", 0)),
                schemaVersion = obj.optInt("schema_version", obj.optInt("schemaVersion", 1)),
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