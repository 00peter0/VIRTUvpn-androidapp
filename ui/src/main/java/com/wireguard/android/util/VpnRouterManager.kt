/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.util.Log
import com.wireguard.android.Application
import com.wireguard.android.backend.WgQuickBackend
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

object VpnRouterManager {
    enum class Availability {
        ENABLED,
        READY,
        WAITING_FOR_TUNNEL,
        WAITING_FOR_HOTSPOT,
        UNSUPPORTED,
        ERROR
    }

    data class Status(
        val availability: Availability,
        val activeTunnel: String? = null,
        val tetherInterfaces: List<String> = emptyList(),
        val detail: String? = null
    ) {
        val canEnable: Boolean
            get() = availability == Availability.READY
        val canDisable: Boolean
            get() = availability == Availability.ENABLED
    }

    suspend fun getStatus(context: Context): Status = withContext(Dispatchers.IO) {
        detect(context.applicationContext)
    }

    suspend fun enable(context: Context): Status = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val status = detect(appContext)
        if (!status.canEnable && !status.canDisable) return@withContext status
        val tunnelName = status.activeTunnel ?: return@withContext status.copy(availability = Availability.WAITING_FOR_TUNNEL)
        val tetherInterfaces = status.tetherInterfaces
        if (tetherInterfaces.isEmpty()) return@withContext status.copy(availability = Availability.WAITING_FOR_HOTSPOT)
        try {
            installRules(tunnelName, tetherInterfaces)
            detect(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to enable VPN router", e)
            status.copy(availability = Availability.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    suspend fun disable(context: Context): Status = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prior = runCatching { detect(appContext) }.getOrNull()
        try {
            removeRules()
            detect(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to disable VPN router", e)
            Status(
                availability = Availability.ERROR,
                activeTunnel = prior?.activeTunnel,
                tetherInterfaces = prior?.tetherInterfaces.orEmpty(),
                detail = e.message ?: e.javaClass.simpleName
            )
        }
    }

    private suspend fun detect(context: Context): Status {
        val backend = Application.getBackend()
        if (backend !is WgQuickBackend) {
            return Status(
                availability = Availability.UNSUPPORTED,
                detail = "kernel backend required"
            )
        }

        val runningTunnel = backend.runningTunnelNames.sorted().firstOrNull()
            ?: return Status(availability = Availability.WAITING_FOR_TUNNEL)
        val tetherInterfaces = readTetherInterfaces(runningTunnel)
        val hotspotActive = HotspotDetector.isWifiHotspotActive(context)
        if (isRuleChainInstalled()) {
            return Status(
                availability = Availability.ENABLED,
                activeTunnel = runningTunnel,
                tetherInterfaces = tetherInterfaces
            )
        }
        if (!hotspotActive) {
            return Status(
                availability = Availability.WAITING_FOR_HOTSPOT,
                activeTunnel = runningTunnel,
                tetherInterfaces = tetherInterfaces
            )
        }
        if (tetherInterfaces.isEmpty()) {
            return Status(
                availability = Availability.WAITING_FOR_HOTSPOT,
                activeTunnel = runningTunnel
            )
        }
        return Status(
            availability = Availability.READY,
            activeTunnel = runningTunnel,
            tetherInterfaces = tetherInterfaces
        )
    }

    private fun readTetherInterfaces(activeTunnel: String): List<String> {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(output, "ip -o link show up 2>/dev/null || ip -o link show 2>/dev/null")
        if (exit != 0) return emptyList()
        return output.asSequence()
            .mapNotNull { line -> line.substringAfter(": ", "").substringBefore(":").substringBefore("@").trim() }
            .filter { name -> isValidInterfaceName(name) }
            .filterNot { name -> name == activeTunnel || name == "lo" }
            .filter { name -> isTetherInterfaceCandidate(name) }
            .distinct()
            .toList()
    }

    private fun isRuleChainInstalled(): Boolean {
        return Application.getRootShell().run(
            null,
            "iptables -t nat -S $NAT_CHAIN >/dev/null 2>&1 && iptables -S $FORWARD_CHAIN >/dev/null 2>&1"
        ) == 0
    }

    private fun installRules(activeTunnel: String, tetherInterfaces: List<String>) {
        val tunnel = checkedInterfaceName(activeTunnel)
        val downstreams = tetherInterfaces.map(::checkedInterfaceName)

        checkedRun("enable IPv4 forwarding", "sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        checkedRun("prepare NAT chain", "iptables -t nat -N $NAT_CHAIN 2>/dev/null || true")
        checkedRun("prepare forward chain", "iptables -N $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN")
        checkedRun("clear forward chain", "iptables -F $FORWARD_CHAIN")
        checkedRun(
            "attach NAT chain",
            "iptables -t nat -C POSTROUTING -j $NAT_CHAIN 2>/dev/null || iptables -t nat -A POSTROUTING -j $NAT_CHAIN"
        )
        checkedRun(
            "attach forward chain",
            "iptables -C FORWARD -j $FORWARD_CHAIN 2>/dev/null || iptables -A FORWARD -j $FORWARD_CHAIN"
        )
        checkedRun("masquerade VPN egress", "iptables -t nat -A $NAT_CHAIN -o $tunnel -j MASQUERADE")
        downstreams.forEach { downstream ->
            checkedRun(
                "allow hotspot to VPN forwarding",
                "iptables -A $FORWARD_CHAIN -i $downstream -o $tunnel -j ACCEPT"
            )
            checkedRun(
                "allow VPN return traffic",
                "iptables -A $FORWARD_CHAIN -i $tunnel -o $downstream -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT || " +
                    "iptables -A $FORWARD_CHAIN -i $tunnel -o $downstream -m state --state RELATED,ESTABLISHED -j ACCEPT"
            )
        }
    }

    private fun removeRules() {
        checkedRun(
            "detach NAT chain",
            "iptables -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null || true"
        )
        checkedRun(
            "detach forward chain",
            "iptables -D FORWARD -j $FORWARD_CHAIN 2>/dev/null || true"
        )
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN 2>/dev/null || true")
        checkedRun("delete NAT chain", "iptables -t nat -X $NAT_CHAIN 2>/dev/null || true")
        checkedRun("clear forward chain", "iptables -F $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("delete forward chain", "iptables -X $FORWARD_CHAIN 2>/dev/null || true")
    }

    private fun checkedRun(label: String, command: String) {
        val exit = Application.getRootShell().run(null, command)
        if (exit != 0) throw IllegalStateException("$label failed with exit code $exit")
    }

    private fun checkedInterfaceName(name: String): String {
        if (!isValidInterfaceName(name)) throw IllegalArgumentException("invalid interface name: $name")
        return name
    }

    private fun isValidInterfaceName(name: String): Boolean {
        return name.length in 1..64 && INTERFACE_NAME_REGEX.matches(name)
    }

    private fun isTetherInterfaceCandidate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("ap") ||
            lower.startsWith("swlan") ||
            lower.startsWith("wlan") ||
            lower.startsWith("rndis") ||
            lower.startsWith("usb") ||
            lower.startsWith("bt-pan") ||
            lower.startsWith("pan") ||
            lower.startsWith("p2p")
    }

    private const val TAG = "VirtuVPN/Router"
    private const val NAT_CHAIN = "VIRTUVPN_ROUTER"
    private const val FORWARD_CHAIN = "VIRTUVPN_ROUTER_FWD"
    private val INTERFACE_NAME_REGEX = Regex("^[A-Za-z0-9_.:=-]+$")
}
