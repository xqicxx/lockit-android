package com.lockit.data.vault

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores coding plan auth data (cookie, tokens, etc.) in SharedPreferences
 * for immediate prefetch on app startup without waiting for vault unlock.
 *
 * Supports multiple providers: qwen_bailian, chatgpt, claude
 */
object CodingPlanPrefs {
    private const val PREFS_NAME = "coding_plan_prefs"
    private const val KEY_ACTIVE_PROVIDER = "active_provider"

    // Provider-specific keys stored as: "{provider}_{field}"
    private val PROVIDER_FIELDS = listOf("cookie", "sec_token", "api_key", "accessToken", "accountId", "sessionKey", "orgId")

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // Legacy single-provider methods (backward compatible)
    fun save(context: Context, provider: String, cookie: String, secToken: String, apiKey: String) {
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, provider)
            .putString("${provider}_cookie", cookie)
            .putString("${provider}_sec_token", secToken)
            .putString("${provider}_api_key", apiKey)
            .apply()
    }

    // New multi-provider methods
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

    // Legacy getters (backward compatible with qwen_bailian)
    fun getProvider(context: Context): String? = getActiveProvider(context)
    fun getCookie(context: Context): String? = getProviderData(context, "qwen_bailian")["cookie"]
    fun getSecToken(context: Context): String? = getProviderData(context, "qwen_bailian")["sec_token"]
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
}