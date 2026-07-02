/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.WebTerminalBrowserActivityBinding
import com.wireguard.android.util.PrivateAddressClassifier
import com.wireguard.android.util.SecureBrowserUrlPolicy
import com.wireguard.android.vcs.VcsAuthGate
import java.io.ByteArrayInputStream

class WebTerminalBrowserActivity : AppCompatActivity() {
    private lateinit var binding: WebTerminalBrowserActivityBinding
    private var terminalUrl: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VcsAuthGate.requireSignedIn(this)) return
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        binding = WebTerminalBrowserActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.ic_logo)
            title = "  ${getString(R.string.web_terminal_browser_title)}"
        }

        terminalUrl = intent.getStringExtra(EXTRA_INITIAL_URL)
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeUrl(it) }

        configureWebView(binding.terminalWebview)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
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
        if (binding.terminalWebview.url.isNullOrBlank() || binding.terminalWebview.url == "about:blank") loadTerminal()
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

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
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
        }
        return true
    }

    private fun normalizeUrl(value: String): String? {
        return SecureBrowserUrlPolicy.normalizeHttpOrHttpsUrl(value, ::isAllowedTerminalUrl)
    }

    private fun credentialsForHost(host: String): Pair<String, String>? {
        val uri = runCatching { Uri.parse(terminalUrl.orEmpty()) }.getOrNull() ?: return null
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
        const val EXTRA_INITIAL_URL = "com.wireguard.android.extra.WEB_TERMINAL_INITIAL_URL"
    }
}
