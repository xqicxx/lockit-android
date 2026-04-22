package com.lockit.domain.claude

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude Coding Plan quota fetcher.
 *
 * 使用方法：
 * 1. WebView 登录 claude.ai
 * 2. 抓取 sessionKey cookie (sk-ant-sid01-xxx)
 * 3. 获取 orgId (from /api/auth/me or JS inject)
 * 4. 存入 credential metadata
 *
 * Metadata 需要的字段：
 * - provider: "claude"
 * - sessionKey: Cookie value (sk-ant-sid01-xxx)
 * - orgId: Organization ID
 */
object ClaudeCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "claude"

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val sessionKey = metadata["sessionKey"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null
            val orgId = metadata["orgId"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            try {
                fetchFromApi(sessionKey, orgId)
            } catch (e: Exception) {
                null
            }
        }

    private fun fetchFromApi(sessionKey: String, orgId: String): CodingPlanQuota? {
        val url = URL("https://claude.ai/api/organizations/$orgId/usage")
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.useCaches = false

            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cookie", "sessionKey=$sessionKey")

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

        // Claude usage API returns tokens used/remaining
        val usage = json.optJSONObject("usage") ?: return null

        val used = usage.optInt("used", 0)
        val total = usage.optInt("total", 0)
        val remaining = usage.optInt("remaining", total - used)

        return CodingPlanQuota(
            sessionUsed = used,
            sessionTotal = total,
            weekUsed = 0,
            weekTotal = 0,
            monthUsed = 0,
            monthTotal = 0,
            instanceName = "Claude",
            instanceType = "Claude Pro",
            status = if (remaining > 0) "VALID" else "EXHAUSTED",
            remainingDays = 0,
            planName = "Claude Pro",
            chargeAmount = 20.0,
            chargeType = "subscription",
            autoRenewFlag = true,
        )
    }
}