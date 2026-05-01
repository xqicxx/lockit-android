package com.lockit.data.sync

import android.content.Context
import android.util.Log
import com.lockit.data.database.LockitDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class BackupMeta(
    val id: String,
    val timestamp: Instant,
    val entryCount: Int,
    val sizeBytes: Long,
)

class VaultBackupManager(
    private val context: Context,
) {
    companion object {
        private const val TAG = "VaultBackupManager"
        private const val BACKUP_DIR = "lockit/backups"
        private val NAME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault())
    }

    private val backupDir: File by lazy {
        File(context.filesDir, BACKUP_DIR).also { it.mkdirs() }
    }

    fun snapshot(): Result<BackupMeta> = runCatching {
        val dbFile = LockitDatabase.getDatabaseFile(context)
        if (!dbFile.exists()) throw IllegalStateException("No vault database to back up")

        val now = Instant.now()
        val name = "vault_${NAME_FORMATTER.format(now)}.db"
        val backupFile = File(backupDir, name)
        backupFile.writeBytes(SqliteVaultFileProvider(context).readVaultBytes())

        val meta = BackupMeta(
            id = name.removeSuffix(".db"),
            timestamp = now,
            entryCount = countEntries(backupFile),
            sizeBytes = backupFile.length(),
        )
        Log.i(TAG, "Snapshot: ${meta.id} (${meta.entryCount} entries, ${meta.sizeBytes} bytes)")
        meta
    }

    fun list(): List<BackupMeta> {
        return backupDir.listFiles()
            ?.filter { it.name.startsWith("vault_") && it.name.endsWith(".db") }
            ?.mapNotNull { file ->
                val id = file.name.removeSuffix(".db").removePrefix("vault_")
                val ts = try {
                    Instant.from(NAME_FORMATTER.parse(id))
                } catch (_: Exception) {
                    null
                }
                if (ts == null) null
                else BackupMeta(
                    id = id,
                    timestamp = ts,
                    entryCount = countEntries(file),
                    sizeBytes = file.length(),
                )
            }
            ?.sortedByDescending { it.timestamp }
            ?: emptyList()
    }

    fun restore(backupId: String): Result<Unit> = runCatching {
        val source = File(backupDir, "vault_${backupId}.db")
        if (!source.exists()) throw IllegalArgumentException("Backup not found: $backupId")

        // Snapshot current state before overwriting (safety net)
        snapshot()

        val dbFile = LockitDatabase.getDatabaseFile(context)
        LockitDatabase.closeAndReset(context)
        source.copyTo(dbFile, overwrite = true)
        Log.i(TAG, "Restored to $backupId")
    }

    fun cleanup(maxAge: Duration = Duration.ofDays(3)) {
        val cutoff = Instant.now().minus(maxAge)
        backupDir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".db").removePrefix("vault_")
            val ts = try {
                Instant.from(NAME_FORMATTER.parse(id))
            } catch (_: Exception) {
                null
            }
            if (ts != null && ts.isBefore(cutoff)) {
                file.delete()
                Log.i(TAG, "Cleaned up backup: $id")
            }
        }
    }

    private fun countEntries(file: File): Int {
        return try {
            val db = LockitDatabase.getRawReadOnly(context, file)
            val count = runBlocking { db.credentialDao().getAllEntities().size }
            db.close()
            count
        } catch (_: Exception) {
            -1 // unknown — UI shows size instead
        }
    }
}
