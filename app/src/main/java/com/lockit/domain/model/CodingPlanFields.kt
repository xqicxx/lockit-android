package com.lockit.domain.model

/**
 * Constants for CodingPlan credential field indices.
 * All reads and writes should use these constants to prevent silent data corruption.
 */
object CodingPlanFields {
    const val PROVIDER = 0   // Provider name (e.g., "qwen_bailian", "openai")
    const val RAW_CURL = 1   // Raw curl command for auth extraction
    const val API_KEY = 2    // API key / access token
    const val COOKIE = 3     // Cookie string (for some providers)
    const val BASE_URL = 4   // Base URL for API calls

    const val FIELD_COUNT = 5
}