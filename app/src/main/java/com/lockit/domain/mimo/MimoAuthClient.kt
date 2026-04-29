package com.lockit.domain.mimo

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object MimoAuthClient {
    private const val USAGE_API = "https://platform.xiaomimimo.com/api/v1/tokenPlan/usage"

    suspend fun fetchCredentials(cookie: String): Map<String, String> = withContext(Dispatchers.IO) {
        try {
            val url = URL(USAGE_API)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("x-timezone", "Asia/Shanghai")

            if (conn.responseCode != 200) throw Exception("HTTP ${conn.responseCode}")

            val body = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(body)
            if (json.optInt("code") != 0) throw Exception("API error")

            val usage = json.optJSONObject("data")?.optJSONObject("usage")
            val items = usage?.optJSONArray("items")
            var planName = "MiMo"
            var totalLimit = 0L
            if (items != null) {
                for (i in 0 until items.length()) {
                    val item = items.getJSONObject(i)
                    if (item.optString("name") == "plan_total_token") {
                        totalLimit = item.optLong("limit", 0)
                    }
                }
            }
            if (totalLimit >= 1_600_000_000) planName = "Max"
            else if (totalLimit >= 700_000_000) planName = "Pro"
            else if (totalLimit >= 200_000_000) planName = "Standard"
            else if (totalLimit >= 60_000_000) planName = "Lite"

            mapOf(
                "provider" to "xiaomi_mimo",
                "cookie" to cookie,
                "apiKey" to "",
                "baseUrl" to "https://api.xiaomimimo.com/v1",
                "plan" to planName,
            )
        } catch (e: Exception) {
            mapOf(
                "provider" to "xiaomi_mimo",
                "cookie" to cookie,
                "apiKey" to "",
                "baseUrl" to "https://api.xiaomimimo.com/v1",
            )
        }
    }
}
