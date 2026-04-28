package com.lockit.data.sync

import android.content.SharedPreferences

/**
 * Persistent store for sync state (checksums, timestamps).
 *
 * Interface allows swapping implementations:
 * - [SharedPrefsSyncStateStore] for production (SharedPreferences)
 * - In-memory impl for tests
 */
interface SyncStateStore {
    val lastSyncChecksum: String?
    var lastSyncTime: Long
    fun recordSync(checksum: String)
    fun clear()
}

/**
 * SharedPreferences-backed sync state store.
 */
class SharedPrefsSyncStateStore(
    private val prefs: SharedPreferences,
) : SyncStateStore {

    companion object {
        const val KEY_LAST_SYNC_CHECKSUM = "last_sync_checksum"
        const val KEY_LAST_SYNC_TIME = "last_sync_time"
    }

    override var lastSyncChecksum: String?
        get() = prefs.getString(KEY_LAST_SYNC_CHECKSUM, null)
        private set(_) = Unit

    override var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
        set(value) { prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply() }

    override fun recordSync(checksum: String) {
        prefs.edit()
            .putString(KEY_LAST_SYNC_CHECKSUM, checksum)
            .putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis())
            .apply()
    }

    override fun clear() {
        prefs.edit()
            .remove(KEY_LAST_SYNC_CHECKSUM)
            .remove(KEY_LAST_SYNC_TIME)
            .apply()
    }
}
