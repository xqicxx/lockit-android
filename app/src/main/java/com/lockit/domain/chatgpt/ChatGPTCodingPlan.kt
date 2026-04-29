package com.lockit.domain.chatgpt

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.SocketTimeoutException
import java.time.Instant

/**
 * ChatGPT Coding Plan quota fetcher.
 *
 * Usage data comes from /backend-api/wham/usage. Account metadata is fetched
 * opportunistically so the UI can show subscription/expiry details when present.
 */
object ChatGPTCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "chatgpt"

    private const val USAGE_API = "https://chatgpt.com/backend-api/wham/usage"
    private const val FETCH_TIMEOUT_MS = 5_000L
    internal const val ACCOUNT_INFO_TIMEOUT_MS = 1_800L
    private const val USAGE_CONNECT_TIMEOUT_MS = 1_500
    private const val USAGE_READ_TIMEOUT_MS = 2_500
    private const val ACCOUNT_CONNECT_TIMEOUT_MS = 800
    private const val ACCOUNT_READ_TIMEOUT_MS = 1_200

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val accessToken = metadata["accessToken"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val accountId = metadata["accountId"]?.takeIf { it.isNotBlank() }

            try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    val accountInfoDeferred = async {
                        try {
                            withTimeout(ACCOUNT_INFO_TIMEOUT_MS) {
                                fetchAccountInfo(accessToken, accountId)
                            }
                        } catch (_: Exception) {
                            ChatGptAccountInfo()
                        }
                    }

                    // Usage API is essential; account enrichment is optional and already running.
                    val usageResponse = fetchUsageResponse(accessToken, accountId)
                        ?: return@withTimeout null

                    val accountInfo = accountInfoDeferred.await()
                    parseResponse(usageResponse, metadata, accountInfo)
                }
            } catch (e: SocketTimeoutException) {
                android.util.Log.e("ChatGPT", "Network timeout — VPN required")
                null
            } catch (e: Exception) {
                android.util.Log.e("ChatGPT", "Fetch failed: ${e.message}")
                null
            }
        }

    private fun fetchUsageResponse(accessToken: String, accountId: String?): String? {
        val url = URL(USAGE_API)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = USAGE_CONNECT_TIMEOUT_MS
            conn.readTimeout = USAGE_READ_TIMEOUT_MS
            conn.useCaches = false
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "Lockit-Android")
            if (!accountId.isNullOrBlank()) {
                conn.setRequestProperty("ChatGPT-Account-Id", accountId)
            }
            if (conn.responseCode != 200) {
                android.util.Log.e("ChatGPT", "Usage API HTTP ${conn.responseCode}")
                return null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            android.util.Log.d("ChatGPT", "Usage API response received (${body.length} chars)")
            return body
        } finally {
            conn.disconnect()
        }
    }

    private data class ChatGptAccountInfo(
        val remainingDays: Int = 0,
        val expiresAt: Instant? = null,
        val accountEmail: String = "",
        val loginMethod: String = "",
        val planName: String = "",
        val tier: String = "",
        val status: String = "",
        val chargeAmount: Double = 0.0,
        val chargeType: String = "",
        val autoRenewFlag: Boolean = false,
        val extraDetails: Map<String, String> = emptyMap(),
    )

    private fun parseResponse(
        response: String,
        metadata: Map<String, String>,
        accountInfo: ChatGptAccountInfo = ChatGptAccountInfo(),
    ): CodingPlanQuota? {
        if (response.isBlank()) return null

        val json = JSONObject(response)
        val planType = firstNonBlank(
            json.optString("plan_type", ""),
            json.optString("plan", ""),
            json.optString("subscription_plan", ""),
            accountInfo.planName,
            metadata["planType"],
        )
        val email = firstNonBlank(
            json.optString("email", ""),
            json.optString("account_email", ""),
            accountInfo.accountEmail,
            metadata["accountEmail"],
        )
        val loginMethod = firstNonBlank(
            json.optString("login_method", ""),
            json.optString("auth_provider", ""),
            accountInfo.loginMethod,
            metadata["loginMethod"],
        )

        val rateLimit = json.optJSONObject("rate_limit")
        val primaryWindow = rateLimit?.optJSONObject("primary_window")
        val secondaryWindow = rateLimit?.optJSONObject("secondary_window")

        val (primaryUsed, primaryTotal) = parseWindow(primaryWindow)
        val (secondaryUsed, secondaryTotal) = parseWindow(secondaryWindow)

        val limitReached = rateLimit?.optBoolean("limit_reached", false) ?: false
        val spendReached = json.optJSONObject("spend_control")?.optBoolean("reached", false) ?: false
        val status = when {
            accountInfo.status.isNotBlank() -> accountInfo.status
            limitReached -> "LIMITED"
            spendReached -> "SPEND_LIMIT"
            planType.isNotBlank() -> "ACTIVE"
            else -> ""
        }
        val spendControl = json.optJSONObject("spend_control")
        val creditsRemaining = firstPositiveDouble(
            json.optDoubleOrNull("credits_remaining"),
            json.optDoubleOrNull("credit_balance"),
            spendControl?.optDoubleOrNull("remaining"),
        )
        val extraDetails = linkedMapOf<String, String>().apply {
            putAll(accountInfo.extraDetails)
            putDetail("Usage plan", planType)
            putDetail("Limit reached", rateLimit?.opt("limit_reached")?.toString())
            putDetail("Spend control reached", spendControl?.opt("reached")?.toString())
            putDetail("Credits remaining", creditsRemaining.takeIf { it > 0.0 }?.toString())
            putDetail("5h window seconds", primaryWindow?.opt("limit_window_seconds")?.toString())
            putDetail("Week window seconds", secondaryWindow?.opt("limit_window_seconds")?.toString())
            putDetail("5h reset", parseResetTime(primaryWindow)?.toString())
            putDetail("Week reset", parseResetTime(secondaryWindow)?.toString())
        }

        return CodingPlanQuota(
            sessionUsed = primaryUsed,
            sessionTotal = primaryTotal,
            weekUsed = secondaryUsed,
            weekTotal = secondaryTotal,
            sessionResetsAt = parseResetTime(primaryWindow),
            weekResetsAt = parseResetTime(secondaryWindow),
            tier = firstNonBlank(accountInfo.tier, planType.uppercase()),
            planName = planType,
            instanceType = planType,
            status = status,
            accountEmail = email,
            loginMethod = loginMethod,
            remainingDays = parseRemainingDays(json, accountInfo.remainingDays),
            subscriptionExpiresAt = parseExpiresAt(json) ?: accountInfo.expiresAt,
            creditsRemaining = creditsRemaining,
            chargeAmount = accountInfo.chargeAmount,
            chargeType = accountInfo.chargeType,
            autoRenewFlag = accountInfo.autoRenewFlag,
            extraDetails = extraDetails,
        )
    }

    // Account check for subscription details — longer timeouts since GFW may slow it down.
    private fun fetchAccountInfo(accessToken: String, accountId: String?): ChatGptAccountInfo {
        val url = URL("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27?timezone_offset_min=0")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = ACCOUNT_CONNECT_TIMEOUT_MS
            conn.readTimeout = ACCOUNT_READ_TIMEOUT_MS
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "Lockit-Android")
            if (!accountId.isNullOrBlank()) conn.setRequestProperty("ChatGPT-Account-Id", accountId)
            if (conn.responseCode != 200) return ChatGptAccountInfo()
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val account = resolveAccountObject(json, accountId)
            val expiresAt = parseExpiresAt(account) ?: parseExpiresAt(json)
            val extraDetails = collectAccountDetails(json, account, accountId, expiresAt)
            val planName = firstNonBlank(
                account?.optString("subscription_plan"),
                account?.optString("plan_type"),
                account?.optString("plan_name"),
                account?.optString("workspace_plan_type"),
            )
            ChatGptAccountInfo(
                remainingDays = expiresAt
                    ?.let { maxOf(0, java.time.temporal.ChronoUnit.DAYS.between(Instant.now(), it).toInt()) }
                    ?: parseRemainingDays(json),
                expiresAt = expiresAt,
                accountEmail = firstNonBlank(
                    account?.optString("email"),
                    account?.optString("account_email"),
                    json.optString("email"),
                ),
                loginMethod = firstNonBlank(
                    account?.optString("auth_provider"),
                    account?.optString("login_method"),
                    json.optString("login_method"),
                ),
                planName = planName,
                tier = planName.uppercase(),
                status = firstNonBlank(
                    account?.optString("status"),
                    account?.optString("account_status"),
                ),
                chargeAmount = firstPositiveDouble(
                    account?.optDoubleOrNull("charge_amount"),
                    account?.optDoubleOrNull("amount"),
                    account?.optDoubleOrNull("price"),
                ),
                chargeType = firstNonBlank(
                    account?.optString("charge_type"),
                    account?.optString("billing_period"),
                    account?.optString("renewal_interval"),
                ),
                autoRenewFlag = account?.optBoolean("auto_renew", false) == true ||
                    account?.optBoolean("will_renew", false) == true,
                extraDetails = extraDetails,
            )
        } catch (_: Exception) { ChatGptAccountInfo() }
        finally { conn.disconnect() }
    }

    private fun collectAccountDetails(
        root: JSONObject,
        account: JSONObject?,
        requestedAccountId: String?,
        expiresAt: Instant?,
    ): Map<String, String> {
        val details = linkedMapOf<String, String>()
        details.putDetail("Account ID", firstNonBlank(
            requestedAccountId,
            account?.optString("account_id"),
            account?.optString("accountId"),
            account?.optString("id"),
        ))
        details.putDetail("Account name", firstNonBlank(
            account?.optString("name"),
            account?.optString("workspace_name"),
            account?.optString("organization_name"),
        ))
        details.putDetail("Structure", account?.optString("structure"))
        details.putDetail("Subscription plan", account?.optString("subscription_plan"))
        details.putDetail("Plan type", account?.optString("plan_type"))
        details.putDetail("Plan name", account?.optString("plan_name"))
        details.putDetail("Workspace plan", account?.optString("workspace_plan_type"))
        details.putDetail("Account status", firstNonBlank(
            account?.optString("status"),
            account?.optString("account_status"),
        ))
        details.putDetail("Billing period", account?.optString("billing_period"))
        details.putDetail("Renewal interval", account?.optString("renewal_interval"))
        details.putDetail("Charge type", account?.optString("charge_type"))
        details.putDetail("Charge amount", firstPositiveDouble(
            account?.optDoubleOrNull("charge_amount"),
            account?.optDoubleOrNull("amount"),
            account?.optDoubleOrNull("price"),
        ).takeIf { it > 0.0 }?.toString())
        details.putDetail("Auto renew", account?.opt("auto_renew")?.toString())
        details.putDetail("Will renew", account?.opt("will_renew")?.toString())
        details.putDetail("Expires at", expiresAt?.toString())
        details.putDetail("Root plan", root.optString("subscription_plan"))
        details.putDetail("Root status", root.optString("status"))
        return details
    }

    private fun resolveAccountObject(json: JSONObject, requestedAccountId: String?): JSONObject? {
        val accounts = json.optJSONObject("accounts") ?: return null
        val requested = requestedAccountId
            ?.takeIf { it.isNotBlank() }
            ?.let { accounts.optJSONObject(it)?.optJSONObject("account") ?: accounts.optJSONObject(it) }
        if (requested != null) return requested
        val def = accounts.optJSONObject("default")?.optJSONObject("account")
            ?: accounts.optJSONObject("default")
        if (def != null) return def
        accounts.keys().forEach { key ->
            val acc = accounts.optJSONObject(key)?.optJSONObject("account") ?: accounts.optJSONObject(key)
            if (acc != null) return acc
        }
        return null
    }

    internal fun parseRemainingDays(json: JSONObject, fallback: Int = 0): Int {
        val direct = listOf("remainingDays", "remaining_days", "days_remaining", "subscription_remaining_days")
            .firstNotNullOfOrNull { key ->
                json.optIntOrNull(key)?.takeIf { it >= 0 }
            }
        if (direct != null) return direct

        val expiresAt = parseExpiresAt(json) ?: parseExpiresAt(resolveAccountObject(json, null))
        return expiresAt
            ?.let { maxOf(0, java.time.temporal.ChronoUnit.DAYS.between(Instant.now(), it).toInt()) }
            ?: fallback
    }

    private fun parseExpiresAt(obj: JSONObject?): Instant? {
        if (obj == null) return null
        val raw = obj.opt("subscription_expires_at")
            ?: obj.opt("subscription_expires_at_timestamp")
            ?: obj.opt("subscription_expires_at_ms")
            ?: obj.opt("expires_at")
            ?: obj.opt("expire_at")
            ?: obj.opt("subscription_expires")
            ?: obj.opt("subscription_expiry")
            ?: obj.opt("expiration_time")
            ?: obj.opt("expires")
            ?: return null
        return when (raw) {
            is Number -> { val v = raw.toLong(); if (v > 1_000_000_000_000L) Instant.ofEpochMilli(v) else Instant.ofEpochSecond(v) }
            is String -> raw.toLongOrNull()?.let { if (it > 1_000_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) } ?: runCatching { Instant.parse(raw) }.getOrNull()
            else -> null
        }
    }

    // Parse rate-limit window — supports used_percent (actual API), current_usage + limit, used + total,
    // remaining + total, and flat daily_limit/weekly_limit fields.
    internal data class WindowResult(val used: Int, val total: Int, val usedPercent: Double)

    internal fun parseWindow(
        window: JSONObject?,
        flatLimit: Int = 0,
        flatUsed: Int = 0,
        defaultTotal: Int = 100,
    ): WindowResult {
        if (flatLimit > 0) {
            val pct = if (flatLimit > 0) flatUsed.toDouble() / flatLimit * 100.0 else 0.0
            return WindowResult(flatUsed, flatLimit, pct)
        }
        if (window == null) return WindowResult(0, 0, 0.0)

        // used_percent (actual ChatGPT API field)
        val rawPct = window.optDouble("used_percent",
            window.optDouble("usedPercent", -1.0))
        if (rawPct >= 0) {
            val total = defaultTotal
            val used = rawPct.toInt().coerceIn(0, total)
            return WindowResult(used, total, rawPct.coerceIn(0.0, 100.0))
        }

        // current_usage + limit / used + total
        val limit = window.optInt("limit", 0)
        val windowTotal = window.optInt("total", 0)
        val effectiveTotal = if (limit > 0) limit else windowTotal
        val usage = window.optInt("current_usage", window.optInt("used", -1))
        if (effectiveTotal > 0 && usage >= 0) {
            val used = usage.coerceAtMost(effectiveTotal)
            return WindowResult(used, effectiveTotal, used.toDouble() / effectiveTotal * 100.0)
        }

        // remaining → used
        val remaining = window.optInt("remaining", -1)
        val fallbackTotal = if (effectiveTotal > 0) effectiveTotal
            else window.optInt("limit_window_seconds", 0).let { if (it > 0) defaultTotal else 0 }
        if (remaining >= 0 && fallbackTotal > 0) {
            val used = fallbackTotal - remaining
            return WindowResult(used, fallbackTotal, used.toDouble() / fallbackTotal * 100.0)
        }

        return WindowResult(0, 0, 0.0)
    }

    private fun parseResetTime(window: JSONObject?): Instant? {
        if (window == null) return null
        // API provides reset_at as Unix seconds
        val resetAtSecs = window.optLong("reset_at", 0)
        if (resetAtSecs > 0) return Instant.ofEpochSecond(resetAtSecs)
        val resetAfter = window.optLong("reset_after_seconds", 0)
        if (resetAfter > 0) return Instant.now().plusSeconds(resetAfter)
        return null
    }

    private fun JSONObject.optIntOrNull(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        return when (val raw = opt(key)) {
            is Number -> raw.toDouble()
            is String -> raw.toDoubleOrNull()
            else -> null
        }?.takeIf { it.isFinite() }
    }

    private fun firstNonBlank(vararg values: String?): String {
        values.forEach { value ->
            if (!value.isNullOrBlank() && value != "null") return value
        }
        return ""
    }

    private fun firstPositiveDouble(vararg values: Double?): Double {
        values.forEach { value ->
            if (value != null && value > 0.0) return value
        }
        return 0.0
    }

    private fun MutableMap<String, String>.putDetail(key: String, value: String?) {
        if (!value.isNullOrBlank() && value != "null") {
            this[key] = value
        }
    }
}
