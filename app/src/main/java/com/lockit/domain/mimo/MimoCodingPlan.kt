package com.lockit.domain.mimo

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota

object MimoCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "mimo"

    private val planCredits: Map<String, Long> = mapOf(
        "lite" to 60_000_000,
        "standard" to 200_000_000,
        "pro" to 700_000_000,
        "max" to 1_600_000_000,
    )

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? {
        val apiKey = metadata["api_key"]?.takeIf { it.isNotBlank() }
            ?: metadata["apiKey"]?.takeIf { it.isNotBlank() }
            ?: return null

        if (apiKey.length < 8) return null

        val plan = metadata["plan"]?.takeIf { it.isNotBlank() } ?: "MiMo"
        val creditTotal = planCredits[plan.lowercase()] ?: planCredits["standard"]!!

        return CodingPlanQuota(
            sessionUsed = 0, sessionTotal = 0,
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
}
