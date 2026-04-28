package com.lockit.domain.claude

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import com.lockit.domain.model.ModelQuota
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
        val usageUrl = URL("https://claude.ai/api/organizations/$orgId/usage")
        val conn = usageUrl.openConnection() as HttpURLConnection
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
            return parseUsageResponse(response, sessionKey, orgId)
        } finally {
            conn.disconnect()
        }
    }

    private fun fetchAccountIdentity(sessionKey: String): Pair<String, String> {
        try {
            val url = URL("https://claude.ai/api/account")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Cookie", "sessionKey=$sessionKey")
            if (conn.responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val email = json.optString("email", "")
                val loginMethod = json.optString("login_method", "")
                    .ifBlank { json.optString("auth_provider", "") }
                return email to loginMethod
            }
        } catch (_: Exception) {}
        return "" to ""
    }

    private fun parseUsageResponse(response: String, sessionKey: String, orgId: String): CodingPlanQuota? {
        if (response.isBlank()) return null

        val json = JSONObject(response)

        // Usage data may be at top level or nested under "usage"
        val usage = json.optJSONObject("usage") ?: json

        // Session quota (5-hour window)
        val fiveHour = usage.optJSONObject("five_hour")
        val sessionUsed = fiveHour?.optInt("used", usage.optInt("used", 0)) ?: usage.optInt("used", 0)
        val sessionTotal = fiveHour?.optInt("total", usage.optInt("total", 0)) ?: usage.optInt("total", 0)

        // Weekly quota
        val sevenDay = usage.optJSONObject("seven_day")
        val weekUsed = sevenDay?.optInt("used", 0) ?: 0
        val weekTotal = sevenDay?.optInt("total", 0) ?: 0

        // Model-specific quotas
        val modelQuotas = mutableMapOf<String, ModelQuota>()
        listOf("seven_day_sonnet", "seven_day_opus", "seven_day_haiku").forEach { key ->
            val mq = usage.optJSONObject(key)
            if (mq != null) {
                val modelName = when (key) {
                    "seven_day_sonnet" -> "Sonnet"
                    "seven_day_opus" -> "Opus"
                    "seven_day_haiku" -> "Haiku"
                    else -> key
                }
                modelQuotas[modelName] = ModelQuota(
                    modelName = modelName,
                    weekUsed = mq.optInt("used", 0),
                    weekTotal = mq.optInt("total", 0),
                )
            }
        }

        // Extra usage (Claude Max overage)
        val extraUsage = usage.optJSONObject("extra_usage")
        val extraUsageSpent = extraUsage?.optDouble("spent", 0.0) ?: 0.0
        val extraUsageLimit = extraUsage?.optDouble("limit", 0.0) ?: 0.0

        // Fetch account identity for email/login
        val (email, loginMethod) = fetchAccountIdentity(sessionKey)

        val remaining = if (sessionTotal > 0) sessionTotal - sessionUsed else 0
        val planName = when {
            extraUsageLimit > 0 -> "Claude Max"
            modelQuotas.isNotEmpty() -> "Claude Max"
            else -> "Claude Pro"
        }
        val tier = when {
            extraUsageLimit > 0 -> "MAX"
            modelQuotas.isNotEmpty() -> "MAX"
            else -> "PRO"
        }

        return CodingPlanQuota(
            sessionUsed = sessionUsed,
            sessionTotal = sessionTotal,
            weekUsed = weekUsed,
            weekTotal = weekTotal,
            monthUsed = 0,
            monthTotal = 0,
            instanceName = "Claude",
            instanceType = planName,
            status = if (remaining > 0) "VALID" else "EXHAUSTED",
            remainingDays = 0,
            planName = planName,
            tier = tier,
            modelQuotas = modelQuotas,
            extraUsageSpent = extraUsageSpent,
            extraUsageLimit = extraUsageLimit,
            chargeAmount = if (extraUsageLimit > 0) 100.0 else 20.0,
            chargeType = "subscription",
            autoRenewFlag = true,
            accountEmail = email,
            loginMethod = loginMethod,
        )
    }
}