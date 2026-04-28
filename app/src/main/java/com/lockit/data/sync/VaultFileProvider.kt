package com.lockit.data.sync

import android.content.Context
import com.lockit.data.database.LockitDatabase
import java.io.File
import java.security.MessageDigest

/**
 * Abstracts vault database file access for VaultSyncEngine.
 *
 * Implementations can wrap SQLite, raw files, or encrypted stores.
 */
interface VaultFileProvider {
    /** Get the vault database file. */
    fun getVaultFile(): File

    /** Compute SHA-256 checksum of the vault file. */
    fun computeChecksum(): String

    /** Replace local vault with downloaded data. Creates backup first. */
    fun closeAndReplace(newBytes: ByteArray)
}

/**
 * Default implementation wrapping LockitDatabase (Room SQLite).
 */
class SqliteVaultFileProvider(
    private val context: Context,
) : VaultFileProvider {

    override fun getVaultFile(): File =
        LockitDatabase.getDatabaseFile(context)

    override fun computeChecksum(): String {
        val dbFile = getVaultFile()
        if (!dbFile.exists()) return "sha256:empty"
        val digest = MessageDigest.getInstance("SHA-256")
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

    override fun closeAndReplace(newBytes: ByteArray) {
        LockitDatabase.closeAndReset(context)
        val dbFile = getVaultFile()
        val backupFile = File(dbFile.parent, "vault.db.backup")
        if (dbFile.exists()) {
            dbFile.copyTo(backupFile, overwrite = true)
        }
        dbFile.writeBytes(newBytes)
    }
}
