package com.lockit.domain.model

import java.time.Instant

/**
 * Model-specific quota data.
 * Used for providers with multiple model quotas (Claude Max, ChatGPT).
 */
data class ModelQuota(
    val modelName: String,            // "Sonnet 4", "Opus 4", "GPT-5.2"
    val usedPercent: Double = 0.0,
    val weekUsed: Int = 0,
    val weekTotal: Int = 0,
    val resetsAt: Instant? = null,
)