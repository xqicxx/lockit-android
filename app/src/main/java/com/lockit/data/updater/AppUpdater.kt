package com.lockit.data.updater

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * GitHub Release info.
 */
data class GitHubRelease(
    val versionName: String,
    val versionCode: Int,
    val changelog: String,
    val apkUrl: String?,
    val downloadSize: Long?,
)

class AppUpdater(private val context: Context) {

    companion object {
        private const val GITHUB_REPO = "xqicxx/lockit"
        private const val RELEASES_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    }

    /**
     * Check GitHub for latest release.
     * Returns null if no update available.
     * @param currentVersionCode Current app version code
     * @param githubToken Optional GitHub personal access token for private repos
     */
    suspend fun checkForUpdate(currentVersionCode: Int, githubToken: String? = null): Result<GitHubRelease?> = withContext(Dispatchers.IO) {
        try {
            val connection = URL(RELEASES_API).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/json")

            // Add Authorization header if token provided (for private repos)
            if (githubToken != null && githubToken.isNotBlank()) {
                connection.setRequestProperty("Authorization", "token $githubToken")
            }

            if (connection.responseCode != 200) {
                return@withContext Result.failure(Exception("HTTP ${connection.responseCode}"))
            }

            val response = BufferedReader(connection.inputStream.reader()).readText()
            val json = JSONObject(response)

            // Parse version from tag_name (e.g., "v1.2.0" or "1.2.0")
            val tagName = json.getString("tag_name")
            val versionName = tagName.removePrefix("v")

            // Parse changelog from body
            val changelog = json.optString("body", "").take(500)

            // Find APK asset
            val assets = json.getJSONArray("assets")
            var apkUrl: String? = null
            var downloadSize: Long? = null

            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.getString("name")
                if (name.endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    downloadSize = asset.optLong("size", 0)
                    break
                }
            }

            // Estimate versionCode from version name (simple: major*10000 + minor*100 + patch)
            val parts = versionName.split(".")
            val remoteVersionCode = if (parts.size >= 3) {
                parts[0].toIntOrNull()?.let { major ->
                    parts[1].toIntOrNull()?.let { minor ->
                        parts[2].toIntOrNull()?.let { patch ->
                            major * 10000 + minor * 100 + patch
                        }
                    }
                }
            } else null

            if (remoteVersionCode == null || remoteVersionCode <= currentVersionCode) {
                return@withContext Result.success<GitHubRelease?>(null) // No update available
            }

            Result.success(
                GitHubRelease(
                    versionName = versionName,
                    versionCode = remoteVersionCode,
                    changelog = changelog,
                    apkUrl = apkUrl,
                    downloadSize = downloadSize,
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Download APK using system DownloadManager.
     */
    fun downloadApk(apkUrl: String, fileName: String = "lockit-update.apk"): Long {
        val request = DownloadManager.Request(Uri.parse(apkUrl))
            .setTitle("Lockit Update")
            .setDescription("Downloading latest version...")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        return downloadManager.enqueue(request)
    }

    /**
     * Get APK file URI for installation.
     */
    fun getApkUri(fileName: String = "lockit-update.apk"): Uri {
        val file = java.io.File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Start APK installation intent.
     */
    fun installApk(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}