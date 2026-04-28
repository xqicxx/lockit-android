package com.lockit.domain.qwen

import com.lockit.domain.CodingPlanFetcher
import com.lockit.domain.CodingPlanQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Qwen (阿里云百炼) Coding Plan quota fetcher.
 *
 * 使用方法：
 * 1. 在阿里云百炼控制台获取 curl 命令
 * 2. 从 curl 中提取 cookie
 * 3. 存入 credential metadata
 *
 * Metadata 需要的字段：
 * - provider: "qwen_bailian"
 * - cookie: 完整的 cookie 字符串
 */
object QwenCodingPlan : CodingPlanFetcher {
    override val providerKey: String = "qwen_bailian"

    /** Exposed for ViewModel to check auth failure type after null result. */
    @Volatile
    var lastHttpStatus: Int = 0
        private set

    private const val API_URL = "https://bailian-cs.console.aliyun.com/data/api.json" +
        "?action=BroadScopeAspnGateway&product=sfm_bailian" +
        "&api=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2"

    override suspend fun fetchQuota(metadata: Map<String, String>): CodingPlanQuota? =
        withContext(Dispatchers.IO) {
            val cookie = metadata["cookie"]?.takeIf { it.isNotBlank() } ?: return@withContext null
            val sanitizedCookie = cookie.sanitize()

            try {
                fetchFromApi(sanitizedCookie)
            } catch (e: Exception) {
                null
            }
        }

    private fun fetchFromApi(cookie: String): CodingPlanQuota? {
        lastHttpStatus = 0 // reset before each request to avoid stale data
        val url = URL(API_URL)
        val conn = url.openConnection() as HttpURLConnection

        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.useCaches = false

        conn.setupHeaders(cookie)
        conn.writeBody()

        lastHttpStatus = conn.responseCode
        val response = conn.readResponse()
        conn.disconnect()

        return parseResponse(response)
    }

    private fun HttpURLConnection.setupHeaders(cookie: String) {
        requestMethod = "POST"
        setRequestProperty("Accept", "*/*")
        setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        setRequestProperty("Origin", "https://bailian.console.aliyun.com")
        setRequestProperty("Referer", "https://bailian.console.aliyun.com/cn-beijing?tab=coding-plan")
        setRequestProperty("Cookie", cookie)
        // 禁用 keep-alive 延迟
        setRequestProperty("Connection", "close")
        doOutput = true
    }

    private fun HttpURLConnection.writeBody() {
        val paramsJson = buildParamsJson()
        val body = "params=${java.net.URLEncoder.encode(paramsJson, "UTF-8")}&region=cn-beijing"

        outputStream.use { it.write(body.toByteArray()) }
    }

    private fun buildParamsJson(): String {
        return JSONObject().apply {
            put("Api", "zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2")
            put("V", "1.0")
            put("Data", JSONObject().apply {
                put("queryCodingPlanInstanceInfoRequest", JSONObject().apply {
                    put("commodityCode", "sfm_codingplan_public_cn")
                    put("onlyLatestOne", true)
                })
                put("cornerstoneParam", JSONObject().apply {
                    put("protocol", "V2")
                    put("console", "ONE_CONSOLE")
                    put("productCode", "p_efm")
                })
            })
        }.toString()
    }

    private fun HttpURLConnection.readResponse(): String {
        if (responseCode != 200) return ""
        return inputStream.bufferedReader().use { it.readText() }
    }

    private fun parseResponse(response: String): CodingPlanQuota? {
        if (response.isBlank()) return null

        val json = JSONObject(response)
        val data = json.optJSONObject("data")
        val dataV2 = data?.optJSONObject("DataV2")
        val innerData = dataV2?.optJSONObject("data")
        val innerData2 = innerData?.optJSONObject("data")
        val instances = innerData2?.optJSONArray("codingPlanInstanceInfos")

        if (instances == null || instances.length() == 0) return null

        val instance = instances.getJSONObject(0)
        val quotaInfo = instance.optJSONObject("codingPlanQuotaInfo") ?: return null

        val planName = instance.optString("planName", "")
        val instanceType = instance.optString("instanceType", "")

        return CodingPlanQuota(
            sessionUsed = quotaInfo.optInt("per5HourUsedQuota", 0),
            sessionTotal = quotaInfo.optInt("per5HourTotalQuota", 0),
            weekUsed = quotaInfo.optInt("perWeekUsedQuota", 0),
            weekTotal = quotaInfo.optInt("perWeekTotalQuota", 0),
            monthUsed = quotaInfo.optInt("perBillMonthUsedQuota", 0),
            monthTotal = quotaInfo.optInt("perBillMonthTotalQuota", 0),
            instanceName = instance.optString("instanceName", ""),
            instanceType = instanceType,
            status = instance.optString("status", ""),
            remainingDays = instance.optInt("remainingDays", 0),
            planName = planName,
            tier = planName.ifBlank { instanceType },
            chargeAmount = instance.optDouble("chargeAmount", 0.0),
            chargeType = instance.optString("chargeType", ""),
            autoRenewFlag = instance.optBoolean("autoRenewFlag", false),
            accountEmail = "", // Bailian uses cookie auth, no email identity
            loginMethod = "COOKIE", // Bailian auth is always cookie-based
        )
    }

    private fun String.sanitize(): String =
        replace("\n", "").replace("\r", "").trim()
}