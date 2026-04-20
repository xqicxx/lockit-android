package com.lockit.domain

import com.lockit.domain.qwen.QwenCodingPlan
import com.lockit.domain.chatgpt.ChatGPTCodingPlan
import com.lockit.domain.claude.ClaudeCodingPlan

/**
 * Result of a coding plan quota fetch.
 */
data class CodingPlanQuota(
    val fiveHourUsed: Int = 0,
    val fiveHourTotal: Int = 0,
    val weekUsed: Int = 0,
    val weekTotal: Int = 0,
    val monthUsed: Int = 0,
    val monthTotal: Int = 0,
    // Instance info
    val instanceName: String = "",
    val instanceType: String = "",
    val status: String = "",
    val remainingDays: Int = 0,
    val chargeAmount: Double = 0.0,
    val chargeType: String = "",
    val autoRenewFlag: Boolean = false,
)

/**
 * Provider-specific coding plan quota fetcher.
 * Each provider (qwen_bailian, openai, etc.) implements its own API call logic.
 */
interface CodingPlanFetcher {
    val providerKey: String

    /**
     * Fetch quota data from the provider's API.
     * @param metadata Stored credential metadata (cookie, baseUrl, etc.)
     * @return Quota data, or null if fetch failed
     */
    suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota?
}

/**
 * Registry of all coding plan fetchers.
 */
object CodingPlanFetchers {
    private val registry: Map<String, CodingPlanFetcher> = mapOf(
        "qwen_bailian" to QwenCodingPlan,
        "chatgpt" to ChatGPTCodingPlan,
        "claude" to ClaudeCodingPlan,
    )

    fun forProvider(key: String): CodingPlanFetcher? = registry[key]
    fun supportedProviders(): Set<String> = registry.keys
}
