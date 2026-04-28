package com.lockit.ui.screens.auth

import androidx.activity.ComponentActivity
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.lockit.domain.qwen.BailianAuthClient
import com.lockit.domain.chatgpt.ChatGptAuthClient
import com.lockit.domain.claude.ClaudeAuthClient
import com.lockit.ui.components.DraggableFloatingButtons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

private val QWEN_BAILIAN_LOGIN_URLS = listOf(
    "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan#/efm/detail",
    "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan#/efm/index",
    "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan",
)

private fun loginUrlFor(provider: String): String {
    return when (provider) {
        "qwen_bailian" -> QWEN_BAILIAN_LOGIN_URLS.first()
        "chatgpt" -> "https://chatgpt.com/auth/login"
        "claude" -> "https://claude.ai/login"
        else -> QWEN_BAILIAN_LOGIN_URLS.first()
    }
}

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
class WebViewAuthActivity : ComponentActivity() {

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

    internal var webView: WebView? = null
    internal var authWebViewClient: AuthWebViewClient? = null
    private var oauthWebChromeClient: OAuthWebChromeClient? = null
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private var provider: String = "qwen_bailian"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        provider = intent.getStringExtra(EXTRA_PROVIDER) ?: "qwen_bailian"

        val rootView = FrameLayout(this)
        rootView.setBackgroundColor(android.graphics.Color.WHITE)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadsImagesAutomatically = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            // Enable popup windows for OAuth login (Google, Microsoft, Apple)
            settings.setSupportMultipleWindows(true)
            settings.javaScriptCanOpenWindowsAutomatically = true
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            val client = AuthWebViewClient(provider, this@WebViewAuthActivity, scope)
            authWebViewClient = client
            webViewClient = client
            // Handle popup windows (OAuth login)
            val chromeClient = OAuthWebChromeClient(provider, this@WebViewAuthActivity)
            oauthWebChromeClient = chromeClient
            webChromeClient = chromeClient

            // Clear stale cookies before loading login page.
            // If old cookies are present, ChatGPT/Claude auto-redirect away from
            // login, causing the poller to fire instantly — before the user logs in.
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()

            loadUrl(loginUrlFor(provider))
        }

        // Handle incoming callback intent from external apps (Alipay, etc.)
        // Called AFTER webView initialization to avoid NPE
        handleCallbackIntent(intent)

        // Glassmorphism floating buttons - draggable overlay
        val floatingButtons = DraggableFloatingButtons(
            onBack = { webView?.goBack() },
            onReset = {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                webView?.clearCache(true)
                webView?.clearHistory()
                webView?.clearFormData()
                authWebViewClient?.resetExtractionState()
                webView?.loadUrl(loginUrlFor(provider))
                android.widget.Toast.makeText(this@WebViewAuthActivity, "已清除登录数据", android.widget.Toast.LENGTH_SHORT).show()
            },
            onClose = {
                setResult(RESULT_CANCELED)
                finish()
            }
        ).createView(this)

        // MATCH_PARENT to allow full-screen drag
        val buttonLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        val webViewLayout = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )

        rootView.addView(webView, webViewLayout)
        rootView.addView(floatingButtons, buttonLayout)

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

    /**
     * Handle callback from external apps (Alipay, etc.) after authentication.
     * Called when activity receives new intent while already running (singleTask).
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallbackIntent(intent)
    }

    /**
     * Process callback URL from external authentication apps.
     * Alipay returns via alipays:// scheme with auth result.
     */
    private fun handleCallbackIntent(intent: Intent) {
        val data = intent.data
        if (data == null) return

        val callbackUrl = data.toString()
        android.util.Log.d("WebViewAuth", "Received callback: $callbackUrl")

        // Handle Alipay callback - load the result URL in WebView
        // Alipay typically returns: alipays://platformapi/startapp?appId=20000067&...
        // or HTTPS callback to account.aliyun.com
        when {
            callbackUrl.startsWith("alipays://") -> {
                // After Alipay auth, reload() would just re-trigger the login page
                // which redirects back to Alipay → infinite loop.
                // Instead, navigate directly to the provider console to detect login.
                authWebViewClient?.resetExtractionState()
                val postLoginUrl = when (provider) {
                    "qwen_bailian" -> "https://bailian.console.aliyun.com/cn-beijing/?tab=coding-plan"
                    "chatgpt" -> "https://chatgpt.com/"
                    "claude" -> "https://claude.ai/"
                    else -> loginUrlFor(provider)
                }
                webView?.loadUrl(postLoginUrl)
                android.widget.Toast.makeText(this, "支付宝认证完成，正在验证...", android.widget.Toast.LENGTH_SHORT).show()
            }
            else -> {
                // Security: Use strict host validation, not contains() which can be bypassed
                val host = data.host
                if (host == "account.aliyun.com" || host == "console.aliyun.com") {
                    // Direct HTTPS callback - load in WebView
                    authWebViewClient?.resetExtractionState()
                    webView?.loadUrl(callbackUrl)
                }
            }
        }
    }

    /**
     * When user returns from external app, trigger WebView to check login status.
     * Only reset if not already extracted (prevents duplicate extraction).
     */
    override fun onResume() {
        super.onResume()
        // Removed: unconditional reload disrupts UX when user returns from password manager
        // onNewIntent already handles deep link callbacks from external apps
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        oauthWebChromeClient?.cleanupAll()
        oauthWebChromeClient = null
        webView?.destroy()
        webView = null
        authWebViewClient = null
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
    private var cookieCheckJob: kotlinx.coroutines.Job? = null
    private var qwenFallbackIndex = 0

    fun resetExtractionState() {
        hasExtracted = false
        cookieCheckJob?.cancel()
        cookieCheckJob = null
        qwenFallbackIndex = 0
    }

    fun hasExtracted(): Boolean = hasExtracted

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        hasExtracted = false
        cookieCheckJob?.cancel()
        android.util.Log.d("WebViewAuth", "Page started: $url")
    }

    override fun onReceivedHttpError(
        view: WebView?,
        request: WebResourceRequest?,
        errorResponse: WebResourceResponse?,
    ) {
        super.onReceivedHttpError(view, request, errorResponse)

        val statusCode = errorResponse?.statusCode ?: return
        if (provider != "qwen_bailian" || request?.isForMainFrame != true || statusCode < 500) {
            return
        }

        tryLoadNextQwenFallback(view, "HTTP $statusCode")
    }

    /**
     * Handle network errors (timeout, connection failed, etc.)
     */
    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?) {
        super.onReceivedError(view, request, error)
        val url = request?.url?.toString() ?: ""
        val errorCode = error?.errorCode ?: -1
        val description = error?.description ?: "Unknown error"

        android.util.Log.e("WebViewAuth", "Error loading $url: $errorCode - $description")

        // Only show error for main page load (not subresources)
        if (request?.isForMainFrame == true) {
            val errorMessage = when (errorCode) {
                android.webkit.WebViewClient.ERROR_TIMEOUT -> "加载超时 - ChatGPT/Claude 需要代理，请确保系统 VPN 已开启"
                android.webkit.WebViewClient.ERROR_CONNECT -> "无法连接服务器 - 请检查网络或 VPN"
                android.webkit.WebViewClient.ERROR_HOST_LOOKUP -> "无法解析域名 - 请检查网络连接"
                -1 -> "网络错误: $description"
                else -> "加载失败 ($errorCode)"
            }
            android.widget.Toast.makeText(activity, errorMessage, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        android.util.Log.d("WebViewAuth", "Page finished: $url")

        if (provider == "qwen_bailian") {
            recoverQwenSystemErrorIfPresent(view)
        }

        if (hasExtracted) return

        // For SPA (Claude/ChatGPT), start polling for cookies
        // because onPageFinished may fire before auth state updates
        if (provider == "claude" || provider == "chatgpt") {
            startCookiePolling(view)
        } else {
            checkLoginAndExtract(view, url)
        }
    }

    private fun recoverQwenSystemErrorIfPresent(view: WebView?) {
        view ?: return
        view.postDelayed({
            view.evaluateJavascript(
                "(function(){return document.body ? document.body.innerText : '';})()"
            ) { pageText ->
                val hasSystemError = pageText.contains("500") &&
                    (pageText.contains("系统异常") || pageText.contains("\\u7cfb\\u7edf\\u5f02\\u5e38"))
                if (hasSystemError) {
                    tryLoadNextQwenFallback(view, "SPA system error")
                }
            }
        }, 1200)
    }

    private fun tryLoadNextQwenFallback(view: WebView?, reason: String) {
        qwenFallbackIndex += 1
        val fallbackUrl = QWEN_BAILIAN_LOGIN_URLS.getOrNull(qwenFallbackIndex)
        if (fallbackUrl == null) {
            android.util.Log.e("WebViewAuth", "Qwen login failed with $reason and no fallback left")
            android.widget.Toast.makeText(activity, "百炼页面系统异常，请稍后重试", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        android.util.Log.w("WebViewAuth", "Qwen login $reason, switching to fallback: $fallbackUrl")
        android.widget.Toast.makeText(activity, "百炼页面异常，正在切换备用入口", android.widget.Toast.LENGTH_SHORT).show()
        view?.post {
            resetExtractionState()
            qwenFallbackIndex = QWEN_BAILIAN_LOGIN_URLS.indexOf(fallbackUrl)
            view.loadUrl(fallbackUrl)
        }
    }

    /**
     * Poll cookies every 2 seconds for SPA login detection.
     * Claude/ChatGPT are SPA - onPageFinished fires before auth state updates.
     *
     * IMPORTANT: Must use CookieManager.getCookie() because HttpOnly cookies
     * (like sessionKey, __Secure-) cannot be read via document.cookie.
     */
    private fun startCookiePolling(view: WebView?) {
        cookieCheckJob?.cancel()
        cookieCheckJob = scope.launch {
            var attempts = 0
            val maxAttempts = 30 // 60 seconds max

            while (!hasExtracted && attempts < maxAttempts) {
                kotlinx.coroutines.delay(2000)
                attempts++

                android.util.Log.d("WebViewAuth", "Cookie poll attempt $attempts for $provider")

                // Use base domain URL for cookie retrieval - SPA redirects may change page URL
                // but cookies are set at domain level, not page level
                val baseDomainUrl = when (provider) {
                    "chatgpt" -> "https://chatgpt.com"
                    "claude" -> "https://claude.ai"
                    else -> ""  // Unknown provider - skip polling
                }
                if (baseDomainUrl.isEmpty()) return@launch  // Early exit for unknown providers

                val cookieManager = CookieManager.getInstance()
                val cookies = cookieManager.getCookie(baseDomainUrl) ?: ""

                // Don't log full cookies - security risk, only log length
                android.util.Log.d("WebViewAuth", "Cookie length: ${cookies.length}")

                // Check login status based on provider
                val currentUrl = view?.url ?: ""
                val isLoggedIn = when (provider) {
                    "claude" -> {
                        // Guard against stale sessionKey from previous session
                        !currentUrl.contains("/login") && cookies.contains("sessionKey=")
                    }
                    "chatgpt" -> {
                        !currentUrl.contains("/auth") &&
                        cookies.contains("__Secure-next-auth.session-token=")
                    }
                    else -> false
                }

                if (isLoggedIn) {
                    hasExtracted = true
                    android.util.Log.d("WebViewAuth", "Login detected after $attempts polls!")
                    // Use base domain URL for credential extraction too
                    extractCredentials(view, baseDomainUrl)
                    break
                }
            }

            if (!hasExtracted && attempts >= maxAttempts) {
                android.widget.Toast.makeText(
                    activity,
                    "登录检测超时，请刷新页面重试",
                    android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun checkLoginAndExtract(view: WebView?, url: String?) {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie(url ?: "") ?: ""

        val isLoggedIn = when (provider) {
            "qwen_bailian" -> {
                url?.contains("console.aliyun.com") == true &&
                cookies.contains("login_aliyunid_ticket")
            }
            else -> false
        }

        if (isLoggedIn) {
            hasExtracted = true
            extractCredentials(view, url)
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false

        // OAuth providers - must load in WebView (not external app)
        // These are HTTPS redirects, not app schemes
        val oauthDomains = listOf(
            "accounts.google.com",
            "login.microsoftonline.com",
            "appleid.apple.com",
            "auth.openai.com",
        )
        val isOAuthRedirect = oauthDomains.any { domain -> url.contains(domain) }
        if (isOAuthRedirect) {
            // Let WebView load OAuth page internally
            android.util.Log.d("WebViewAuth", "OAuth redirect: $url")
            return false  // Continue loading in WebView
        }

        // Handle external app schemes (Alipay, Taobao, DingTalk, Tmall, WeChat, SMS, Tel)
        if (url.startsWith("alipay://") ||
            url.startsWith("alipays://") ||
            url.startsWith("taobao://") ||
            url.startsWith("tmall://") ||
            url.startsWith("dingtalk://") ||
            url.startsWith("weixin://") ||
            url.startsWith("sms:") ||
            url.startsWith("tel:")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                activity.startActivity(intent)
                return true
            } catch (e: android.content.ActivityNotFoundException) {
                // External app not installed, continue loading in WebView
                android.widget.Toast.makeText(activity, "External app not installed", android.widget.Toast.LENGTH_SHORT).show()
                return false
            }
        }

        // Handle intent:// deeplink format (Android standard)
        if (url.startsWith("intent://")) {
            try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                activity.startActivity(intent)
                return true
            } catch (e: Exception) {
                // Fallback URL if app not installed
                val fallbackUrl = request?.url?.getQueryParameter("fallback_url")
                if (fallbackUrl != null) {
                    view?.loadUrl(fallbackUrl)
                    return true
                }
                return false
            }
        }

        // All other URLs load in WebView
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
            android.widget.Toast.makeText(activity, "获取中...", android.widget.Toast.LENGTH_SHORT).show()
            val result = ChatGptAuthClient.fetchCredentials(cookies)
            if (result.containsKey("error")) {
                returnFailed(result["error"] ?: "凭证获取失败")
            } else {
                returnResult(result)
            }
        }
    }

    private fun extractClaudeCredentials(cookies: String, view: WebView?) {
        scope.launch {
            android.widget.Toast.makeText(activity, "获取中...", android.widget.Toast.LENGTH_SHORT).show()
            val result = ClaudeAuthClient.fetchCredentials(cookies)
            if (result.containsKey("error")) {
                returnFailed(result["error"] ?: "凭证获取失败")
            } else {
                returnResult(result)
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

/**
 * Simple WebViewClient for OAuth popup windows.
 * Does NOT attempt credential extraction - popups are just for OAuth login.
 * The main WebView handles credential extraction after OAuth completes.
 */
class OAuthPopupWebViewClient(
    private val activity: WebViewAuthActivity,
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false

        // Handle external app schemes from OAuth popups (rare but possible)
        if (url.startsWith("alipay://") ||
            url.startsWith("alipays://") ||
            url.startsWith("weixin://") ||
            url.startsWith("sms:") ||
            url.startsWith("tel:")) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                activity.startActivity(intent)
                return true
            } catch (e: android.content.ActivityNotFoundException) {
                return false
            }
        }

        // All other URLs load in the popup WebView
        return false
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        android.util.Log.d("OAuthPopup", "Popup page finished: $url")
        // Do NOT extract credentials - this is just an OAuth popup
    }
}

/**
 * WebChromeClient that handles popup windows for OAuth login.
 * Google/Microsoft/Apple OAuth may open new windows for authentication.
 */
class OAuthWebChromeClient(
    private val provider: String,
    private val activity: WebViewAuthActivity,
) : WebChromeClient() {

    // Track popup WebViews for cleanup
    private val popupWebViews = mutableListOf<WebView>()

    override fun onCreateWindow(
        view: WebView?,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message?
    ): Boolean {
        android.util.Log.d("OAuthWebChrome", "onCreateWindow: isDialog=$isDialog, isUserGesture=$isUserGesture")

        // Validate resultMsg before proceeding
        if (resultMsg == null) {
            android.util.Log.e("OAuthWebChrome", "resultMsg is null, cannot create popup")
            return false
        }

        // Create a new WebView for the popup
        val popupWebView = WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
            // Popups should NOT create more popups to avoid infinite loops
            settings.setSupportMultipleWindows(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            // Critical: Enable third-party cookies for OAuth (Google, Microsoft, Apple)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            // Use simple client that does NOT extract credentials - popups are just for OAuth
            webViewClient = OAuthPopupWebViewClient(activity)
        }

        // Show the popup in a fullscreen dialog so the user can interact with it
        val dialog = android.app.Dialog(activity, android.R.style.Theme_NoTitleBar_Fullscreen)
        dialog.setContentView(popupWebView)
        dialog.show()
        // Store dialog reference for cleanup
        popupWebView.tag = dialog

        // Configure the WebView to load in the popup
        val transport = resultMsg.obj as? android.webkit.WebView.WebViewTransport
        if (transport != null) {
            transport.webView = popupWebView
            resultMsg.sendToTarget()
            popupWebViews.add(popupWebView)
            return true
        } else {
            android.util.Log.e("OAuthWebChrome", "Transport cast failed, cleaning up")
            dialog.dismiss()
            popupWebView.destroy()  // Prevent WebView leak
            return false
        }
    }

    override fun onCloseWindow(window: WebView?) {
        android.util.Log.d("OAuthWebChrome", "onCloseWindow")
        // Dismiss the dialog to prevent window leaks
        (window?.tag as? android.app.Dialog)?.dismiss()
        popupWebViews.remove(window)
        window?.destroy()

        // When popup closes, navigate to provider root so the SPA detects session
        // cookie and auto-redirects away from login page. reload() keeps the page
        // on /auth/login, which causes cookie polling's URL check to always fail.
        activity.authWebViewClient?.resetExtractionState()
        val rootUrl = when (provider) {
            "chatgpt" -> "https://chatgpt.com/"
            "claude" -> "https://claude.ai/"
            else -> "https://chatgpt.com/" // fallback, only chatgpt/claude use OAuth popups
        }
        activity.webView?.loadUrl(rootUrl)
    }

    /**
     * Cleanup all popups when activity destroys.
     * Dismiss dialogs and destroy WebViews to prevent memory/window leaks.
     */
    fun cleanupAll() {
        popupWebViews.forEach { webView ->
            (webView.tag as? android.app.Dialog)?.dismiss()
            webView.destroy()
        }
        popupWebViews.clear()
    }
}
