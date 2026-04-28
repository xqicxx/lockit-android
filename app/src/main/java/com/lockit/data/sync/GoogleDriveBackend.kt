package com.lockit.data.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.ByteArrayContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File as DriveFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
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
class GoogleDriveBackend(private val context: Context) : SyncBackend {

    override val name = "Google Drive"

    companion object {
        private const val FOLDER_NAME = "lockit-sync"
        private const val VAULT_FILE_NAME = "vault.enc"
        private const val MANIFEST_FILE_NAME = "manifest.json"
        private const val MIME_TYPE = "application/octet-stream"
    }

    private var driveService: Drive? = null
    private var folderId: String? = null

    private val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
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
        return GoogleSignIn.getLastSignedInAccount(context) != null
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
                val credential = GoogleAccountCredential.usingOAuth2(
                    context,
                    listOf(DriveScopes.DRIVE_APPDATA)
                ).apply {
                    selectedAccount = account.account
                }
                driveService = Drive.Builder(
                    com.google.api.client.http.javanet.NetHttpTransport(),
                    GsonFactory.getDefaultInstance(),
                    credential,
                ).setApplicationName("Lockit").build()

                // Create or find lockit-sync folder
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
                    val folderMetadata = DriveFile().apply {
                        name = FOLDER_NAME
                        mimeType = "application/vnd.google-apps.folder"
                    }
                    val created = driveService!!.files().create(folderMetadata)
                        .setFields("id")
                        .execute()
                    folderId = created.id
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
                val vaultMetadata = DriveFile().apply {
                    name = VAULT_FILE_NAME
                    mimeType = MIME_TYPE
                    parents = listOf(fid)
                }
                val vaultContent = ByteArrayContent(MIME_TYPE, encryptedData)

                if (existingVault != null) {
                    drive.files().update(existingVault.id, vaultMetadata, vaultContent).execute()
                } else {
                    drive.files().create(vaultMetadata, vaultContent).execute()
                }

                // Upload manifest.json
                val existingManifest = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("name='$MANIFEST_FILE_NAME' and '$fid' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files
                    .firstOrNull()

                val manifestMetadata = DriveFile().apply {
                    name = MANIFEST_FILE_NAME
                    mimeType = "application/json"
                    parents = listOf(fid)
                }
                val manifestContent = ByteArrayContent("application/json", manifest.toJson().toByteArray(Charsets.UTF_8))

                if (existingManifest != null) {
                    drive.files().update(existingManifest.id, manifestMetadata, manifestContent).execute()
                } else {
                    drive.files().create(manifestMetadata, manifestContent).execute()
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

                // Delete all files in folder
                val files = drive.files().list()
                    .setSpaces("appDataFolder")
                    .setQ("'$fid' in parents")
                    .setFields("files(id)")
                    .execute()
                    .files

                for (file in files) {
                    drive.files().delete(file.id).execute()
                }

                // Delete folder
                drive.files().delete(fid).execute()
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

    /**
     * Get last backup time from cloud manifest.
     */
    suspend fun getLastBackupTime(): Result<String?> {
        return withContext(Dispatchers.IO) {
            getManifest().map { manifest ->
                manifest?.updatedAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(it) }
            }
        }
    }
}