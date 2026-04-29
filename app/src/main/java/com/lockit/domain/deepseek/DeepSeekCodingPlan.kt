package com.lockit.domain.deepseek

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object DeepSeekCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "deepseek"

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val apiKey = metadata["api_key"]?.takeIf { it.isNotBlank() }
                ?: metadata["apiKey"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            try {
                val balance = fetchBalance(apiKey)
                CodingPlanQuota(
                    creditsRemaining = balance,
                    creditsCurrency = "CNY",
                    instanceType = "pay_as_you_go",
                    status = "ACTIVE",
                    loginMethod = "API_KEY",
                )
            } catch (_: Exception) {
                null
            }
        }

    private fun fetchBalance(apiKey: String): Double {
        val url = URL("https://api.deepseek.com/user/balance")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.setRequestProperty("Accept", "application/json")

        return try {
            if (conn.responseCode != 200) 0.0
            else {
                val body = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(body)
                val raw = json.optJSONArray("balance_infos")?.optJSONObject(0)?.optString("total_balance", "0")
                    ?: json.optString("total_balance", "0")
                raw.toDoubleOrNull() ?: 0.0
            }
        } finally {
            conn.disconnect()
        }
    }
}
