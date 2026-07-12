/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.graphics.Bitmap
import android.graphics.Color
import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wireguard.android.R
import com.wireguard.android.Application
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.util.VpnRouterOperationFormatter
import com.wireguard.android.util.VpnRouterAttestation
import com.wireguard.android.util.VpnRouterManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class VpnRouterActivity : AppCompatActivity() {
    private lateinit var routerStatus: TextView
    private lateinit var routerUplinkStatus: TextView
    private lateinit var routerProtectionStatus: TextView
    private lateinit var routerGuestAccessStatus: TextView
    private lateinit var routerGuestDownload: TextView
    private lateinit var routerGuestQr: ImageView
    private lateinit var routerModeSelector: TextView
    private lateinit var routerUplinkSelector: TextView
    private lateinit var routerDnsSelector: TextView
    private var routerMonitorJob: Job? = null
    private var operationDialog: AlertDialog? = null
    private var operationDialogMessage: TextView? = null
    private var refreshing = false
    private var uplinkPreferenceSupported = false
    private var lastActiveTunnel: String? = null
    private var kernelRequirementDialogShown = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.vcs_vpn_router_page_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.vpn_router_activity)
        routerStatus = findViewById(R.id.router_status)
        routerUplinkStatus = findViewById(R.id.router_uplink_status)
        routerProtectionStatus = findViewById(R.id.router_protection_status)
        routerGuestAccessStatus = findViewById(R.id.router_guest_access_status)
        routerGuestDownload = findViewById(R.id.router_guest_download)
        routerGuestQr = findViewById(R.id.router_guest_qr)
        routerModeSelector = findViewById(R.id.router_mode_selector)
        routerUplinkSelector = findViewById(R.id.router_uplink_selector)
        routerDnsSelector = findViewById(R.id.router_dns_selector)
        routerModeSelector.setOnClickListener { showCompatibilityModeSelector() }
        routerUplinkSelector.setOnClickListener { showUplinkPreferenceSelector() }
        routerDnsSelector.setOnClickListener { showDnsModeSelector() }
        refreshStatus(showProgress = false)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        startRouterMonitor()
    }

    override fun onPause() {
        routerMonitorJob?.cancel()
        routerMonitorJob = null
        dismissOperationDialog()
        super.onPause()
    }

    private fun startRouterMonitor() {
        if (routerMonitorJob != null) return
        routerMonitorJob = lifecycleScope.launch {
            while (true) {
                refreshStatus(showProgress = true)
                delay(3000)
            }
        }
    }

    private fun refreshStatus(showProgress: Boolean) {
        if (refreshing) return
        lifecycleScope.launch {
            refreshing = true
            try {
                val router = runCatching { VpnRouterManager.getStatus(this@VpnRouterActivity) }
                    .mapCatching { current ->
                        val tunnelChanged = lastActiveTunnel != null && current.activeTunnel != lastActiveTunnel
                        val shouldShowProgress = showProgress && tunnelChanged
                        if (current.needsReconcile) {
                            if (shouldShowProgress) showOperationDialog()
                            val progressJob = if (shouldShowProgress) launch {
                                while (true) {
                                    renderOperationStatus(VpnRouterManager.getOperationStatus(this@VpnRouterActivity))
                                    delay(250)
                                }
                            } else null
                            try {
                                VpnRouterManager.reconcile(this@VpnRouterActivity)
                            } finally {
                                progressJob?.cancel()
                                if (shouldShowProgress) {
                                    renderOperationStatus(VpnRouterManager.getOperationStatus(this@VpnRouterActivity))
                                    delay(450)
                                    dismissOperationDialog()
                                }
                            }
                        } else {
                            current
                        }
                    }
                    .getOrElse { e ->
                        VpnRouterManager.Status(
                            availability = VpnRouterManager.Availability.ERROR,
                            detail = e.message ?: e.javaClass.simpleName
                        )
                }
                renderRouterStatus(router)
                uplinkPreferenceSupported = runCatching {
                    Application.getBackend() is GoBackend && GoBackend.isVpnServiceTunnelActive()
                }.getOrDefault(false)
                if (!uplinkPreferenceSupported &&
                    VpnRouterManager.getUplinkPreference(this@VpnRouterActivity) != VpnRouterManager.UplinkPreference.AUTOMATIC
                ) {
                    VpnRouterManager.setUplinkPreference(
                        this@VpnRouterActivity,
                        VpnRouterManager.UplinkPreference.AUTOMATIC
                    )
                }
                renderCompatibilityMode()
                renderUplinkPreference()
                renderDnsMode()

                routerProtectionStatus.setText(
                    if (router.availability == VpnRouterManager.Availability.ENABLED) {
                        R.string.vcs_hotspot_vpn_router_active
                    } else {
                        R.string.vcs_hotspot_vpn_bypass_warning
                    }
                )
                routerProtectionStatus.setTextColor(
                    if (router.availability == VpnRouterManager.Availability.ENABLED) GREEN else RED
                )
                renderGuestAccess(router)
                lastActiveTunnel = router.activeTunnel
            } finally {
                refreshing = false
            }
        }
    }

    private fun maybeShowKernelRequirementDialog(status: VpnRouterManager.Status) {
        if (kernelRequirementDialogShown || status.availability != VpnRouterManager.Availability.UNSUPPORTED) return
        kernelRequirementDialogShown = true
        if (!VpnRouterManager.isKernelModuleAvailable()) {
            VcsDialogs.show(
                context = this,
                title = getString(R.string.vcs_vpn_router_kernel_missing_title),
                message = getString(R.string.vcs_vpn_router_kernel_missing_message),
                positive = VcsDialogs.action(this, android.R.string.ok, primary = true)
            )
            return
        }
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_vpn_router_kernel_backend_title),
            message = getString(R.string.vcs_vpn_router_kernel_backend_message),
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.vcs_vpn_router_kernel_backend_action, primary = true) {
                lifecycleScope.launch {
                    VpnRouterManager.prepareKernelBackend(this@VpnRouterActivity)
                    packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                    }
                    exitProcess(0)
                }
            }
        )
    }

    private fun showOperationDialog() {
        if (operationDialog?.isShowing == true) return
        operationDialogMessage = TextView(this).apply {
            text = VpnRouterOperationFormatter.message(this@VpnRouterActivity, VpnRouterManager.getOperationStatus(this@VpnRouterActivity))
            setTextColor(Color.parseColor("#AFC0CC"))
            textSize = 14f
            setLineSpacing(4f, 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        operationDialog = VcsDialogs.show(
            this,
            title = getString(R.string.vcs_vpn_router_operation_title),
            customView = operationDialogMessage,
            cancelable = false
        )
    }

    private fun renderOperationStatus(status: VpnRouterManager.OperationStatus) {
        operationDialogMessage?.text = VpnRouterOperationFormatter.message(this, status)
    }

    private fun dismissOperationDialog() {
        operationDialog?.dismiss()
        operationDialog = null
        operationDialogMessage = null
    }

    private fun renderDnsMode() {
        routerDnsSelector.text = labelForDnsMode(VpnRouterManager.getDnsMode(this))
    }

    private fun renderCompatibilityMode() {
        routerModeSelector.text = labelForCompatibilityMode(VpnRouterManager.getCompatibilityMode(this))
    }

    private fun renderUplinkPreference() {
        routerUplinkSelector.text = labelForUplinkPreference(VpnRouterManager.getUplinkPreference(this))
        routerUplinkSelector.alpha = if (uplinkPreferenceSupported) 1f else 0.72f
    }

    private fun showUplinkPreferenceSelector() {
        lifecycleScope.launch {
            val supportedNow = Application.getBackend() is GoBackend && GoBackend.isVpnServiceTunnelActive()
            uplinkPreferenceSupported = supportedNow
            renderUplinkPreference()
            if (!supportedNow) {
                VcsDialogs.show(
                    context = this@VpnRouterActivity,
                    title = getString(R.string.vcs_vpn_router_uplink_preference),
                    message = getString(R.string.vcs_vpn_router_uplink_automatic_only),
                    positive = VcsDialogs.action(this@VpnRouterActivity, android.R.string.ok, primary = true)
                )
                return@launch
            }
            val modes = VpnRouterManager.UplinkPreference.values().toList()
            val current = VpnRouterManager.getUplinkPreference(this@VpnRouterActivity)
            VcsDialogs.showChoice(
                context = this@VpnRouterActivity,
                title = getString(R.string.vcs_vpn_router_uplink_preference),
                items = modes.map { labelForUplinkPreference(it) },
                selectedIndex = modes.indexOf(current).takeIf { it >= 0 }
            ) { which ->
                val mode = modes[which]
                if (mode == current) return@showChoice
                VpnRouterManager.setUplinkPreference(this@VpnRouterActivity, mode)
                renderUplinkPreference()
            }
        }
    }

    private fun showCompatibilityModeSelector() {
        val modes = listOf(
            VpnRouterManager.CompatibilityMode.STRICT,
            VpnRouterManager.CompatibilityMode.NESTED
        )
        val current = VpnRouterManager.getCompatibilityMode(this)
        VcsDialogs.showChoice(
            context = this,
            title = getString(R.string.vcs_vpn_router_mode),
            items = modes.map { labelForCompatibilityMode(it) },
            selectedIndex = modes.indexOf(current).takeIf { it >= 0 }
        ) { which ->
            val mode = modes[which]
            if (mode == current) return@showChoice
            VpnRouterManager.setCompatibilityMode(this, mode)
            renderCompatibilityMode()
            lifecycleScope.launch {
                val router = VpnRouterManager.reconcile(this@VpnRouterActivity)
                renderRouterStatus(router)
            }
        }
    }

    private fun showDnsModeSelector() {
        val modes = listOf(
            VpnRouterManager.DnsMode.COPY_TUNNEL,
            VpnRouterManager.DnsMode.CLOUDFLARE,
            VpnRouterManager.DnsMode.QUAD9,
            VpnRouterManager.DnsMode.FAMILY
        )
        val current = VpnRouterManager.getDnsMode(this)
        VcsDialogs.showChoice(
            context = this,
            title = getString(R.string.vcs_vpn_router_dns),
            items = modes.map { labelForDnsMode(it) },
            selectedIndex = modes.indexOf(current).takeIf { it >= 0 }
        ) { which ->
            val mode = modes[which]
            if (mode == current) return@showChoice
            VpnRouterManager.setDnsMode(this, mode)
            renderDnsMode()
            lifecycleScope.launch {
                val router = VpnRouterManager.reconcile(this@VpnRouterActivity)
                renderRouterStatus(router)
            }
        }
    }

    private fun labelForDnsMode(mode: VpnRouterManager.DnsMode): String =
        getString(
            when (mode) {
                VpnRouterManager.DnsMode.COPY_TUNNEL -> R.string.vcs_vpn_router_dns_copy_tunnel
                VpnRouterManager.DnsMode.CLOUDFLARE -> R.string.vcs_vpn_router_dns_cloudflare
                VpnRouterManager.DnsMode.QUAD9 -> R.string.vcs_vpn_router_dns_quad9
                VpnRouterManager.DnsMode.FAMILY -> R.string.vcs_vpn_router_dns_family
            }
        )

    private fun labelForCompatibilityMode(mode: VpnRouterManager.CompatibilityMode): String =
        getString(
            when (mode) {
                VpnRouterManager.CompatibilityMode.STRICT -> R.string.vcs_vpn_router_mode_strict
                VpnRouterManager.CompatibilityMode.NESTED -> R.string.vcs_vpn_router_mode_nested
            }
        )

    private fun labelForUplinkPreference(preference: VpnRouterManager.UplinkPreference): String =
        getString(
            when (preference) {
                VpnRouterManager.UplinkPreference.AUTOMATIC -> R.string.vcs_vpn_router_uplink_automatic
                VpnRouterManager.UplinkPreference.PREFER_WIFI -> R.string.vcs_vpn_router_uplink_prefer_wifi
                VpnRouterManager.UplinkPreference.PREFER_MOBILE -> R.string.vcs_vpn_router_uplink_prefer_mobile
            }
        )

    private fun renderRouterStatus(status: VpnRouterManager.Status) {
        val tunnel = status.activeTunnel ?: getString(R.string.vcs_vpn_status_no_tunnel)
        val interfaces = status.tetherInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: getString(R.string.vcs_vpn_router_no_interfaces)
        val uplinks = status.uplinkInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", ") { uplink ->
            getString(labelForUplinkType(uplink.type), uplink.interfaceName)
        } ?: getString(R.string.vcs_vpn_router_uplink_none)
        routerUplinkStatus.text = getString(R.string.vcs_vpn_router_uplink_detected, uplinks)
        routerUplinkStatus.setTextColor(
            if (status.uplinkInterfaces.isEmpty()) YELLOW else TEXT
        )
        when (status.availability) {
            VpnRouterManager.Availability.ENABLED -> {
                routerStatus.text = getString(R.string.vcs_vpn_router_on, tunnel, interfaces)
                routerStatus.setTextColor(GREEN)
            }
            VpnRouterManager.Availability.DEGRADED -> {
                routerStatus.text = status.detail ?: getString(R.string.vcs_vpn_router_degraded)
                routerStatus.setTextColor(RED)
            }
            VpnRouterManager.Availability.READY -> {
                routerStatus.text = getString(R.string.vcs_vpn_router_ready, tunnel, interfaces)
                routerStatus.setTextColor(TEXT)
            }
            VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> {
                routerStatus.setText(R.string.vcs_vpn_router_waiting_tunnel)
                routerStatus.setTextColor(YELLOW)
            }
            VpnRouterManager.Availability.WAITING_FOR_HOTSPOT -> {
                routerStatus.setText(R.string.vcs_vpn_router_waiting_hotspot)
                routerStatus.setTextColor(YELLOW)
            }
            VpnRouterManager.Availability.UNSUPPORTED -> {
                routerStatus.text = status.detail ?: getString(R.string.vcs_vpn_router_unsupported)
                routerStatus.setTextColor(YELLOW)
            }
            VpnRouterManager.Availability.ERROR -> {
                routerStatus.text = getString(R.string.vcs_vpn_router_error, status.detail ?: "unknown error")
                routerStatus.setTextColor(RED)
            }
        }
    }

    private fun renderGuestAccess(status: VpnRouterManager.Status) {
        val active = status.availability == VpnRouterManager.Availability.ENABLED
        routerGuestAccessStatus.setText(
            if (active) {
                R.string.vcs_vpn_router_guest_access_active
            } else {
                R.string.vcs_vpn_router_guest_access_inactive
            }
        )
        routerGuestAccessStatus.setTextColor(if (active) GREEN else YELLOW)
        val qrValue = if (active) {
            VpnRouterAttestation.pairingLandingUrl(this)
        } else {
            "VirtuVPN Router guest install is available after router mode is enabled."
        }
        routerGuestDownload.text = if (active) {
            getString(R.string.vcs_vpn_router_guest_download)
        } else {
            getString(R.string.vcs_vpn_router_guest_download_inactive)
        }
        routerGuestQr.setImageBitmap(createQrBitmap(qrValue))
    }

    private fun createQrBitmap(value: String): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, QR_SIZE, QR_SIZE)
        val bitmap = Bitmap.createBitmap(QR_SIZE, QR_SIZE, Bitmap.Config.ARGB_8888)
        for (x in 0 until QR_SIZE) {
            for (y in 0 until QR_SIZE) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    private fun labelForUplinkType(type: VpnRouterManager.UplinkType): Int {
        return when (type) {
            VpnRouterManager.UplinkType.MOBILE -> R.string.vcs_vpn_router_uplink_mobile
            VpnRouterManager.UplinkType.WIFI_SHARING -> R.string.vcs_vpn_router_uplink_wifi
            VpnRouterManager.UplinkType.ETHERNET -> R.string.vcs_vpn_router_uplink_ethernet
            VpnRouterManager.UplinkType.UNKNOWN -> R.string.vcs_vpn_router_uplink_unknown
        }
    }

    private companion object {
        val TEXT: Int = Color.parseColor("#AFC0CC")
        val GREEN: Int = Color.parseColor("#86EFAC")
        val YELLOW: Int = Color.parseColor("#FBBF24")
        val RED: Int = Color.parseColor("#F87171")
        const val QR_SIZE: Int = 512
    }
}
