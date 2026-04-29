package com.lockit.domain

object CodingPlanProviders {
    const val QWEN_BAILIAN = "qwen_bailian"
    const val CHATGPT = "chatgpt"
    const val CLAUDE = "claude"
    const val MIMO = "mimo"
    const val DEEPSEEK = "deepseek"

    fun normalize(value: String?): String {
        return when (value?.trim()?.lowercase()) {
            "qwen", "bailian", QWEN_BAILIAN -> QWEN_BAILIAN
            "openai", "codex", CHATGPT -> CHATGPT
            "anthropic", CLAUDE -> CLAUDE
            "mimo", "xiaomi", "xiaomi_mimo", MIMO -> MIMO
            "deepseek", DEEPSEEK -> DEEPSEEK
            else -> value?.trim()?.lowercase().orEmpty()
        }
    }

    fun defaultBaseUrl(provider: String): String {
        return when (normalize(provider)) {
            QWEN_BAILIAN -> "https://coding.dashscope.aliyuncs.com/v1"
            CHATGPT -> "https://chatgpt.com/backend-api/wham/usage"
            CLAUDE -> "https://claude.ai/api/organizations"
            MIMO -> "https://api.xiaomimimo.com/v1"
            DEEPSEEK -> "https://api.deepseek.com/v1"
            else -> ""
        }
    }
}
