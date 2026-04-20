package com.lockit.ui.screens.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lockit.R
import com.lockit.ui.components.BrutalistButton
import com.lockit.ui.theme.JetBrainsMonoFamily
import com.lockit.ui.theme.Primary
import com.lockit.ui.theme.SurfaceHighest
import com.lockit.ui.theme.TacticalRed
import com.lockit.ui.theme.White
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * WebView authentication activity for extracting login credentials.
 *
 * Supports providers: qwen_bailian, chatgpt, claude
 *
 * Flow:
 * 1. User clicks "WebView 一键登录" button
 * 2. Opens WebView to provider login page
 * 3. User logs in manually
 * 4. Activity detects login success and extracts tokens/cookies
 * 5. Returns extracted credentials via Intent result
 */
class WebViewAuthActivity : Activity() {

    companion object {
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_CREDENTIAL_DATA = "credential_data"

        const val RESULT_SUCCESS = 1
        const val RESULT_FAILED = 2

        fun createIntent(context: Context, provider: String): Intent {
            return Intent(context, WebViewAuthActivity::class.java).apply {
                putExtra(EXTRA_PROVIDER, provider)
            }
        }
    }

    private var webView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "qwen_bailian"

        val rootView = FrameLayout(this)
        rootView.setBackgroundColor(android.graphics.Color.WHITE)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadsImagesAutomatically = true
            settings.blockNetworkLoads = false

            webViewClient = AuthWebViewClient(provider, this@WebViewAuthActivity)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    // Could show progress indicator here
                }
            }

            // Set initial URL based on provider
            val loginUrl = when (provider) {
                "qwen_bailian" -> "https://account.aliyun.com/login.htm"
                "chatgpt" -> "https://chatgpt.com/auth/login"
                "claude" -> "https://claude.ai/login"
                else -> "https://account.aliyun.com/login.htm"
            }
            loadUrl(loginUrl)
        }

        // Add close button overlay
        val closeButton = android.widget.Button(this).apply {
            text = "关闭"
            setBackgroundColor(android.graphics.Color.parseColor("#A30000"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                setResult(RESULT_CANCELED)
                finish()
            }
        }

        val closeLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.RIGHT
            setMargins(16, 16, 16, 16)
        }

        rootView.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        rootView.addView(closeButton, closeLayout)

        setContentView(rootView)
    }

    override fun onDestroy() {
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

/**
 * WebViewClient that monitors page loads and extracts auth tokens after login.
 */
class AuthWebViewClient(
    private val provider: String,
    private val activity: WebViewAuthActivity,
) : WebViewClient() {

    private var hasExtracted = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        hasExtracted = false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)

        if (hasExtracted) return

        // Check if user has logged in by URL pattern
        val isLoggedIn = when (provider) {
            "qwen_bailian" -> url?.contains("console.aliyun.com") == true
            "chatgpt" -> url?.contains("chatgpt.com") == true && !url.contains("auth/login")
            "claude" -> url?.contains("claude.ai") == true && !url.contains("login")
            else -> false
        }

        if (isLoggedIn) {
            hasExtracted = true
            extractCredentials(view, url)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        return false
    }

    private fun extractCredentials(view: WebView?, currentUrl: String?) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(currentUrl ?: "") ?: ""

        when (provider) {
            "qwen_bailian" -> extractQwenCredentials(cookies)
            "chatgpt" -> extractChatGPTCredentials(cookies, view)
            "claude" -> extractClaudeCredentials(cookies, view)
        }
    }

    private fun extractQwenCredentials(cookies: String) {
        // Extract sec_token from cookie
        val secToken = cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("sec_token=") }
            ?.substringAfter("sec_token=")
            ?.trim()

        if (secToken != null && secToken.isNotBlank()) {
            val resultData = mapOf(
                "provider" to "qwen_bailian",
                "cookie" to cookies,
                "sec_token" to secToken
            )
            returnResult(resultData)
        } else {
            returnFailed("凭证获取失败")
        }
    }

    private fun extractChatGPTCredentials(cookies: String, view: WebView?) {
        // Need to call /api/auth/session to get access_token and account_id
        view?.post {
            Thread {
                try {
                    val sessionUrl = URL("https://chatgpt.com/api/auth/session")
                    val conn = sessionUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Cookie", cookies)

                    if (conn.responseCode == 200) {
                        val response = conn.inputStream.bufferedReader().use { it.readText() }
                        conn.disconnect()

                        val json = JSONObject(response)
                        val accessToken = json.optString("accessToken", "")
                        val userObj = json.optJSONObject("user")
                        val accountId = userObj?.optString("id", "") ?: ""

                        if (accessToken.isNotBlank() && accountId.isNotBlank()) {
                            val resultData = mapOf(
                                "provider" to "chatgpt",
                                "accessToken" to accessToken,
                                "accountId" to accountId
                            )
                            activity.runOnUiThread { returnResult(resultData) }
                        } else {
                            activity.runOnUiThread { returnFailed("凭证获取失败") }
                        }
                    } else {
                        conn.disconnect()
                        activity.runOnUiThread { returnFailed("凭证获取失败") }
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread { returnFailed("获取失败: ${e.message}") }
                }
            }.start()
        }
    }

    private fun extractClaudeCredentials(cookies: String, view: WebView?) {
        // Extract sessionKey from cookie and fetch orgId via JS injection
        val sessionKey = cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("sessionKey=") }
            ?.substringAfter("sessionKey=")
            ?.trim()

        if (sessionKey != null && sessionKey.isNotBlank()) {
            // Use JS to get orgId from localStorage or page context
            view?.evaluateJavascript(
                "(function() { " +
                "try { " +
                "  const orgData = JSON.parse(localStorage.getItem('claude-organization') || '{}');" +
                "  return orgData.activeOrganization?.id || '';" +
                "} catch(e) { return ''; }" +
                "})();",
                { orgId ->
                    if (orgId.isNotBlank() && orgId != "null") {
                        val resultData = mapOf(
                            "provider" to "claude",
                            "sessionKey" to sessionKey,
                            "orgId" to orgId.trim('"')
                        )
                        returnResult(resultData)
                    } else {
                        // Fallback: try to get from /api/auth/me
                        fetchClaudeOrgIdFromApi(cookies, sessionKey)
                    }
                }
            )
        } else {
            returnFailed("凭证获取失败")
        }
    }

    private fun fetchClaudeOrgIdFromApi(cookies: String, sessionKey: String) {
        Thread {
            try {
                val authUrl = URL("https://claude.ai/api/auth/me")
                val conn = authUrl.openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 10000
                conn.setRequestProperty("Cookie", "sessionKey=$sessionKey")

                if (conn.responseCode == 200) {
                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(response)
                    val orgs = json.optJSONArray("organizations")
                    val orgId = orgs?.optJSONObject(0)?.optString("id", "") ?: ""

                    if (orgId.isNotBlank()) {
                        val resultData = mapOf(
                            "provider" to "claude",
                            "sessionKey" to sessionKey,
                            "orgId" to orgId
                        )
                        activity.runOnUiThread { returnResult(resultData) }
                    } else {
                        activity.runOnUiThread { returnFailed("凭证获取失败") }
                    }
                } else {
                    conn.disconnect()
                    activity.runOnUiThread { returnFailed("凭证获取失败") }
                }
            } catch (e: Exception) {
                activity.runOnUiThread { returnFailed("获取失败: ${e.message}") }
            }
        }.start()
    }

    private fun returnResult(data: Map<String, String>) {
        val intent = Intent().apply {
            putExtra(WebViewAuthActivity.EXTRA_CREDENTIAL_DATA, data.map { "${it.key}=${it.value}" }.joinToString(";"))
        }
        activity.setResult(WebViewAuthActivity.RESULT_SUCCESS, intent)
        activity.finish()
    }

    private fun returnFailed(message: String) {
        activity.setResult(WebViewAuthActivity.RESULT_FAILED)
        activity.finish()
    }
}