package com.lockit.utils

import java.net.URLDecoder

/**
 * Parses a raw curl command string into structured key-value pairs for CodingPlan display.
 */
object CodingPlanParser {

    data class ParsedPlan(
        val provider: String = "",
        val baseUrl: String = "",
        val apiKey: String = "",
        // Legacy fields (for backwards compatibility with old curl-based entries)
        val url: String = "",
        val api: String = "",
        val commodityCode: String = "",
        val region: String = "",
        val instanceId: String = "",
        val createdTime: String = "",
        val expireTime: String = "",
        val status: String = "",
        val rawParams: String = "",
        val extraFields: Map<String, String> = emptyMap(),
    )

    private val CURL_URL_REGEX = Regex("curl\\s+'([^']+)'")
    private val CURL_URL_DOUBLE_REGEX = Regex("curl\\s+\"([^\"]+)\"")
    private val DATA_RAW_SINGLE_REGEX = Regex("--data-raw\\s+'([^']*)'", RegexOption.DOT_MATCHES_ALL)
    private val DATA_RAW_DOUBLE_REGEX = Regex("--data-raw\\s+\"([^\"]*)\"", RegexOption.DOT_MATCHES_ALL)
    private val REGION_REGEX = Regex("region=([^&\\s]+)")

    private fun extractField(json: String, fieldName: String): String {
        return Regex("\"${Regex.escape(fieldName)}\"\\s*:\\s*\"([^\"]+)\"")
            .find(json)?.groupValues?.get(1) ?: ""
    }

    /**
     * Parse a raw curl command into structured fields.
     */
    fun parseCurl(raw: String): ParsedPlan {
        var url = ""
        var rawParams = ""
        val extraFields = mutableMapOf<String, String>()

        val urlMatch = CURL_URL_REGEX.find(raw) ?: CURL_URL_DOUBLE_REGEX.find(raw)
        urlMatch?.let { url = it.groupValues[1] }

        val dataMatch = DATA_RAW_SINGLE_REGEX.find(raw) ?: DATA_RAW_DOUBLE_REGEX.find(raw)
        dataMatch?.let { rawParams = it.groupValues[1] }

        REGION_REGEX.find(raw)?.let { extraFields["region"] = it.groupValues[1] }

        val decodedParams = try {
            URLDecoder.decode(rawParams, "UTF-8")
        } catch (_: Exception) {
            rawParams
        }

        val api = extractField(decodedParams, "Api")
        val commodityCode = extractField(decodedParams, "commodityCode")
        val traceId = extractField(decodedParams, "feTraceId")
        val productCode = extractField(decodedParams, "productCode")

        val extra = if (productCode.isNotBlank()) extraFields + ("productCode" to productCode) else extraFields
        return ParsedPlan(
            url = url,
            api = api,
            commodityCode = commodityCode,
            region = extraFields["region"] ?: "",
            instanceId = traceId,
            rawParams = rawParams,
            extraFields = extra,
        )
    }

    /**
     * Parse metadata Map stored in the credential's metadata field.
     * Supports both new provider-based format and legacy curl-based format.
     */
    fun parseMetadata(metadata: Map<String, String>): ParsedPlan {
        return ParsedPlan(
            provider = metadata["provider"] ?: "",
            baseUrl = metadata["baseUrl"] ?: "",
            apiKey = metadata["apiKey"] ?: "",
            // Legacy curl-based fields
            url = metadata["url"] ?: "",
            api = metadata["api"] ?: "",
            commodityCode = metadata["commodityCode"] ?: "",
            region = metadata["region"] ?: "",
            instanceId = metadata["instanceId"] ?: "",
            createdTime = metadata["createdTime"] ?: "",
            expireTime = metadata["expireTime"] ?: "",
            status = metadata["status"] ?: "",
        )
    }

    /**
     * Check if the coding plan has expired based on expireTime field.
     * Returns true if expired, false if still valid.
     */
    fun isExpired(expireTime: String): Boolean {
        if (expireTime.isBlank()) return false
        val date = parseDate(expireTime) ?: return false
        return date.before(java.util.Date())
    }

    /**
     * Check if the plan expires within the given days (default 7).
     */
    fun expiresWithin(expireTime: String, days: Int = 7): Boolean {
        if (expireTime.isBlank()) return false
        val date = parseDate(expireTime) ?: return false
        val now = System.currentTimeMillis()
        if (date.time < now) return false
        return date.time < now + (days * 24L * 60 * 60 * 1000)
    }

    private fun parseDate(expireTime: String): java.util.Date? {
        val normalized = expireTime.replace("T", " ").trimEnd('Z', 'z')
        return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(normalized)
            ?: java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).parse(normalized)
    }
}
