package com.lockit.data.sync

import android.content.SharedPreferences
import java.util.UUID

/**
 * Manages sync key lifecycle and device identity.
 *
 * Reusable outside of VaultSyncEngine — any sync implementation can use this
 * for key generation, validation, and device identification.
 */
class SyncKeyManager(
    private val prefs: SharedPreferences,
) {
    companion object {
        const val KEY_SYNC_KEY = "sync_key"
        const val KEY_DEVICE_ID = "device_id"

        /** Generate a new random 256-bit sync key. */
        fun generateKey(): ByteArray = SyncCrypto.generateSyncKey()

        /** Encode raw key bytes to Base64 for QR code / manual input. */
        fun encodeKey(key: ByteArray): String = SyncCrypto.encodeSyncKey(key)

        /** Decode Base64-encoded key from QR scan or manual input. */
        fun decodeKey(encoded: String): ByteArray = SyncCrypto.decodeSyncKey(encoded)

        /** Validate encoded key format without actually using it. */
        fun isValidKeyFormat(encoded: String): Boolean = try {
            decodeKey(encoded)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun hasSyncKey(): Boolean = prefs.contains(KEY_SYNC_KEY)

    fun getSyncKeyEncoded(): String? = prefs.getString(KEY_SYNC_KEY, null)

    fun setSyncKey(encodedKey: String) {
        SyncCrypto.decodeSyncKey(encodedKey) // validate
        prefs.edit().putString(KEY_SYNC_KEY, encodedKey).apply()
    }

    fun clearSyncKey() {
        prefs.edit().remove(KEY_SYNC_KEY).apply()
    }

    fun getDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val newId = "${android.os.Build.MODEL}-${UUID.randomUUID().toString().take(8)}"
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }
}
