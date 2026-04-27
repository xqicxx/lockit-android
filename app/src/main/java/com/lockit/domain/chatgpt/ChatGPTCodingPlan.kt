package com.lockit.domain.chatgpt

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import com.lockit.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * ChatGPT Coding Plan quota fetcher.
 *
 * 使用方法：
 * 1. WebView 登录 chatgpt.com
 * 2. 抓取 access_token (from /api/auth/session)
 * 3. 存入 credential metadata
 *
 * Metadata 需要的字段：
 * - provider: "chatgpt"
 * - accessToken: Bearer token from session API
 * - accountId: ChatGPT-Account-Id header value
 */
object ChatGPTCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "chatgpt"

    private const val USAGE_API = "https://chatgpt.com/backend-api/wham/usage"
    private const val ACCOUNT_API = "https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27?timezone_offset_min=0"

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val accessToken = metadata["accessToken"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val accountId = metadata["accountId"]?.takeIf { it.isNotBlank() }
            val fallbackEmail = metadata["accountEmail"].orEmpty()
            val fallbackLoginMethod = metadata["loginMethod"].orEmpty()

            try {
                fetchFromApi(accessToken, accountId, fallbackEmail, fallbackLoginMethod)
            } catch (e: Exception) {
                null
            }
        }

    private fun fetchFromApi(
        accessToken: String,
        accountId: String?,
        fallbackEmail: String,
        fallbackLoginMethod: String,
    ): CodingPlanQuota? {
        val accountSummary = fetchAccountSummary(accessToken, accountId)
        val url = URL(USAGE_API)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.useCaches = false

            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "Lockit-Android")
            if (!accountId.isNullOrBlank()) {
                conn.setRequestProperty("ChatGPT-Account-Id", accountId)
            }

            if (conn.responseCode != 200) {
                android.util.Log.e("ChatGPTCodingPlan", "Usage API failed: HTTP ${conn.responseCode}")
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            return parseResponse(
                response = response,
                accountSummary = accountSummary,
                fallbackEmail = fallbackEmail,
                fallbackLoginMethod = fallbackLoginMethod,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(
        response: String,
        accountSummary: ChatGptAccountSummary?,
        fallbackEmail: String,
        fallbackLoginMethod: String,
    ): CodingPlanQuota? {
        if (response.isBlank()) return null

        val json = JSONObject(response)

        val rateLimit = json.optJSONObject("rate_limit")
        val primaryWindow = rateLimit?.optJSONObject("primary_window")
        val secondaryWindow = rateLimit?.optJSONObject("secondary_window")

        val primaryUsedPercent = primaryWindow?.optDouble("used_percent", 0.0) ?: 0.0
        val secondaryUsedPercent = secondaryWindow?.optDouble("used_percent", 0.0) ?: 0.0
        val primaryWindowSeconds = primaryWindow?.optLong("limit_window_seconds", 0L) ?: 0L
        val secondaryWindowSeconds = secondaryWindow?.optLong("limit_window_seconds", 0L) ?: 0L
        val primaryResetAfter = primaryWindow?.optLong("reset_after_seconds", 0L) ?: 0L
        val secondaryResetAfter = secondaryWindow?.optLong("reset_after_seconds", 0L) ?: 0L

        val primaryTotal = if (primaryWindowSeconds > 0) 100 else 0
        val secondaryTotal = if (secondaryWindowSeconds > 0) 100 else 0
        val primaryUsed = primaryUsedPercent.toInt().coerceIn(0, 100)
        val secondaryUsed = secondaryUsedPercent.toInt().coerceIn(0, 100)
        val now = Instant.now()
        val primaryResetsAt = if (primaryResetAfter > 0) now.plusSeconds(primaryResetAfter) else null
        val secondaryResetsAt = if (secondaryResetAfter > 0) now.plusSeconds(secondaryResetAfter) else null
        val usagePlanType = json.optString("plan_type", "ChatGPT")

        return CodingPlanQuota(
            sessionUsed = primaryUsed,
            sessionTotal = primaryTotal,
            weekUsed = secondaryUsed,
            weekTotal = secondaryTotal,
            monthUsed = 0,
            monthTotal = 0,
            instanceName = "ChatGPT",
            instanceType = accountSummary?.instanceType ?: usagePlanType,
            status = accountSummary?.status ?: "ACTIVE",
            sessionResetsAt = primaryResetsAt,
            weekResetsAt = secondaryResetsAt,
            modelQuotas = mapOf(
                "primary" to ModelQuota(
                    modelName = "5-hour window",
                    usedPercent = primaryUsedPercent,
                    weekUsed = primaryUsed,
                    weekTotal = primaryTotal,
                    resetsAt = primaryResetsAt,
                ),
                "secondary" to ModelQuota(
                    modelName = "Weekly window",
                    usedPercent = secondaryUsedPercent,
                    weekUsed = secondaryUsed,
                    weekTotal = secondaryTotal,
                    resetsAt = secondaryResetsAt,
                ),
            ).filterValues { it.weekTotal > 0 },
            remainingDays = accountSummary?.remainingDays ?: 0,
            planName = accountSummary?.planName.orEmpty(),
            tier = accountSummary?.tier.orEmpty(),
            chargeAmount = 0.0,
            chargeType = accountSummary?.chargeType.orEmpty(),
            autoRenewFlag = accountSummary?.autoRenew ?: false,
            accountEmail = accountSummary?.accountEmail?.ifBlank { fallbackEmail } ?: fallbackEmail,
            loginMethod = accountSummary?.loginMethod?.ifBlank { fallbackLoginMethod } ?: fallbackLoginMethod,
        )
    }

    private fun fetchAccountSummary(accessToken: String, accountId: String?): ChatGptAccountSummary? {
        val url = URL(ACCOUNT_API)
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.useCaches = false
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "Lockit-Android")
            if (!accountId.isNullOrBlank()) {
                conn.setRequestProperty("ChatGPT-Account-Id", accountId)
            }

            if (conn.responseCode != 200) {
                android.util.Log.w("ChatGPTCodingPlan", "Account API failed: HTTP ${conn.responseCode}")
                return null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            parseAccountSummary(JSONObject(response), accountId)
        } catch (e: Exception) {
            android.util.Log.w("ChatGPTCodingPlan", "Account API error: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun parseAccountSummary(json: JSONObject, requestedAccountId: String?): ChatGptAccountSummary? {
        val account = resolveAccountObject(json, requestedAccountId)
        val accountPlan = firstObject(account, json, "account_plan")
        val entitlement = firstObject(account, json, "entitlement")
        val user = json.optJSONObject("user")

        val subscriptionPlan = firstNonBlank(
            account?.optString("subscription_plan"),
            accountPlan?.optString("subscription_plan"),
            entitlement?.optString("subscription_plan"),
            json.optString("subscription_plan"),
        )
        val structure = firstNonBlank(
            account?.optString("structure"),
            accountPlan?.optString("structure"),
            entitlement?.optString("structure"),
        )
        val workspaceName = firstNonBlank(
            account?.optString("name"),
            account?.optString("workspace_name"),
            account?.optString("organization_name"),
            accountPlan?.optString("name"),
            entitlement?.optString("plan_name"),
        )
        val tier = normalizeTierLabel(subscriptionPlan, structure)
        val planName = firstNonBlank(workspaceName, subscriptionPlan, tier, "ChatGPT")
        val accountEmail = firstNonBlank(
            account?.optString("email"),
            user?.optString("email"),
            json.optString("email"),
        )
        val loginMethod = normalizeLoginMethod(
            firstNonBlank(
                user?.optString("auth_provider"),
                user?.optString("login_method"),
                json.optString("authProvider"),
                json.optString("loginMethod"),
            )
        )
        val autoRenew = firstBoolean(
            accountPlan?.opt("will_renew"),
            entitlement?.opt("will_renew"),
            account?.opt("will_renew"),
            accountPlan?.opt("auto_renew"),
            entitlement?.opt("auto_renew"),
        )
        val expiresAt = firstInstant(
            accountPlan?.opt("subscription_expires_at"),
            accountPlan?.opt("subscription_expires_at_timestamp"),
            accountPlan?.opt("expires_at"),
            entitlement?.opt("expires_at"),
            account?.opt("expires_at"),
            account?.opt("subscription_expires_at"),
            json.opt("expires_at"),
        )
        val remainingDays = expiresAt?.let { computeRemainingDays(it) } ?: 0
        val status = when {
            expiresAt != null && expiresAt.isBefore(Instant.now()) -> "EXPIRED"
            subscriptionPlan.isNotBlank() || structure.isNotBlank() -> "ACTIVE"
            else -> ""
        }
        val instanceType = firstNonBlank(structure.uppercase(), tier, subscriptionPlan)

        return ChatGptAccountSummary(
            tier = tier,
            planName = planName,
            instanceType = instanceType,
            status = status,
            remainingDays = remainingDays,
            autoRenew = autoRenew,
            chargeType = if (tier.equals("FREE", ignoreCase = true)) "free" else "subscription",
            accountEmail = accountEmail,
            loginMethod = loginMethod,
        )
    }

    private fun resolveAccountObject(json: JSONObject, requestedAccountId: String?): JSONObject? {
        val accounts = json.optJSONObject("accounts") ?: return null
        if (!requestedAccountId.isNullOrBlank()) {
            val direct = accounts.optJSONObject(requestedAccountId)
            if (direct != null) return direct.optJSONObject("account") ?: direct
        }

        val defaultAccount = accounts.optJSONObject("default")
            ?.optJSONObject("account")
        if (defaultAccount != null) return defaultAccount

        accounts.keys().forEach { key ->
            val account = accounts.optJSONObject(key) ?: return@forEach
            val candidate = account.optJSONObject("account") ?: account
            val candidateId = firstNonBlank(
                candidate.optString("account_id"),
                candidate.optString("id"),
                candidate.optString("accountId"),
            )
            if (requestedAccountId.isNullOrBlank() || candidateId == requestedAccountId) {
                return candidate
            }
        }
        return null
    }

    private fun firstObject(primary: JSONObject?, fallback: JSONObject, key: String): JSONObject? {
        return primary?.optJSONObject(key) ?: fallback.optJSONObject(key)
    }

    private fun firstNonBlank(vararg values: String?): String {
        values.forEach { value ->
            if (!value.isNullOrBlank() && value != "null") {
                return value
            }
        }
        return ""
    }

    private fun firstBoolean(vararg values: Any?): Boolean {
        values.forEach { value ->
            when (value) {
                is Boolean -> return value
                is String -> {
                    if (value.equals("true", ignoreCase = true)) return true
                    if (value.equals("false", ignoreCase = true)) return false
                }
                is Number -> return value.toInt() != 0
            }
        }
        return false
    }

    private fun firstInstant(vararg values: Any?): Instant? {
        values.forEach { value ->
            val parsed = parseInstant(value)
            if (parsed != null) return parsed
        }
        return null
    }

    private fun parseInstant(value: Any?): Instant? {
        return when (value) {
            is Number -> {
                val raw = value.toLong()
                if (raw <= 0L) null else if (raw > 1_000_000_000_000L) Instant.ofEpochMilli(raw) else Instant.ofEpochSecond(raw)
            }
            is String -> {
                val trimmed = value.trim()
                if (trimmed.isBlank() || trimmed == "null") {
                    null
                } else {
                    trimmed.toLongOrNull()?.let { numeric ->
                        if (numeric > 1_000_000_000_000L) Instant.ofEpochMilli(numeric) else Instant.ofEpochSecond(numeric)
                    } ?: runCatching { Instant.parse(trimmed) }.getOrNull()
                }
            }
            else -> null
        }
    }

    private fun computeRemainingDays(expiresAt: Instant): Int {
        val now = Instant.now()
        if (!expiresAt.isAfter(now)) return 0
        val days = ChronoUnit.DAYS.between(now, expiresAt).toInt()
        return if (days == 0) 1 else days
    }

    private fun normalizeTierLabel(subscriptionPlan: String, structure: String): String {
        val combined = "$subscriptionPlan $structure".lowercase()
        return when {
            combined.contains("team") || combined.contains("business") || combined.contains("workspace") -> "TEAM"
            combined.contains("enterprise") -> "TEAM"
            combined.contains("pro") -> "PRO"
            combined.contains("plus") -> "PLUS"
            combined.contains("free") -> "FREE"
            subscriptionPlan.isNotBlank() -> subscriptionPlan.uppercase()
            structure.isNotBlank() -> structure.uppercase()
            else -> ""
        }
    }

    private fun normalizeLoginMethod(raw: String): String {
        return when (raw.lowercase()) {
            "google-oauth2", "google" -> "GOOGLE"
            "microsoft", "microsoft-oauth2" -> "MICROSOFT"
            "apple", "apple-oauth2" -> "APPLE"
            "password" -> "PASSWORD"
            "" -> ""
            else -> raw.uppercase()
        }
    }

    private data class ChatGptAccountSummary(
        val tier: String,
        val planName: String,
        val instanceType: String,
        val status: String,
        val remainingDays: Int,
        val autoRenew: Boolean,
        val chargeType: String,
        val accountEmail: String,
        val loginMethod: String,
    )
}
