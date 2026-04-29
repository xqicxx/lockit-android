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

    // MiMo plan credits from docs: Lite=60M, Standard=200M, Pro=700M, Max=1600M
    private val planCredits: Map<String, Long> = mapOf(
        "lite" to 60_000_000, "standard" to 200_000_000,
        "pro" to 700_000_000, "max" to 1_600_000_000,
    )

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val apiKey = metadata["api_key"]?.takeIf { it.isNotBlank() }
                ?: metadata["apiKey"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            val plan = metadata["plan"]?.takeIf { it.isNotBlank() } ?: "MiMo"
            val creditTotal = planCredits[plan.lowercase()] ?: planCredits["standard"]!!

            // Actually call the API to validate the key and get real data
            val modelCount = try {
                fetchModelCount(apiKey)
            } catch (_: Exception) {
                0
            }

            if (modelCount <= 0) return@withContext null

            CodingPlanQuota(
                sessionUsed = modelCount,
                sessionTotal = 0,
                weekUsed = 0, weekTotal = 0,
                monthUsed = 0, monthTotal = creditTotal.toInt(),
                instanceName = "MiMo Token Plan",
                instanceType = "token_plan",
                status = "ACTIVE",
                planName = plan,
                tier = plan,
                loginMethod = "API_KEY",
            )
        }

    private fun fetchModelCount(apiKey: String): Int {
        val url = URL("https://api.xiaomimimo.com/v1/models")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("api-key", apiKey)

        return try {
            if (conn.responseCode != 200) 0
            else {
                val body = conn.inputStream.bufferedReader().readText()
                JSONObject(body).optJSONArray("data")?.length() ?: 0
            }
        } finally {
            conn.disconnect()
        }
    }
}
