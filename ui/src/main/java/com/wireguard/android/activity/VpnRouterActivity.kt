/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.app.DownloadManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wireguard.android.R
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.util.VpnRouterAttestation
import com.wireguard.android.util.VpnRouterManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class VpnRouterActivity : AppCompatActivity() {
    private lateinit var routerStatus: TextView
    private lateinit var routerUplinkStatus: TextView
    private lateinit var routerProtectionStatus: TextView
    private lateinit var routerGuestAccessStatus: TextView
    private lateinit var routerGuestDownload: TextView
    private lateinit var routerGuestQr: ImageView
    private lateinit var routerDnsGroup: RadioGroup
    private var routerMonitorJob: Job? = null
    private var operationDialog: AlertDialog? = null
    private var operationDialogMessage: TextView? = null
    private var refreshing = false
    private var lastActiveTunnel: String? = null

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
        routerDnsGroup = findViewById(R.id.router_dns_group)
        routerGuestDownload.setOnClickListener {
            downloadGuestApp()
        }
        routerDnsGroup.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.router_dns_cloudflare -> VpnRouterManager.DnsMode.CLOUDFLARE
                R.id.router_dns_quad9 -> VpnRouterManager.DnsMode.QUAD9
                R.id.router_dns_family -> VpnRouterManager.DnsMode.FAMILY
                else -> VpnRouterManager.DnsMode.COPY_TUNNEL
            }
            VpnRouterManager.setDnsMode(this, mode)
            lifecycleScope.launch {
                val router = VpnRouterManager.reconcile(this@VpnRouterActivity)
                renderRouterStatus(router)
            }
        }
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

    private fun showOperationDialog() {
        if (operationDialog?.isShowing == true) return
        operationDialogMessage = TextView(this).apply {
            text = operationMessage(VpnRouterManager.getOperationStatus(this@VpnRouterActivity))
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
        operationDialogMessage?.text = operationMessage(status)
    }

    private fun dismissOperationDialog() {
        operationDialog?.dismiss()
        operationDialog = null
        operationDialogMessage = null
    }

    private fun operationMessage(status: VpnRouterManager.OperationStatus): String {
        val activeStage = when (status.stage) {
            VpnRouterManager.OperationStage.LOCKING_HOTSPOT -> 0
            VpnRouterManager.OperationStage.DETECTING_TUNNEL -> 1
            VpnRouterManager.OperationStage.APPLYING_DNS -> 2
            VpnRouterManager.OperationStage.APPLYING_FIREWALL -> 3
            VpnRouterManager.OperationStage.VERIFYING_RULES -> 4
            VpnRouterManager.OperationStage.COMPLETE -> 5
            VpnRouterManager.OperationStage.ERROR -> 6
            VpnRouterManager.OperationStage.IDLE -> -1
        }
        val labels = listOf(
            getString(R.string.vcs_vpn_router_operation_locking),
            getString(R.string.vcs_vpn_router_operation_detecting),
            getString(R.string.vcs_vpn_router_operation_dns),
            getString(R.string.vcs_vpn_router_operation_firewall),
            getString(R.string.vcs_vpn_router_operation_verify)
        )
        val prefix = labels.mapIndexed { index, label ->
            val marker = when {
                activeStage > index -> "[done]"
                activeStage == index -> "[now]"
                else -> "[wait]"
            }
            "$marker $label"
        }.joinToString("\n")
        val detail = status.detail?.takeIf { it.isNotBlank() } ?: getString(R.string.vcs_vpn_router_operation_default_detail)
        return "$prefix\n\n$detail"
    }

    private fun renderDnsMode() {
        val checkedId = when (VpnRouterManager.getDnsMode(this)) {
            VpnRouterManager.DnsMode.CLOUDFLARE -> R.id.router_dns_cloudflare
            VpnRouterManager.DnsMode.QUAD9 -> R.id.router_dns_quad9
            VpnRouterManager.DnsMode.FAMILY -> R.id.router_dns_family
            VpnRouterManager.DnsMode.COPY_TUNNEL -> R.id.router_dns_copy_tunnel
        }
        if (routerDnsGroup.checkedRadioButtonId != checkedId) {
            routerDnsGroup.check(checkedId)
        }
    }

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
                routerStatus.setText(R.string.vcs_vpn_router_unsupported)
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
        val qrValue = if (active) VpnRouterAttestation.pairingUri(this) else VIRTUVPN_DOWNLOAD_URL
        routerGuestDownload.text = if (active) {
            getString(R.string.vcs_vpn_router_guest_download)
        } else {
            getString(R.string.vcs_vpn_router_guest_download)
        }
        routerGuestQr.setImageBitmap(createQrBitmap(qrValue))
    }

    private fun downloadGuestApp() {
        val request = DownloadManager.Request(Uri.parse(VIRTUVPN_DOWNLOAD_URL))
            .setTitle(getString(R.string.vcs_vpn_router_guest_download))
            .setDescription(getString(R.string.vcs_vpn_router_guest_download_description))
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, GUEST_APK_FILE_NAME)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
        val manager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        runCatching { manager.enqueue(request) }
            .onSuccess {
                Toast.makeText(this, R.string.vcs_vpn_router_guest_download_started, Toast.LENGTH_LONG).show()
            }
            .onFailure { e ->
                Toast.makeText(this, getString(R.string.vcs_vpn_router_guest_download_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
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
        const val VIRTUVPN_DOWNLOAD_URL: String = "https://vcs.virtucomputing.com/api/mobile/android/apk/guest"
        const val GUEST_APK_FILE_NAME: String = "virtuvpn-guest.apk"
    }
}
