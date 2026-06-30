/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.databinding.SecureBrowserActivityBinding
import com.wireguard.android.util.SecureBrowserBlocker
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.util.VpnRouterAttestation
import com.wireguard.android.util.VpnRouterManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
    private var routerAttestationRetryJob: Job? = null
    private var routerAttestationWatchJob: Job? = null
    private var egressLookupJob: Job? = null
    private var vpnNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var navigationDragActive = false
    private var navigationDownRawX = 0f
    private var navigationDownRawY = 0f
    private var navigationStartX = 0f
    private var navigationStartY = 0f
    private var boundNetwork: Network? = null
    private var boundNetworkKind: BoundNetworkKind? = null
    private var routerAttestationWatchFailures = 0
    private var documentStartWebRtcProtection = false
    private var userInitiatedNavigation = false
    private var defaultUserAgent: String? = null
    private var desktopMode = false
    private var textZoom = 100
    private var findMatches = 0
    private var findActiveMatch = 0
    private var blockedTrackers = 0
    private var currentProtectionLabel: String? = null
    private var currentEgressSummary: String? = null
    private lateinit var egressStatusButton: TextView
    private val browserTabs = mutableListOf(BrowserTab())
    private var activeTabIndex = 0
    @Volatile
    private var blocked = true

    private val pairRouterResultLauncher = registerForActivityResult(ScanContract()) { result ->
        val qrCode = result.contents ?: return@registerForActivityResult
        val pairing = VpnRouterAttestation.parsePairingValue(qrCode)
        if (pairing == null) {
            Toast.makeText(this, R.string.vcs_secure_browser_router_pair_error, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        confirmRouterPairing(pairing)
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            launchRouterPairingScanner()
        } else {
            Toast.makeText(this, R.string.vcs_secure_browser_camera_permission_required, Toast.LENGTH_LONG).show()
        }
    }

    private data class BrowserProtection(
        val allowed: Boolean,
        val label: String,
        val detail: String? = null,
        val retryRouterAttestation: Boolean = false,
        val source: ProtectionSource = ProtectionSource.NONE
    )

    private data class EgressIdentity(
        val ip: String,
        val country: String,
        val countryCode: String
    )

    private data class BrowserTab(
        var url: String? = null,
        var title: String? = null
    )

    private enum class BoundNetworkKind {
        VPN,
        ROUTER_WIFI
    }

    private enum class ProtectionSource {
        NONE,
        VPN,
        LOCAL_ROUTER,
        ATTESTED_ROUTER
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = SecureBrowserActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        configureActionBar()

        configureWebView(binding.secureWebview)
        configurePullToRefresh()
        configureBrowserTools()
        ensureInitialTab()
        renderBrowserTabs()
        renderQuickLinks()
        configureMovableNavigation()
        updateNavigationButtons()
        updateSecurityBadges(null)
        binding.goButton.setOnClickListener { openTypedUrl() }
        binding.savePageButton.setOnClickListener { saveCurrentPage() }
        binding.browserBackButton.setOnClickListener { navigateBack() }
        binding.browserForwardButton.setOnClickListener { navigateForward() }
        binding.browserReloadButton.setOnClickListener { reloadPage() }
        binding.pairRouterButton.setOnClickListener { scanRouterPairingQr() }
        binding.pastePairRouterButton.setOnClickListener { showPasteRouterPairingDialog() }
        binding.forgetRoutersButton.setOnClickListener { confirmForgetRouters() }
        binding.urlInput.setOnEditorActionListener { _, _, _ ->
            openTypedUrl()
            true
        }
        intent.getStringExtra(EXTRA_INITIAL_URL)?.takeIf { it.isNotBlank() }?.let {
            binding.urlInput.setText(it)
        }
    }

    private fun configureActionBar() {
        egressStatusButton = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(32)
            )
            background = getDrawable(R.drawable.fastest_button_background)
            foreground = selectableForeground()
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER
            minWidth = dp(116)
            maxWidth = dp(168)
            setPadding(dp(10), 0, dp(10), 0)
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            setText(R.string.vcs_secure_browser_egress_checking_short)
            setTextColor(getColor(android.R.color.holo_green_light))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = getString(R.string.vcs_secure_browser_egress_description)
            setOnClickListener { showEgressStatusDialog() }
        }
        val titleView = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            ).also { it.marginEnd = dp(10) }
            gravity = android.view.Gravity.CENTER_VERTICAL
            setSingleLine(true)
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = getString(R.string.vcs_secure_browser_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val actionBarView = LinearLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(titleView)
            addView(egressStatusButton)
            addView(actionBarFeaturesButton())
        }
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(false)
            setIcon(null)
            setDisplayShowTitleEnabled(false)
            setDisplayShowCustomEnabled(true)
            setCustomView(
                actionBarView,
                androidx.appcompat.app.ActionBar.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun actionBarFeaturesButton(): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(32)).also { it.marginStart = dp(8) }
            background = getDrawable(R.drawable.fastest_button_background)
            foreground = selectableForeground()
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER
            setSingleLine(true)
            setText(R.string.vcs_secure_browser_features_icon)
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = getString(R.string.vcs_secure_browser_features_description)
            setOnClickListener { showBrowserFeaturesDialog() }
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
        routerAttestationRetryJob?.cancel()
        routerAttestationRetryJob = null
        routerAttestationWatchJob?.cancel()
        routerAttestationWatchJob = null
        egressLookupJob?.cancel()
        egressLookupJob = null
        unregisterVpnNetworkCallback()
        if (::binding.isInitialized) lockBrowser(showToast = false)
        clearEphemeralBrowserData()
        unbindBrowserNetwork()
        super.onPause()
    }

    override fun onDestroy() {
        routerAttestationRetryJob?.cancel()
        routerAttestationRetryJob = null
        routerAttestationWatchJob?.cancel()
        routerAttestationWatchJob = null
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
                if (blocked || !ensureBoundNetworkStillProtected() || !isAllowedBrowserUrl(request.url, request.isForMainFrame)) {
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
                if (blocked || !ensureBoundNetworkStillProtected() || !isAllowedBrowserUrl(request.url, request.isForMainFrame)) {
                    return blockedResponse()
                }
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
                    updateActiveBrowserTab(url, view.title)
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

    private fun showBrowserFeaturesDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val zoomValue = TextView(this).apply {
            text = getString(R.string.vcs_secure_browser_text_zoom_value, textZoom)
            setTextColor(Color.parseColor("#AFC0CC"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = android.view.Gravity.CENTER
            minWidth = dp(72)
        }
        container.addView(featureActionButton(getString(R.string.vcs_secure_browser_find)) {
            showFindBar()
        })
        container.addView(featureRow().apply {
            addView(featureLabel(getString(R.string.vcs_secure_browser_features_zoom)))
            addView(featureSmallButton(getString(R.string.vcs_secure_browser_zoom_down)) {
                changeTextZoom(-10)
                zoomValue.text = getString(R.string.vcs_secure_browser_text_zoom_value, textZoom)
            })
            addView(zoomValue)
            addView(featureSmallButton(getString(R.string.vcs_secure_browser_zoom_up)) {
                changeTextZoom(10)
                zoomValue.text = getString(R.string.vcs_secure_browser_text_zoom_value, textZoom)
            })
        })
        container.addView(featureActionButton(binding.desktopModeButton.text.toString()) {
            toggleDesktopMode()
            (it as? TextView)?.text = binding.desktopModeButton.text
        })
        container.addView(featureInfo(binding.httpsBadge.text.toString(), binding.httpsBadge.currentTextColor))
        container.addView(featureInfo(binding.trackerBadge.text.toString(), binding.trackerBadge.currentTextColor))

        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_secure_browser_features),
            customView = container,
            negative = VcsDialogs.action(this, android.R.string.cancel)
        )
    }

    private fun featureRow(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(48)
            ).also { it.topMargin = dp(8) }
        }

    private fun featureLabel(value: String): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            gravity = android.view.Gravity.CENTER_VERTICAL
            text = value
            setTextColor(Color.parseColor("#E5F2F7"))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun featureActionButton(value: String, action: (View) -> Unit): TextView =
        featureInfo(value, Color.WHITE).apply {
            isClickable = true
            isFocusable = true
            foreground = selectableForeground()
            setOnClickListener { view -> action(view) }
        }

    private fun featureSmallButton(value: String, action: (View) -> Unit): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(40)).also { it.marginStart = dp(6) }
            background = getDrawable(R.drawable.fastest_button_background)
            foreground = selectableForeground()
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER
            text = value
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setOnClickListener { view -> action(view) }
        }

    private fun featureInfo(value: String, color: Int): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(44)
            ).also { it.topMargin = dp(8) }
            background = getDrawable(R.drawable.fastest_button_background)
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            text = value
            setTextColor(color)
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun selectableForeground() =
        TypedValue().let { outValue ->
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            getDrawable(outValue.resourceId)
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
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchRouterPairingScanner()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchRouterPairingScanner() {
        val options = ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt(getString(R.string.vcs_secure_browser_pair_router_scan_prompt))
            .setBeepEnabled(false)
            .setOrientationLocked(false)
            .setCaptureActivity(CaptureActivity::class.java)
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
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        lateinit var dialog: AlertDialog
        dialog = VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_secure_browser_paste_router_pair_title),
            customView = input,
            negative = VcsDialogs.action(this, android.R.string.cancel),
            neutral = VcsDialogs.Action(
                text = getString(R.string.vcs_secure_browser_paste_router_pair_clipboard),
                dismissAfterClick = false
            ) {
                val text = clipboardText()
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SecureBrowserActivity, R.string.vcs_secure_browser_paste_router_pair_empty, Toast.LENGTH_LONG).show()
                } else {
                    input.setText(text)
                    input.selectAll()
                }
            },
            positive = VcsDialogs.Action(
                text = getString(R.string.vcs_secure_browser_router_pair_action),
                primary = true,
                dismissAfterClick = false
            ) {
                val pairing = parsePairingKey(input.text?.toString().orEmpty())
                if (pairing == null) {
                    Toast.makeText(this@SecureBrowserActivity, R.string.vcs_secure_browser_router_pair_error, Toast.LENGTH_LONG).show()
                } else {
                    dialog.dismiss()
                    confirmRouterPairing(pairing)
                }
            }
        )
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
        return VpnRouterAttestation.parsePairingValue(value)
    }

    private fun confirmRouterPairing(pairing: VpnRouterAttestation.Pairing) {
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_secure_browser_router_pair_title),
            message = getString(R.string.vcs_secure_browser_router_pair_confirm, pairing.routerId.take(8)),
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.Action(
                text = getString(R.string.vcs_secure_browser_router_pair_action),
                primary = true
            ) {
                VpnRouterAttestation.importPairing(this, pairing)
                Toast.makeText(this, R.string.vcs_secure_browser_router_pair_success, Toast.LENGTH_LONG).show()
                refreshBrowserProtectionAsync()
            }
        )
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
            updateActiveBrowserTab(url, title = null)
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

    private fun ensureInitialTab() {
        if (browserTabs.isEmpty()) browserTabs.add(BrowserTab())
        activeTabIndex = activeTabIndex.coerceIn(browserTabs.indices)
    }

    private fun renderBrowserTabs() {
        ensureInitialTab()
        binding.browserTabsRow.removeAllViews()
        browserTabs.forEachIndexed { index, tab ->
            binding.browserTabsRow.addView(browserTabButton(index, tab))
        }
        binding.browserTabsRow.addView(newBrowserTabButton())
    }

    private fun browserTabButton(index: Int, tab: BrowserTab): View =
        LinearLayout(this).apply {
            val isActive = index == activeTabIndex
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).also { it.marginEnd = dp(8) }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.fastest_button_background)
            foreground = selectableForeground()
            isClickable = true
            isFocusable = true
            minimumWidth = dp(104)
            setPadding(dp(12), 0, dp(4), 0)
            alpha = if (isActive) 1f else 0.72f
            contentDescription = getString(R.string.vcs_secure_browser_tab_description, index + 1, tabLabel(tab))
            setOnClickListener { switchBrowserTab(index) }
            setOnLongClickListener {
                closeBrowserTab(index)
                true
            }
            addView(TextView(this@SecureBrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                gravity = android.view.Gravity.CENTER
                maxWidth = dp(132)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = tabLabel(tab)
                setTextColor(if (isActive) Color.WHITE else Color.parseColor("#AFC0CC"))
                textSize = 12f
                setTypeface(typeface, if (isActive) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            })
            addView(TextView(this@SecureBrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also {
                    it.marginStart = dp(4)
                }
                background = getDrawable(R.drawable.fastest_button_background)
                foreground = selectableForeground()
                isClickable = true
                isFocusable = true
                gravity = android.view.Gravity.CENTER
                setSingleLine(true)
                text = "X"
                setTextColor(Color.parseColor("#FF8A8A"))
                textSize = 11f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                contentDescription = getString(R.string.vcs_secure_browser_close_tab_description, tabLabel(tab))
                alpha = if (browserTabs.size > 1) 1f else 0.42f
                setOnClickListener { closeBrowserTab(index) }
            })
        }

    private fun newBrowserTabButton(): TextView =
        TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(36))
            background = getDrawable(R.drawable.fastest_button_background)
            foreground = selectableForeground()
            isClickable = true
            isFocusable = true
            gravity = android.view.Gravity.CENTER
            setSingleLine(true)
            setText(R.string.vcs_secure_browser_new_tab)
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            contentDescription = getString(R.string.vcs_secure_browser_new_tab_description)
            setOnClickListener { openNewBrowserTab() }
        }

    private fun tabLabel(tab: BrowserTab): String {
        val title = tab.title?.trim()
        if (!title.isNullOrBlank() && title != "about:blank") return title
        val url = tab.url?.trim()
        if (!url.isNullOrBlank()) {
            val host = runCatching { Uri.parse(url).host }.getOrNull()
            if (!host.isNullOrBlank()) return host.removePrefix("www.")
            return url
        }
        return getString(R.string.vcs_secure_browser_untitled_tab)
    }

    private fun openNewBrowserTab() {
        saveActiveBrowserTab()
        browserTabs.add(BrowserTab())
        activeTabIndex = browserTabs.lastIndex
        clearFindMatches()
        resetPageSecurityState(null)
        binding.urlInput.setText("")
        binding.browserRefresh.isRefreshing = false
        binding.secureWebview.stopLoading()
        binding.secureWebview.loadUrl("about:blank")
        renderBrowserTabs()
        updateNavigationButtons()
    }

    private fun switchBrowserTab(index: Int) {
        if (index !in browserTabs.indices || index == activeTabIndex) return
        saveActiveBrowserTab()
        activeTabIndex = index
        val tab = browserTabs[index]
        clearFindMatches()
        resetPageSecurityState(tab.url)
        binding.urlInput.setText(tab.url.orEmpty())
        binding.browserRefresh.isRefreshing = false
        binding.secureWebview.stopLoading()
        if (tab.url.isNullOrBlank()) {
            binding.secureWebview.loadUrl("about:blank")
        } else {
            loadUrlWithPrivacyHeaders(tab.url!!)
        }
        renderBrowserTabs()
        updateNavigationButtons()
    }

    private fun closeBrowserTab(index: Int) {
        if (index !in browserTabs.indices || browserTabs.size == 1) return
        saveActiveBrowserTab()
        browserTabs.removeAt(index)
        activeTabIndex = when {
            activeTabIndex > index -> activeTabIndex - 1
            activeTabIndex >= browserTabs.size -> browserTabs.lastIndex
            else -> activeTabIndex
        }
        val tab = browserTabs[activeTabIndex]
        clearFindMatches()
        resetPageSecurityState(tab.url)
        binding.urlInput.setText(tab.url.orEmpty())
        binding.browserRefresh.isRefreshing = false
        binding.secureWebview.stopLoading()
        if (tab.url.isNullOrBlank()) {
            binding.secureWebview.loadUrl("about:blank")
        } else {
            loadUrlWithPrivacyHeaders(tab.url!!)
        }
        renderBrowserTabs()
        updateNavigationButtons()
    }

    private fun saveActiveBrowserTab() {
        val tab = browserTabs.getOrNull(activeTabIndex) ?: return
        val webUrl = binding.secureWebview.url?.takeUnless { it == "about:blank" }
        val typedUrl = binding.urlInput.text?.toString()?.trim()?.takeIf { it.isNotBlank() }
        tab.url = webUrl ?: typedUrl ?: tab.url
        tab.title = binding.secureWebview.title?.takeIf { it.isNotBlank() && it != "about:blank" } ?: tab.title
    }

    private fun updateActiveBrowserTab(url: String?, title: String?) {
        val tab = browserTabs.getOrNull(activeTabIndex) ?: return
        if (!url.isNullOrBlank() && url != "about:blank") tab.url = url
        if (!title.isNullOrBlank() && title != "about:blank") tab.title = title
        renderBrowserTabs()
    }

    private fun quickLinkButton(label: String, url: String): View {
        return LinearLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(40)
            ).also {
                it.marginEnd = dp(8)
            }
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = getDrawable(R.drawable.fastest_button_background)
            val outValue = TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
            foreground = getDrawable(outValue.resourceId)
            isClickable = true
            isFocusable = true
            minimumWidth = dp(96)
            setPadding(dp(14), 0, dp(6), 0)
            setOnClickListener {
                binding.urlInput.setText(url)
                openTypedUrl()
            }
            setOnLongClickListener {
                confirmDeleteBookmark(url)
                true
            }
            addView(TextView(this@SecureBrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                gravity = android.view.Gravity.CENTER
                maxWidth = dp(150)
                setSingleLine(true)
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = label
                setTextColor(getColor(android.R.color.white))
                textSize = 13f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            })
            addView(TextView(this@SecureBrowserActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also {
                    it.marginStart = dp(4)
                }
                background = getDrawable(R.drawable.fastest_button_background)
                foreground = selectableForeground()
                isClickable = true
                isFocusable = true
                gravity = android.view.Gravity.CENTER
                setSingleLine(true)
                text = "X"
                setTextColor(Color.parseColor("#FF8A8A"))
                textSize = 12f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                contentDescription = getString(R.string.vcs_secure_browser_delete_bookmark_description, label)
                setOnClickListener { confirmDeleteBookmark(url) }
            })
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
            return@withContext BrowserProtection(true, vpnProtectionLabel(), source = ProtectionSource.VPN)
        }
        unbindBrowserNetwork()
        val routerStatus = runCatching { VpnRouterManager.getStatus(this@SecureBrowserActivity) }.getOrNull()
        if (routerStatus?.availability == VpnRouterManager.Availability.ENABLED) {
            val tunnel = routerStatus.activeTunnel ?: getString(R.string.vcs_vpn_status_no_tunnel)
            return@withContext BrowserProtection(
                true,
                getString(R.string.vcs_secure_browser_egress_router, tunnel),
                source = ProtectionSource.LOCAL_ROUTER
            )
        }
        if (!bindToWifiNetwork()) {
            return@withContext BrowserProtection(
                false,
                getString(R.string.vcs_secure_browser_egress_blocked),
                retryRouterAttestation = VpnRouterAttestation.pairedRouters(this@SecureBrowserActivity).isNotEmpty()
            )
        }
        val attestation = runCatching { VpnRouterAttestation.verifyFromCurrentGatewayDetailed(this@SecureBrowserActivity) }.getOrNull()
        if (attestation?.result != null) {
            Log.i(TAG, "Verified VPN Router attestation: ${attestation.result.routerId.take(8)}")
            return@withContext BrowserProtection(
                true,
                getString(R.string.vcs_secure_browser_egress_router_attested),
                source = ProtectionSource.ATTESTED_ROUTER
            )
        }
        Log.i(TAG, "VPN Router attestation failed: ${attestation?.failureReason ?: "exception"}")
        unbindBrowserNetwork()
        val detail = attestationFailureDetail(attestation?.failureReason)
        BrowserProtection(
            false,
            getString(R.string.vcs_secure_browser_egress_blocked),
            detail,
            retryRouterAttestation = attestation?.failureReason != VpnRouterAttestation.FailureReason.NO_PAIRING &&
                attestation?.failureReason != VpnRouterAttestation.FailureReason.EXPIRED_PAIRING
        )
    }

    private fun attestationFailureDetail(reason: VpnRouterAttestation.FailureReason?): String {
        return when (reason) {
            VpnRouterAttestation.FailureReason.NO_PAIRING -> getString(R.string.vcs_secure_browser_blocked_no_pairing)
            VpnRouterAttestation.FailureReason.NO_WIFI_GATEWAY -> getString(R.string.vcs_secure_browser_blocked_no_gateway)
            VpnRouterAttestation.FailureReason.UNREACHABLE -> getString(R.string.vcs_secure_browser_blocked_router_unreachable)
            VpnRouterAttestation.FailureReason.INVALID_RESPONSE -> getString(R.string.vcs_secure_browser_blocked_router_unprotected)
            VpnRouterAttestation.FailureReason.ROUTER_NOT_PAIRED -> getString(R.string.vcs_secure_browser_blocked_router_not_paired)
            VpnRouterAttestation.FailureReason.EXPIRED_PAIRING -> getString(R.string.vcs_secure_browser_blocked_pairing_expired)
            VpnRouterAttestation.FailureReason.CLOCK_SKEW -> getString(R.string.vcs_secure_browser_blocked_clock_skew)
            VpnRouterAttestation.FailureReason.BAD_SIGNATURE -> getString(R.string.vcs_secure_browser_blocked_bad_signature)
            null -> getString(R.string.vcs_secure_browser_blocked_detail)
        }
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
                val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return
                if (isUsableVpnCapabilities(capabilities)) {
                    refreshBrowserProtectionAsync()
                }
            }

            override fun onLost(network: Network) {
                if (network == boundNetwork) {
                    lockBrowserFromNetworkCallback()
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (network == boundNetwork) {
                    val stillProtected = when (boundNetworkKind) {
                        BoundNetworkKind.VPN -> isUsableVpnCapabilities(networkCapabilities)
                        BoundNetworkKind.ROUTER_WIFI -> isUsableRouterWifiCapabilities(networkCapabilities)
                        null -> false
                    }
                    if (!stillProtected) lockBrowserFromNetworkCallback()
                } else if (isUsableVpnCapabilities(networkCapabilities)) {
                    refreshBrowserProtectionAsync()
                }
            }

            override fun onUnavailable() {
                lockBrowserFromNetworkCallback()
            }
        }
        vpnNetworkCallback = callback
        val request = NetworkRequest.Builder()
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

    private fun startRouterAttestationRetry() {
        if (routerAttestationRetryJob?.isActive == true) return
        routerAttestationRetryJob = lifecycleScope.launch {
            while (isActive && blocked) {
                delay(ROUTER_ATTESTATION_RETRY_MS)
                if (!isActive || !blocked || !::binding.isInitialized) return@launch
                val protection = resolveBrowserProtection()
                setBrowserProtection(protection)
                if (protection.allowed || !protection.retryRouterAttestation) return@launch
            }
        }
    }

    private fun startRouterAttestationWatch() {
        if (routerAttestationWatchJob?.isActive == true) return
        routerAttestationWatchFailures = 0
        routerAttestationWatchJob = lifecycleScope.launch {
            while (isActive && !blocked && boundNetworkKind == BoundNetworkKind.ROUTER_WIFI) {
                delay(ROUTER_ATTESTATION_WATCH_MS)
                if (!isActive || blocked || !::binding.isInitialized || boundNetworkKind != BoundNetworkKind.ROUTER_WIFI) {
                    return@launch
                }
                val stillProtected = withContext(Dispatchers.IO) {
                    if (!ensureBoundNetworkStillProtected()) return@withContext false
                    val verification = runCatching {
                        VpnRouterAttestation.verifyFromCurrentGatewayDetailed(this@SecureBrowserActivity)
                    }.getOrNull()
                    if (verification?.result != null) {
                        routerAttestationWatchFailures = 0
                        return@withContext true
                    }
                    when (verification?.failureReason) {
                        VpnRouterAttestation.FailureReason.UNREACHABLE,
                        VpnRouterAttestation.FailureReason.NO_WIFI_GATEWAY,
                        null -> {
                            routerAttestationWatchFailures++
                            routerAttestationWatchFailures < ROUTER_ATTESTATION_WATCH_MAX_TRANSIENT_FAILURES
                        }
                        else -> false
                    }
                }
                if (!stillProtected) {
                    routerAttestationWatchFailures = 0
                    setBrowserProtection(
                        BrowserProtection(
                            false,
                            getString(R.string.vcs_secure_browser_egress_blocked),
                            getString(R.string.vcs_secure_browser_blocked_detail),
                            retryRouterAttestation = true
                        )
                    )
                    return@launch
                }
            }
        }
    }

    private fun lockBrowserFromNetworkCallback() {
        lifecycleScope.launch {
            if (::binding.isInitialized) {
                setBrowserProtection(BrowserProtection(false, getString(R.string.vcs_secure_browser_egress_blocked)))
            }
        }
    }

    private fun setBrowserProtection(protection: BrowserProtection) {
        currentProtectionLabel = protection.label
        currentEgressSummary = null
        egressLookupJob?.cancel()
        egressStatusButton.text = getString(
            if (protection.allowed) {
                R.string.vcs_secure_browser_egress_protected_short
            } else {
                R.string.vcs_secure_browser_egress_required_short
            }
        )
        egressStatusButton.contentDescription = protection.label
        egressStatusButton.setTextColor(getColor(if (protection.allowed) android.R.color.holo_green_light else android.R.color.holo_red_light))
        if (protection.allowed) {
            routerAttestationRetryJob?.cancel()
            routerAttestationRetryJob = null
            blocked = false
            if (protection.source == ProtectionSource.ATTESTED_ROUTER) {
                startRouterAttestationWatch()
            } else {
                routerAttestationWatchJob?.cancel()
                routerAttestationWatchJob = null
                routerAttestationWatchFailures = 0
            }
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
            routerAttestationWatchJob?.cancel()
            routerAttestationWatchJob = null
            routerAttestationWatchFailures = 0
            lockBrowser(showToast = !blocked)
            binding.secureWebview.visibility = View.GONE
            binding.vpnBlocker.visibility = View.VISIBLE
            binding.blockerDetail.text = protection.detail ?: getString(R.string.vcs_secure_browser_blocked_detail)
            if (protection.retryRouterAttestation) {
                startRouterAttestationRetry()
            } else {
                routerAttestationRetryJob?.cancel()
                routerAttestationRetryJob = null
            }
        }
        updateNavigationButtons()
    }

    private fun showEgressStatusDialog() {
        val baseLabel = currentProtectionLabel ?: getString(R.string.vcs_secure_browser_egress_checking)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val message = TextView(this).apply {
            text = currentEgressSummary ?: baseLabel
            setTextColor(Color.parseColor("#E5F2F7"))
            textSize = 14f
            setLineSpacing(dp(2).toFloat(), 1f)
        }
        content.addView(message)
        if (blocked) {
            content.addView(TextView(this).apply {
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).also { it.topMargin = dp(12) }
                text = getString(R.string.vcs_secure_browser_required_pair_flow)
                setTextColor(Color.parseColor("#B8C8D2"))
                textSize = 13f
                setLineSpacing(dp(2).toFloat(), 1f)
            })
        }
        lateinit var dialog: AlertDialog
        val checkAction = VcsDialogs.Action(
            text = getString(R.string.vcs_secure_browser_egress_modal_check),
            primary = true,
            dismissAfterClick = false
        ) {
            if (!blocked) refreshEgressIdentityOnDemand(message)
        }
        dialog = VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_secure_browser_egress_modal_title),
            customView = content,
            positive = if (blocked) {
                VcsDialogs.Action(
                    text = getString(R.string.vcs_secure_browser_pair_router),
                    primary = true,
                    dismissAfterClick = false
                ) {
                    dialog.dismiss()
                    scanRouterPairingQr()
                }
            } else {
                checkAction
            },
            neutral = if (blocked) {
                VcsDialogs.Action(
                    text = getString(R.string.vcs_secure_browser_paste_router_pair),
                    dismissAfterClick = false
                ) {
                    dialog.dismiss()
                    showPasteRouterPairingDialog()
                }
            } else {
                null
            },
            negative = VcsDialogs.action(this, android.R.string.cancel)
        )
    }

    private fun refreshEgressIdentityOnDemand(target: TextView? = null) {
        if (blocked) return
        val baseLabel = currentProtectionLabel ?: return
        egressLookupJob?.cancel()
        target?.text = getString(R.string.vcs_secure_browser_egress_checking)
        egressLookupJob = lifecycleScope.launch {
            val identity = withContext(Dispatchers.IO) { fetchEgressIdentity() }
            if (identity != null && ::binding.isInitialized && !blocked) {
                currentEgressSummary = getString(
                    R.string.vcs_secure_browser_egress_with_country,
                    baseLabel,
                    listOf(flagEmoji(identity.countryCode), identity.country).filter { it.isNotBlank() }.joinToString(" "),
                    identity.ip
                )
                target?.text = currentEgressSummary
            } else if (::binding.isInitialized && !blocked) {
                currentEgressSummary = getString(R.string.vcs_secure_browser_egress_modal_unavailable)
                target?.text = currentEgressSummary
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
        if (bound) {
            boundNetwork = vpnNetwork
            boundNetworkKind = BoundNetworkKind.VPN
        }
        return bound
    }

    private fun bindToWifiNetwork(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val wifiNetwork = findWifiNetwork(connectivityManager) ?: return false
        if (boundNetwork == wifiNetwork) return true
        val bound = connectivityManager.bindProcessToNetwork(wifiNetwork)
        if (bound) {
            boundNetwork = wifiNetwork
            boundNetworkKind = BoundNetworkKind.ROUTER_WIFI
        }
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

    private fun findWifiNetwork(connectivityManager: ConnectivityManager): Network? {
        val activeNetwork = connectivityManager.activeNetwork
        if (activeNetwork?.let { network -> isUsableWifiNetwork(connectivityManager, network) } == true) {
            return activeNetwork
        }
        return connectivityManager.allNetworks.firstOrNull { network ->
            isUsableWifiNetwork(connectivityManager, network)
        }
    }

    private fun isUsableVpnNetwork(connectivityManager: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isUsableVpnCapabilities(capabilities)
    }

    private fun isUsableWifiNetwork(connectivityManager: ConnectivityManager, network: Network): Boolean {
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return isUsableRouterWifiCapabilities(capabilities)
    }

    private fun isUsableVpnCapabilities(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isUsableRouterWifiCapabilities(capabilities: NetworkCapabilities): Boolean {
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun ensureBoundNetworkStillProtected(): Boolean {
        val network = boundNetwork ?: return true
        val kind = boundNetworkKind ?: return false
        val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network)
        val protected = when (kind) {
            BoundNetworkKind.VPN -> capabilities?.let { isUsableVpnCapabilities(it) } == true
            BoundNetworkKind.ROUTER_WIFI -> capabilities?.let { isUsableRouterWifiCapabilities(it) } == true
        }
        if (!protected) {
            runOnUiThread { lockBrowser(showToast = false) }
        }
        return protected
    }

    private fun unbindBrowserNetwork() {
        if (boundNetwork == null) return
        getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        boundNetwork = null
        boundNetworkKind = null
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
        private const val TAG = "VirtuVPN/SecBrowser"
        private const val MIN_TEXT_ZOOM = 70
        private const val MAX_TEXT_ZOOM = 160
        private const val ROUTER_ATTESTATION_RETRY_MS = 2_000L
        private const val ROUTER_ATTESTATION_WATCH_MS = 3_000L
        private const val ROUTER_ATTESTATION_WATCH_MAX_TRANSIENT_FAILURES = 3
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
                throw new DOMException('WebRTC is disabled by VirtuVPN Secured Browser', 'SecurityError');
              };
              var rejected = function() {
                return Promise.reject(new DOMException('WebRTC is disabled by VirtuVPN Secured Browser', 'SecurityError'));
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
