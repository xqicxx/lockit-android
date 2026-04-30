package com.lockit.data.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.Locale

/**
 * Google Drive backend for vault sync.
 * Implements SyncBackend interface with bidirectional sync support.
 * Uses appDataFolder for privacy (not visible to user).
 *
 * File structure:
 * - lockit-sync/vault.enc  ← encrypted vault.db
 * - lockit-sync/manifest.json ← sync metadata
 */
class GoogleDriveBackend(private val context: Context) : SyncBackend, CloudBackupStore {

    override val name = "Google Drive"

    companion object {
        internal const val APP_DATA_FOLDER_ID = "appDataFolder"
        private const val FOLDER_NAME = "lockit-sync"
        private const val VAULT_FILE_NAME = "vault.enc"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val MIME_TYPE = "application/octet-stream"
        private const val BACKUP_MIME_TYPE = "application/octet-stream"
        val REQUIRED_SCOPES: Array<Scope> = arrayOf(
            Scope(DriveScopes.DRIVE_APPDATA),
            Scope(DriveScopes.DRIVE_FILE),
        )
        private val REQUIRED_SCOPE_STRINGS = listOf(
            DriveScopes.DRIVE_APPDATA,
            DriveScopes.DRIVE_FILE,
        )

        fun hasRequiredPermissions(account: GoogleSignInAccount?): Boolean =
            account != null && GoogleSignIn.hasPermissions(account, *REQUIRED_SCOPES)
    }

    private var driveService: Drive? = null
    private var folderId: String? = null

    private val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(REQUIRED_SCOPES.first(), *REQUIRED_SCOPES.drop(1).toTypedArray())
            .build()
        GoogleSignIn.getClient(context, options)
    }

    fun getSignInIntent(): Intent = signInClient.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun signOut() {
        signInClient.signOut()
        driveService = null
        folderId = null
    }

    override suspend fun isConfigured(): Boolean {
        return hasRequiredPermissions(GoogleSignIn.getLastSignedInAccount(context))
    }

    override suspend fun configure(credentials: Map<String, String>): Result<Unit> {
        val account = GoogleSignIn.getLastSignedInAccount(context)
        if (account == null) {
            return Result.failure(IllegalStateException("No Google account signed in"))
        }
        return initDriveService(account)
    }

    private suspend fun initDriveService(account: GoogleSignInAccount): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                if (!hasRequiredPermissions(account)) {
                    return@withContext Result.failure(DrivePermissionRequiredException())
                }
                val selectedAccount = account.account
                    ?: return@withContext Result.failure(IllegalStateException("Google account missing Android Account handle"))
                val credential = GoogleAccountCredential.usingOAuth2(context, REQUIRED_SCOPE_STRINGS)
                    .apply { this.selectedAccount = selectedAccount }
                driveService = Drive.Builder(
                    NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential,
                ).setApplicationName("Lockit").build()

                // Prefer the legacy lockit-sync folder if it exists. New installs write
                // directly into appDataFolder to avoid folder-create permission failures.
                val existing = driveService!!.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$FOLDER_NAME' and mimeType='application/vnd.google-apps.folder'")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                if (existing != null) {
                    folderId = existing.id
                } else {
                    folderId = APP_DATA_FOLDER_ID
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun uploadVault(
        encryptedData: ByteArray,
        manifest: SyncManifest
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val fid = folderId ?: return@withContext Result.failure(IllegalStateException("Folder not found"))

                // Find existing vault.enc
                val existingVault = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$VAULT_FILE_NAME' and '$fid' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                // Upload vault.enc
                val vaultContent = ByteArrayContent(MIME_TYPE, encryptedData)

                if (existingVault != null) {
                    val meta = DriveFile().apply {
                        name = VAULT_FILE_NAME
                        mimeType = MIME_TYPE
                    }
                    drive.files().update(existingVault.id, meta, vaultContent).execute()
                } else {
                    val meta = DriveFile().apply {
                        name = VAULT_FILE_NAME
                        mimeType = MIME_TYPE
                        parents = listOf(fid)
                    }
                    drive.files().create(meta, vaultContent).execute()
                }

                // Upload manifest.json
                val existingManifest = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$MANIFEST_FILE_NAME' and '$fid' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                val manifestContent = ByteArrayContent("application/json", manifest.toJson().toByteArray(Charsets.UTF_8))

                if (existingManifest != null) {
                    val meta = DriveFile().apply {
                        name = MANIFEST_FILE_NAME
                        mimeType = "application/json"
                    }
                    drive.files().update(existingManifest.id, meta, manifestContent).execute()
                } else {
                    val meta = DriveFile().apply {
                        name = MANIFEST_FILE_NAME
                        mimeType = "application/json"
                        parents = listOf(fid)
                    }
                    drive.files().create(meta, manifestContent).execute()
                }

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun downloadVault(): Result<ByteArray> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val fid = folderId ?: return@withContext Result.failure(IllegalStateException("Folder not found"))

                val vaultFile = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$VAULT_FILE_NAME' and '$fid' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                if (vaultFile == null) {
                    return@withContext Result.failure(IllegalStateException("No vault.enc in cloud"))
                }

                val outputStream = ByteArrayOutputStream()
                drive.files().get(vaultFile.id)
                    .executeMediaAndDownloadTo(outputStream)

                Result.success(outputStream.toByteArray())
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun getManifest(): Result<SyncManifest?> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val fid = folderId ?: return@withContext Result.failure(IllegalStateException("Folder not found"))

                val manifestFile = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$MANIFEST_FILE_NAME' and '$fid' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                if (manifestFile == null) {
                    return@withContext Result.success(null)
                }

                val outputStream = ByteArrayOutputStream()
                drive.files().get(manifestFile.id)
                    .executeMediaAndDownloadTo(outputStream)

                val json = outputStream.toString("UTF-8")
                Result.success(SyncManifest.fromJson(json))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun deleteSyncData(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val fid = folderId ?: return@withContext Result.success(Unit)

                val files = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ(syncDataQuery(fid))
                    .setFields("files(id)")
                    .execute()
                    .files

                for (file in files) {
                    drive.files().delete(file.id).execute()
                }

                if (fid != APP_DATA_FOLDER_ID) {
                    drive.files().delete(fid).execute()
                }
                folderId = null

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun disconnect() {
        signOut()
    }

    // --- CloudBackupStore implementation ---

    override suspend fun uploadBackup(encryptedData: ByteArray, timestamp: Instant): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val drive = driveService ?: return@withContext Result.failure(IllegalStateException("Drive not initialized"))
                val fid = backupParentId()

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
                val parentId = backupParentId()

                val files = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("'$parentId' in parents and name contains 'vault_'")
                    .setFields("files(id,name,size)")
                    .execute()
                    .files

                val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                Result.success(files.mapNotNull { f ->
                    val nameWithoutExt = f.name.removeSuffix(".enc")
                    val id = nameWithoutExt
                    val ts = try {
                        Instant.ofEpochMilli(sdf.parse(nameWithoutExt.removePrefix("vault_"))!!.time)
                    } catch (_: Exception) { null }
                    if (ts != null) CloudBackupMeta(id = id, timestamp = ts, sizeBytes = f.size?.toLong() ?: 0L) else null
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
                val parentId = backupParentId()

                val existing = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='${backupId}.enc' and '$parentId' in parents")
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
            listBackups().fold(
                onSuccess = { backups ->
                    val cutoff = Instant.now().minus(maxAge)
                    backups.filter { it.timestamp.isBefore(cutoff) }.forEach { meta ->
                        deleteBackup(meta.id)
                    }
                    Result.success(Unit)
                },
                onFailure = { Result.failure(it) },
            )
        }
    }

    private fun backupParentId(): String =
        folderId ?: throw IllegalStateException("Sync folder not initialized — call initDriveService first")

    private fun syncDataQuery(parentId: String): String =
        "'$parentId' in parents and (name='$VAULT_FILE_NAME' or name='$MANIFEST_FILE_NAME' or name contains 'vault_')"

    suspend fun getLastBackupTime(): Result<String?> {
        return withContext(Dispatchers.IO) {
            getManifest().map { manifest ->
                manifest?.updatedAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(it) }
            }
        }
    }
}

class DrivePermissionRequiredException : IllegalStateException(
    "Google Drive permission required: drive.appdata and drive.file"
)
