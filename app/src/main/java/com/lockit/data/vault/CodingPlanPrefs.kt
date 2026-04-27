package com.lockit.data.vault

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.security.crypto.MasterKey.KeyScheme
import com.lockit.domain.CodingPlanProviders
import com.lockit.domain.CodingPlanQuota
import org.json.JSONObject
import java.time.Instant

/**
 * Stores coding plan auth data (cookie, tokens, etc.) in EncryptedSharedPreferences
 * for immediate prefetch on app startup without waiting for vault unlock.
 *
 * Uses AES-256-GCM encryption via Android Keystore - no user PIN required.
 * Root users cannot read credentials even with file access.
 *
 * Supports multiple providers: qwen_bailian, chatgpt, claude
 *
 * Also caches last successful quota data for instant display on app startup.
 */
object CodingPlanPrefs {
    private const val TAG = "CodingPlanPrefs"
    private const val PREFS_NAME = "coding_plan_prefs"
    private const val KEY_ACTIVE_PROVIDER = "active_provider"

    // Quota cache keys (per-provider to prevent cross-talk)
    private fun quotaCacheKey(provider: String) = "quota_cache_${CodingPlanProviders.normalize(provider)}"
    private fun quotaCacheTimeKey(provider: String) = "quota_cache_time_${CodingPlanProviders.normalize(provider)}"
    private const val KEY_VAULT_UNLOCKED = "vault_unlocked"

    private val PROVIDER_FIELDS = listOf(
        "cookie",
        "api_key",
        "accessToken",
        "accountId",
        "sessionKey",
        "orgId",
        "accountEmail",
        "loginMethod",
    )

    // Cached instances (memoization for performance)
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    @Volatile
    private var cachedMasterKey: MasterKey? = null

    private fun getMasterKey(context: Context): MasterKey {
        return cachedMasterKey ?: synchronized(this) {
            cachedMasterKey ?: MasterKey.Builder(context)
                .setKeyScheme(KeyScheme.AES256_GCM)
                .build().also { cachedMasterKey = it }
        }
    }

    /**
     * Get cached SharedPreferences instance.
     * Handles legacy plaintext data migration from previous Keystore failures.
     */
    private fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: try {
                // Check plaintext prefs for legacy data (from when Keystore was broken)
                val plaintextPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val plaintextProvider = plaintextPrefs.getString(KEY_ACTIVE_PROVIDER, null)

                val encryptedPrefs = EncryptedSharedPreferences.create(
                    context,
                    PREFS_NAME,
                    getMasterKey(context),
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )

                val encryptedProvider = encryptedPrefs.getString(KEY_ACTIVE_PROVIDER, null)

                // If encrypted prefs empty but plaintext has data, use plaintext (legacy mode)
                // New saves will write to whichever prefs we're using
                if (encryptedProvider == null && plaintextProvider != null) {
                    plaintextPrefs
                } else {
                    encryptedPrefs
                }
            } catch (e: Exception) {
                Log.e(TAG, "Keystore error, falling back to plaintext: ${e.message}")
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            }.also { cachedPrefs = it }
        }
    }

    fun save(context: Context, provider: String, cookie: String, apiKey: String) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, normalizedProvider)
            .putString("${normalizedProvider}_cookie", cookie)
            .putString("${normalizedProvider}_api_key", apiKey)
            .apply()
    }

    fun setActiveProvider(context: Context, provider: String) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        getPrefs(context).edit()
            .putString(KEY_ACTIVE_PROVIDER, normalizedProvider)
            .apply()
    }

    fun getActiveProvider(context: Context): String? =
        getPrefs(context).getString(KEY_ACTIVE_PROVIDER, null)

    fun saveProviderData(context: Context, provider: String, data: Map<String, String>) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val editor = getPrefs(context).edit()
        data.forEach { (key, value) ->
            editor.putString("${normalizedProvider}_$key", value)
        }
        editor.putString(KEY_ACTIVE_PROVIDER, normalizedProvider)
        editor.apply()
    }

    fun getProviderData(context: Context, provider: String): Map<String, String> {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val prefs = getPrefs(context)
        return PROVIDER_FIELDS.associateWith { field ->
            prefs.getString("${normalizedProvider}_$field", "") ?: ""
        }.filterValues { it.isNotBlank() }
    }

    fun getProvider(context: Context): String? = getActiveProvider(context)
    fun getCookie(context: Context): String? = getProviderData(context, "qwen_bailian")["cookie"]
    fun getApiKey(context: Context): String? = getProviderData(context, "qwen_bailian")["api_key"]

    fun hasData(context: Context): Boolean {
        val provider = getActiveProvider(context)
        if (provider == null) return false
        return getProviderData(context, provider).isNotEmpty()
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
        clearCache()
    }

    fun clearProvider(context: Context, provider: String) {
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val editor = getPrefs(context).edit()
        PROVIDER_FIELDS.forEach { field ->
            editor.remove("${normalizedProvider}_$field")
        }
        editor.apply()
    }

    fun getMetadata(context: Context): Map<String, String> {
        val provider = getActiveProvider(context) ?: return emptyMap()
        return getProviderData(context, provider) + ("provider" to provider)
    }

    // === Quota Cache Methods ===

    /**
     * Save quota data to cache for instant display on next app startup.
     */
    fun saveQuotaCache(context: Context, quota: CodingPlanQuota?, provider: String) {
        if (quota == null) return
        val normalizedProvider = CodingPlanProviders.normalize(provider)
        val quotaJson = quotaToJson(quota)
        getPrefs(context).edit()
            .putString(quotaCacheKey(normalizedProvider), quotaJson)
            .putLong(quotaCacheTimeKey(normalizedProvider), System.currentTimeMillis())
            .putBoolean(KEY_VAULT_UNLOCKED, true)
            .apply()
        Log.d(TAG, "Quota cache saved for provider=$normalizedProvider")
    }

    /**
     * Load cached quota data for a specific provider.
     */
    fun loadQuotaCache(context: Context, provider: String): CodingPlanQuota? {
        val quotaJson = getPrefs(context).getString(quotaCacheKey(provider), null) ?: return null
        return quotaFromJson(quotaJson)
    }

    /**
     * Load cached quota for the active provider (widget compatibility).
     */
    fun loadQuotaCache(context: Context): CodingPlanQuota? {
        val provider = getActiveProvider(context) ?: return null
        return loadQuotaCache(context, provider)
    }

    fun getCacheTimestamp(context: Context, provider: String): Long =
        getPrefs(context).getLong(quotaCacheTimeKey(provider), 0)

    fun getCacheTimestamp(context: Context): Long {
        val provider = getActiveProvider(context) ?: return 0L
        return getCacheTimestamp(context, provider)
    }

    fun setVaultUnlocked(context: Context, unlocked: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_VAULT_UNLOCKED, unlocked).apply()
    }

    fun isVaultUnlocked(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_VAULT_UNLOCKED, false)

    fun clearQuotaCache(context: Context) {
        val editor = getPrefs(context).edit()
        val knownProviders = listOf("qwen_bailian", "chatgpt", "claude")
        knownProviders.forEach { p ->
            editor.remove(quotaCacheKey(p))
            editor.remove(quotaCacheTimeKey(p))
        }
        editor.apply()
    }

    private fun JSONObject.optInstantOrNull(key: String): Instant? {
        val raw = optString(key)
        return raw
            .takeIf { it.isNotBlank() && it != "null" }
            ?.let { Instant.parse(it) }
    }

    private fun quotaToJson(quota: CodingPlanQuota): String {
        return JSONObject().apply {
            put("sessionUsed", quota.sessionUsed)
            put("sessionTotal", quota.sessionTotal)
            put("weekUsed", quota.weekUsed)
            put("weekTotal", quota.weekTotal)
            put("monthUsed", quota.monthUsed)
            put("monthTotal", quota.monthTotal)
            put("instanceName", quota.instanceName)
            put("instanceType", quota.instanceType)
            put("status", quota.status)
            put("remainingDays", quota.remainingDays)
            put("planName", quota.planName)
            put("tier", quota.tier)
            put("creditsRemaining", quota.creditsRemaining)
            put("creditsCurrency", quota.creditsCurrency)
            put("extraUsageSpent", quota.extraUsageSpent)
            put("extraUsageLimit", quota.extraUsageLimit)
            put("chargeAmount", quota.chargeAmount)
            put("chargeType", quota.chargeType)
            put("autoRenewFlag", quota.autoRenewFlag)
            put("accountEmail", quota.accountEmail)
            put("loginMethod", quota.loginMethod)
            quota.sessionResetsAt?.let { put("sessionResetsAt", it.toString()) }
            quota.weekResetsAt?.let { put("weekResetsAt", it.toString()) }
            quota.monthResetsAt?.let { put("monthResetsAt", it.toString()) }
            val modelQuotasJson = JSONObject()
            quota.modelQuotas.forEach { (model, mq) ->
                modelQuotasJson.put(model, JSONObject().apply {
                    put("modelName", mq.modelName)
                    put("usedPercent", mq.usedPercent)
                    put("weekUsed", mq.weekUsed)
                    put("weekTotal", mq.weekTotal)
                    mq.resetsAt?.let { put("resetsAt", it.toString()) }
                })
            }
            put("modelQuotas", modelQuotasJson)
        }.toString()
    }

    private fun quotaFromJson(json: String): CodingPlanQuota? {
        return try {
            val obj = JSONObject(json)
            val modelQuotas = mutableMapOf<String, com.lockit.domain.model.ModelQuota>()
            obj.optJSONObject("modelQuotas")?.let { mqObj ->
                mqObj.keys().forEach { model ->
                    val mq = mqObj.getJSONObject(model)
                    modelQuotas[model] = com.lockit.domain.model.ModelQuota(
                        modelName = mq.optString("modelName", model),
                        usedPercent = mq.optDouble("usedPercent", 0.0),
                        weekUsed = mq.optInt("weekUsed", 0),
                        weekTotal = mq.optInt("weekTotal", 0),
                        resetsAt = mq.optInstantOrNull("resetsAt")
                    )
                }
            }
            CodingPlanQuota(
                sessionUsed = obj.optInt("sessionUsed", 0),
                sessionTotal = obj.optInt("sessionTotal", 0),
                weekUsed = obj.optInt("weekUsed", 0),
                weekTotal = obj.optInt("weekTotal", 0),
                monthUsed = obj.optInt("monthUsed", 0),
                monthTotal = obj.optInt("monthTotal", 0),
                instanceName = obj.optString("instanceName", ""),
                instanceType = obj.optString("instanceType", ""),
                status = obj.optString("status", ""),
                remainingDays = obj.optInt("remainingDays", 0),
                planName = obj.optString("planName", ""),
                tier = obj.optString("tier", ""),
                creditsRemaining = obj.optDouble("creditsRemaining", 0.0),
                creditsCurrency = obj.optString("creditsCurrency", "USD"),
                extraUsageSpent = obj.optDouble("extraUsageSpent", 0.0),
                extraUsageLimit = obj.optDouble("extraUsageLimit", 0.0),
                chargeAmount = obj.optDouble("chargeAmount", 0.0),
                chargeType = obj.optString("chargeType", ""),
                autoRenewFlag = obj.optBoolean("autoRenewFlag", false),
                accountEmail = obj.optString("accountEmail", ""),
                loginMethod = obj.optString("loginMethod", ""),
                sessionResetsAt = obj.optInstantOrNull("sessionResetsAt"),
                weekResetsAt = obj.optInstantOrNull("weekResetsAt"),
                monthResetsAt = obj.optInstantOrNull("monthResetsAt"),
                modelQuotas = modelQuotas
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse quota cache: ${e.message}")
            null
        }
    }

    fun clearCache() {
        synchronized(this) {
            cachedPrefs = null
        }
    }
}
