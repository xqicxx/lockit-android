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
import com.lockit.domain.qwen.BailianAuthClient
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
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = AuthWebViewClient(provider, this@WebViewAuthActivity, scope)
            webChromeClient = object : WebChromeClient() {}

            val loginUrl = when (provider) {
                "qwen_bailian" -> "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan#/efm/coding-plan-detail"
                "chatgpt" -> "https://chatgpt.com/auth/login"
                "claude" -> "https://claude.ai/login"
                else -> "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan#/efm/coding-plan-detail"
            }
            loadUrl(loginUrl)
        }

        val backButton = android.widget.Button(this).apply {
            text = "返回"
            setBackgroundColor(android.graphics.Color.parseColor("#333333"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                webView?.goBack()
            }
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

        val resetButton = android.widget.Button(this).apply {
            text = "重新登录"
            setBackgroundColor(android.graphics.Color.parseColor("#666666"))
            setTextColor(android.graphics.Color.WHITE)
            setOnClickListener {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                webView?.clearCache(true)
                webView?.clearHistory()
                webView?.clearFormData()
                (webView?.webViewClient as? AuthWebViewClient)?.resetExtractionState()
                val loginUrl = when (provider) {
                    "qwen_bailian" -> "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan#/efm/coding-plan-detail"
                    "chatgpt" -> "https://chatgpt.com/auth/login"
                    "claude" -> "https://claude.ai/login"
                    else -> "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan#/efm/coding-plan-detail"
                }
                webView?.loadUrl(loginUrl)
                android.widget.Toast.makeText(this@WebViewAuthActivity, "已清除登录数据", android.widget.Toast.LENGTH_SHORT).show()
            }
        }

        // Button container - vertical layout on right side center
        val buttonContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(8, 8, 8, 8)
        }
        buttonContainer.addView(backButton, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) })
        buttonContainer.addView(resetButton, android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(0, 0, 0, 8) })
        buttonContainer.addView(closeButton)

        val buttonLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL
            setMargins(0, 0, 16, 0)
        }

        val webViewLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        rootView.addView(webView, webViewLayout)
        rootView.addView(buttonContainer, buttonLayout)

        // Apply window insets - WebView needs to avoid status bar and navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            webViewLayout.topMargin = statusBarHeight
            webViewLayout.bottomMargin = navigationBarHeight
            webView?.layoutParams = webViewLayout
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

    fun resetExtractionState() {
        hasExtracted = false
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Reset extraction state on any page navigation (allows retry for all providers)
        hasExtracted = false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)

        if (hasExtracted) return

        // Check if user has actually logged in by verifying cookies exist
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url ?: "") ?: ""

        val isLoggedIn = when (provider) {
            // Only consider logged in when we have valid cookies (not just page URL)
            "qwen_bailian" -> {
                url?.contains("console.aliyun.com") == true &&
                cookies.contains("aliyun_choice") || cookies.contains("login_aliyunid")
            }
            "chatgpt" -> url?.contains("chatgpt.com") == true && !url.contains("auth/login") && cookies.isNotBlank()
            "claude" -> url?.contains("claude.ai") == true && !url.contains("login") && cookies.contains("sessionKey")
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
            "qwen_bailian" -> extractQwenCredentials(cookies, view)
            "chatgpt" -> extractChatGPTCredentials(cookies)
            "claude" -> extractClaudeCredentials(cookies, view)
        }
    }

    private fun extractQwenCredentials(cookies: String, view: WebView?) {
        if (cookies.isBlank()) {
            hasExtracted = false
            android.widget.Toast.makeText(activity, "未获取到 cookie", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        scope.launch {
            android.widget.Toast.makeText(activity, "获取中...", android.widget.Toast.LENGTH_SHORT).show()
            val result = BailianAuthClient.fetchCredentials(cookies)
            android.widget.Toast.makeText(activity, "apiKey: ${result["apiKey"]}", android.widget.Toast.LENGTH_SHORT).show()
            returnResult(result)
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
                            "provider" to "openai",
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
                        "provider" to "anthropic",
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
                            "provider" to "anthropic",
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