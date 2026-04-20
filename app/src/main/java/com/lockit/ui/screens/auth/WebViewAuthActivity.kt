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
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
 * 5. Returns extracted credentials via Intent result (JSON serialized)
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
    private val scope = CoroutineScope(Dispatchers.Main + Job())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "qwen_bailian"

        val rootView = FrameLayout(this)
        rootView.setBackgroundColor(android.graphics.Color.WHITE)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            // Override User-Agent to mimic Chrome browser, bypassing Google Safe Browsing
            // detection (disallowed_useragent error). WebView's default UA contains
            // "wv" and "Mobile Safari" which triggers Google's block.
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

            webViewClient = AuthWebViewClient(provider, this@WebViewAuthActivity, scope)
            webChromeClient = object : WebChromeClient() {}

            val loginUrl = when (provider) {
                "qwen_bailian" -> "https://account.aliyun.com/login.htm"
                "chatgpt" -> "https://chatgpt.com/auth/login"
                "claude" -> "https://claude.ai/login"
                else -> "https://account.aliyun.com/login.htm"
            }
            loadUrl(loginUrl)
        }

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

        // Apply window insets to position close button below status bar
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val density = view.context.resources.displayMetrics.density
            val marginPx = (16 * density).toInt()  // 16dp to pixels
            val marginTopPx = marginPx + statusBarHeight  // Add status bar height
            closeLayout.setMargins(marginPx, marginTopPx, marginPx, marginPx)
            closeButton.layoutParams = closeLayout
            insets
        }

        setContentView(rootView)
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        webView?.destroy()
        webView = null
        super.onDestroy()
    }
}

/**
 * WebViewClient that monitors page loads and extracts auth tokens after login.
 * Uses coroutines for async operations to avoid memory leaks.
 */
class AuthWebViewClient(
    private val provider: String,
    private val activity: WebViewAuthActivity,
    private val scope: CoroutineScope,
) : WebViewClient() {

    private var hasExtracted = false

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        hasExtracted = false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)

        if (hasExtracted) return

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
            "chatgpt" -> extractChatGPTCredentials(cookies)
            "claude" -> extractClaudeCredentials(cookies, view)
        }
    }

    private fun extractQwenCredentials(cookies: String) {
        val secToken = cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("sec_token=") }
            ?.substringAfter("sec_token=")
            ?.trim()

        if (secToken != null && secToken.isNotBlank()) {
            returnResult(mapOf(
                "provider" to "qwen_bailian",
                "cookie" to cookies,
                "sec_token" to secToken
            ))
        } else {
            returnFailed("凭证获取失败")
        }
    }

    private fun extractChatGPTCredentials(cookies: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val sessionUrl = URL("https://chatgpt.com/api/auth/session")
                    val conn = sessionUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Cookie", cookies)

                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        return@withContext null
                    }

                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(response)
                    val accessToken = json.optString("accessToken", "")
                    val userObj = json.optJSONObject("user")
                    val accountId = userObj?.optString("id", "") ?: ""

                    if (accessToken.isNotBlank() && accountId.isNotBlank()) {
                        mapOf(
                            "provider" to "chatgpt",
                            "accessToken" to accessToken,
                            "accountId" to accountId
                        )
                    } else null
                }
                if (result != null) {
                    returnResult(result)
                } else {
                    returnFailed("凭证获取失败")
                }
            } catch (e: Exception) {
                returnFailed("获取失败: ${e.message}")
            }
        }
    }

    private fun extractClaudeCredentials(cookies: String, view: WebView?) {
        val sessionKey = cookies.split(";")
            .map { it.trim() }
            .find { it.startsWith("sessionKey=") }
            ?.substringAfter("sessionKey=")
            ?.trim()

        if (sessionKey == null || sessionKey.isBlank()) {
            returnFailed("凭证获取失败")
            return
        }

        view?.evaluateJavascript(
            "(function() { " +
            "try { " +
            "  const orgData = JSON.parse(localStorage.getItem('claude-organization') || '{}');" +
            "  return orgData.activeOrganization?.id || '';" +
            "} catch(e) { return ''; }" +
            "})();",
            { orgId ->
                if (orgId.isNotBlank() && orgId != "null") {
                    returnResult(mapOf(
                        "provider" to "claude",
                        "sessionKey" to sessionKey,
                        "orgId" to orgId.trim('"')
                    ))
                } else {
                    fetchClaudeOrgIdFromApi(sessionKey)
                }
            }
        )
    }

    private fun fetchClaudeOrgIdFromApi(sessionKey: String) {
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val authUrl = URL("https://claude.ai/api/auth/me")
                    val conn = authUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("Cookie", "sessionKey=$sessionKey")

                    if (conn.responseCode != 200) {
                        conn.disconnect()
                        return@withContext null
                    }

                    val response = conn.inputStream.bufferedReader().use { it.readText() }
                    conn.disconnect()

                    val json = JSONObject(response)
                    val orgs = json.optJSONArray("organizations")
                    val orgId = orgs?.optJSONObject(0)?.optString("id", "") ?: ""

                    if (orgId.isNotBlank()) {
                        mapOf(
                            "provider" to "claude",
                            "sessionKey" to sessionKey,
                            "orgId" to orgId
                        )
                    } else null
                }
                if (result != null) {
                    returnResult(result)
                } else {
                    returnFailed("凭证获取失败")
                }
            } catch (e: Exception) {
                returnFailed("获取失败: ${e.message}")
            }
        }
    }

    /**
     * Return result as JSON string to avoid delimiter fragility.
     */
    private fun returnResult(data: Map<String, String>) {
        val json = JSONObject(data).toString()
        val intent = Intent().apply {
            putExtra(WebViewAuthActivity.EXTRA_CREDENTIAL_DATA, json)
        }
        activity.setResult(WebViewAuthActivity.RESULT_SUCCESS, intent)
        activity.finish()
    }

    private fun returnFailed(message: String) {
        activity.setResult(WebViewAuthActivity.RESULT_FAILED)
        activity.finish()
    }
}