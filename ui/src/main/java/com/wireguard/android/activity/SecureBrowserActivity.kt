/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.HttpAuthHandler
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.databinding.SecureBrowserActivityBinding
import com.wireguard.android.util.SecureBrowserBlocker
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.util.VpnRouterAttestation
import com.wireguard.android.util.VpnRouterManager
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

class SecureBrowserActivity : AppCompatActivity() {
    private lateinit var binding: SecureBrowserActivityBinding
    private var monitorJob: Job? = null
    private var egressLookupJob: Job? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var navigationDragActive = false
    private var navigationDownRawX = 0f
    private var navigationDownRawY = 0f
    private var navigationStartX = 0f
    private var navigationStartY = 0f
    private var boundNetwork: Network? = null
    private var documentStartWebRtcProtection = false
    private var userInitiatedNavigation = false
    private var defaultUserAgent: String? = null
    private var desktopMode = false
    private var textZoom = 100
    private var findMatches = 0
    private var findActiveMatch = 0
    private var blockedTrackers = 0
    private var currentProtectionLabel: String? = null
    @Volatile
    private var blocked = true

    private val pairRouterResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents ?: return@registerForActivityResult
        val pairing = runCatching { VpnRouterAttestation.parsePairingUri(Uri.parse(qrCode)) }.getOrNull()
        if (pairing == null) {
            Toast.makeText(this, R.string.vcs_secure_browser_router_pair_error, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        confirmRouterPairing(pairing)
    }

    private data class BrowserProtection(
        val allowed: Boolean,
        val label: String,
        val detail: String? = null
    )

    private data class EgressIdentity(
        val ip: String,
        val country: String,
        val countryCode: String
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SecureBrowserActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.ic_logo)
            title = "  ${getString(R.string.vcs_secure_browser_title)}"
        }

        configureWebView(binding.secureWebview)
        configurePullToRefresh()
        configureBrowserTools()
        renderQuickLinks()
        configureMovableNavigation()
        updateNavigationButtons()
        binding.egressStatus.setText(R.string.vcs_secure_browser_egress_checking)
        binding.egressStatus.setOnClickListener { refreshEgressIdentityOnDemand() }
        updateSecurityBadges(null)
        binding.goButton.setOnClickListener { openTypedUrl() }
        binding.savePageButton.setOnClickListener { saveCurrentPage() }
        binding.browserBackButton.setOnClickListener { navigateBack() }
        binding.browserForwardButton.setOnClickListener { navigateForward() }
        binding.browserReloadButton.setOnClickListener { reloadPage() }
        binding.pairRouterButton.setOnClickListener { scanRouterPairingQr() }
        binding.pastePairRouterButton.setOnClickListener { showPasteRouterPairingDialog() }
        binding.forgetRoutersButton.setOnClickListener { confirmForgetRouters() }
        binding.openVpnSettingsButton.setOnClickListener { openVpnSettings() }
        binding.urlInput.setOnEditorActionListener { _, _, _ ->
            openTypedUrl()
            true
        }
        intent.getStringExtra(EXTRA_INITIAL_URL)?.takeIf { it.isNotBlank() }?.let {
            binding.urlInput.setText(it)
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        registerVpnNetworkCallback()
        monitorJob = lifecycleScope.launch { refreshBrowserProtection() }
    }

    override fun onPause() {
        monitorJob?.cancel()
        monitorJob = null
        egressLookupJob?.cancel()
        egressLookupJob = null
        unregisterVpnNetworkCallback()
        if (::binding.isInitialized) lockBrowser(showToast = false)
        clearEphemeralBrowserData()
        unbindBrowserNetwork()
        super.onPause()
    }

    override fun onDestroy() {
        egressLookupJob?.cancel()
        unregisterVpnNetworkCallback()
        unbindBrowserNetwork()
        if (::binding.isInitialized) {
            binding.secureWebview.stopLoading()
            binding.secureWebview.loadUrl("about:blank")
            clearEphemeralBrowserData()
            binding.secureWebview.destroy()
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
            setGeolocationEnabled(false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
        }
        defaultUserAgent = webView.settings.userAgentString
        textZoom = getPreferences(MODE_PRIVATE).getInt(PREF_TEXT_ZOOM, 100).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        desktopMode = getPreferences(MODE_PRIVATE).getBoolean(PREF_DESKTOP_MODE, false)
        applyTextZoom()
        applyDesktopMode(reload = false)
        installDocumentStartWebRtcProtection(webView)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                binding.pageProgress.progress = newProgress.coerceIn(0, 100)
                binding.pageProgress.visibility = if (newProgress in 1..99) View.VISIBLE else View.GONE
                if (newProgress >= 100) binding.browserRefresh.isRefreshing = false
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }
        }
        webView.setDownloadListener { _, _, _, _, _ ->
            Toast.makeText(this, R.string.vcs_secure_browser_blocked_detail, Toast.LENGTH_SHORT).show()
        }
        webView.setOnLongClickListener { showLinkMenuFromHitTest() }
        webView.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findActiveMatch = if (numberOfMatches > 0) activeMatchOrdinal + 1 else 0
            findMatches = numberOfMatches
            updateFindMatchLabel()
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (blocked || !isAllowedBrowserUrl(request.url, request.isForMainFrame)) {
                    lockBrowser(showToast = blocked.not())
                    return true
                }
                if (request.isForMainFrame) {
                    loadUrlWithPrivacyHeaders(request.url.toString())
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (blocked || !isAllowedBrowserUrl(request.url, request.isForMainFrame)) return blockedResponse()
                if (SecureBrowserBlocker.shouldBlock(request.url, request.isForMainFrame)) {
                    incrementBlockedTrackers()
                    return trackerBlockedResponse()
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                resetPageSecurityState(url)
                binding.pageProgress.progress = 5
                binding.pageProgress.visibility = View.VISIBLE
                if (!documentStartWebRtcProtection) injectWebRtcProtection(view)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.browserRefresh.isRefreshing = false
                binding.pageProgress.visibility = View.GONE
                if (!documentStartWebRtcProtection) injectWebRtcProtection(view)
                if (!url.isNullOrBlank() && url != "about:blank") {
                    binding.urlInput.setText(url)
                }
                updateSecurityBadges(url)
                updateNavigationButtons()
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

    private fun configureBrowserTools() {
        updateTextZoomLabel()
        updateDesktopModeButton()
        binding.findToggleButton.setOnClickListener { showFindBar() }
        binding.findCloseButton.setOnClickListener { hideFindBar() }
        binding.findPrevButton.setOnClickListener { findNext(backward = true) }
        binding.findNextButton.setOnClickListener { findNext(backward = false) }
        binding.findInput.addTextChangedListener { text ->
            val query = text?.toString().orEmpty()
            if (query.isBlank()) {
                clearFindMatches()
            } else {
                binding.secureWebview.findAllAsync(query)
            }
        }
        binding.findInput.setOnEditorActionListener { _, _, _ ->
            findNext(backward = false)
            true
        }
        binding.textZoomDownButton.setOnClickListener { changeTextZoom(-10) }
        binding.textZoomUpButton.setOnClickListener { changeTextZoom(10) }
        binding.desktopModeButton.setOnClickListener { toggleDesktopMode() }
    }

    private fun configurePullToRefresh() {
        binding.browserRefresh.setColorSchemeColors(getColor(android.R.color.holo_green_light))
        binding.browserRefresh.setOnRefreshListener {
            if (blocked || binding.secureWebview.url.isNullOrBlank() || binding.secureWebview.url == "about:blank") {
                binding.browserRefresh.isRefreshing = false
                return@setOnRefreshListener
            }
            binding.secureWebview.reload()
            updateNavigationButtons()
        }
    }

    private fun resetPageSecurityState(url: String?) {
        blockedTrackers = 0
        updateSecurityBadges(url)
    }

    private fun incrementBlockedTrackers() {
        blockedTrackers += 1
        runOnUiThread { updateSecurityBadges(binding.secureWebview.url) }
    }

    private fun updateSecurityBadges(url: String?) {
        val normalizedUrl = url?.takeIf { it.isNotBlank() && it != "about:blank" }
        val isHttps = normalizedUrl?.let { Uri.parse(it).scheme.equals("https", ignoreCase = true) } == true
        binding.httpsBadge.text = when {
            blocked -> getString(R.string.vcs_secure_browser_https_waiting)
            isHttps -> getString(R.string.vcs_secure_browser_https_locked)
            else -> getString(R.string.vcs_secure_browser_https_waiting)
        }
        binding.httpsBadge.setTextColor(getColor(if (isHttps && !blocked) android.R.color.holo_green_light else android.R.color.darker_gray))
        binding.trackerBadge.text = if (blockedTrackers == 0) {
            getString(R.string.vcs_secure_browser_trackers_blocked_zero)
        } else {
            getString(R.string.vcs_secure_browser_trackers_blocked_count, blockedTrackers)
        }
    }

    private fun showFindBar() {
        binding.findBar.visibility = View.VISIBLE
        binding.findInput.requestFocus()
        val query = binding.findInput.text?.toString().orEmpty()
        if (query.isNotBlank()) binding.secureWebview.findAllAsync(query)
    }

    private fun hideFindBar() {
        binding.findBar.visibility = View.GONE
        binding.findInput.setText("")
        clearFindMatches()
    }

    private fun findNext(backward: Boolean) {
        if (blocked || binding.findInput.text.isNullOrBlank()) return
        binding.secureWebview.findNext(backward)
    }

    private fun clearFindMatches() {
        findMatches = 0
        findActiveMatch = 0
        binding.secureWebview.clearMatches()
        updateFindMatchLabel()
    }

    private fun updateFindMatchLabel() {
        binding.findMatchLabel.text = if (findMatches > 0) {
            getString(R.string.vcs_secure_browser_find_matches, findActiveMatch, findMatches)
        } else {
            ""
        }
    }

    private fun changeTextZoom(delta: Int) {
        textZoom = (textZoom + delta).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        getPreferences(MODE_PRIVATE).edit().putInt(PREF_TEXT_ZOOM, textZoom).apply()
        applyTextZoom()
    }

    private fun applyTextZoom() {
        binding.secureWebview.settings.textZoom = textZoom
        updateTextZoomLabel()
    }

    private fun updateTextZoomLabel() {
        binding.textZoomLabel.text = getString(R.string.vcs_secure_browser_text_zoom_value, textZoom)
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        getPreferences(MODE_PRIVATE).edit().putBoolean(PREF_DESKTOP_MODE, desktopMode).apply()
        applyDesktopMode(reload = true)
    }

    private fun applyDesktopMode(reload: Boolean) {
        binding.secureWebview.settings.userAgentString = if (desktopMode) DESKTOP_USER_AGENT else defaultUserAgent
        binding.secureWebview.settings.useWideViewPort = desktopMode
        binding.secureWebview.settings.loadWithOverviewMode = desktopMode
        updateDesktopModeButton()
        if (reload && !blocked && !binding.secureWebview.url.isNullOrBlank() && binding.secureWebview.url != "about:blank") {
            binding.secureWebview.reload()
        }
    }

    private fun updateDesktopModeButton() {
        binding.desktopModeButton.text = getString(
            if (desktopMode) R.string.vcs_secure_browser_desktop_on else R.string.vcs_secure_browser_desktop_off
        )
        binding.desktopModeButton.alpha = if (desktopMode) 1f else 0.72f
    }

    private fun showLinkMenuFromHitTest(): Boolean {
        val url = binding.secureWebview.hitTestResult
            .extra
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return false
        if (!isAllowedBrowserUrl(Uri.parse(url), isTopLevel = true)) return false
        AlertDialog.Builder(this)
            .setTitle(url)
            .setItems(
                arrayOf(
                    getString(R.string.vcs_secure_browser_link_open),
                    getString(R.string.vcs_secure_browser_link_copy),
                    getString(R.string.vcs_secure_browser_link_share)
                )
            ) { _, which ->
                when (which) {
                    0 -> openLinkFromMenu(url)
                    1 -> copyLink(url)
                    2 -> shareLink(url)
                }
            }
            .show()
        return true
    }

    private fun openLinkFromMenu(url: String) {
        if (blocked) return
        loadUrlWithPrivacyHeaders(url)
    }

    private fun copyLink(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.vcs_secure_browser_link), url))
        Toast.makeText(this, R.string.vcs_secure_browser_link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareLink(url: String) {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, url)
        startActivity(Intent.createChooser(intent, getString(R.string.vcs_secure_browser_link_share)))
    }

    private fun scanRouterPairingQr() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.vcs_secure_browser_pair_router_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
        pairRouterResultLauncher.launch(options)
    }

    private fun showPasteRouterPairingDialog() {
        val input = EditText(this).apply {
            setSingleLine(false)
            minLines = 2
            hint = getString(R.string.vcs_secure_browser_paste_router_pair_hint)
            setText(clipboardText().orEmpty())
            setSelectAllOnFocus(true)
            setTextColor(Color.parseColor("#FFFFFF"))
            setHintTextColor(Color.parseColor("#8EA2AE"))
            setBackgroundResource(R.drawable.vcs_dialog_input_background)
            setPadding(28, 18, 28, 18)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.vcs_secure_browser_paste_router_pair_title)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.vcs_secure_browser_paste_router_pair_clipboard, null)
            .setPositiveButton(R.string.vcs_secure_browser_router_pair_action, null)
            .create()
        dialog.setOnShowListener {
            VcsDialogs.applyDefaultStyle(dialog)
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val text = clipboardText()
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SecureBrowserActivity, R.string.vcs_secure_browser_paste_router_pair_empty, Toast.LENGTH_LONG).show()
                } else {
                    input.setText(text)
                    input.selectAll()
                }
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val pairing = parsePairingKey(input.text?.toString().orEmpty())
                if (pairing == null) {
                    Toast.makeText(this@SecureBrowserActivity, R.string.vcs_secure_browser_router_pair_error, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                confirmRouterPairing(pairing)
            }
        }
        dialog.show()
    }

    private fun showStyledPlatformDialog(dialog: AlertDialog) {
        dialog.setOnShowListener { VcsDialogs.applyDefaultStyle(dialog) }
        dialog.show()
    }

    private fun clipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val item = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0) ?: return null
        return item.coerceToText(this)?.toString()?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parsePairingKey(value: String): VpnRouterAttestation.Pairing? {
        val trimmed = value.trim()
        val direct = runCatching { VpnRouterAttestation.parsePairingUri(Uri.parse(trimmed)) }.getOrNull()
        if (direct != null) return direct
        val hash = trimmed.substringAfter('#', missingDelimiterValue = "")
        if (hash.isNotBlank()) {
            val fromHash = runCatching {
                VpnRouterAttestation.parsePairingUri(Uri.parse("virtuvpn://router-pair?$hash"))
            }.getOrNull()
            if (fromHash != null) return fromHash
        }
        return null
    }

    private fun confirmRouterPairing(pairing: VpnRouterAttestation.Pairing) {
        showStyledPlatformDialog(AlertDialog.Builder(this)
            .setTitle(R.string.vcs_secure_browser_router_pair_title)
            .setMessage(getString(R.string.vcs_secure_browser_router_pair_confirm, pairing.routerId.take(8)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_secure_browser_router_pair_action) { _, _ ->
                VpnRouterAttestation.importPairing(this, pairing)
                Toast.makeText(this, R.string.vcs_secure_browser_router_pair_success, Toast.LENGTH_LONG).show()
                refreshBrowserProtectionAsync()
            }
            .create())
    }

    private fun confirmForgetRouters() {
        val count = VpnRouterAttestation.pairedRouters(this).size
        if (count == 0) {
            Toast.makeText(this, R.string.vcs_secure_browser_router_pair_none, Toast.LENGTH_SHORT).show()
            return
        }
        showStyledPlatformDialog(AlertDialog.Builder(this)
            .setTitle(R.string.vcs_secure_browser_router_forget_title)
            .setMessage(resources.getQuantityString(R.plurals.vcs_secure_browser_router_forget_confirm, count, count))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_secure_browser_router_forget_action) { _, _ ->
                VpnRouterAttestation.clearPairings(this)
                Toast.makeText(this, R.string.vcs_secure_browser_router_forget_success, Toast.LENGTH_LONG).show()
                refreshBrowserProtectionAsync()
            }
            .create())
    }

    private fun openVpnSettings() {
        startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    }

    private fun openTypedUrl() {
        val url = normalizeUrl(binding.urlInput.text?.toString().orEmpty())
        if (url == null) return
        val uri = Uri.parse(url)
        if (!isAllowedBrowserUrl(uri, isTopLevel = true)) {
            Toast.makeText(this, R.string.vcs_secure_browser_blocked_public_http, Toast.LENGTH_LONG).show()
            return
        }
        lifecycleScope.launch {
            val protection = resolveBrowserProtection()
            if (!protection.allowed) {
                setBrowserProtection(protection)
                Toast.makeText(this@SecureBrowserActivity, R.string.vcs_secure_browser_stopped, Toast.LENGTH_LONG).show()
                return@launch
            }
            setBrowserProtection(protection)
            userInitiatedNavigation = true
            loadUrlWithPrivacyHeaders(url)
            updateNavigationButtons()
        }
    }

    private fun navigateBack() {
        if (blocked || !binding.secureWebview.canGoBack()) return
        binding.secureWebview.goBack()
        updateNavigationButtons()
    }

    private fun navigateForward() {
        if (blocked || !binding.secureWebview.canGoForward()) return
        binding.secureWebview.goForward()
        updateNavigationButtons()
    }

    private fun reloadPage() {
        if (blocked || binding.secureWebview.url.isNullOrBlank() || binding.secureWebview.url == "about:blank") return
        binding.browserRefresh.isRefreshing = true
        binding.secureWebview.reload()
        updateNavigationButtons()
    }

    private fun updateNavigationButtons() {
        val canNavigate = !blocked
        binding.browserRefresh.isEnabled = canNavigate &&
            !binding.secureWebview.url.isNullOrBlank() &&
            binding.secureWebview.url != "about:blank"
        binding.browserBackButton.isEnabled = canNavigate && binding.secureWebview.canGoBack()
        binding.browserForwardButton.isEnabled = canNavigate && binding.secureWebview.canGoForward()
        binding.browserReloadButton.isEnabled = canNavigate &&
            !binding.secureWebview.url.isNullOrBlank() &&
            binding.secureWebview.url != "about:blank"
        binding.findToggleButton.isEnabled = canNavigate
        setButtonAlpha(binding.browserBackButton)
        setButtonAlpha(binding.browserForwardButton)
        setButtonAlpha(binding.browserReloadButton)
        setButtonAlpha(binding.findToggleButton)
    }

    private fun setButtonAlpha(view: View) {
        view.alpha = if (view.isEnabled) 1f else 0.42f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun configureMovableNavigation() {
        val dragTouchListener = View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    navigationDownRawX = event.rawX
                    navigationDownRawY = event.rawY
                    navigationStartX = binding.browserNavigationPad.x
                    navigationStartY = binding.browserNavigationPad.y
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!navigationDragActive) return@OnTouchListener false
                    moveNavigationPad(
                        navigationStartX + event.rawX - navigationDownRawX,
                        navigationStartY + event.rawY - navigationDownRawY
                    )
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!navigationDragActive) return@OnTouchListener false
                    navigationDragActive = false
                    binding.browserViewContainer.requestDisallowInterceptTouchEvent(false)
                    saveNavigationPadPosition()
                    true
                }
                else -> false
            }
        }
        val dragLongClickListener = View.OnLongClickListener {
            navigationDragActive = true
            binding.browserViewContainer.requestDisallowInterceptTouchEvent(true)
            true
        }
        listOf(
            binding.browserNavigationPad,
            binding.browserReloadButton,
            binding.browserBackButton,
            binding.browserForwardButton
        ).forEach { view ->
            view.setOnTouchListener(dragTouchListener)
            view.setOnLongClickListener(dragLongClickListener)
        }

        binding.browserNavigationPad.post { restoreNavigationPadPosition() }
    }

    private fun moveNavigationPad(targetX: Float, targetY: Float) {
        val parent = binding.browserViewContainer
        val pad = binding.browserNavigationPad
        val maxX = (parent.width - pad.width).coerceAtLeast(0).toFloat()
        val maxY = (parent.height - pad.height).coerceAtLeast(0).toFloat()
        pad.x = targetX.coerceIn(0f, maxX)
        pad.y = targetY.coerceIn(0f, maxY)
    }

    private fun saveNavigationPadPosition() {
        getPreferences(MODE_PRIVATE)
            .edit()
            .putFloat(PREF_NAVIGATION_PAD_X, binding.browserNavigationPad.x)
            .putFloat(PREF_NAVIGATION_PAD_Y, binding.browserNavigationPad.y)
            .apply()
    }

    private fun restoreNavigationPadPosition() {
        val preferences = getPreferences(MODE_PRIVATE)
        if (!preferences.contains(PREF_NAVIGATION_PAD_X) || !preferences.contains(PREF_NAVIGATION_PAD_Y)) return
        moveNavigationPad(
            preferences.getFloat(PREF_NAVIGATION_PAD_X, binding.browserNavigationPad.x),
            preferences.getFloat(PREF_NAVIGATION_PAD_Y, binding.browserNavigationPad.y)
        )
    }

    private fun normalizeUrl(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        val uri = Uri.parse(withScheme)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") return null
        if (uri.host.isNullOrBlank()) return null
        return uri.toString()
    }

    private fun credentialsForHost(host: String): Pair<String, String>? {
        val candidates = listOf(
            binding.urlInput.text?.toString().orEmpty(),
            binding.secureWebview.originalUrl.orEmpty(),
            binding.secureWebview.url.orEmpty(),
            intent.getStringExtra(EXTRA_INITIAL_URL).orEmpty()
        )
        for (candidate in candidates) {
            val uri = runCatching { Uri.parse(candidate) }.getOrNull() ?: continue
            val uriHost = uri.host ?: continue
            if (!uriHost.equals(host, ignoreCase = true)) continue
            val userInfo = uri.encodedUserInfo ?: uri.userInfo ?: continue
            val parts = userInfo.split(":", limit = 2)
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) continue
            return Uri.decode(parts[0]) to Uri.decode(parts[1])
        }
        return null
    }

    private fun renderQuickLinks() {
        binding.quickLinksRow.removeAllViews()
        bookmarkedUrls().forEach { url ->
            binding.quickLinksRow.addView(quickLinkButton(bookmarkLabel(url), url))
        }
    }

    private fun quickLinkButton(label: String, url: String): TextView {
        return TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).also {
                it.marginEnd = dp(8)
            }
            background = getDrawable(R.drawable.fastest_button_background)
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER
            minWidth = dp(96)
            setPadding(dp(16), 0, dp(16), 0)
            text = label
            setTextColor(getColor(android.R.color.white))
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener {
                binding.urlInput.setText(url)
                openTypedUrl()
            }
            setOnLongClickListener {
                confirmDeleteBookmark(url)
                true
            }
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun saveCurrentPage() {
        val currentValue = binding.secureWebview.url?.takeUnless { it == "about:blank" }
            ?: binding.urlInput.text?.toString().orEmpty()
        val url = normalizeUrl(currentValue)
        if (url == null) return
        val uri = Uri.parse(url)
        if (!isAllowedBrowserUrl(uri, isTopLevel = true)) {
            Toast.makeText(this, R.string.vcs_secure_browser_blocked_public_http, Toast.LENGTH_LONG).show()
            return
        }

        val urls = bookmarkedUrls().toMutableList()
        if (urls.any { it.equals(url, ignoreCase = true) }) {
            Toast.makeText(this, R.string.vcs_secure_browser_bookmark_exists, Toast.LENGTH_SHORT).show()
            return
        }
        urls.add(url)
        saveBookmarkedUrls(urls)
        renderQuickLinks()
        Toast.makeText(this, R.string.vcs_secure_browser_bookmark_saved, Toast.LENGTH_SHORT).show()
    }

    private fun confirmDeleteBookmark(url: String) {
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_secure_browser_delete_bookmark_title),
            message = bookmarkLabel(url),
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.delete, primary = true) { deleteBookmark(url) }
        )
    }

    private fun deleteBookmark(url: String) {
        val hiddenDefaults = hiddenDefaultBookmarks().toMutableSet()
        if (DEFAULT_BOOKMARKS.any { it.equals(url, ignoreCase = true) }) {
            hiddenDefaults.add(url.lowercase())
        }
        val urls = bookmarkedUrls()
            .filterNot { it.equals(url, ignoreCase = true) }
        saveBookmarkedUrls(urls, hiddenDefaults)
        renderQuickLinks()
        Toast.makeText(this, R.string.vcs_secure_browser_bookmark_deleted, Toast.LENGTH_SHORT).show()
    }

    private fun bookmarkedUrls(): List<String> {
        val stored = getPreferences(MODE_PRIVATE).getString(PREF_BOOKMARKS, null)
        val saved = if (stored.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                val array = JSONArray(stored)
                (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
            }.getOrDefault(emptyList())
        }
        val hiddenDefaults = hiddenDefaultBookmarks()
        val defaults = DEFAULT_BOOKMARKS.filterNot { hiddenDefaults.contains(it.lowercase()) }
        return (defaults + saved).distinctBy { it.lowercase() }
    }

    private fun saveBookmarkedUrls(urls: List<String>, hiddenDefaults: Set<String> = hiddenDefaultBookmarks()) {
        val customUrls = urls.filterNot { url ->
            DEFAULT_BOOKMARKS.any { it.equals(url, ignoreCase = true) }
        }
        getPreferences(MODE_PRIVATE)
            .edit()
            .putString(PREF_BOOKMARKS, JSONArray(customUrls).toString())
            .putString(PREF_HIDDEN_DEFAULT_BOOKMARKS, JSONArray(hiddenDefaults.toList()).toString())
            .apply()
    }

    private fun hiddenDefaultBookmarks(): Set<String> {
        val stored = getPreferences(MODE_PRIVATE).getString(PREF_HIDDEN_DEFAULT_BOOKMARKS, null)
        if (stored.isNullOrBlank()) return emptySet()
        return runCatching {
            val array = JSONArray(stored)
            (0 until array.length()).mapNotNull { index ->
                array.optString(index).takeIf { it.isNotBlank() }?.lowercase()
            }.toSet()
        }.getOrDefault(emptySet())
    }

    private fun bookmarkLabel(url: String): String {
        val uri = Uri.parse(url)
        if (url.equals(GOOGLE_URL, ignoreCase = true)) return getString(R.string.vcs_secure_browser_bookmark_google)
        return uri.host?.removePrefix("www.") ?: url
    }

    private suspend fun resolveBrowserProtection(): BrowserProtection = withContext(Dispatchers.IO) {
        if (bindToVpnNetwork()) {
            return@withContext BrowserProtection(true, vpnProtectionLabel())
        }
        unbindBrowserNetwork()
        val routerStatus = runCatching { VpnRouterManager.getStatus(this@SecureBrowserActivity) }.getOrNull()
        if (routerStatus?.availability == VpnRouterManager.Availability.ENABLED) {
            val tunnel = routerStatus.activeTunnel ?: getString(R.string.vcs_vpn_status_no_tunnel)
            return@withContext BrowserProtection(true, getString(R.string.vcs_secure_browser_egress_router, tunnel))
        }
        val attestation = runCatching { VpnRouterAttestation.verifyFromCurrentGatewayDetailed(this@SecureBrowserActivity) }.getOrNull()
        if (attestation?.result != null) {
            return@withContext BrowserProtection(
                true,
                getString(R.string.vcs_secure_browser_egress_router_attested)
            )
        }
        val detail = when (attestation?.failureReason) {
            VpnRouterAttestation.FailureReason.CLOCK_SKEW -> getString(R.string.vcs_secure_browser_blocked_clock_skew)
            VpnRouterAttestation.FailureReason.EXPIRED_PAIRING -> getString(R.string.vcs_secure_browser_blocked_pairing_expired)
            else -> getString(R.string.vcs_secure_browser_blocked_detail)
        }
        BrowserProtection(false, getString(R.string.vcs_secure_browser_egress_blocked), detail)
    }

    private suspend fun vpnProtectionLabel(): String {
        val backend = Application.getBackend()
        val tunnels = if (backend is WgQuickBackend) {
            runCatching { backend.runningTunnelNames.toList().sorted() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        return if (tunnels.isNotEmpty()) {
            getString(R.string.vcs_secure_browser_egress_virtu, tunnels.joinToString(", "))
        } else {
            getString(R.string.vcs_secure_browser_egress_vpn)
        }
    }

    private fun registerVpnNetworkCallback() {
        if (vpnNetworkCallback != null) return
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                refreshBrowserProtectionAsync()
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    lockBrowserFromNetworkCallback()
                } else {
                    refreshBrowserProtectionAsync()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (!networkCapabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                ) {
                    if (network == boundNetwork) lockBrowserFromNetworkCallback()
                } else {
                    refreshBrowserProtectionAsync()
                }
            }

            override fun onUnavailable() {
                lockBrowserFromNetworkCallback()
            }
        }
        vpnNetworkCallback = callback
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        runCatching { connectivityManager.registerNetworkCallback(request, callback) }
            .onFailure {
                vpnNetworkCallback = null
                lockBrowserFromNetworkCallback()
            }
    }

    private fun unregisterVpnNetworkCallback() {
        val callback = vpnNetworkCallback ?: return
        getSystemService(ConnectivityManager::class.java)?.let { connectivityManager ->
            runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        }
        vpnNetworkCallback = null
    }

    private fun refreshBrowserProtectionAsync() {
        lifecycleScope.launch { refreshBrowserProtection() }
    }

    private suspend fun refreshBrowserProtection() {
        setBrowserProtection(resolveBrowserProtection())
    }

    private fun lockBrowserFromNetworkCallback() {
        lifecycleScope.launch {
            unbindBrowserNetwork()
            if (::binding.isInitialized) {
                setBrowserProtection(BrowserProtection(false, getString(R.string.vcs_secure_browser_egress_blocked)))
            }
        }
    }

    private fun setBrowserProtection(protection: BrowserProtection) {
        currentProtectionLabel = protection.label
        egressLookupJob?.cancel()
        binding.egressStatus.text = if (protection.allowed) {
            getString(R.string.vcs_secure_browser_egress_tap_to_check, protection.label)
        } else {
            protection.label
        }
        binding.egressStatus.setTextColor(getColor(if (protection.allowed) android.R.color.holo_green_light else android.R.color.holo_red_light))
        if (protection.allowed) {
            blocked = false
            binding.vpnBlocker.visibility = View.GONE
            binding.secureWebview.visibility = View.VISIBLE
            val initialUrl = binding.urlInput.text?.toString().orEmpty()
            if (userInitiatedNavigation &&
                (binding.secureWebview.url.isNullOrBlank() || binding.secureWebview.url == "about:blank")
            ) {
                normalizeUrl(initialUrl)?.takeIf { isAllowedBrowserUrl(Uri.parse(it), isTopLevel = true) }?.let {
                    loadUrlWithPrivacyHeaders(it)
                }
            }
        } else {
            egressLookupJob?.cancel()
            lockBrowser(showToast = !blocked)
            binding.secureWebview.visibility = View.GONE
            binding.vpnBlocker.visibility = View.VISIBLE
            binding.blockerDetail.text = protection.detail ?: getString(R.string.vcs_secure_browser_blocked_detail)
        }
        updateNavigationButtons()
    }

    private fun refreshEgressIdentityOnDemand() {
        if (blocked) return
        val baseLabel = currentProtectionLabel ?: return
        egressLookupJob?.cancel()
        binding.egressStatus.text = getString(R.string.vcs_secure_browser_egress_checking)
        egressLookupJob = lifecycleScope.launch {
            val identity = withContext(Dispatchers.IO) { fetchEgressIdentity() }
            if (identity != null && ::binding.isInitialized && !blocked) {
                binding.egressStatus.text = getString(
                    R.string.vcs_secure_browser_egress_with_country,
                    baseLabel,
                    listOf(flagEmoji(identity.countryCode), identity.country).filter { it.isNotBlank() }.joinToString(" "),
                    identity.ip
                )
            } else if (::binding.isInitialized && !blocked) {
                binding.egressStatus.text = getString(R.string.vcs_secure_browser_egress_tap_to_check, baseLabel)
            }
        }
    }

    private fun fetchEgressIdentity(): EgressIdentity? {
        val ip = fetchText("https://ipv4.icanhazip.com")
            ?: fetchText("https://ipv6.icanhazip.com")
            ?: return null
        if (!isValidIpLiteral(ip)) return null
        val geo = fetchText("https://ipwho.is/${Uri.encode(ip)}") ?: return EgressIdentity(ip, "", "")
        return runCatching {
            val json = JSONObject(geo)
            if (!json.optBoolean("success", false)) return@runCatching EgressIdentity(ip, "", "")
            EgressIdentity(
                ip = ip,
                country = json.optString("country"),
                countryCode = json.optString("country_code")
            )
        }.getOrDefault(EgressIdentity(ip, "", ""))
    }

    private fun isValidIpLiteral(value: String): Boolean {
        if (value.any { it.isWhitespace() || it == '/' || it == '?' || it == '#' }) return false
        val parts = value.split('.')
        if (parts.size == 4 && parts.all { part -> part.toIntOrNull()?.let { it in 0..255 } == true }) return true
        return ':' in value && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
    }

    private fun fetchText(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "VirtuVPN")
        }
        conn.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (_: Throwable) {
        null
    }

    private fun flagEmoji(iso2: String): String {
        if (iso2.length != 2) return ""
        val cc = iso2.uppercase()
        if (!cc.all { it in 'A'..'Z' }) return ""
        val base = 0x1F1E6
        return String(Character.toChars(base + (cc[0] - 'A'))) + String(Character.toChars(base + (cc[1] - 'A')))
    }

    private fun lockBrowser(showToast: Boolean) {
        if (showToast) {
            Toast.makeText(this, R.string.vcs_secure_browser_stopped, Toast.LENGTH_LONG).show()
        }
        if (!blocked) {
            stopBrowser()
        }
        blocked = true
    }

    private fun stopBrowser() {
        binding.browserRefresh.isRefreshing = false
        binding.pageProgress.visibility = View.GONE
        clearFindMatches()
        resetPageSecurityState(null)
        binding.secureWebview.stopLoading()
        binding.secureWebview.loadUrl("about:blank")
    }

    private fun loadUrlWithPrivacyHeaders(url: String) {
        binding.secureWebview.loadUrl(url, PRIVACY_REQUEST_HEADERS)
    }

    private fun clearEphemeralBrowserData() {
        if (!::binding.isInitialized) return
        binding.secureWebview.clearHistory()
        binding.secureWebview.clearCache(true)
        binding.secureWebview.clearFormData()
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    private fun blockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))).apply {
            responseHeaders = PRIVACY_RESPONSE_HEADERS
        }

    private fun trackerBlockedResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))).apply {
            responseHeaders = PRIVACY_RESPONSE_HEADERS + mapOf("Cache-Control" to "no-store")
        }

    private fun installDocumentStartWebRtcProtection(webView: WebView) {
        documentStartWebRtcProtection = if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            runCatching {
                WebViewCompat.addDocumentStartJavaScript(webView, WEBRTC_PROTECTION_SCRIPT, setOf("*"))
                true
            }.getOrDefault(false)
        } else {
            false
        }
    }

    private fun injectWebRtcProtection(webView: WebView) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) return
        webView.evaluateJavascript(WEBRTC_PROTECTION_SCRIPT, null)
    }

    private fun bindToVpnNetwork(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val vpnNetwork = findVpnNetwork(connectivityManager) ?: return false
        if (boundNetwork == vpnNetwork) return true
        val bound = connectivityManager.bindProcessToNetwork(vpnNetwork)
        if (bound) boundNetwork = vpnNetwork
        return bound
    }

    private fun findVpnNetwork(connectivityManager: ConnectivityManager): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork?.let { network -> isUsableVpnNetwork(connectivityManager, network) } == true) {
            return activeNetwork
        }
        return connectivityManager.allNetworks.firstOrNull { network ->
            isUsableVpnNetwork(connectivityManager, network)
        }
    }

    private fun isUsableVpnNetwork(connectivityManager: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun unbindBrowserNetwork() {
        if (boundNetwork == null) return
        getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        boundNetwork = null
    }

    private fun isAllowedBrowserUrl(uri: Uri, isTopLevel: Boolean): Boolean {
        return when (uri.scheme?.lowercase()) {
            "https" -> !uri.host.isNullOrBlank() && (isTopLevel || !isPrivateHttpHost(uri.host))
            "wss" -> !uri.host.isNullOrBlank() && (isTopLevel || !isPrivateHttpHost(uri.host))
            "about", "blob" -> true
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

    companion object {
        private const val PREF_BOOKMARKS = "secure_browser_bookmarks"
        private const val PREF_HIDDEN_DEFAULT_BOOKMARKS = "secure_browser_hidden_default_bookmarks"
        private const val PREF_NAVIGATION_PAD_X = "secure_browser_navigation_pad_x"
        private const val PREF_NAVIGATION_PAD_Y = "secure_browser_navigation_pad_y"
        private const val PREF_TEXT_ZOOM = "secure_browser_text_zoom"
        private const val PREF_DESKTOP_MODE = "secure_browser_desktop_mode"
        private const val MIN_TEXT_ZOOM = 70
        private const val MAX_TEXT_ZOOM = 160
        const val EXTRA_INITIAL_URL = "com.wireguard.android.extra.SECURE_BROWSER_INITIAL_URL"
        private const val GOOGLE_URL = "https://www.google.com/"
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
        private val PRIVACY_REQUEST_HEADERS = mapOf(
            "DNT" to "1",
            "Sec-GPC" to "1"
        )
        private val PRIVACY_RESPONSE_HEADERS = mapOf(
            "Referrer-Policy" to "no-referrer",
            "Permissions-Policy" to "interest-cohort=()",
            "Sec-GPC" to "1"
        )
        private val DEFAULT_BOOKMARKS = listOf(GOOGLE_URL)
        private const val WEBRTC_PROTECTION_SCRIPT = """
            (function() {
              if (window.__virtuvpnWebRtcProtection) return;
              Object.defineProperty(window, '__virtuvpnWebRtcProtection', { value: true, configurable: false });
              try { Object.defineProperty(navigator, 'globalPrivacyControl', { value: true, configurable: true }); } catch (e) {}
              try { Object.defineProperty(navigator, 'doNotTrack', { value: '1', configurable: true }); } catch (e) {}
              try { Object.defineProperty(window, 'doNotTrack', { value: '1', configurable: true }); } catch (e) {}
              try { Object.defineProperty(navigator, 'msDoNotTrack', { value: '1', configurable: true }); } catch (e) {}
              var installReferrerPolicy = function() {
                try {
                  if (!document.head || document.querySelector('meta[name="referrer"][data-virtuvpn="true"]')) return;
                  var meta = document.createElement('meta');
                  meta.name = 'referrer';
                  meta.content = 'no-referrer';
                  meta.setAttribute('data-virtuvpn', 'true');
                  document.head.prepend(meta);
                } catch (e) {}
              };
              installReferrerPolicy();
              try { document.addEventListener('DOMContentLoaded', installReferrerPolicy, { once: true }); } catch (e) {}
              var blocked = function() {
                throw new DOMException('WebRTC is disabled by VirtuVPN Secure Browser', 'SecurityError');
              };
              var rejected = function() {
                return Promise.reject(new DOMException('WebRTC is disabled by VirtuVPN Secure Browser', 'SecurityError'));
              };
              try { Object.defineProperty(window, 'RTCPeerConnection', { value: blocked, configurable: false }); } catch (e) { window.RTCPeerConnection = blocked; }
              try { Object.defineProperty(window, 'webkitRTCPeerConnection', { value: blocked, configurable: false }); } catch (e) { window.webkitRTCPeerConnection = blocked; }
              try { Object.defineProperty(window, 'mozRTCPeerConnection', { value: blocked, configurable: false }); } catch (e) { window.mozRTCPeerConnection = blocked; }
              if (navigator.mediaDevices) {
                try { navigator.mediaDevices.getUserMedia = rejected; } catch (e) {}
                try { navigator.mediaDevices.enumerateDevices = function() { return Promise.resolve([]); }; } catch (e) {}
              }
              try { navigator.getUserMedia = function() {}; } catch (e) {}
              try { navigator.webkitGetUserMedia = function() {}; } catch (e) {}
              try { navigator.mozGetUserMedia = function() {}; } catch (e) {}
            })();
        """
    }
}
