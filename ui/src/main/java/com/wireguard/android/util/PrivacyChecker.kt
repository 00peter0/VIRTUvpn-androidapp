/*
 * Copyright © 2025-2026 Virtu VPN (vcs.virtucomputing.com). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config

data class PrivacyResult(
    val score: Int,
    val vpnActive: Boolean,
    val dnsProtected: Boolean,
    val ipHidden: Boolean,
    val encrypted: Boolean,
    val wifiName: String?,
    val wifiTrusted: Boolean
)

object PrivacyChecker {

    fun check(context: Context, state: Tunnel.State?, config: Config?): PrivacyResult {
        val vpnActive = state == Tunnel.State.UP
        val dnsProtected = vpnActive && config?.`interface`?.dnsServers?.isNotEmpty() == true
        val ipHidden = vpnActive
        val encrypted = vpnActive // WireGuard always encrypts

        val wifiInfo = getWifiInfo(context)
        val wifiName = wifiInfo.first
        val wifiTrusted = wifiInfo.second

        var score = 0
        if (vpnActive) score += 30
        if (dnsProtected) score += 25
        if (ipHidden) score += 25
        if (encrypted) score += 20

        // Bonus/penalty for WiFi
        if (!vpnActive && !wifiTrusted) score = maxOf(score - 10, 0)

        return PrivacyResult(
            score = score,
            vpnActive = vpnActive,
            dnsProtected = dnsProtected,
            ipHidden = ipHidden,
            encrypted = encrypted,
            wifiName = wifiName,
            wifiTrusted = wifiTrusted
        )
    }

    @Suppress("deprecation")
    private fun getWifiInfo(context: Context): Pair<String?, Boolean> {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return Pair(null, false)
            val caps = cm.getNetworkCapabilities(network) ?: return Pair(null, false)

            val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            if (!isWifi) return Pair("Mobile Data", true)

            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val info = wifiManager?.connectionInfo
            var ssid = info?.ssid?.replace("\"", "") ?: "Unknown"
            if (ssid == "<unknown ssid>") ssid = "Unknown WiFi"

            // Simple heuristic: known home networks are "trusted"
            val trusted = ssid != "Unknown WiFi" && !ssid.contains("Free", ignoreCase = true) &&
                    !ssid.contains("Guest", ignoreCase = true) && !ssid.contains("Public", ignoreCase = true) &&
                    !ssid.contains("Hotel", ignoreCase = true) && !ssid.contains("Airport", ignoreCase = true) &&
                    !ssid.contains("Cafe", ignoreCase = true) && !ssid.contains("Starbucks", ignoreCase = true)

            return Pair(ssid, trusted)
        } catch (_: Exception) {
            return Pair(null, false)
        }
    }
}
