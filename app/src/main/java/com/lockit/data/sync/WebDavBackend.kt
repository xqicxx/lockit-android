package com.lockit.data.sync

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant

/**
 * WebDAV backend for vault sync.
 * Implements SyncBackend interface with standard WebDAV protocol.
 * Supports: 坚果云, Nextcloud, Seafile, OwnCloud, and any WebDAV server.
 *
 * File structure on WebDAV server:
 * - /lockit-sync/vault.enc  ← encrypted vault.db
 * - /lockit-sync/manifest.json ← sync metadata
 *
 * Uses optimistic locking with ETag/If-Match for conflict detection.
 * Credentials stored in EncryptedSharedPreferences for security.
 */
class WebDavBackend(private val context: Context) : SyncBackend {

    override val name = "WebDAV"

    companion object {
        private const val TAG = "WebDavBackend"
        private const val PREFS_NAME = "webdav_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_BASE_PATH = "base_path"

        private const val DEFAULT_BASE_PATH = "/lockit-sync"
        private const val VAULT_FILE = "vault.enc"
        private const val MANIFEST_FILE = "manifest.json"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val BINARY_MEDIA = "application/octet-stream".toMediaType()
    }

    @Volatile
    private var client: OkHttpClient? = null
    @Volatile
    private var serverUrl: String? = null
    @Volatile
    private var username: String? = null
    @Volatile
    private var password: String? = null
    @Volatile
    private var basePath: String? = null

    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    override suspend fun isConfigured(): Boolean {
        return prefs.getString(KEY_SERVER_URL, null) != null &&
               prefs.getString(KEY_USERNAME, null) != null
    }

    override suspend fun configure(credentials: Map<String, String>): Result<Unit> {
        val url = credentials["serverUrl"] ?: credentials["url"]
        val user = credentials["username"] ?: credentials["user"]
        val pass = credentials["password"] ?: credentials["pass"]
        val path = credentials["basePath"] ?: DEFAULT_BASE_PATH

        if (url == null || user == null || pass == null) {
            return Result.failure(IllegalArgumentException("Missing required credentials: serverUrl, username, password"))
        }

        val normalizedUrl = url.trimEnd('/')

        synchronized(this) {
            client = OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            serverUrl = normalizedUrl
            username = user
            password = pass
            basePath = path
        }

        return withContext(Dispatchers.IO) {
            try {
                val testUrl = "$normalizedUrl$path"
                val request = Request.Builder()
                    .url(testUrl)
                    .method("PROPFIND", null)
                    .header("Authorization", Credentials.basic(user, pass))
                    .header("Depth", "0")
                    .build()

                client!!.newCall(request).execute().use { response ->
                    if (response.isSuccessful || response.code == 207) {
                        saveCredentials(normalizedUrl, user, pass, path)
                        Log.i(TAG, "WebDAV configured: $normalizedUrl$path")
                        Result.success(Unit)
                    } else if (response.code == 404) {
                        val createResult = createBaseFolderInternal()
                        if (createResult.isSuccess) {
                            saveCredentials(normalizedUrl, user, pass, path)
                            Result.success(Unit)
                        } else {
                            Result.failure(IOException("Failed to create folder: ${createResult.exceptionOrNull()?.message}"))
                        }
                    } else if (response.code == 401) {
                        Result.failure(IOException("Authentication failed: invalid username or password"))
                    } else {
                        Result.failure(IOException("WebDAV connection failed: HTTP ${response.code}"))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "WebDAV configure failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    private fun saveCredentials(url: String, user: String, pass: String, path: String) {
        prefs.edit()
            .putString(KEY_SERVER_URL, url)
            .putString(KEY_USERNAME, user)
            .putString(KEY_PASSWORD, pass)
            .putString(KEY_BASE_PATH, path)
            .apply()
    }

    private suspend fun createBaseFolderInternal(): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    Result.failure(IOException("Credentials not configured"))
                } else {
                    val request = Request.Builder()
                        .url("$url$path")
                        .method("MKCOL", null)
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful || response.code == 201) {
                            Log.i(TAG, "Created WebDAV folder: $url$path")
                            Result.success(Unit)
                        } else {
                            Result.failure(IOException("MKCOL failed: HTTP ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    override suspend fun uploadVault(
        encryptedData: ByteArray,
        manifest: SyncManifest
    ): Result<Unit> {
        loadCredentials()

        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    Result.failure(IOException("Credentials not configured"))
                } else {
                    val vaultUrl = "$url$path/$VAULT_FILE"
                    val vaultRequest = Request.Builder()
                        .url(vaultUrl)
                        .put(encryptedData.toRequestBody(BINARY_MEDIA))
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()

                    httpClient.newCall(vaultRequest).execute().use { vaultResponse ->
                        if (!vaultResponse.isSuccessful) {
                            Result.failure(IOException("Upload vault failed: HTTP ${vaultResponse.code}"))
                        } else {
                            val manifestUrl = "$url$path/$MANIFEST_FILE"
                            val manifestJson = manifest.toJson()
                            val manifestRequest = Request.Builder()
                                .url(manifestUrl)
                                .put(manifestJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA))
                                .header("Authorization", Credentials.basic(user, pass))
                                .build()

                            httpClient.newCall(manifestRequest).execute().use { manifestResponse ->
                                if (!manifestResponse.isSuccessful) {
                                    Result.failure(IOException("Upload manifest failed: HTTP ${manifestResponse.code}"))
                                } else {
                                    Log.i(TAG, "Uploaded vault (${encryptedData.size} bytes) + manifest")
                                    Result.success(Unit)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Upload failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override suspend fun downloadVault(): Result<ByteArray> {
        loadCredentials()

        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    Result.failure(IOException("Credentials not configured"))
                } else {
                    val vaultUrl = "$url$path/$VAULT_FILE"
                    val request = Request.Builder()
                        .url(vaultUrl)
                        .get()
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val data = response.body?.bytes()
                            if (data != null) {
                                Log.i(TAG, "Downloaded vault: ${data.size} bytes")
                                Result.success(data)
                            } else {
                                Result.failure(IOException("Empty response body"))
                            }
                        } else if (response.code == 404) {
                            Result.failure(IOException("No vault.enc in cloud"))
                        } else {
                            Result.failure(IOException("Download failed: HTTP ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override suspend fun getManifest(): Result<SyncManifest?> {
        loadCredentials()

        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    Result.failure(IOException("Credentials not configured"))
                } else {
                    val manifestUrl = "$url$path/$MANIFEST_FILE"
                    val request = Request.Builder()
                        .url(manifestUrl)
                        .get()
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = response.body?.string()
                            if (json != null) {
                                val manifest = SyncManifest.fromJson(json)
                                Log.i(TAG, "Got manifest: checksum=${manifest.vaultChecksum}")
                                Result.success(manifest)
                            } else {
                                Result.success(null)
                            }
                        } else if (response.code == 404) {
                            Result.success(null)
                        } else {
                            Result.failure(IOException("Get manifest failed: HTTP ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Get manifest failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override suspend fun deleteSyncData(): Result<Unit> {
        loadCredentials()

        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    Result.failure(IOException("Credentials not configured"))
                } else {
                    // Delete vault.enc
                    val vaultRequest = Request.Builder()
                        .url("$url$path/$VAULT_FILE")
                        .delete()
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()
                    httpClient.newCall(vaultRequest).execute().use { }

                    // Delete manifest.json
                    val manifestRequest = Request.Builder()
                        .url("$url$path/$MANIFEST_FILE")
                        .delete()
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()
                    httpClient.newCall(manifestRequest).execute().use { }

                    // Delete folder
                    val folderRequest = Request.Builder()
                        .url("$url$path")
                        .delete()
                        .header("Authorization", Credentials.basic(user, pass))
                        .build()
                    httpClient.newCall(folderRequest).execute().use { }

                    Log.i(TAG, "Deleted all sync data")
                    Result.success(Unit)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed: ${e.message}")
                Result.failure(e)
            }
        }
    }

    override suspend fun disconnect() {
        prefs.edit().clear().apply()
        synchronized(this) {
            client = null
            serverUrl = null
            username = null
            password = null
            basePath = null
        }
        Log.i(TAG, "WebDAV disconnected")
    }

    private fun loadCredentials() {
        synchronized(this) {
            if (serverUrl == null) {
                serverUrl = prefs.getString(KEY_SERVER_URL, null)
                username = prefs.getString(KEY_USERNAME, null)
                password = prefs.getString(KEY_PASSWORD, null)
                basePath = prefs.getString(KEY_BASE_PATH, DEFAULT_BASE_PATH)

                if (serverUrl != null && username != null && password != null) {
                    client = OkHttpClient.Builder()
                        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                }
            }
        }
    }

    suspend fun getVaultETag(): String? {
        loadCredentials()

        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    null
                } else {
                    val request = Request.Builder()
                        .url("$url$path/$VAULT_FILE")
                        .method("PROPFIND", null)
                        .header("Authorization", Credentials.basic(user, pass))
                        .header("Depth", "0")
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        val body = response.body?.string()
                        body?.let { parseETag(it) }
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun parseETag(xmlResponse: String): String? {
        val etagPattern = Regex("<getetag>([^<]+)</getetag>", RegexOption.IGNORE_CASE)
        return etagPattern.find(xmlResponse)?.groupValues?.getOrNull(1)?.trim('"')
    }

    suspend fun uploadVaultWithLocking(
        encryptedData: ByteArray,
        manifest: SyncManifest,
        expectedETag: String?
    ): Result<Unit> {
        loadCredentials()

        return withContext(Dispatchers.IO) {
            try {
                val url = serverUrl
                val path = basePath ?: DEFAULT_BASE_PATH
                val user = username
                val pass = password
                val httpClient = client

                if (url == null || user == null || pass == null || httpClient == null) {
                    Result.failure(IOException("Credentials not configured"))
                } else {
                    val vaultUrl = "$url$path/$VAULT_FILE"

                    val builder = Request.Builder()
                        .url(vaultUrl)
                        .put(encryptedData.toRequestBody(BINARY_MEDIA))
                        .header("Authorization", Credentials.basic(user, pass))

                    if (expectedETag != null) {
                        builder.header("If-Match", "\"$expectedETag\"")
                    }

                    httpClient.newCall(builder.build()).execute().use { response ->
                        if (response.isSuccessful) {
                            // Vault uploaded with locking, now upload manifest separately
                            val manifestUrl = "$url$path/$MANIFEST_FILE"
                            val manifestJson = manifest.toJson()
                            val manifestRequest = Request.Builder()
                                .url(manifestUrl)
                                .put(manifestJson.toByteArray(Charsets.UTF_8).toRequestBody(JSON_MEDIA))
                                .header("Authorization", Credentials.basic(user, pass))
                                .build()

                            httpClient.newCall(manifestRequest).execute().use { manifestResponse ->
                                if (manifestResponse.isSuccessful) {
                                    Log.i(TAG, "Uploaded vault with locking (${encryptedData.size} bytes)")
                                    Result.success(Unit)
                                } else {
                                    Result.failure(IOException("Upload manifest failed: HTTP ${manifestResponse.code}"))
                                }
                            }
                        } else if (response.code == 412) {
                            Result.failure(IOException("CONFLICT: ETag mismatch, another device modified the vault"))
                        } else {
                            Result.failure(IOException("Upload failed: HTTP ${response.code}"))
                        }
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}