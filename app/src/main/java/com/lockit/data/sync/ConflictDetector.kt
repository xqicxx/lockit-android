package com.lockit.data.sync

import java.time.Instant

/**
 * Pure-logic conflict detection for vault sync.
 * No Android dependencies — testable with unit tests.
 *
 * Reusable for any sync scenario that uses checksum-based conflict detection.
 */
object ConflictDetector {

    /** Result of conflict detection. Null means no conflict. */
    data class ConflictInfo(
        val localChecksum: String,
        val cloudChecksum: String,
        val localUpdated: Instant,
        val cloudUpdated: Instant,
        val localDevice: String,
        val cloudDevice: String,
    )

    /**
     * Check for push conflict: cloud was modified since our last sync,
     * or first-ever push with cloud data different from local.
     */
    fun checkPushConflict(
        cloudManifest: SyncManifest?,
        lastSyncChecksum: String?,
        localChecksum: String,
        localDevice: String,
    ): ConflictInfo? {
        if (cloudManifest == null) return null

        val conflict = if (lastSyncChecksum != null) {
            cloudManifest.vaultChecksum != lastSyncChecksum
        } else {
            cloudManifest.vaultChecksum != localChecksum
        }

        return if (conflict) ConflictInfo(
            localChecksum = localChecksum,
            cloudChecksum = cloudManifest.vaultChecksum,
            localUpdated = Instant.now(),
            cloudUpdated = cloudManifest.updatedAt,
            localDevice = localDevice,
            cloudDevice = cloudManifest.updatedBy,
        ) else null
    }

    /**
     * Check for pull conflict: local was modified since our last sync.
     */
    fun checkPullConflict(
        cloudManifest: SyncManifest,
        lastSyncChecksum: String?,
        localChecksum: String,
        localDevice: String,
    ): ConflictInfo? {
        val conflict = if (lastSyncChecksum != null) {
            localChecksum != lastSyncChecksum
        } else {
            localChecksum != "sha256:empty" && localChecksum != cloudManifest.vaultChecksum
        }

        return if (conflict) ConflictInfo(
            localChecksum = localChecksum,
            cloudChecksum = cloudManifest.vaultChecksum,
            localUpdated = Instant.now(),
            cloudUpdated = cloudManifest.updatedAt,
            localDevice = localDevice,
            cloudDevice = cloudManifest.updatedBy,
        ) else null
    }

    /** Map conflict info to exception for structured error handling. */
    fun toException(info: ConflictInfo): SyncConflictException =
        SyncConflictException(
            localChecksum = info.localChecksum,
            cloudChecksum = info.cloudChecksum,
            localUpdated = info.localUpdated,
            cloudUpdated = info.cloudUpdated,
            localDevice = info.localDevice,
            cloudDevice = info.cloudDevice,
        )
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

    fun toSyncConflict(): SyncConflict = SyncConflict(
        localChecksum = localChecksum,
        cloudChecksum = cloudChecksum,
        localUpdated = localUpdated,
        cloudUpdated = cloudUpdated,
        localDevice = localDevice,
        cloudDevice = cloudDevice,
    )
}
