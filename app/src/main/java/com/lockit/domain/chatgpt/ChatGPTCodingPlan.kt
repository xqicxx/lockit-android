package com.lockit.domain.chatgpt

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                ?: return@withContext null

            try {
                fetchFromApi(accessToken, accountId)
            } catch (e: Exception) {
                null
            }
        }

    private fun fetchFromApi(accessToken: String, accountId: String): CodingPlanQuota? {
        val url = URL(USAGE_API)
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.useCaches = false

            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("ChatGPT-Account-Id", accountId)

            if (conn.responseCode != 200) {
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

        // ChatGPT usage API returns daily/weekly limits
        val dailyLimit = json.optJSONObject("daily_limit")
        val weeklyLimit = json.optJSONObject("weekly_limit")

        val dailyUsed = dailyLimit?.optInt("used", 0) ?: 0
        val dailyTotal = dailyLimit?.optInt("total", 0) ?: 0
        val weeklyUsed = weeklyLimit?.optInt("used", 0) ?: 0
        val weeklyTotal = weeklyLimit?.optInt("total", 0) ?: 0

        return CodingPlanQuota(
            sessionUsed = dailyUsed,
            sessionTotal = dailyTotal,
            weekUsed = weeklyUsed,
            weekTotal = weeklyTotal,
            monthUsed = 0,
            monthTotal = 0,
            instanceName = "ChatGPT",
            instanceType = "GPT-4",
            status = "VALID",
            remainingDays = 0,
            chargeAmount = 0.0,
            chargeType = "subscription",
            autoRenewFlag = true,
        )
    }
}