package com.lockit.data.sync

import android.content.SharedPreferences
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant

class VaultSyncEngineSafetyTest {

    @Test
    fun `sync does not overwrite existing cloud vault on first sync when local differs`() = runBlocking {
        val backend = FakeSyncBackend(
            manifest = SyncManifest(
                vaultChecksum = "sha256:cloud",
                updatedAt = Instant.parse("2026-04-30T10:15:30Z"),
                updatedBy = "other-device",
                encryptedSize = 64,
            ),
        )
        val keyManager = SyncKeyManager(InMemorySharedPreferences()).apply {
            setSyncKey(SyncCrypto.encodeSyncKey(SyncCrypto.generateSyncKey()))
        }
        val stateStore = SharedPrefsSyncStateStore(InMemorySharedPreferences())
        val engine = VaultSyncEngine(
            backend = backend,
            keyManager = keyManager,
            stateStore = stateStore,
            vaultFile = SafetyVaultFileProvider(checksum = "sha256:local"),
        )

        val result = engine.sync()

        assertTrue(result.exceptionOrNull() is SyncConflictException)
        assertFalse("first sync must not overwrite cloud data", backend.uploaded)
    }

    @Test
    fun `sync does not overwrite cloud when both local and cloud changed`() = runBlocking {
        val backend = FakeSyncBackend(
            manifest = SyncManifest(
                vaultChecksum = "sha256:cloud-new",
                updatedAt = Instant.parse("2026-04-30T10:15:30Z"),
                updatedBy = "other-device",
                encryptedSize = 64,
            ),
        )
        val keyManager = SyncKeyManager(InMemorySharedPreferences()).apply {
            setSyncKey(SyncCrypto.encodeSyncKey(SyncCrypto.generateSyncKey()))
        }
        val stateStore = SharedPrefsSyncStateStore(InMemorySharedPreferences()).apply {
            recordSync("sha256:base")
        }
        val engine = VaultSyncEngine(
            backend = backend,
            keyManager = keyManager,
            stateStore = stateStore,
            vaultFile = SafetyVaultFileProvider(checksum = "sha256:local-new"),
        )

        val result = engine.sync()

        assertTrue(result.exceptionOrNull() is SyncConflictException)
        assertFalse("conflict must not overwrite cloud data", backend.uploaded)
    }
}

private class FakeSyncBackend(
    private val manifest: SyncManifest?,
) : SyncBackend {
    var uploaded = false

    override val name: String = "Fake"

    override suspend fun isConfigured(): Boolean = true

    override suspend fun configure(credentials: Map<String, String>): Result<Unit> =
        Result.success(Unit)

    override suspend fun uploadVault(encryptedData: ByteArray, manifest: SyncManifest): Result<Unit> {
        uploaded = true
        return Result.success(Unit)
    }

    override suspend fun downloadVault(): Result<ByteArray> =
        Result.failure(IllegalStateException("not used"))

    override suspend fun getManifest(): Result<SyncManifest?> =
        Result.success(manifest)

    override suspend fun deleteSyncData(): Result<Unit> =
        Result.success(Unit)

    override suspend fun disconnect() = Unit
}

private class SafetyVaultFileProvider(
    private val checksum: String,
) : VaultFileProvider {
    private val file = kotlin.io.path.createTempFile().toFile()

    init {
        file.writeBytes("local-vault".toByteArray())
        file.deleteOnExit()
    }

    override fun getVaultFile(): File = file

    override fun computeChecksum(): String = checksum

    override fun closeAndReplace(newBytes: ByteArray) {
        file.writeBytes(newBytes)
    }
}

private class InMemorySharedPreferences : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val updates = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { if (key != null) updates[key] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { if (key != null) updates[key] = value }
        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { if (key != null) updates[key] = value }
        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { if (key != null) updates[key] = value }
        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { if (key != null) updates[key] = value }
        override fun remove(key: String?): SharedPreferences.Editor = apply { if (key != null) removals.add(key) }
        override fun clear(): SharedPreferences.Editor = apply { values.clear() }
        override fun commit(): Boolean {
            removals.forEach { values.remove(it) }
            values.putAll(updates)
            return true
        }
        override fun apply() {
            commit()
        }
    }
}
