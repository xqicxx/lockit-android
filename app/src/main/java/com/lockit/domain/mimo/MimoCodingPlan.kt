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

    private const val API_BASE = "https://api.xiaomimimo.com/v1"

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val apiKey = metadata["api_key"]?.takeIf { it.isNotBlank() }
                ?: metadata["apiKey"]?.takeIf { it.isNotBlank() }
                ?: return@withContext null

            // MiMo has no programmatic quota API — validate key exists, return static limits
            if (apiKey.length < 8) return@withContext null

            CodingPlanQuota(
                sessionUsed = 0,
                sessionTotal = 100,
                weekUsed = 0,
                weekTotal = 0,
                monthUsed = 0,
                monthTotal = 0,
                instanceName = "MiMo Token Plan",
                instanceType = "token_plan",
                status = "ACTIVE",
                planName = metadata["plan"] ?: "MiMo",
                tier = metadata["plan"] ?: "MiMo",
                accountEmail = "",
                loginMethod = "API_KEY",
            )
        }
}
