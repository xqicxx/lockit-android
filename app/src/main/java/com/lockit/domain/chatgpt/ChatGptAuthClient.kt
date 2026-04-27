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

                Log.d(TAG, "Session response: OK (${response.length} chars)")

                val json = JSONObject(response)
                val accessToken = json.optString("accessToken", "")
                val accountId = fetchAccountId(accessToken).ifBlank { extractAccountId(json) }
                val accountEmail = extractAccountEmail(json)
                val loginMethod = extractLoginMethod(json)

                if (accessToken.isNotBlank()) {
                    mapOf(
                        "provider" to "chatgpt",
                        "accessToken" to accessToken,
                        "accountId" to accountId,
                        "accountEmail" to accountEmail,
                        "loginMethod" to loginMethod,
                        "baseUrl" to "https://chatgpt.com/backend-api/wham/usage",
                        "apiKey" to accessToken  // For API_KEY field compatibility
                    )
                } else {
                    Log.e(TAG, "Missing accessToken")
                    mapOf(
                        "provider" to "chatgpt",
                        "error" to "Missing credentials"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
                mapOf(
                    "provider" to "chatgpt",
                    "error" to (e.message ?: "Unknown error")
                )
            }
        }
    }

    private fun extractAccountId(json: JSONObject): String {
        val accountsArray = json.optJSONArray("accounts")
        if (accountsArray != null) {
            for (i in 0 until accountsArray.length()) {
                val account = accountsArray.optJSONObject(i) ?: continue
                val accountId = account.optString("account_id")
                    .ifBlank { account.optString("id") }
                    .ifBlank { account.optString("accountId") }
                if (accountId.isNotBlank()) return accountId
            }
        }

        val accountsObject = json.optJSONObject("accounts")
        if (accountsObject != null) {
            accountsObject.keys().forEach { key ->
                val account = accountsObject.optJSONObject(key)
                val accountId = account?.optString("account_id").orEmpty()
                    .ifBlank { account?.optString("id").orEmpty() }
                    .ifBlank { account?.optString("accountId").orEmpty() }
                    .ifBlank { key }
                if (accountId.isNotBlank()) return accountId
            }
        }

        return json.optString("account_id")
            .ifBlank { json.optString("accountId") }
    }

    private fun extractAccountEmail(json: JSONObject): String {
        val user = json.optJSONObject("user")
        return user?.optString("email").orEmpty()
            .ifBlank { json.optString("email") }
    }

    private fun extractLoginMethod(json: JSONObject): String {
        val user = json.optJSONObject("user")
        return user?.optString("auth_provider").orEmpty()
            .ifBlank { user?.optString("login_method").orEmpty() }
            .ifBlank { json.optString("authProvider") }
            .ifBlank { json.optString("loginMethod") }
    }

    private fun fetchAccountId(accessToken: String): String {
        return try {
            val url = URL("https://chatgpt.com/backend-api/accounts/check/v4-2023-04-27?timezone_offset_min=0")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            conn.setRequestProperty("User-Agent", "Lockit-Android")

            if (conn.responseCode != 200) {
                Log.w(TAG, "Account check failed: ${conn.responseCode}")
                conn.disconnect()
                return ""
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val accounts = JSONObject(response).optJSONObject("accounts") ?: return ""
            val defaultAccount = accounts.optJSONObject("default")
                ?.optJSONObject("account")
            defaultAccount?.optString("account_id", "") ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "Account check error: ${e.message}")
            ""
        }
    }
}
