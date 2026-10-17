/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.WebTerminalBrowserActivityBinding
import com.wireguard.android.util.PrivateAddressClassifier
import com.wireguard.android.util.SecureBrowserUrlPolicy
import com.wireguard.android.vcs.VcsAuthGate
import com.wireguard.android.vcs.VcsManagedClient
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

class WebTerminalBrowserActivity : AppCompatActivity() {
    private lateinit var binding: WebTerminalBrowserActivityBinding
    private var terminalUrl: String? = null
    private var terminalFullscreen = false
    private var terminalOwnerDeviceId: String? = null
    private var sourceTerminalUrl: String? = null
    private var accessClosed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark("#05070A".toColorInt()),
            navigationBarStyle = SystemBarStyle.dark("#05070A".toColorInt()))
        super.onCreate(savedInstanceState)
        if (!VcsAuthGate.requireSignedIn(this)) return
        sourceTerminalUrl = intent.getStringExtra(EXTRA_INITIAL_URL)
        terminalOwnerDeviceId = VcsManagedClient.sessionInfo(this)?.deviceId
        if (!hasCurrentTerminalAccess()) { closeOutOfAccountTerminal(); return }
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        binding = WebTerminalBrowserActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // This host owns system-bar/cutout padding. The web page handles
            // the overlay keyboard; reserving it here would apply it twice.
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }

        terminalUrl = intent.getStringExtra(EXTRA_INITIAL_URL)
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeUrl(it) }
            ?.let { it.toUri().buildUpon().appendQueryParameter("vcsNative", "android").build().toString() }

        configureWebView(binding.terminalWebview)
        lifecycleScope.launch {
            VcsManagedClient.tunnelAccountChanges.collect {
                if (!hasCurrentTerminalAccess()) closeOutOfAccountTerminal()
            }
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (terminalFullscreen) {
                    setTerminalFullscreen(false)
                    binding.terminalWebview.evaluateJavascript("window.vcsExitFullscreen && window.vcsExitFullscreen();", null)
                    return
                }
                Log.i(TAG, "Back requested; canGoBack=${binding.terminalWebview.canGoBack()}")
                if (binding.terminalWebview.canGoBack()) {
                    binding.terminalWebview.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
        loadTerminal()
    }

    override fun onResume() {
        super.onResume()
        if (!VcsAuthGate.requireSignedIn(this) || !::binding.isInitialized) return
        if (!hasCurrentTerminalAccess()) { closeOutOfAccountTerminal(); return }
        if (binding.terminalWebview.url.isNullOrBlank() || binding.terminalWebview.url == "about:blank") loadTerminal()
    }

    private fun hasCurrentTerminalAccess(): Boolean = terminalOwnerDeviceId != null &&
        VcsManagedClient.sessionInfo(this)?.deviceId == terminalOwnerDeviceId &&
        VcsManagedClient.isCurrentWebTerminalUrl(this, sourceTerminalUrl)

    private fun closeOutOfAccountTerminal() {
        if (accessClosed) return
        accessClosed = true
        if (::binding.isInitialized) {
            binding.terminalWebview.stopLoading()
            binding.terminalWebview.loadUrl("about:blank")
        }
        Toast.makeText(this, R.string.web_terminal_current_account_required, Toast.LENGTH_LONG).show()
        finish()
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) {
            binding.terminalWebview.stopLoading()
            binding.terminalWebview.loadUrl("about:blank")
            binding.terminalWebview.destroy()
        }
        super.onDestroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            mediaPlaybackRequiresUserGesture = true
            textZoom = 100
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = false
            useWideViewPort = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
        webView.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, R.string.web_terminal_browser_blocked_detail, Toast.LENGTH_SHORT).show()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.i(TAG, "Terminal page loaded: $url")
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Log.e(TAG, "Terminal page failed: ${error.errorCode} ${error.description}; ${request.url}")
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (handleTerminalCommand(request.url)) return true
                if (!isAllowedTerminalUrl(request.url)) {
                    Toast.makeText(this@WebTerminalBrowserActivity, R.string.web_terminal_browser_blocked_detail, Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (!isAllowedTerminalUrl(request.url)) return blockedResponse()
                return null
            }

            override fun onReceivedHttpAuthRequest(view: WebView, handler: HttpAuthHandler, host: String, realm: String) {
                val credentials = credentialsForHost(host)
                if (credentials == null) {
                    handler.cancel()
                    return
                }
                handler.proceed(credentials.first, credentials.second)
            }
        }
    }

    private fun loadTerminal() {
        val url = terminalUrl
        if (url.isNullOrBlank()) {
            binding.terminalWebview.visibility = android.view.View.GONE
            binding.terminalBlocker.visibility = android.view.View.VISIBLE
            return
        }
        binding.terminalBlocker.visibility = android.view.View.GONE
        binding.terminalWebview.visibility = android.view.View.VISIBLE
        binding.terminalWebview.loadUrl(url)
    }

    private fun reloadTerminal() {
        val url = terminalUrl ?: return
        binding.terminalWebview.loadUrl(url)
    }

    private fun handleTerminalCommand(uri: Uri): Boolean {
        if (uri.scheme?.lowercase() != "vcs-terminal") return false
        when (uri.host?.lowercase()) {
            "reload" -> reloadTerminal()
            "close" -> finish()
            "fullscreen" -> setTerminalFullscreen(uri.getQueryParameter("enabled") == "1")
        }
        return true
    }

    private fun setTerminalFullscreen(enabled: Boolean) {
        terminalFullscreen = enabled
        WindowInsetsControllerCompat(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (enabled) hide(WindowInsetsCompat.Type.systemBars())
            else show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(binding.root)
    }

    private fun normalizeUrl(value: String): String? {
        return SecureBrowserUrlPolicy.normalizeHttpOrHttpsUrl(value, ::isAllowedTerminalUrl)
    }

    private fun credentialsForHost(host: String): Pair<String, String>? {
        val uri = runCatching { terminalUrl.orEmpty().toUri() }.getOrNull() ?: return null
        val uriHost = uri.host ?: return null
        if (!uriHost.equals(host, ignoreCase = true)) return null
        val userInfo = uri.encodedUserInfo ?: uri.userInfo ?: return null
        val parts = userInfo.split(":", limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return Uri.decode(parts[0]) to Uri.decode(parts[1])
    }

    private fun isAllowedTerminalUrl(uri: Uri): Boolean {
        return when (uri.scheme?.lowercase()) {
            "https" -> !uri.host.isNullOrBlank()
            "http" -> isPrivateHttpHost(uri.host)
            "wss" -> !uri.host.isNullOrBlank()
            "ws" -> isPrivateHttpHost(uri.host)
            "about", "data", "blob" -> true
            else -> false
        }
    }

    private fun isPrivateHttpHost(host: String?): Boolean {
        return PrivateAddressClassifier.isPrivateOrLocalHost(host)
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        private const val TAG = "VirtuWebTerminal"
        const val EXTRA_INITIAL_URL = "com.wireguard.android.extra.WEB_TERMINAL_INITIAL_URL"
    }
}
