package com.lockit.data.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant

class CloudBackupCoordinatorTest {

    @Test
    fun `upload current vault encrypts local bytes with sync key`() = runBlocking {
        val syncKey = SyncCrypto.generateSyncKey()
        val encodedKey = SyncCrypto.encodeSyncKey(syncKey)
        val vaultFile = FakeVaultFileProvider("vault-v1".toByteArray())
        val store = FakeCloudBackupStore()
        val timestamp = Instant.parse("2026-04-30T10:15:30Z")
        val coordinator = CloudBackupCoordinator(
            syncKeyProvider = { encodedKey },
            vaultFile = vaultFile,
            now = { timestamp },
        )

        val result = coordinator.uploadCurrentVault(store)

        assertTrue(result.isSuccess)
        assertEquals(timestamp, store.lastUploadTimestamp)
        assertArrayEquals("vault-v1".toByteArray(), SyncCrypto.decrypt(store.lastUpload!!, syncKey))
    }

    @Test
    fun `restore backup downloads selected version and replaces vault bytes`() = runBlocking {
        val syncKey = SyncCrypto.generateSyncKey()
        val encodedKey = SyncCrypto.encodeSyncKey(syncKey)
        val encryptedBackup = SyncCrypto.encrypt("vault-old".toByteArray(), syncKey)
        val vaultFile = FakeVaultFileProvider("vault-new".toByteArray())
        val store = FakeCloudBackupStore().apply {
            backups["vault_2026-04-30_10-15-30"] = encryptedBackup
        }
        val coordinator = CloudBackupCoordinator(
            syncKeyProvider = { encodedKey },
            vaultFile = vaultFile,
        )

        val result = coordinator.restoreBackup(store, "vault_2026-04-30_10-15-30")

        assertTrue(result.isSuccess)
        assertArrayEquals("vault-old".toByteArray(), vaultFile.replacedBytes)
    }

    @Test
    fun `restore backup fails without sync key`() = runBlocking {
        val vaultFile = FakeVaultFileProvider("vault-new".toByteArray())
        val store = FakeCloudBackupStore()
        val coordinator = CloudBackupCoordinator(
            syncKeyProvider = { null },
            vaultFile = vaultFile,
        )

        val result = coordinator.restoreBackup(store, "vault_2026-04-30_10-15-30")

        assertTrue(result.isFailure)
        assertEquals(null, vaultFile.replacedBytes)
    }
}

private class FakeVaultFileProvider(initialBytes: ByteArray) : VaultFileProvider {
    private val file = kotlin.io.path.createTempFile().toFile()
    var replacedBytes: ByteArray? = null

    init {
        file.writeBytes(initialBytes)
        file.deleteOnExit()
    }

    override fun getVaultFile(): File = file

    override fun computeChecksum(): String = "sha256:test"

    override fun closeAndReplace(newBytes: ByteArray) {
        replacedBytes = newBytes
        file.writeBytes(newBytes)
    }
}

private class FakeCloudBackupStore : CloudBackupStore {
    val backups = mutableMapOf<String, ByteArray>()
    var lastUpload: ByteArray? = null
    var lastUploadTimestamp: Instant? = null

    override suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit> {
        lastUpload = encryptedData
        lastUploadTimestamp = timestamp
        return Result.success(Unit)
    }

    override suspend fun listBackups(): Result<List<CloudBackupMeta>> =
        Result.success(emptyList())

    override suspend fun downloadBackup(backupId: String): Result<ByteArray> =
        backups[backupId]?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("Backup not found: $backupId"))

    override suspend fun deleteBackup(backupId: String): Result<Unit> =
        Result.success(Unit)

    override suspend fun cleanupOld(maxAge: Duration): Result<Unit> =
        Result.success(Unit)
}
