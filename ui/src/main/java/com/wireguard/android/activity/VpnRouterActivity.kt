/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.wireguard.android.R
import com.wireguard.android.util.VpnRouterManager
import kotlinx.coroutines.launch

class VpnRouterActivity : AppCompatActivity() {
    private lateinit var routerStatus: TextView
    private lateinit var routerUplinkStatus: TextView
    private lateinit var routerProtectionStatus: TextView
    private lateinit var routerGuestAccessStatus: TextView
    private lateinit var routerGuestDashboard: TextView
    private lateinit var routerGuestQr: ImageView
    private lateinit var routerDnsGroup: RadioGroup

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.vcs_vpn_router_page_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setContentView(R.layout.vpn_router_activity)
        routerStatus = findViewById(R.id.router_status)
        routerUplinkStatus = findViewById(R.id.router_uplink_status)
        routerProtectionStatus = findViewById(R.id.router_protection_status)
        routerGuestAccessStatus = findViewById(R.id.router_guest_access_status)
        routerGuestDashboard = findViewById(R.id.router_guest_dashboard)
        routerGuestQr = findViewById(R.id.router_guest_qr)
        routerDnsGroup = findViewById(R.id.router_dns_group)
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
        refreshStatus()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        lifecycleScope.launch {
            val router = runCatching { VpnRouterManager.getStatus(this@VpnRouterActivity) }
                .mapCatching { current ->
                    if (current.availability == VpnRouterManager.Availability.ENABLED) {
                        VpnRouterManager.reconcile(this@VpnRouterActivity)
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
        }
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
        val gateway = status.tetherInterfaces.firstOrNull()?.let { interfaceName ->
            if (interfaceName == "swlan0") "192.168.115.186" else null
        } ?: "192.168.115.186"
        val dashboardUrl = "http://$gateway:8787/"
        routerGuestDashboard.text = getString(R.string.vcs_vpn_router_guest_dashboard_with_address, dashboardUrl)
        routerGuestQr.setImageBitmap(createQrBitmap(dashboardUrl))
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
