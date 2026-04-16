package com.lockit.data.sync

import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.http.FileContent
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import com.google.api.services.drive.DriveScopes
import com.google.api.services.drive.model.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Handles encrypted vault backup/restore via Google Drive app data folder.
 * The vault SQLite database is already encrypted with AES-256-GCM,
 * so it is safe to store in Google Drive.
 */
class GoogleDriveSyncManager(private val context: Context) {

    companion object {
        private const val DRIVE_FILE_NAME = "lockit_vault_backup.db"
        private const val MIME_TYPE = "application/octet-stream"
    }

    private val signInClient: GoogleSignInClient by lazy {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
        GoogleSignIn.getClient(context, options)
    }

    fun getSignInIntent(): Intent = signInClient.signInIntent

    fun getSignedInAccount(): GoogleSignInAccount? = GoogleSignIn.getLastSignedInAccount(context)

    fun signOut() {
        GoogleSignIn.getLastSignedInAccount(context)?.let {
            signInClient.signOut()
        }
    }

    private fun buildDriveService(account: GoogleSignInAccount): Drive {
        val credential = GoogleAccountCredential.usingOAuth2(
            context,
            listOf(DriveScopes.DRIVE_APPDATA)
        ).apply {
            selectedAccount = account.account
        }
        return Drive.Builder(
            com.google.api.client.http.javanet.NetHttpTransport(),
            GsonFactory.getDefaultInstance(),
            credential,
        ).setApplicationName("Lockit").build()
    }

    suspend fun uploadVault(
        account: GoogleSignInAccount,
        dbFilePath: String,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val driveService = buildDriveService(account)

            val existing = driveService.files().list()
                .setSpaces("appDataFolder")
                .setQ("name='$DRIVE_FILE_NAME'")
                .setFields("files(id)")
                .execute()
                .files
                .firstOrNull()

            val metadata = File().apply {
                name = DRIVE_FILE_NAME
                mimeType = MIME_TYPE
                if (existing != null) {
                    driveService.files().delete(existing.id).execute()
                }
            }

            val fileContent = FileContent(MIME_TYPE, java.io.File(dbFilePath))
            driveService.files().create(metadata, fileContent)
                .setFields("id")
                .execute()

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getLastBackupTime(account: GoogleSignInAccount): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val driveService = buildDriveService(account)
            val result = driveService.files().list()
                .setSpaces("appDataFolder")
                .setQ("name='$DRIVE_FILE_NAME'")
                .setFields("files(modifiedTime)")
                .execute()

            val time = result.files.firstOrNull()?.modifiedTime?.value
            Result.success(time?.let { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(it) })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
