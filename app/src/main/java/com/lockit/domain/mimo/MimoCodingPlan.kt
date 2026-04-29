package com.lockit.domain.mimo

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MimoCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "mimo"

    private const val USAGE_URL = "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage"

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val apiKey = metadata["api_key"]?.takeIf { it.isNotBlank() }
                ?: metadata["apiKey"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            val cookie = metadata["cookie"]?.replace("\n", "")?.replace("\r", "")?.trim()
                ?.takeIf { it.isNotBlank() }

            // Try to fetch real usage via cookie, fall back to static plan info
            val usage = if (cookie != null) fetchUsage(cookie, apiKey) else null

            if (usage != null) usage
            else if (apiKey.length >= 8) staticFallback(metadata)
            else null
        }

    private fun fetchUsage(cookie: String, apiKey: String): CodingPlanQuota? {
        return try {
            val url = URL(USAGE_URL)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("api-key", apiKey)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("x-timezone", "Asia/Shanghai")

            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.optInt("code") != 0) return null

            val data = json.optJSONObject("data") ?: return null
            val usage = data.optJSONObject("usage") ?: return null
            val items = usage.optJSONArray("items")

            var totalUsed = 0
            var totalLimit = 0
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optString("name") == "plan_total_token") {
                        totalUsed = item.optInt("used", 0)
                        totalLimit = item.optInt("limit", 0)
                    }
                }
            }

            CodingPlanQuota(
                sessionUsed = totalUsed,
                sessionTotal = totalLimit,
                weekUsed = 0, weekTotal = 0,
                monthUsed = 0, monthTotal = 0,
                instanceName = "MiMo Token Plan",
                instanceType = "token_plan",
                status = if (totalUsed < totalLimit) "ACTIVE" else "EXHAUSTED",
                planName = "MiMo",
                tier = "MiMo",
                loginMethod = if (cookie.isNotBlank()) "COOKIE" else "API_KEY",
            )
        } catch (_: Exception) { null }
    }

    private fun staticFallback(metadata: Map<String, String>): CodingPlanQuota {
        return CodingPlanQuota(
            sessionUsed = 0, sessionTotal = 0,
            weekUsed = 0, weekTotal = 0,
            monthUsed = 0, monthTotal = 0,
            instanceName = "MiMo Token Plan",
            instanceType = "token_plan",
            status = "ACTIVE",
            planName = metadata["plan"] ?: "MiMo",
            tier = metadata["plan"] ?: "MiMo",
            loginMethod = "API_KEY",
        )
    }
}
