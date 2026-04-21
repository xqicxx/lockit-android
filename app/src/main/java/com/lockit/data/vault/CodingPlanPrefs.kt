package com.lockit.data.vault

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

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
    private const val PREFS_NAME = "coding_plan_prefs_encrypted"
    private const val KEY_ACTIVE_PROVIDER = "active_provider"

    private val PROVIDER_FIELDS = listOf("cookie", "api_key", "accessToken", "accountId", "sessionKey", "orgId")

    private fun getMasterKey(context: Context): MasterKey {
        return MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun getEncryptedPrefs(context: Context): androidx.security.crypto.EncryptedSharedPreferences {
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            getMasterKey(context),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as androidx.security.crypto.EncryptedSharedPreferences
    }

    fun save(context: Context, provider: String, cookie: String, apiKey: String) {
        getEncryptedPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, provider)
            .putString("${provider}_cookie", cookie)
            .putString("${provider}_api_key", apiKey)
            .apply()
    }

    fun setActiveProvider(context: Context, provider: String) {
        getEncryptedPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, provider)
            .apply()
    }

    fun getActiveProvider(context: Context): String? =
        getEncryptedPrefs(context).getString(KEY_ACTIVE_PROVIDER, null)

    fun saveProviderData(context: Context, provider: String, data: Map<String, String>) {
        val editor = getEncryptedPrefs(context).edit()
        data.forEach { (key, value) ->
            editor.putString("${provider}_$key", value)
        }
        editor.putString(KEY_ACTIVE_PROVIDER, provider)
        editor.apply()
    }

    fun getProviderData(context: Context, provider: String): Map<String, String> {
        val prefs = getEncryptedPrefs(context)
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
        getEncryptedPrefs(context).edit().clear().apply()
    }

    fun clearProvider(context: Context, provider: String) {
        val editor = getEncryptedPrefs(context).edit()
        PROVIDER_FIELDS.forEach { field ->
            editor.remove("${provider}_$field")
        }
        editor.apply()
    }

    fun getMetadata(context: Context): Map<String, String> {
        val provider = getActiveProvider(context) ?: return emptyMap()
        return getProviderData(context, provider) + ("provider" to provider)
    }
}