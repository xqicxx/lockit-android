package com.lockit.data.vault

import android.content.Context
import android.content.SharedPreferences

/**
 * Stores coding plan auth data (cookie, secToken) in SharedPreferences
 * for immediate prefetch on app startup without waiting for vault unlock.
 */
object CodingPlanPrefs {
    private const val PREFS_NAME = "coding_plan_prefs"
    private const val KEY_PROVIDER = "provider"
    private const val KEY_COOKIE = "cookie"
    private const val KEY_SEC_TOKEN = "sec_token"
    private const val KEY_API_KEY = "api_key"

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun save(context: Context, provider: String, cookie: String, secToken: String, apiKey: String) {
        getPrefs(context).edit()
            .putString(KEY_PROVIDER, provider)
            .putString(KEY_COOKIE, cookie)
            .putString(KEY_SEC_TOKEN, secToken)
            .putString(KEY_API_KEY, apiKey)
            .apply()
    }

    fun getProvider(context: Context): String? = getPrefs(context).getString(KEY_PROVIDER, null)
    fun getCookie(context: Context): String? = getPrefs(context).getString(KEY_COOKIE, null)
    fun getSecToken(context: Context): String? = getPrefs(context).getString(KEY_SEC_TOKEN, null)
    fun getApiKey(context: Context): String? = getPrefs(context).getString(KEY_API_KEY, null)

    fun hasData(context: Context): Boolean =
        getProvider(context) != null && getCookie(context) != null

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun getMetadata(context: Context): Map<String, String> {
        val prefs = getPrefs(context)
        return mapOf(
            "provider" to (prefs.getString(KEY_PROVIDER, "") ?: ""),
            "cookie" to (prefs.getString(KEY_COOKIE, "") ?: ""),
            "secToken" to (prefs.getString(KEY_SEC_TOKEN, "") ?: ""),
            "apiKey" to (prefs.getString(KEY_API_KEY, "") ?: ""),
        ).filterValues { it.isNotBlank() }
    }
}