package com.lockit.domain.claude

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Claude (Anthropic) 认证客户端。
 * 用于从 WebView cookie 中提取 sessionKey 和 orgId。
 */
object ClaudeAuthClient {

    private const val TAG = "ClaudeAuth"

    /**
     * 从 cookie 获取 sessionKey。
     */
    fun extractSessionKey(cookies: String): String? {
        return cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("sessionKey=") }
            ?.substringAfter("sessionKey=")
            ?.trim()
    }

    /**
     * 从 API 获取 orgId。
     */
    suspend fun fetchOrgId(sessionKey: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val authUrl = URL("https://claude.ai/api/auth/me")
                val conn = authUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("Cookie", "sessionKey=$sessionKey")

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    Log.e(TAG, "Auth API failed: ${conn.responseCode}")
                    return@withContext null
                }

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                Log.d(TAG, "Auth response: $response")

                val json = JSONObject(response)
                val orgs = json.optJSONArray("organizations")
                orgs?.optJSONObject(0)?.optString("id", "")
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching orgId: ${e.message}")
                null
            }
        }
    }

    /**
     * 从 cookie 获取所有凭据（sessionKey + orgId）。
     */
    suspend fun fetchCredentials(cookie: String): Map<String, String> {
        val sessionKey = extractSessionKey(cookie)

        if (sessionKey == null || sessionKey.isBlank()) {
            Log.e(TAG, "Missing sessionKey in cookie")
            return mapOf(
                "provider" to "anthropic",
                "error" to "Missing sessionKey"
            )
        }

        val orgId = fetchOrgId(sessionKey)

        return mapOf(
            "provider" to "anthropic",
            "sessionKey" to sessionKey,
            "orgId" to (orgId ?: ""),
            "baseUrl" to "https://api.anthropic.com/v1",
            "apiKey" to sessionKey  // For API_KEY field compatibility
        )
    }
}