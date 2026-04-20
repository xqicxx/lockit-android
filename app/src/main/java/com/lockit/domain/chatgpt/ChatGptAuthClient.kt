package com.lockit.domain.chatgpt

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * ChatGPT (OpenAI) 认证客户端。
 * 用于从 WebView cookie 中提取 access token。
 */
object ChatGptAuthClient {

    private const val TAG = "ChatGptAuth"

    /**
     * 从 cookie 获取所有凭据（accessToken + accountId）。
     */
    suspend fun fetchCredentials(cookie: String): Map<String, String> {
        return withContext(Dispatchers.IO) {
            try {
                val sessionUrl = URL("https://chatgpt.com/api/auth/session")
                val conn = sessionUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("Cookie", cookie)

                if (conn.responseCode != 200) {
                    conn.disconnect()
                    Log.e(TAG, "Session API failed: ${conn.responseCode}")
                    return@withContext mapOf(
                        "provider" to "openai",
                        "error" to "Session API failed"
                    )
                }

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                Log.d(TAG, "Session response: $response")

                val json = JSONObject(response)
                val accessToken = json.optString("accessToken", "")
                val userObj = json.optJSONObject("user")
                val accountId = userObj?.optString("id", "") ?: ""

                if (accessToken.isNotBlank() && accountId.isNotBlank()) {
                    mapOf(
                        "provider" to "openai",
                        "accessToken" to accessToken,
                        "accountId" to accountId,
                        "baseUrl" to "https://api.openai.com/v1"
                    )
                } else {
                    Log.e(TAG, "Missing accessToken or accountId")
                    mapOf(
                        "provider" to "openai",
                        "error" to "Missing credentials"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                mapOf(
                    "provider" to "openai",
                    "error" to (e.message ?: "Unknown error")
                )
            }
        }
    }
}