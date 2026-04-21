package com.lockit.data.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKey.KeyScheme

/**
 * Stores coding plan auth data (cookie, tokens, etc.) in EncryptedSharedPreferences
 * for immediate prefetch on app startup without waiting for vault unlock.
 *
 * Uses AES-256-GCM encryption via Android Keystore - no user PIN required.
 * Root users cannot read credentials even with file access.
 *
 * Supports multiple providers: qwen_bailian, chatgpt, claude
 */
object CodingPlanPrefs {
    private const val TAG = "CodingPlanPrefs"
    private const val PREFS_NAME = "coding_plan_prefs"  // Same name for migration
    private const val KEY_ACTIVE_PROVIDER = "active_provider"

    private val PROVIDER_FIELDS = listOf("cookie", "api_key", "accessToken", "accountId", "sessionKey", "orgId")

    // Cached instances (memoization for performance)
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    @Volatile
    private var cachedMasterKey: MasterKey? = null

    /**
     * Get cached MasterKey instance.
     */
    private fun getMasterKey(context: Context): MasterKey {
        return cachedMasterKey ?: synchronized(this) {
            cachedMasterKey ?: MasterKey.Builder(context)
                .setKeyScheme(KeyScheme.AES256_GCM)
                .build().also { cachedMasterKey = it }
        }
    }

    /**
     * Get cached EncryptedSharedPreferences instance.
     * Falls back to plaintext prefs if Keystore is corrupted.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: try {
                EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    getMasterKey(context),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (e: Exception) {
                Log.e(TAG, "Keystore error, falling back to plaintext: ${e.message}")
                // Fallback to plaintext if Keystore is corrupted
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }.also { cachedPrefs = it }
        }
    }

    fun save(context: Context, provider: String, cookie: String, apiKey: String) {
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, provider)
            .putString("${provider}_cookie", cookie)
            .putString("${provider}_api_key", apiKey)
            .apply()
    }

    fun setActiveProvider(context: Context, provider: String) {
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, provider)
            .apply()
    }

    fun getActiveProvider(context: Context): String? =
        getPrefs(context).getString(KEY_ACTIVE_PROVIDER, null)

    fun saveProviderData(context: Context, provider: String, data: Map<String, String>) {
        val editor = getPrefs(context).edit()
        data.forEach { (key, value) ->
            editor.putString("${provider}_$key", value)
        }
        editor.putString(KEY_ACTIVE_PROVIDER, provider)
        editor.apply()
    }

    fun getProviderData(context: Context, provider: String): Map<String, String> {
        val prefs = getPrefs(context)
        return PROVIDER_FIELDS.associateWith { field ->
            prefs.getString("${provider}_$field", "") ?: ""
        }.filterValues { it.isNotBlank() }
    }

    fun getProvider(context: Context): String? = getActiveProvider(context)
    fun getCookie(context: Context): String? = getProviderData(context, "qwen_bailian")["cookie"]
    fun getApiKey(context: Context): String? = getProviderData(context, "qwen_bailian")["api_key"]

    fun hasData(context: Context): Boolean =
        getActiveProvider(context) != null && getProviderData(context, getActiveProvider(context) ?: "").isNotEmpty()

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun clearProvider(context: Context, provider: String) {
        val editor = getPrefs(context).edit()
        PROVIDER_FIELDS.forEach { field ->
            editor.remove("${provider}_$field")
        }
        editor.apply()
    }

    fun getMetadata(context: Context): Map<String, String> {
        val provider = getActiveProvider(context) ?: return emptyMap()
        return getProviderData(context, provider) + ("provider" to provider)
    }

    /**
     * Clear cached instances (call when prefs need to be recreated, e.g., after clear).
     */
    fun clearCache() {
        synchronized(this) {
            cachedPrefs = null
            // Don't clear cachedMasterKey - it's bound to Keystore and reusable
        }
    }
}