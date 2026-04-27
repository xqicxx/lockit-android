package com.lockit.domain.qwen

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Bailian (阿里云百炼) 认证客户端。
 * 用于从 WebView cookie 中提取 API key 等凭据。
 */
object BailianAuthClient {

    private const val TAG = "BailianAuth"

    /**
     * 从 cookie 获取所有凭据（instanceId + apiKey + curlCommand）。
     */
    suspend fun fetchCredentials(cookie: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val instanceId = fetchInstanceId(cookie)
                Log.d(TAG, "instanceId: ${if (instanceId.isNotBlank()) "OK" else "EMPTY"}")

                val apiKey = if (instanceId.isNotBlank()) fetchApiKeys(cookie, instanceId) else null
                Log.d(TAG, "apiKey: ${if (apiKey?.isNotBlank() == true) "OK" else "EMPTY"}")

                val curlCommand = buildCurlCommand(cookie)

                mapOf(
                    "provider" to "qwen_bailian",
                    "cookie" to cookie,
                    "apiKey" to (apiKey ?: ""),
                    "rawCurl" to curlCommand,
                    "baseUrl" to "https://coding.dashscope.aliyuncs.com/v1"
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                mapOf(
                    "provider" to "qwen_bailian",
                    "cookie" to cookie,
                    "apiKey" to "",
                    "rawCurl" to buildCurlCommand(cookie),
                    "baseUrl" to "https://coding.dashscope.aliyuncs.com/v1"
                )
            }
        }
    }

    /**
     * 获取 instanceId。
     */
    private fun fetchInstanceId(cookie: String): String {
        val apiUrl = "https://bailian-cs.console.aliyun.com/data/api.json" +
            "?action=BroadScopeAspnGateway&product=sfm_bailian" +
            "&api=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2&_v=undefined"

        val params = JSONObject().apply {
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

        val encodedParams = java.net.URLEncoder.encode(params, "UTF-8")
        val body = "params=$encodedParams&region=cn-beijing"

        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Cookie", cookie)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }

        if (conn.responseCode != 200) {
            conn.disconnect()
            return ""
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        Log.d(TAG, "Instance response: OK (${response.length} chars)")

        // Parse: data.DataV2.data.data.codingPlanInstanceInfos[0].instanceId
        val json = JSONObject(response)
        val data = json.optJSONObject("data")
        if (data == null) {
            Log.e(TAG, "JSON parse failed: 'data' is null. Response: ${response.take(200)}")
            return ""
        }
        val dataV2 = data.optJSONObject("DataV2")
        if (dataV2 == null) {
            Log.e(TAG, "JSON parse failed: 'DataV2' is null")
            return ""
        }
        val innerData = dataV2.optJSONObject("data")
        if (innerData == null) {
            Log.e(TAG, "JSON parse failed: 'data' (inner) is null")
            return ""
        }
        val innerData2 = innerData.optJSONObject("data")
        if (innerData2 == null) {
            Log.e(TAG, "JSON parse failed: 'data' (inner2) is null")
            return ""
        }
        val instances = innerData2.optJSONArray("codingPlanInstanceInfos")
        if (instances == null || instances.length() == 0) {
            Log.e(TAG, "JSON parse failed: 'codingPlanInstanceInfos' is null/empty")
            return ""
        }
        return instances.getJSONObject(0).optString("instanceId", "")
    }

    /**
     * 获取 API keys。
     */
    private fun fetchApiKeys(cookie: String, instanceId: String): String? {
        if (instanceId.isBlank()) return null

        val apiUrl = "https://bailian-cs.console.aliyun.com/data/api.json" +
            "?action=BroadScopeAspnGateway&product=sfm_bailian" +
            "&api=zeldaEasy.bailian-dash-workspace.codingPlan.listCodingPlanApiKeysPlain4Agent&_v=undefined"

        val params = JSONObject().apply {
            put("Api", "zeldaEasy.bailian-dash-workspace.codingPlan.listCodingPlanApiKeysPlain4Agent")
            put("V", "1.0")
            put("Data", JSONObject().apply {
                put("instanceId", instanceId)
                put("cornerstoneParam", JSONObject().apply {
                    put("protocol", "V2")
                    put("console", "ONE_CONSOLE")
                    put("productCode", "p_efm")
                })
            })
        }.toString()

        val encodedParams = java.net.URLEncoder.encode(params, "UTF-8")
        val body = "params=$encodedParams&region=cn-beijing"

        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        conn.setRequestProperty("Cookie", cookie)
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toByteArray()) }

        if (conn.responseCode != 200) {
            conn.disconnect()
            return null
        }

        val response = conn.inputStream.bufferedReader().use { it.readText() }
        conn.disconnect()

        Log.d(TAG, "API response: OK (${response.length} chars)")

        // Parse: data.DataV2.data.data[0].apiKey
        val json = JSONObject(response)
        val data = json.optJSONObject("data")
        if (data == null) {
            Log.e(TAG, "API JSON parse failed: 'data' is null. Response: ${response.take(200)}")
            return null
        }
        val dataV2 = data.optJSONObject("DataV2")
        if (dataV2 == null) {
            Log.e(TAG, "API JSON parse failed: 'DataV2' is null")
            return null
        }
        val innerData = dataV2.optJSONObject("data")
        if (innerData == null) {
            Log.e(TAG, "API JSON parse failed: 'data' (inner) is null")
            return null
        }
        val dataArray = innerData.optJSONArray("data")
        if (dataArray == null || dataArray.length() == 0) {
            Log.e(TAG, "API JSON parse failed: 'data' array is null/empty")
            return null
        }
        return dataArray.getJSONObject(0).optString("apiKey", "")
    }

    /**
     * 构建 curl 命令。
     */
    fun buildCurlCommand(cookie: String): String {
        val apiUrl = "https://bailian-cs.console.aliyun.com/data/api.json" +
            "?action=BroadScopeAspnGateway&product=sfm_bailian" +
            "&api=zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2&_v=undefined"
        val params = """{"Api":"zeldaEasy.broadscope-bailian.codingPlan.queryCodingPlanInstanceInfoV2","V":"1.0","Data":{"queryCodingPlanInstanceInfoRequest":{"commodityCode":"sfm_codingplan_public_cn","onlyLatestOne":true},"cornerstoneParam":{"protocol":"V2","console":"ONE_CONSOLE","productCode":"p_efm"}}}"""
        val encodedParams = java.net.URLEncoder.encode(params, "UTF-8")
        val body = "params=$encodedParams&region=cn-beijing"

        return "curl '$apiUrl' -H 'accept: */*' -H 'content-type: application/x-www-form-urlencoded' -H 'origin: https://bailian.console.aliyun.com' -H 'referer: https://bailian.console.aliyun.com/cn-beijing?tab=coding-plan' -b '$cookie' --data-raw '$body'"
    }
}