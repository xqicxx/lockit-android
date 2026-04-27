package com.lockit.domain

import com.lockit.domain.qwen.QwenCodingPlan
import com.lockit.domain.chatgpt.ChatGPTCodingPlan
import com.lockit.domain.claude.ClaudeCodingPlan
import com.lockit.domain.model.ModelQuota
import java.time.Instant

/**
 * Result of a coding plan quota fetch.
 * Extended to support multi-model quotas, credits, reset times, and identity.
 */
data class CodingPlanQuota(
    // === Time window quotas ===
    val sessionUsed: Int = 0,
    val sessionTotal: Int = 0,
    val weekUsed: Int = 0,
    val weekTotal: Int = 0,
    val monthUsed: Int = 0,
    val monthTotal: Int = 0,

    // === Model-specific quotas (Claude Max / ChatGPT) ===
    val modelQuotas: Map<String, ModelQuota> = emptyMap(),

    // === Credits / balance ===
    val creditsRemaining: Double = 0.0,
    val creditsCurrency: String = "USD",

    // === Extra usage (Claude Max) ===
    val extraUsageSpent: Double = 0.0,
    val extraUsageLimit: Double = 0.0,

    // === Instance info ===
    val instanceName: String = "",
    val instanceType: String = "",
    val status: String = "",
    val remainingDays: Int = 0,
    val planName: String = "",
    val tier: String = "",

    // === Reset times ===
    val sessionResetsAt: Instant? = null,
    val weekResetsAt: Instant? = null,
    val monthResetsAt: Instant? = null,

    // === Charge info ===
    val chargeAmount: Double = 0.0,
    val chargeType: String = "",
    val autoRenewFlag: Boolean = false,

    // === Identity ===
    val accountEmail: String = "",
    val loginMethod: String = "",
) {
    // Legacy aliases for backwards compatibility
    @Deprecated("Use sessionUsed instead", ReplaceWith("sessionUsed"))
    val fiveHourUsed: Int = sessionUsed

    @Deprecated("Use sessionTotal instead", ReplaceWith("sessionTotal"))
    val fiveHourTotal: Int = sessionTotal
}

/**
 * Provider-specific coding plan quota fetcher.
 * Each provider (qwen_bailian, chatgpt, claude, etc.) implements its own API call logic.
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

    fun forProvider(key: String): CodingPlanFetcher? = registry[CodingPlanProviders.normalize(key)]
    fun supportedProviders(): Set<String> = registry.keys
}
