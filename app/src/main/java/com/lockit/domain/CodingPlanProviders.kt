package com.lockit.domain

import com.lockit.domain.model.Credential

/**
 * Canonical provider keys for CodingPlan credentials.
 * UI fields may still contain legacy names such as "openai" or "anthropic".
 */
object CodingPlanProviders {
    const val QWEN_BAILIAN = "qwen_bailian"
    const val CHATGPT = "chatgpt"
    const val CLAUDE = "claude"

    fun normalize(raw: String?): String {
        return when (raw?.trim()?.lowercase()) {
            null, "" -> QWEN_BAILIAN
            "qwen", "qwen_bailian", "bailian" -> QWEN_BAILIAN
            "openai", "chatgpt", "codex" -> CHATGPT
            "anthropic", "claude" -> CLAUDE
            else -> raw.trim().lowercase()
        }
    }

    fun fromCredential(credential: Credential): String {
        return normalize(
            credential.metadata["provider"]
                ?: credential.service.takeIf { it.isNotBlank() }
                ?: credential.name
        )
    }
}
