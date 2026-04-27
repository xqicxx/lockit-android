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

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val accessToken = metadata["accessToken"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val accountId = metadata["accountId"]?.takeIf { it.isNotBlank() }

            try {
                fetchFromApi(accessToken, accountId)
            } catch (e: Exception) {
                null
            }
        }

    private fun fetchFromApi(accessToken: String, accountId: String?): CodingPlanQuota? {
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
            return parseResponse(response)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseResponse(response: String): CodingPlanQuota? {
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
        val planType = json.optString("plan_type", "ChatGPT")

        return CodingPlanQuota(
            sessionUsed = primaryUsed,
            sessionTotal = primaryTotal,
            weekUsed = secondaryUsed,
            weekTotal = secondaryTotal,
            monthUsed = 0,
            monthTotal = 0,
            instanceName = "ChatGPT",
            instanceType = planType,
            status = "VALID",
            sessionResetsAt = primaryResetsAt,
            weekResetsAt = secondaryResetsAt,
            modelQuotas = mapOf(
                "primary" to ModelQuota(
                    modelName = "Primary window",
                    usedPercent = primaryUsedPercent,
                    weekUsed = primaryUsed,
                    weekTotal = primaryTotal,
                    resetsAt = primaryResetsAt,
                ),
                "secondary" to ModelQuota(
                    modelName = "Secondary window",
                    usedPercent = secondaryUsedPercent,
                    weekUsed = secondaryUsed,
                    weekTotal = secondaryTotal,
                    resetsAt = secondaryResetsAt,
                ),
            ).filterValues { it.weekTotal > 0 },
            remainingDays = 0,
            chargeAmount = 0.0,
            chargeType = "subscription",
            autoRenewFlag = true,
        )
    }
}
