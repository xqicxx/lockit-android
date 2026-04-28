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
 * All data (plan_type, email, rate limits) comes from the single
 * /backend-api/wham/usage endpoint. The account check API is not called
 * because it always times out and the usage API already returns everything.
 */
object ChatGPTCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "chatgpt"

    private const val USAGE_API = "https://chatgpt.com/backend-api/wham/usage"
    private val FETCH_TIMEOUT_MS = 10_000L

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val accessToken = metadata["accessToken"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val accountId = metadata["accountId"]?.takeIf { it.isNotBlank() }

            try {
                withTimeout(FETCH_TIMEOUT_MS) {
                    // Usage API is essential — must succeed
                    val usageResponse = fetchUsageResponse(accessToken, accountId)
                        ?: return@withTimeout null

                    // Account API is optional — try in parallel for remaining days
                    val remainingDaysDeferred = async {
                        try { withTimeout(8000L) { fetchRemainingDays(accessToken, accountId) } }
                        catch (_: Exception) { 0 }
                    }

                    val remainingDays = remainingDaysDeferred.await()
                    parseResponse(usageResponse, remainingDays)
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
            conn.connectTimeout = 2000
            conn.readTimeout = 3000
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
            android.util.Log.d("ChatGPT", "=== USAGE API ===\n$body\n=== END ===")
            return body
        } finally {
            conn.disconnect()
        }
    }

    // All identity + plan fields from usage API top-level. remainingDays from optional account API.
    private fun parseResponse(response: String, remainingDays: Int = 0): CodingPlanQuota? {
        if (response.isBlank()) return null

        val json = JSONObject(response)
        val planType = json.optString("plan_type", "")
        val email = json.optString("email", "")

        val rateLimit = json.optJSONObject("rate_limit")
        val primaryWindow = rateLimit?.optJSONObject("primary_window")
        val secondaryWindow = rateLimit?.optJSONObject("secondary_window")

        val (primaryUsed, primaryTotal) = parseWindow(primaryWindow)
        val (secondaryUsed, secondaryTotal) = parseWindow(secondaryWindow)

        val limitReached = rateLimit?.optBoolean("limit_reached", false) ?: false
        val spendReached = json.optJSONObject("spend_control")?.optBoolean("reached", false) ?: false
        val status = when {
            limitReached -> "LIMITED"
            spendReached -> "SPEND_LIMIT"
            planType.isNotBlank() -> "ACTIVE"
            else -> ""
        }

        return CodingPlanQuota(
            sessionUsed = primaryUsed,
            sessionTotal = primaryTotal,
            weekUsed = secondaryUsed,
            weekTotal = secondaryTotal,
            sessionResetsAt = parseResetTime(primaryWindow),
            weekResetsAt = parseResetTime(secondaryWindow),
            tier = planType.uppercase(),
            planName = planType,
            instanceType = planType,
            status = status,
            accountEmail = email,
            remainingDays = remainingDays,
        )
    }

    // Account check for subscription expiry — longer timeouts since GFW may slow it down
    private fun fetchRemainingDays(accessToken: String, accountId: String?): Int {
        val url = URL("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27?timezone_offset_min=0")
        val conn = url.openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "Lockit-Android")
            if (!accountId.isNullOrBlank()) conn.setRequestProperty("ChatGPT-Account-Id", accountId)
            if (conn.responseCode != 200) return 0
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val account = resolveAccountObject(json, accountId)
            val expiresAt = parseExpiresAt(account)
            if (expiresAt == null) 0
            else maxOf(0, java.time.temporal.ChronoUnit.DAYS.between(Instant.now(), expiresAt).toInt())
        } catch (_: Exception) { 0 }
        finally { conn.disconnect() }
    }

    private fun resolveAccountObject(json: JSONObject, requestedAccountId: String?): JSONObject? {
        val accounts = json.optJSONObject("accounts") ?: return null
        val def = accounts.optJSONObject("default")?.optJSONObject("account")
        if (def != null) return def
        accounts.keys().forEach { key ->
            val acc = accounts.optJSONObject(key)?.optJSONObject("account") ?: accounts.optJSONObject(key)
            if (acc != null) return acc
        }
        return null
    }

    private fun parseExpiresAt(account: JSONObject?): Instant? {
        if (account == null) return null
        val raw = account.opt("subscription_expires_at") ?: account.opt("expires_at") ?: return null
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
}
