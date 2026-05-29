/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.annotation.SuppressLint
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.WebTerminalBrowserActivityBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class WebTerminalBrowserActivity : AppCompatActivity() {
    private lateinit var binding: WebTerminalBrowserActivityBinding
    private var monitorJob: Job? = null
    private var terminalUrl: String? = null
    @Volatile
    private var blocked = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    override fun onResume() {
        super.onResume()
        monitorJob = lifecycleScope.launch {
            while (isActive) {
                setTerminalAllowed(isTerminalAllowed())
                delay(300)
            }
        }
    }

    override fun onPause() {
        monitorJob?.cancel()
        monitorJob = null
        lockTerminal(showToast = false)
        super.onPause()
    }

    override fun onDestroy() {
        binding.terminalWebview.stopLoading()
        binding.terminalWebview.loadUrl("about:blank")
        binding.terminalWebview.destroy()
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!blocked && binding.terminalWebview.canGoBack()) {
            binding.terminalWebview.goBack()
            return
        }
        super.onBackPressed()
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
                if (blocked || !isAllowedTerminalUrl(request.url)) {
                    lockTerminal(showToast = blocked.not())
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (blocked || !isAllowedTerminalUrl(request.url)) return blockedResponse()
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

    private fun reloadTerminal() {
        if (blocked) return
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

    private suspend fun isTerminalAllowed(): Boolean = withContext(Dispatchers.IO) {
        val tunnelUp = runCatching { Application.getBackend().runningTunnelNames.isNotEmpty() }.getOrDefault(false)
        tunnelUp && hasVpnNetwork()
    }

    private fun setTerminalAllowed(allowed: Boolean) {
        if (allowed) {
            blocked = false
            binding.terminalBlocker.visibility = android.view.View.GONE
            binding.terminalWebview.visibility = android.view.View.VISIBLE
            val url = terminalUrl
            if (!url.isNullOrBlank() && (binding.terminalWebview.url.isNullOrBlank() || binding.terminalWebview.url == "about:blank")) {
                binding.terminalWebview.loadUrl(url)
            }
        } else {
            lockTerminal(showToast = !blocked)
            binding.terminalWebview.visibility = android.view.View.GONE
            binding.terminalBlocker.visibility = android.view.View.VISIBLE
        }
    }

    private fun lockTerminal(showToast: Boolean) {
        if (showToast) {
            Toast.makeText(this, R.string.vcs_secure_browser_stopped, Toast.LENGTH_LONG).show()
        }
        if (!blocked) {
            binding.terminalWebview.stopLoading()
            binding.terminalWebview.loadUrl("about:blank")
        }
        blocked = true
    }

    private fun normalizeUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "http://$trimmed"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        if (!isAllowedTerminalUrl(uri)) return null
        return uri.toString()
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
        val normalized = host
            ?.trim()
            ?.lowercase()
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?: return false
        if (normalized == "localhost") return true
        if (!normalized.contains('.')) return true
        if (normalized.endsWith(".local") ||
            normalized.endsWith(".lan") ||
            normalized.endsWith(".internal") ||
            normalized.endsWith(".home") ||
            normalized.endsWith(".test") ||
            normalized.endsWith(".vcs")
        ) return true
        return isPrivateIpv4(normalized) || isPrivateIpv6(normalized)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.')
        if (octets.size != 4) return false
        val values = octets.map { it.toIntOrNull() ?: return false }
        if (values.any { it !in 0..255 }) return false
        val first = values[0]
        val second = values[1]
        return first == 10 ||
            first == 127 ||
            first == 169 && second == 254 ||
            first == 172 && second in 16..31 ||
            first == 192 && second == 168 ||
            first == 100 && second in 64..127
    }

    private fun isPrivateIpv6(host: String): Boolean {
        return host == "::1" ||
            host.startsWith("fc") ||
            host.startsWith("fd") ||
            host.startsWith("fe80:")
    }

    private fun hasVpnNetwork(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val activeNetwork = connectivityManager.activeNetwork
        val activeCaps = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        if (activeCaps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) return true

        return connectivityManager.allNetworks.any { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    companion object {
        const val EXTRA_INITIAL_URL = "com.wireguard.android.extra.WEB_TERMINAL_INITIAL_URL"
    }
}
