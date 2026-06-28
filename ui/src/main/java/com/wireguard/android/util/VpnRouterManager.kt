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
    enum class UplinkType {
        MOBILE,
        WIFI_SHARING,
        ETHERNET,
        UNKNOWN
    }

    data class Uplink(
        val interfaceName: String,
        val type: UplinkType
    )

    enum class DnsMode(val preferenceValue: String, val resolvers: List<String>) {
        COPY_TUNNEL("copy_tunnel", emptyList()),
        CLOUDFLARE("cloudflare", listOf("1.1.1.1", "1.0.0.1")),
        GOOGLE("google", listOf("8.8.8.8", "8.8.4.4")),
        QUAD9("quad9", listOf("9.9.9.9", "149.112.112.112")),
        FAMILY("family", listOf("1.1.1.3", "1.0.0.3"));

        companion object {
            fun fromPreference(value: String?): DnsMode {
                return values().firstOrNull { it.preferenceValue == value } ?: COPY_TUNNEL
            }
        }
    }

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
        val uplinkInterfaces: List<Uplink> = emptyList(),
        val dnsResolvers: List<String> = emptyList(),
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

    suspend fun reconcile(context: Context): Status = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val status = detect(appContext)
        if (status.availability != Availability.ENABLED) return@withContext status
        val tunnelName = status.activeTunnel ?: return@withContext status
        if (status.tetherInterfaces.isEmpty()) return@withContext status
        try {
            installRules(appContext, tunnelName, status.tetherInterfaces)
            detect(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to reconcile VPN router", e)
            status.copy(availability = Availability.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
    }

    fun getDnsMode(context: Context): DnsMode {
        return DnsMode.fromPreference(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_DNS_MODE, DnsMode.COPY_TUNNEL.preferenceValue)
        )
    }

    fun setDnsMode(context: Context, mode: DnsMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DNS_MODE, mode.preferenceValue)
            .apply()
    }

    suspend fun enable(context: Context): Status = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val status = detect(appContext)
        if (!status.canEnable && !status.canDisable) return@withContext status
        val tunnelName = status.activeTunnel ?: return@withContext status.copy(availability = Availability.WAITING_FOR_TUNNEL)
        val tetherInterfaces = status.tetherInterfaces
        if (tetherInterfaces.isEmpty()) return@withContext status.copy(availability = Availability.WAITING_FOR_HOTSPOT)
        try {
            installRules(appContext, tunnelName, tetherInterfaces)
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
        val installed = runCatching { isRuleChainInstalled() }.getOrElse { e ->
            return Status(
                availability = Availability.UNSUPPORTED,
                detail = e.message ?: "root required"
            )
        }

        val runningTunnel = readVpnInterfaces().firstOrNull()
        val allUpInterfaces = readUpInterfaces()
        if (runningTunnel == null) {
            val dnsResolvers = resolveDnsResolvers(context, null)
            return Status(
                availability = if (installed) Availability.ENABLED else Availability.WAITING_FOR_TUNNEL,
                uplinkInterfaces = readUplinkInterfaces(allUpInterfaces, null, emptyList()),
                dnsResolvers = dnsResolvers,
                detail = if (installed) "router rules installed; no active VPN interface detected" else null
            )
        }
        val tetherInterfaces = readTetherInterfaces(runningTunnel, allUpInterfaces)
        val uplinkInterfaces = readUplinkInterfaces(allUpInterfaces, runningTunnel, tetherInterfaces)
        val dnsResolvers = resolveDnsResolvers(context, runningTunnel)
        val hotspotActive = HotspotDetector.isWifiHotspotActive(context)
        if (installed) {
            return Status(
                availability = Availability.ENABLED,
                activeTunnel = runningTunnel,
                tetherInterfaces = tetherInterfaces,
                uplinkInterfaces = uplinkInterfaces,
                dnsResolvers = dnsResolvers
            )
        }
        if (!hotspotActive) {
            return Status(
                availability = Availability.WAITING_FOR_HOTSPOT,
                activeTunnel = runningTunnel,
                tetherInterfaces = tetherInterfaces,
                uplinkInterfaces = uplinkInterfaces,
                dnsResolvers = dnsResolvers
            )
        }
        if (tetherInterfaces.isEmpty()) {
            return Status(
                availability = Availability.WAITING_FOR_HOTSPOT,
                activeTunnel = runningTunnel,
                uplinkInterfaces = uplinkInterfaces,
                dnsResolvers = dnsResolvers
            )
        }
        return Status(
            availability = Availability.READY,
            activeTunnel = runningTunnel,
            tetherInterfaces = tetherInterfaces,
            uplinkInterfaces = uplinkInterfaces,
            dnsResolvers = dnsResolvers
        )
    }

    private suspend fun readVpnInterfaces(): List<String> {
        val names = linkedSetOf<String>()
        val backend = Application.getBackend()
        if (backend is WgQuickBackend) {
            names += backend.runningTunnelNames
        }
        names += readUpInterfaces()
            .filter { name -> isValidInterfaceName(name) }
            .filter { name -> isVpnInterfaceCandidate(name) }
        return names.toList().sortedWith(compareBy(::vpnInterfacePriority, { it }))
    }

    private fun readTetherInterfaces(activeTunnel: String, upInterfaces: List<String>): List<String> {
        return upInterfaces.asSequence()
            .filter { name -> isValidInterfaceName(name) }
            .filterNot { name -> name == activeTunnel || name == "lo" }
            .filter { name -> isTetherInterfaceCandidate(name) }
            .distinct()
            .toList()
    }

    private fun readUplinkInterfaces(
        upInterfaces: List<String>,
        activeTunnel: String?,
        tetherInterfaces: List<String>
    ): List<Uplink> {
        val excluded = (tetherInterfaces + listOfNotNull(activeTunnel) + "lo").toSet()
        val routeUplinks = readDefaultRouteInterfaces().asSequence()
            .filter { name -> isValidInterfaceName(name) }
            .filterNot { name -> name in excluded }
            .filterNot { name -> isVpnInterfaceCandidate(name) }
            .distinct()
            .toList()
        val uplinks = routeUplinks.ifEmpty {
            upInterfaces.asSequence()
                .filter { name -> isValidInterfaceName(name) }
                .filterNot { name -> name in excluded }
                .filterNot { name -> isVpnInterfaceCandidate(name) }
                .filter { name -> isPhysicalUplinkCandidate(name) }
                .distinct()
                .toList()
        }
        return uplinks.asSequence()
            .map { name -> Uplink(name, classifyUplink(name)) }
            .toList()
    }

    private fun readDefaultRouteInterfaces(): List<String> {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(
            output,
            "ip route show table all 2>/dev/null; ip -6 route show table all 2>/dev/null"
        )
        if (exit != 0) return emptyList()
        return output.asSequence()
            .map { line -> line.trim() }
            .filter { line -> line.startsWith("default ") }
            .mapNotNull { line ->
                Regex("""\bdev\s+([^ ]+)""").find(line)?.groupValues?.getOrNull(1)
            }
            .map { name -> name.substringBefore("@").trim() }
            .filter { name -> name.isNotBlank() }
            .distinct()
            .toList()
    }

    private fun readUpInterfaces(): List<String> {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(output, "ip -o link show up 2>/dev/null || ip -o link show 2>/dev/null")
        if (exit != 0) return emptyList()
        return output.asSequence()
            .mapNotNull { line -> line.substringAfter(": ", "").substringBefore(":").substringBefore("@").trim() }
            .distinct()
            .toList()
    }

    private fun isRuleChainInstalled(): Boolean {
        return Application.getRootShell().run(
            null,
            "iptables -t nat -S $NAT_CHAIN >/dev/null 2>&1 && iptables -S $FORWARD_CHAIN >/dev/null 2>&1"
        ) == 0
    }

    private suspend fun resolveDnsResolvers(context: Context, activeTunnel: String?): List<String> {
        val mode = getDnsMode(context)
        if (mode != DnsMode.COPY_TUNNEL) return mode.resolvers
        return readTunnelConfigDns(activeTunnel)
            .ifEmpty { readAndroidDnsProperties() }
            .ifEmpty { DnsMode.QUAD9.resolvers }
            .filter { resolver -> isIpv4Address(resolver) }
            .distinct()
            .take(MAX_DNS_RESOLVERS)
    }

    private suspend fun readTunnelConfigDns(activeTunnel: String?): List<String> {
        if (activeTunnel == null) return emptyList()
        return runCatching {
            val tunnels = Application.getTunnelManager().getTunnels()
            val tunnel = tunnels[activeTunnel] ?: tunnels.firstOrNull { it.name == activeTunnel }
            tunnel?.getConfigAsync()?.`interface`?.dnsServers.orEmpty()
                .mapNotNull { address -> address.hostAddress }
                .filter { resolver -> isIpv4Address(resolver) }
        }.getOrDefault(emptyList())
    }

    private fun readAndroidDnsProperties(): List<String> {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(
            output,
            "getprop 2>/dev/null | sed -n 's/^\\[net\\.dns[0-9]*\\]: \\[\\([^]]*\\)\\]/\\1/p; s/^\\[net\\.[^.]*\\.dns[0-9]*\\]: \\[\\([^]]*\\)\\]/\\1/p'"
        )
        if (exit != 0) return emptyList()
        return output.asSequence()
            .map { line -> line.trim() }
            .filter { resolver -> isIpv4Address(resolver) }
            .distinct()
            .take(MAX_DNS_RESOLVERS)
            .toList()
    }

    private suspend fun installRules(context: Context, activeTunnel: String, tetherInterfaces: List<String>) {
        val tunnel = checkedInterfaceName(activeTunnel)
        val downstreams = tetherInterfaces.map(::checkedInterfaceName)
        val dnsResolver = runCatching { resolveDnsResolvers(context, activeTunnel).firstOrNull() }
            .getOrElse { DnsMode.QUAD9.resolvers }
            ?: DnsMode.QUAD9.resolvers.first()

        checkedRun("enable IPv4 forwarding", "sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        checkedRun("prepare NAT chain", "iptables -t nat -N $NAT_CHAIN 2>/dev/null || true")
        checkedRun("prepare DNS chain", "iptables -t nat -N $DNS_CHAIN 2>/dev/null || true")
        checkedRun("prepare forward chain", "iptables -N $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN")
        checkedRun("clear DNS chain", "iptables -t nat -F $DNS_CHAIN")
        checkedRun("clear forward chain", "iptables -F $FORWARD_CHAIN")
        checkedRun(
            "attach NAT chain",
            "iptables -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null || true; " +
                "iptables -t nat -I POSTROUTING 1 -j $NAT_CHAIN"
        )
        checkedRun(
            "attach DNS chain",
            "iptables -t nat -D PREROUTING -j $DNS_CHAIN 2>/dev/null || true; " +
                "iptables -t nat -I PREROUTING 1 -j $DNS_CHAIN"
        )
        checkedRun(
            "attach forward chain",
            "iptables -D FORWARD -j $FORWARD_CHAIN 2>/dev/null || true; " +
                "iptables -I FORWARD 1 -j $FORWARD_CHAIN"
        )
        checkedRun("masquerade VPN egress", "iptables -t nat -A $NAT_CHAIN -o $tunnel -j MASQUERADE")
        checkedRun("clear stale hotspot VPN routes", "while ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY 2>/dev/null; do :; done")
        downstreams.forEach { downstream ->
            checkedRun(
                "route hotspot UDP DNS",
                "iptables -t nat -A $DNS_CHAIN -i $downstream -p udp --dport 53 -j DNAT --to-destination $dnsResolver"
            )
            checkedRun(
                "route hotspot TCP DNS",
                "iptables -t nat -A $DNS_CHAIN -i $downstream -p tcp --dport 53 -j DNAT --to-destination $dnsResolver"
            )
            checkedRun(
                "route hotspot traffic to VPN",
                "ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream 2>/dev/null || true; " +
                    "ip rule add pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream lookup $tunnel"
            )
            checkedRun(
                "allow hotspot to VPN forwarding",
                "iptables -A $FORWARD_CHAIN -i $downstream -o $tunnel -j ACCEPT"
            )
            checkedRun(
                "allow VPN return traffic",
                "iptables -A $FORWARD_CHAIN -i $tunnel -o $downstream -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT || " +
                    "iptables -A $FORWARD_CHAIN -i $tunnel -o $downstream -m state --state RELATED,ESTABLISHED -j ACCEPT"
            )
            checkedRun(
                "block hotspot bypass traffic",
                "iptables -A $FORWARD_CHAIN -i $downstream -j REJECT"
            )
        }
        checkedRun("flush route cache", "ip route flush cache 2>/dev/null || true")
    }

    private fun removeRules() {
        checkedRun("remove hotspot VPN routes", "while ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY 2>/dev/null; do :; done")
        checkedRun("flush route cache", "ip route flush cache 2>/dev/null || true")
        checkedRun(
            "detach NAT chain",
            "iptables -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null || true"
        )
        checkedRun(
            "detach DNS chain",
            "iptables -t nat -D PREROUTING -j $DNS_CHAIN 2>/dev/null || true"
        )
        checkedRun(
            "detach forward chain",
            "iptables -D FORWARD -j $FORWARD_CHAIN 2>/dev/null || true"
        )
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN 2>/dev/null || true")
        checkedRun("delete NAT chain", "iptables -t nat -X $NAT_CHAIN 2>/dev/null || true")
        checkedRun("clear DNS chain", "iptables -t nat -F $DNS_CHAIN 2>/dev/null || true")
        checkedRun("delete DNS chain", "iptables -t nat -X $DNS_CHAIN 2>/dev/null || true")
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

    private fun isIpv4Address(address: String): Boolean {
        val parts = address.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toIntOrNull() in 0..255
        }
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

    private fun isPhysicalUplinkCandidate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("rmnet") ||
            lower.startsWith("ccmni") ||
            lower.startsWith("pdp") ||
            lower.startsWith("wwan") ||
            lower.startsWith("wlan") ||
            lower.startsWith("eth") ||
            lower.startsWith("usb")
    }

    private fun classifyUplink(name: String): UplinkType {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("rmnet") || lower.startsWith("ccmni") || lower.startsWith("pdp") ||
                lower.startsWith("wwan") -> UplinkType.MOBILE
            lower.startsWith("wlan") -> UplinkType.WIFI_SHARING
            lower.startsWith("eth") || lower.startsWith("usb") -> UplinkType.ETHERNET
            else -> UplinkType.UNKNOWN
        }
    }

    private fun isVpnInterfaceCandidate(name: String): Boolean {
        val lower = name.lowercase(Locale.ROOT)
        return lower.startsWith("tun") || lower.startsWith("wg")
    }

    private fun vpnInterfacePriority(name: String): Int {
        val lower = name.lowercase(Locale.ROOT)
        return when {
            lower.startsWith("wg") -> 0
            lower.startsWith("tun") -> 1
            else -> 2
        }
    }

    private const val TAG = "VirtuVPN/Router"
    private const val PREFS = "virtuvpn_router"
    private const val KEY_DNS_MODE = "dns_mode"
    private const val NAT_CHAIN = "VIRTUVPN_ROUTER"
    private const val DNS_CHAIN = "VIRTUVPN_ROUTER_DNS"
    private const val FORWARD_CHAIN = "VIRTUVPN_ROUTER_FWD"
    private const val HOTSPOT_VPN_RULE_PRIORITY = 20900
    private const val MAX_DNS_RESOLVERS = 2
    private val INTERFACE_NAME_REGEX = Regex("^[A-Za-z0-9_.:=-]+$")
}
