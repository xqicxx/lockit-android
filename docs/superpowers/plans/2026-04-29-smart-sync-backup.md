# Smart Sync + Auto Backup Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add 4-layer sync (auto-backup → auto-sync → one-click SYNC → manual push/pull) with backup restore UI.

**Architecture:** New `VaultBackupManager` (local snapshots), `CloudBackupStore` interface (cloud backups), `VaultAutoSync` (CRUD → backup+sync). Extend `VaultSyncEngine` with smart sync API. `GoogleDriveBackend` and `WebDavBackend` implement `CloudBackupStore`. `ConfigScreen` gets SYNC button + backup list + restore flow.

**Tech Stack:** Kotlin, Jetpack Compose, Room, already-established MVVM + interface-DI patterns.

---

### Task 1: VaultBackupManager — local backup snapshots

**Files:**
- Create: `app/src/main/java/com/lockit/data/sync/VaultBackupManager.kt`

- [ ] **Step 1: Create VaultBackupManager**

```kotlin
package com.lockit.data.sync

import android.content.Context
import android.util.Log
import com.lockit.data.database.LockitDatabase
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

/**
 * Local vault snapshot manager.
 * Stores backups in lockit/backups/, retains 3 days by default.
 */
class VaultBackupManager(
    private val context: Context,
    private val maxAge: Duration = Duration.ofDays(3),
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

    /** Create a snapshot of the current vault database. */
    fun snapshot(): Result<BackupMeta> = runCatching {
        val dbFile = LockitDatabase.getDatabaseFile(context)
        if (!dbFile.exists()) throw IllegalStateException("No vault database to back up")

        val now = Instant.now()
        val name = "vault_${NAME_FORMATTER.format(now)}.db"
        val backupFile = File(backupDir, name)
        dbFile.copyTo(backupFile, overwrite = false)

        val meta = BackupMeta(
            id = name.removeSuffix(".db"),
            timestamp = now,
            entryCount = countEntries(backupFile),
            sizeBytes = backupFile.length(),
        )
        Log.i(TAG, "Snapshot: ${meta.id} (${meta.entryCount} entries, ${meta.sizeBytes} bytes)")
        meta
    }

    /** List backups, newest first. */
    fun list(): List<BackupMeta> {
        return backupDir.listFiles()
            ?.filter { it.name.startsWith("vault_") && it.name.endsWith(".db") }
            ?.mapNotNull { file ->
                val id = file.name.removeSuffix(".db").removePrefix("vault_")
                val ts = try {
                    Instant.from(NAME_FORMATTER.parse(id))
                } catch (_: Exception) { null }
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

    /** Restore to a specific backup. Backs up current state first. */
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

    /** Delete backups older than maxAge. */
    fun cleanup() {
        val cutoff = Instant.now().minus(maxAge)
        backupDir.listFiles()?.forEach { file ->
            val id = file.name.removeSuffix(".db").removePrefix("vault_")
            val ts = try { Instant.from(NAME_FORMATTER.parse(id)) } catch (_: Exception) { null }
            if (ts != null && ts.isBefore(cutoff)) {
                file.delete()
                Log.i(TAG, "Cleaned up backup: $id")
            }
        }
    }

    private fun countEntries(file: File): Int {
        // Estimate: count by reading file size — simple heuristic.
        // Full Room DB read requires opening the DB which is heavyweight.
        // Return 0 if can't determine; caller can use size as fallback.
        return try {
            val db = LockitDatabase.getRawReadOnly(file)
            val count = db.credentialDao().getAllRaw().size
            db.close()
            count
        } catch (_: Exception) {
            -1 // unknown — UI shows size instead
        }
    }
}
```

- [ ] **Step 2: Add getRawReadOnly to LockitDatabase**

Add a static helper for reading a backup file without opening the live DB.

```kotlin
// In LockitDatabase.kt, add to companion object:

fun getRawReadOnly(file: java.io.File): LockitDatabase {
    return Room.databaseBuilder(
        com.lockit.LockitApp.instance,
        LockitDatabase::class.java,
        file.absolutePath
    ).build()
}
```

- [ ] **Step 3: Add getAllRaw to CredentialDao**

```kotlin
// In CredentialDao.kt:
@Query("SELECT * FROM credential_entity ORDER BY updated_at DESC")
suspend fun getAllRaw(): List<CredentialEntity>
```

- [ ] **Step 4: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/sync/VaultBackupManager.kt \
        app/src/main/java/com/lockit/data/database/LockitDatabase.kt \
        app/src/main/java/com/lockit/data/database/CredentialDao.kt
git commit -m "feat: add VaultBackupManager for local 3-day vault snapshots"
```

---

### Task 2: CloudBackupStore interface + SyncOutcome enum

**Files:**
- Create: `app/src/main/java/com/lockit/data/sync/CloudBackupStore.kt`

- [ ] **Step 1: Create CloudBackupStore interface**

```kotlin
package com.lockit.data.sync

import java.time.Duration
import java.time.Instant

data class CloudBackupMeta(
    val id: String,
    val timestamp: Instant,
    val sizeBytes: Long,
)

/**
 * Cloud backup storage interface.
 * Implementations: GoogleDriveBackend, WebDavBackend, S3, etc.
 *
 * Cloud backups use the same encryption as vault sync (SyncCrypto).
 * Retention: 90 days by default.
 */
interface CloudBackupStore {
    suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit>
    suspend fun listBackups(): Result<List<CloudBackupMeta>>
    suspend fun deleteBackup(backupId: String): Result<Unit>
    suspend fun cleanupOld(maxAge: Duration = Duration.ofDays(90)): Result<Unit>
}
```

- [ ] **Step 2: Add SyncOutcome enum to SyncManager.kt**

```kotlin
// Add below ResolveStrategy in SyncManager.kt:
enum class SyncOutcome {
    AlreadyUpToDate,
    Pushed,
    Pulled,
    LocalWon,
    CloudWon,
    Error,
}
```

- [ ] **Step 3: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/sync/CloudBackupStore.kt app/src/main/java/com/lockit/data/sync/SyncManager.kt
git commit -m "feat: add CloudBackupStore interface and SyncOutcome enum"
```

---

### Task 3: VaultSyncEngine — add smart sync API

**Files:**
- Modify: `app/src/main/java/com/lockit/data/sync/SyncManager.kt`

- [ ] **Step 1: Add push/pull with strategy overloads**

Add after the existing `pull()` method (before `forcePush`):

```kotlin
    // --- Push with auto-resolve ---

    suspend fun push(strategy: ResolveStrategy): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val localChecksum = vaultFile.computeChecksum()
                val cloudManifest = backend.getManifest().getOrNull()
                val conflict = ConflictDetector.checkPushConflict(
                    cloudManifest = cloudManifest,
                    lastSyncChecksum = stateStore.lastSyncChecksum,
                    localChecksum = localChecksum,
                    localDevice = getDeviceId(),
                )
                when {
                    conflict == null -> encryptAndUpload(localChecksum)
                    strategy == ResolveStrategy.KeepLocal -> forcePush()
                    strategy == ResolveStrategy.KeepCloud -> Result.success(Unit) // skip, cloud has newer
                    strategy == ResolveStrategy.LastWriteWins -> {
                        val cloudManifestNonNull = cloudManifest!!
                        if (conflict.localUpdated.isAfter(conflict.cloudUpdated)) {
                            forcePush()
                        } else {
                            Result.success(Unit) // cloud newer, skip push
                        }
                    }
                    else -> Result.failure(ConflictDetector.toException(conflict))
                }
            }
        }
    }

    // --- Pull with auto-resolve ---

    suspend fun pull(strategy: ResolveStrategy): Result<Unit> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val cloudManifest = backend.getManifest().getOrNull()
                if (cloudManifest == null) {
                    Result.failure(IllegalStateException("No cloud vault exists"))
                } else {
                    val localChecksum = vaultFile.computeChecksum()
                    val conflict = ConflictDetector.checkPullConflict(
                        cloudManifest = cloudManifest,
                        lastSyncChecksum = stateStore.lastSyncChecksum,
                        localChecksum = localChecksum,
                        localDevice = getDeviceId(),
                    )
                    when {
                        conflict == null -> downloadAndDecrypt(cloudManifest)
                        strategy == ResolveStrategy.KeepCloud -> forcePull()
                        strategy == ResolveStrategy.KeepLocal -> Result.success(Unit)
                        strategy == ResolveStrategy.LastWriteWins -> {
                            if (conflict.cloudUpdated.isAfter(conflict.localUpdated)) {
                                forcePull()
                            } else {
                                Result.success(Unit) // local newer, skip pull
                            }
                        }
                        else -> Result.failure(ConflictDetector.toException(conflict))
                    }
                }
            }
        }
    }
```

- [ ] **Step 2: Add sync() method**

Add after the strategy overloads (before forcePush):

```kotlin
    // --- One-click smart sync ---

    suspend fun sync(): Result<SyncOutcome> {
        return withContext(Dispatchers.IO) {
            if (!hasSyncKey()) {
                Result.failure(IllegalStateException("No Sync Key configured"))
            } else if (!backend.isConfigured()) {
                Result.failure(IllegalStateException("Backend not configured"))
            } else {
                val cloudManifest = backend.getManifest().getOrNull()
                when {
                    cloudManifest == null -> {
                        // NeverSynced: create initial cloud data
                        forcePush().map { SyncOutcome.Pushed }
                    }
                    else -> {
                        val localChecksum = vaultFile.computeChecksum()
                        val lastChecksum = stateStore.lastSyncChecksum
                        val localDevice = getDeviceId()

                        when {
                            cloudManifest.vaultChecksum == localChecksum -> {
                                Result.success(SyncOutcome.AlreadyUpToDate)
                            }
                            lastChecksum == null -> {
                                // First sync: cloud exists, but no local sync record
                                // Push local if different, else pull
                                push(ResolveStrategy.LastWriteWins).map { SyncOutcome.Pushed }
                            }
                            cloudManifest.vaultChecksum == lastChecksum && localChecksum != lastChecksum -> {
                                // Local ahead
                                push(ResolveStrategy.LastWriteWins).map { SyncOutcome.Pushed }
                            }
                            localChecksum == lastChecksum && cloudManifest.vaultChecksum != lastChecksum -> {
                                // Cloud ahead
                                pull(ResolveStrategy.LastWriteWins).map { SyncOutcome.Pulled }
                            }
                            else -> {
                                // Both changed: LastWriteWins
                                val conflict = ConflictDetector.checkPushConflict(
                                    cloudManifest = cloudManifest,
                                    lastSyncChecksum = lastChecksum,
                                    localChecksum = localChecksum,
                                    localDevice = localDevice,
                                )
                                if (conflict != null && conflict.localUpdated.isAfter(conflict.cloudUpdated)) {
                                    forcePush().map { SyncOutcome.LocalWon }
                                } else if (conflict != null) {
                                    forcePull().map { SyncOutcome.CloudWon }
                                } else {
                                    push(ResolveStrategy.LastWriteWins).map { SyncOutcome.Pushed }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
```

- [ ] **Step 3: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/sync/SyncManager.kt
git commit -m "feat: add smart sync API — sync(), push/pull with ResolveStrategy"
```

---

### Task 4: GoogleDriveBackend implements CloudBackupStore

**Files:**
- Modify: `app/src/main/java/com/lockit/data/sync/GoogleDriveBackend.kt`

- [ ] **Step 1: Add CloudBackupStore implementation to GoogleDriveBackend**

Change class signature to:
```kotlin
class GoogleDriveBackend(private val context: Context) : SyncBackend, CloudBackupStore {
```

Add these companion constants:
```kotlin
private const val BACKUP_FOLDER_NAME = "lockit-sync/backups"
private const val BACKUP_MIME_TYPE = "application/octet-stream"
```

Add these methods at the end of the class (before the closing brace):

```kotlin
    // --- CloudBackupStore implementation ---

    override suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val fid = ensureBackupFolder(drive)

                val name = "vault_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(java.util.Date.from(timestamp))}.enc"
                val metadata = DriveFile().apply {
                    this.name = name
                    mimeType = BACKUP_MIME_TYPE
                    parents = listOf(fid)
                }
                val content = ByteArrayContent(BACKUP_MIME_TYPE, encryptedData)
                drive.files().create(metadata, content).setFields("id").execute()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun listBackups(): Result<List<CloudBackupMeta>> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val backupFolder = findBackupFolder(drive) ?: return@withContext Result.success(emptyList())

                val files = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("'${backupFolder.id}' in parents and name contains 'vault_'")
                    .setFields("files(id,name,size)")
                    .execute()
                    .files

                val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                Result.success(files.mapNotNull { f ->
                    val id = f.name.removeSuffix(".enc")
                    val ts = try {
                        Instant.ofEpochMilli(sdf.parse(id.removePrefix("vault_"))!!.time)
                    } catch (_: Exception) { null }
                    if (ts != null) CloudBackupMeta(id = id, timestamp = ts, sizeBytes = f.size) else null
                })
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun deleteBackup(backupId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val backupFolder = findBackupFolder(drive) ?: return@withContext Result.success(Unit)

                val existing = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='${backupId}.enc' and '${backupFolder.id}' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                if (existing != null) drive.files().delete(existing.id).execute()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun cleanupOld(maxAge: Duration): Result<Unit> {
        return withContext(Dispatchers.IO) {
            listBackups().onSuccess { backups ->
                val cutoff = Instant.now().minus(maxAge)
                backups.filter { it.timestamp.isBefore(cutoff) }.forEach { meta ->
                    deleteBackup(meta.id)
                }
            }
        }
    }

    private fun ensureBackupFolder(drive: Drive): String {
        findBackupFolder(drive)?.let { return it.id }
        val folderMetadata = DriveFile().apply {
            name = "lockit-sync"
            mimeType = "application/vnd.google-apps.folder"
        }
        val parentFolder = drive.files().create(folderMetadata).setFields("id").execute()

        val backupMetadata = DriveFile().apply {
            name = "backups"
            mimeType = "application/vnd.google-apps.folder"
            parents = listOf(parentFolder.id)
        }
        return drive.files().create(backupMetadata).setFields("id").execute().id
    }

    private fun findBackupFolder(drive: Drive): DriveFile? {
        val parent = drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name='lockit-sync' and mimeType='application/vnd.google-apps.folder'")
            .setFields("files(id)")
            .execute().files.firstOrNull() ?: return null

        return drive.files().list()
            .setSpaces("appDataFolder")
            .setQ("name='backups' and '${parent.id}' in parents")
            .setFields("files(id)")
            .execute().files.firstOrNull()
    }
```

Add import at top: `import java.time.Duration`

- [ ] **Step 2: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/sync/GoogleDriveBackend.kt
git commit -m "feat: GoogleDriveBackend implements CloudBackupStore — 90-day cloud backups"
```

---

### Task 5: WebDavBackend implements CloudBackupStore

**Files:**
- Modify: `app/src/main/java/com/lockit/data/sync/WebDavBackend.kt`

- [ ] **Step 1: Add CloudBackupStore to WebDavBackend**

Change class signature to:
```kotlin
class WebDavBackend(private val context: Context) : SyncBackend, CloudBackupStore {
```

Add companion constants:
```kotlin
private const val BACKUP_FOLDER = "backups"
```

Add methods before the `disconnect()` method:

```kotlin
    // --- CloudBackupStore implementation ---

    override suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit> {
        loadCredentials()
        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl ?: return@withContext Result.failure(IOException("Not configured"))
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username ?: return@withContext Result.failure(IOException("Not configured"))
                val pass = password ?: return@withContext Result.failure(IOException("Not configured"))
                val httpClient = client ?: return@withContext Result.failure(IOException("Not configured"))

                val name = "vault_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(java.util.Date.from(timestamp))}.enc"
                val backupUrl = "$url$path/$BACKUP_FOLDER/$name"

                // Ensure backups folder exists
                ensureBackupFolder(httpClient, url, path, user, pass)

                val request = Request.Builder()
                    .url(backupUrl)
                    .put(encryptedData.toRequestBody(BINARY_MEDIA))
                    .header("Authorization", Credentials.basic(user, pass))
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Result.success(Unit)
                    else Result.failure(IOException("Upload backup failed: HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun listBackups(): Result<List<CloudBackupMeta>> {
        loadCredentials()
        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl ?: return@withContext Result.failure(IOException("Not configured"))
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username ?: return@withContext Result.failure(IOException("Not configured"))
                val pass = password ?: return@withContext Result.failure(IOException("Not configured"))
                val httpClient = client ?: return@withContext Result.failure(IOException("Not configured"))

                val backupUrl = "$url$path/$BACKUP_FOLDER"
                val request = Request.Builder()
                    .url(backupUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(user, pass))
                    .header("Depth", "1")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 207) {
                        val xml = response.body?.string() ?: ""
                        Result.success(parseBackupList(xml))
                    } else if (response.code == 404) {
                        Result.success(emptyList())
                    } else {
                        Result.failure(IOException("List backups failed: HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun deleteBackup(backupId: String): Result<Unit> {
        loadCredentials()
        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl ?: return@withContext Result.failure(IOException("Not configured"))
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username ?: return@withContext Result.failure(IOException("Not configured"))
                val pass = password ?: return@withContext Result.failure(IOException("Not configured"))
                val httpClient = client ?: return@withContext Result.failure(IOException("Not configured"))

                val request = Request.Builder()
                    .url("$url$path/$BACKUP_FOLDER/${backupId}.enc")
                    .delete()
                    .header("Authorization", Credentials.basic(user, pass))
                    .build()

                httpClient.newCall(request).execute().use { }
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun cleanupOld(maxAge: Duration): Result<Unit> {
        return withContext(Dispatchers.IO) {
            listBackups().onSuccess { backups ->
                val cutoff = Instant.now().minus(maxAge)
                backups.filter { it.timestamp.isBefore(cutoff) }.forEach { meta ->
                    deleteBackup(meta.id)
                }
            }
        }
    }

    private fun ensureBackupFolder(client: OkHttpClient, url: String, path: String, user: String, pass: String) {
        val folderUrl = "$url$path/$BACKUP_FOLDER"
        val propfind = Request.Builder()
            .url(folderUrl)
            .method("PROPFIND", null)
            .header("Authorization", Credentials.basic(user, pass))
            .header("Depth", "0")
            .build()
        client.newCall(propfind).execute().use { resp ->
            if (resp.code == 404) {
                val mkcol = Request.Builder()
                    .url(folderUrl)
                    .method("MKCOL", null)
                    .header("Authorization", Credentials.basic(user, pass))
                    .build()
                client.newCall(mkcol).execute().use { }
            }
        }
    }

    private fun parseBackupList(xml: String): List<CloudBackupMeta> {
        val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
        val hrefPattern = Regex("<(?:\\w+:)?href>([^<]+)</(?:\\w+:)?href>")
        val propstatPattern = Regex("<(?:\\w+:)?propstat>(.*?)</(?:\\w+:)?propstat>", RegexOption.DOT_MATCHES_ALL)
        val sizePattern = Regex("<(?:\\w+:)?getcontentlength>(\\d+)</(?:\\w+:)?getcontentlength>")

        return hrefPattern.findAll(xml).mapNotNull { hrefMatch ->
            val href = hrefMatch.groupValues[1]
            val name = href.substringAfterLast('/').removeSuffix(".enc")
            if (!name.startsWith("vault_")) return@mapNotNull null
            val id = name
            val ts = try {
                Instant.ofEpochMilli(sdf.parse(id.removePrefix("vault_"))!!.time)
            } catch (_: Exception) { null } ?: return@mapNotNull null

            // Find size in corresponding propstat (approximate)
            var size = 0L
            // Simple: find the size in the XML near this href
            val afterHref = xml.substring(hrefMatch.range.last + 1)
            sizePattern.find(afterHref)?.let { size = it.groupValues[1].toLong() }

            CloudBackupMeta(id = id, timestamp = ts, sizeBytes = size)
        }.toList()
    }
```

Add imports at top:
```kotlin
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale
```

- [ ] **Step 2: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/sync/WebDavBackend.kt
git commit -m "feat: WebDavBackend implements CloudBackupStore — 90-day cloud backups"
```

---

### Task 6: VaultAutoSync — CRUD → backup + sync

**Files:**
- Create: `app/src/main/java/com/lockit/data/sync/VaultAutoSync.kt`

- [ ] **Step 1: Create VaultAutoSync**

```kotlin
package com.lockit.data.sync

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Watches for credential changes and triggers backup + sync automatically.
 *
 * Fire-and-forget: backup/sync failures are logged, never thrown to caller.
 * Backup always happens before sync (local snapshot first, then cloud push).
 */
class VaultAutoSync(
    private val backupManager: VaultBackupManager,
    private val syncEngine: VaultSyncEngine,
    private val cloudBackupStore: CloudBackupStore? = null,
) {
    companion object {
        private const val TAG = "VaultAutoSync"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called after any credential add/update/delete. Fire-and-forget. */
    fun onCredentialChanged() {
        scope.launch {
            // 1. Local backup (always, even without sync key)
            backupManager.snapshot()
                .onSuccess { Log.d(TAG, "Auto-backup: ${it.id}") }
                .onFailure { Log.e(TAG, "Auto-backup failed: ${it.message}") }

            // 2. Cleanup old local backups (3 days)
            runCatching { backupManager.cleanup() }

            // 3. Cloud sync (only if sync key is configured)
            if (syncEngine.hasSyncKey()) {
                syncEngine.push(ResolveStrategy.LastWriteWins)
                    .onSuccess { Log.d(TAG, "Auto-sync pushed") }
                    .onFailure { Log.e(TAG, "Auto-sync failed: ${it.message}") }

                // 4. Upload cloud backup
                if (cloudBackupStore != null) {
                    uploadCloudBackup()
                }

                // 5. Cleanup old cloud backups (90 days)
                cloudBackupStore?.let {
                    runCatching { it.cleanupOld() }
                }
            }
        }
    }

    private suspend fun uploadCloudBackup() {
        try {
            val syncKey = syncEngine.getSyncKeyEncoded() ?: return
            val key = SyncCrypto.decodeSyncKey(syncKey)
            val vaultFile = syncEngine.vaultFileProvider() // need to expose this
            // ... requires access to vaultFileProvider
        } catch (e: Exception) {
            Log.e(TAG, "Cloud backup upload failed: ${e.message}")
        }
    }
}
```

Actually, VaultAutoSync needs access to the vault file for cloud backup upload. Let's expose it from VaultSyncEngine.

- [ ] **Step 2: Expose vaultFileProvider from VaultSyncEngine**

In VaultSyncEngine, add:
```kotlin
internal fun getVaultFileProvider(): VaultFileProvider = vaultFile
internal fun getSyncKeyBytes(): ByteArray? {
    val encoded = getSyncKeyEncoded() ?: return null
    return SyncCrypto.decodeSyncKey(encoded)
}
```

Update VaultAutoSync's `uploadCloudBackup`:
```kotlin
private suspend fun uploadCloudBackup() {
    try {
        val key = syncEngine.getSyncKeyBytes() ?: return
        val plaintext = syncEngine.getVaultFileProvider().getVaultFile().readBytes()
        val encrypted = SyncCrypto.encrypt(plaintext, key)
        cloudBackupStore?.uploadBackup(encrypted, java.time.Instant.now())
    } catch (e: Exception) {
        Log.e(TAG, "Cloud backup upload failed: ${e.message}")
    }
}
```

- [ ] **Step 3: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/sync/VaultAutoSync.kt app/src/main/java/com/lockit/data/sync/SyncManager.kt
git commit -m "feat: add VaultAutoSync — auto backup+sync on credential changes"
```

---

### Task 7: Wire VaultAutoSync into VaultManager

**Files:**
- Modify: `app/src/main/java/com/lockit/data/vault/VaultManager.kt`

- [ ] **Step 1: Add onChange listener to VaultManager**

In VaultManager, add a listener field:
```kotlin
private var onChangeListener: (() -> Unit)? = null

fun setOnChangeListener(listener: (() -> Unit)?) {
    onChangeListener = listener
}
```

After each CRUD success, call the listener. At the end of `addCredential`:
```kotlin
onChangeListener?.invoke()
```

At the end of `updateCredential`:
```kotlin
onChangeListener?.invoke()
```

At the end of `deleteCredential`:
```kotlin
onChangeListener?.invoke()
```

- [ ] **Step 2: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/data/vault/VaultManager.kt
git commit -m "feat: add onChangeListener to VaultManager for auto-sync trigger"
```

---

### Task 8: Wire VaultAutoSync in LockitApp

**Files:**
- Modify: `app/src/main/java/com/lockit/LockitApp.kt`

- [ ] **Step 1: Create VaultAutoSync in LockitApp and wire to VaultManager**

```kotlin
// In LockitApp, add lazy properties:
val vaultBackupManager by lazy { VaultBackupManager(this) }
val vaultAutoSync: VaultAutoSync? = null // set after SyncEngine is configured

// In MainActivity or ConfigScreen, after creating SyncEngine:
app.vaultManager.setOnChangeListener {
    app.getOrCreateAutoSync()?.onCredentialChanged()
}
```

Actually, cleaner approach: add a `configureAutoSync` method:

```kotlin
fun configureAutoSync(syncEngine: VaultSyncEngine, cloudBackupStore: CloudBackupStore?) {
    val autoSync = VaultAutoSync(vaultBackupManager, syncEngine, cloudBackupStore)
    vaultManager.setOnChangeListener { autoSync.onCredentialChanged() }
}
```

- [ ] **Step 2: Build and commit**

```bash
./gradlew assembleDebug
git add app/src/main/java/com/lockit/LockitApp.kt
git commit -m "feat: wire VaultAutoSync into LockitApp lifecycle"
```

---

### Task 9: ConfigScreen — SYNC button + backup list + restore UI

**Files:**
- Modify: `app/src/main/java/com/lockit/ui/screens/config/ConfigScreen.kt`

- [ ] **Step 1: Add SYNC button to Google Drive and WebDAV sections**

After the existing PUSH/PULL row, add imports:
```kotlin
import com.lockit.data.sync.SyncOutcome
import com.lockit.data.sync.VaultBackupManager
import com.lockit.data.sync.BackupMeta
import java.time.ZoneId
import java.time.format.DateTimeFormatter
```

Add SYNC button state:
```kotlin
var syncOutcome by remember { mutableStateOf<SyncOutcome?>(null) }
var showBackupList by remember { mutableStateOf(false) }
var backupList by remember { mutableStateOf<List<BackupMeta>>(emptyList()) }
var showRestoreConfirm by remember { mutableStateOf<BackupMeta?>(null) }
val backupManager = remember { VaultBackupManager(context) }
```

Add SYNC button composable (insert before PUSH/PULL row):
```kotlin
// SYNC button
BrutalistButton(
    text = "SYNC",
    onClick = {
        scope.launch {
            syncOutcome = null
            val result = googleSyncEngine.sync()
            syncOutcome = result.getOrNull() ?: SyncOutcome.Error
        }
    },
    enabled = !isSyncing && canSync,
    modifier = Modifier.fillMaxWidth(),
)
Spacer(modifier = Modifier.height(4.dp))

// Outcome label
syncOutcome?.let { outcome ->
    Text(
        text = when (outcome) {
            SyncOutcome.AlreadyUpToDate -> "Already up to date"
            SyncOutcome.Pushed -> "Pushed to cloud"
            SyncOutcome.Pulled -> "Pulled from cloud"
            SyncOutcome.LocalWon -> "Conflict → kept local"
            SyncOutcome.CloudWon -> "Conflict → kept cloud"
            SyncOutcome.Error -> "Sync failed"
        },
        fontFamily = JetBrainsMonoFamily,
        fontSize = 9.sp,
        color = when (outcome) {
            SyncOutcome.Error -> TacticalRed
            else -> IndustrialOrange
        },
    )
    Spacer(modifier = Modifier.height(8.dp))
}
```

- [ ] **Step 2: Add backup list + restore UI**

After SYNC outcome label, add:
```kotlin
// Backup list section
Spacer(modifier = Modifier.height(12.dp))
BrutalistButton(
    text = if (showBackupList) "HIDE BACKUPS" else "RESTORE BACKUPS",
    onClick = {
        showBackupList = !showBackupList
        if (showBackupList) backupList = backupManager.list()
    },
    modifier = Modifier.fillMaxWidth(),
)

if (showBackupList) {
    Spacer(modifier = Modifier.height(8.dp))
    if (backupList.isEmpty()) {
        Text(
            "No backups yet — they are created automatically when you add/edit credentials.",
            fontFamily = JetBrainsMonoFamily,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        val dtf = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())
        backupList.forEach { meta ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, MaterialTheme.colorScheme.outline)
                    .clickable { showRestoreConfirm = meta }
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    dtf.format(meta.timestamp),
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                )
                Text(
                    if (meta.entryCount >= 0) "${meta.entryCount} entries" else "${meta.sizeBytes / 1024} KB",
                    fontFamily = JetBrainsMonoFamily,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
        }
    }
}

// Restore confirmation dialog
showRestoreConfirm?.let { meta ->
    val dtf = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    BrutalistConfirmDialog(
        title = "Restore Backup",
        message = "Restore vault to ${dtf.format(meta.timestamp)}?\n\nThis will snapshot your current vault first, then replace it with the backup.",
        confirmLabel = "RESTORE",
        onConfirm = {
            scope.launch {
                backupManager.restore(meta.id)
                    .onSuccess { syncStatusMessage = "Restored to ${dtf.format(meta.timestamp)}" }
                    .onFailure { syncStatusMessage = "Restore failed: ${it.message}" }
                showRestoreConfirm = null
            }
        },
        onDismiss = { showRestoreConfirm = null },
    )
}
```

- [ ] **Step 3: Add same SYNC button + backup UI to WebDAV section**

Apply the same SYNC button pattern for WebDAV using `webDavSyncEngine`.

- [ ] **Step 4: Build, install, and commit**

```bash
./gradlew assembleDebug installDebug
git add app/src/main/java/com/lockit/ui/screens/config/ConfigScreen.kt
git commit -m "feat: add SYNC button, backup list, and restore UI to ConfigScreen"
```

---

### Task 10: Final integration test

- [ ] **Step 1: Verify all modules compile**

```bash
./gradlew assembleDebug testDebugUnitTest lintDebug
```

- [ ] **Step 2: Install on emulator and smoke test**

```bash
./gradlew installDebug
adb shell am start -n com.lockit/.ui.MainActivity
```

Manual verification:
1. Open app → Config → verify SYNC button visible
2. Add a credential → verify auto-backup created in `lockit/backups/`
3. Tap RESTORE BACKUPS → verify list shows backup entries
4. Tap SYNC → verify outcome message

- [ ] **Step 3: Final commit**

```bash
git add -A
git commit -m "feat: complete smart sync + auto backup implementation"
```
