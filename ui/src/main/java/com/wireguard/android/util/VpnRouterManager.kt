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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        QUAD9("quad9", listOf("9.9.9.9", "149.112.112.112")),
        FAMILY("family", listOf("1.1.1.3", "1.0.0.3"));

        companion object {
            fun fromPreference(value: String?): DnsMode {
                if (value == "google") return QUAD9
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

    enum class OperationStage {
        IDLE,
        LOCKING_HOTSPOT,
        DETECTING_TUNNEL,
        APPLYING_DNS,
        APPLYING_FIREWALL,
        VERIFYING_RULES,
        COMPLETE,
        ERROR
    }

    data class OperationStatus(
        val stage: OperationStage,
        val detail: String? = null
    ) {
        val active: Boolean
            get() = stage != OperationStage.IDLE && stage != OperationStage.COMPLETE && stage != OperationStage.ERROR
    }

    suspend fun getStatus(context: Context): Status = withContext(Dispatchers.IO) {
        detect(context.applicationContext)
    }

    fun getOperationStatus(context: Context): OperationStatus {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stage = runCatching {
            OperationStage.valueOf(prefs.getString(KEY_OPERATION_STAGE, OperationStage.IDLE.name) ?: OperationStage.IDLE.name)
        }.getOrDefault(OperationStage.IDLE)
        return OperationStatus(stage, prefs.getString(KEY_OPERATION_DETAIL, null))
    }

    suspend fun reconcile(context: Context): Status = withContext(Dispatchers.IO) {
        routerMutex.withLock {
        val appContext = context.applicationContext
        val status = detect(appContext)
        if (status.availability != Availability.ENABLED) return@withLock status
        setOperation(appContext, OperationStage.LOCKING_HOTSPOT, "Blocking hotspot fallback before changing VPN routes")
        VpnRouterGuestServer.ensureStarted(appContext, Application.getCoroutineScope())
        disableHotspotAutoShutdown(appContext)
        setOperation(appContext, OperationStage.DETECTING_TUNNEL, "Detecting active VPN interface")
        val tunnelName = status.activeTunnel ?: return@withLock status
        if (status.tetherInterfaces.isEmpty()) return@withLock status
        try {
            installRules(appContext, tunnelName, status.tetherInterfaces)
            setOperation(appContext, OperationStage.COMPLETE, "VPN router is protected")
            detect(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to reconcile VPN router", e)
            setOperation(appContext, OperationStage.ERROR, e.message ?: e.javaClass.simpleName)
            status.copy(availability = Availability.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
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
        routerMutex.withLock {
        val appContext = context.applicationContext
        val status = detect(appContext)
        if (!status.canEnable && !status.canDisable) return@withLock status
        val tunnelName = status.activeTunnel ?: return@withLock status.copy(availability = Availability.WAITING_FOR_TUNNEL)
        val tetherInterfaces = status.tetherInterfaces
        if (tetherInterfaces.isEmpty()) return@withLock status.copy(availability = Availability.WAITING_FOR_HOTSPOT)
        try {
            setOperation(appContext, OperationStage.LOCKING_HOTSPOT, "Blocking hotspot fallback before enabling router")
            installRules(appContext, tunnelName, tetherInterfaces)
            setOperation(appContext, OperationStage.COMPLETE, "VPN router is protected")
            detect(appContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to enable VPN router", e)
            setOperation(appContext, OperationStage.ERROR, e.message ?: e.javaClass.simpleName)
            status.copy(availability = Availability.ERROR, detail = e.message ?: e.javaClass.simpleName)
        }
        }
    }

    suspend fun disable(context: Context): Status = withContext(Dispatchers.IO) {
        routerMutex.withLock {
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
            .filter { name -> isPhysicalUplinkCandidate(name) }
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
            .filter { line -> hasUsableLinkState(line) }
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
        setOperation(context, OperationStage.DETECTING_TUNNEL, "Using VPN interface $activeTunnel")
        val tunnel = checkedInterfaceName(activeTunnel)
        val downstreams = tetherInterfaces.map(::checkedInterfaceName)
        val uplinks = readUplinkInterfaces(readUpInterfaces(), activeTunnel, downstreams)
            .map { uplink -> checkedInterfaceName(uplink.interfaceName) }
        val dnsResolvers = runCatching { resolveDnsResolvers(context, activeTunnel) }
            .getOrElse { DnsMode.QUAD9.resolvers }
            .ifEmpty { DnsMode.QUAD9.resolvers }
        val dnsResolver = dnsResolvers.first()
        val vpnOwnerUid = readVpnOwnerUid()

        VpnRouterGuestServer.ensureStarted(context, Application.getCoroutineScope())
        disableHotspotAutoShutdown(context)
        disableTetherOffload(context)
        setOperation(context, OperationStage.APPLYING_DNS, "Applying router DNS for hotspot clients")
        overrideTetherDnsForwarders(dnsResolvers)
        checkedRun("enable IPv4 forwarding", "sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        setOperation(context, OperationStage.APPLYING_FIREWALL, "Installing fail-closed firewall and VPN routes")
        checkedRun("prepare NAT chain", "iptables -t nat -N $NAT_CHAIN 2>/dev/null || true")
        checkedRun("prepare DNS chain", "iptables -t nat -N $DNS_CHAIN 2>/dev/null || true")
        checkedRun("prepare forward chain", "iptables -N $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("prepare output chain", "iptables -N $OUTPUT_CHAIN 2>/dev/null || true")
        checkedRun("prepare IPv6 forward chain", "ip6tables -N $IPV6_FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN")
        checkedRun("clear DNS chain", "iptables -t nat -F $DNS_CHAIN")
        checkedRun("clear forward chain", "iptables -F $FORWARD_CHAIN")
        checkedRun("remove legacy access chain", "iptables -F VIRTUVPN_ROUTER_ACCESS 2>/dev/null || true; iptables -X VIRTUVPN_ROUTER_ACCESS 2>/dev/null || true")
        checkedRun("clear output chain", "iptables -F $OUTPUT_CHAIN")
        checkedRun("clear IPv6 forward chain", "ip6tables -F $IPV6_FORWARD_CHAIN")
        checkedRun(
            "attach NAT chain",
            "while iptables -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null; do :; done; " +
                "iptables -t nat -I POSTROUTING 1 -j $NAT_CHAIN"
        )
        checkedRun(
            "attach DNS chain",
            "while iptables -t nat -D PREROUTING -j $DNS_CHAIN 2>/dev/null; do :; done; " +
                "iptables -t nat -I PREROUTING 1 -j $DNS_CHAIN"
        )
        checkedRun(
            "remove legacy portal chain",
            "while iptables -t nat -D PREROUTING -j VIRTUVPN_ROUTER_PORTAL 2>/dev/null; do :; done; " +
                "iptables -t nat -F VIRTUVPN_ROUTER_PORTAL 2>/dev/null || true; " +
                "iptables -t nat -X VIRTUVPN_ROUTER_PORTAL 2>/dev/null || true"
        )
        checkedRun(
            "attach forward chain",
            "while iptables -D FORWARD -j $FORWARD_CHAIN 2>/dev/null; do :; done; " +
                "iptables -I FORWARD 1 -j $FORWARD_CHAIN"
        )
        checkedRun(
            "attach output chain",
            "while iptables -D OUTPUT -j $OUTPUT_CHAIN 2>/dev/null; do :; done; " +
                "iptables -I OUTPUT 1 -j $OUTPUT_CHAIN"
        )
        checkedRun(
            "attach IPv6 forward chain",
            "while ip6tables -D FORWARD -j $IPV6_FORWARD_CHAIN 2>/dev/null; do :; done; " +
                "ip6tables -I FORWARD 1 -j $IPV6_FORWARD_CHAIN"
        )
        checkedRun("masquerade VPN egress", "iptables -t nat -A $NAT_CHAIN -o $tunnel -j MASQUERADE")
        checkedRun("allow phone VPN egress", "iptables -A $OUTPUT_CHAIN -o $tunnel -j RETURN")
        checkedRun("allow WireGuard fwmark transport", "iptables -A $OUTPUT_CHAIN -m mark --mark 0x20000 -j RETURN || true")
        if (vpnOwnerUid != null) {
            checkedRun("allow active VPN provider transport", "iptables -A $OUTPUT_CHAIN -m owner --uid-owner $vpnOwnerUid -j RETURN")
        }
        uplinks.forEach { uplink ->
            checkedRun("block phone uplink bypass", "iptables -A $OUTPUT_CHAIN -o $uplink -j REJECT")
        }
        checkedRun("finish output chain", "iptables -A $OUTPUT_CHAIN -j RETURN")
        checkedRun("prepare hotspot fallback block route", "ip route replace unreachable default table $HOTSPOT_BLOCK_ROUTE_TABLE")
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
                "block hotspot mobile fallback",
                "ip rule del pref $HOTSPOT_BLOCK_RULE_PRIORITY iif $downstream 2>/dev/null || true; " +
                    "ip rule add pref $HOTSPOT_BLOCK_RULE_PRIORITY iif $downstream lookup $HOTSPOT_BLOCK_ROUTE_TABLE"
            )
            checkedRun(
                "route hotspot traffic to VPN",
                "ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream 2>/dev/null || true; " +
                    "ip rule add pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream lookup $tunnel"
            )
            checkedRun(
                "block hotspot DNS over TLS",
                "iptables -A $FORWARD_CHAIN -i $downstream -p tcp --dport 853 -j REJECT --reject-with tcp-reset"
            )
            checkedRun(
                "block hotspot DNS over QUIC",
                "iptables -A $FORWARD_CHAIN -i $downstream -p udp --dport 853 -j REJECT --reject-with icmp-port-unreachable"
            )
            encryptedDnsBlocklist(dnsResolvers).forEach { resolver ->
                checkedRun(
                    "block hotspot DoH TCP $resolver",
                    "iptables -A $FORWARD_CHAIN -i $downstream -d $resolver -p tcp --dport 443 -j REJECT --reject-with tcp-reset"
                )
                checkedRun(
                    "block hotspot DoH QUIC $resolver",
                    "iptables -A $FORWARD_CHAIN -i $downstream -d $resolver -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable"
                )
            }
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
            checkedRun(
                "block hotspot IPv6 bypass traffic",
                "ip6tables -A $IPV6_FORWARD_CHAIN -i $downstream -j REJECT"
            )
        }
        setOperation(context, OperationStage.VERIFYING_RULES, "Verifying VPN route and mobile fallback block")
        downstreams.forEach { downstream ->
            checkedRun(
                "verify hotspot routing",
                "ip rule show | grep -q \"^$HOTSPOT_VPN_RULE_PRIORITY:.*iif $downstream .*lookup $tunnel\" && " +
                    "ip rule show | grep -q \"^$HOTSPOT_BLOCK_RULE_PRIORITY:.*iif $downstream .*lookup $HOTSPOT_BLOCK_ROUTE_TABLE\" && " +
                    "ip route show table $HOTSPOT_BLOCK_ROUTE_TABLE | grep -q \"unreachable default\""
            )
        }
        checkedRun("flush route cache", "ip route flush cache 2>/dev/null || true")
    }

    private fun setOperation(context: Context, stage: OperationStage, detail: String? = null) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OPERATION_STAGE, stage.name)
            .putString(KEY_OPERATION_DETAIL, detail ?: "")
            .apply()
    }

    private fun removeRules() {
        checkedRun(
            "remove hotspot VPN routes",
            "while ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY 2>/dev/null; do :; done; " +
                "while ip rule del pref $HOTSPOT_BLOCK_RULE_PRIORITY 2>/dev/null; do :; done; " +
                "ip route flush table $HOTSPOT_BLOCK_ROUTE_TABLE 2>/dev/null || true"
        )
        checkedRun("flush route cache", "ip route flush cache 2>/dev/null || true")
        checkedRun(
            "detach NAT chain",
            "iptables -t nat -D POSTROUTING -j $NAT_CHAIN 2>/dev/null || true"
        )
        checkedRun(
            "detach DNS chain",
            "iptables -t nat -D PREROUTING -j $DNS_CHAIN 2>/dev/null || true"
        )
        checkedRun("detach legacy portal chain", "while iptables -t nat -D PREROUTING -j VIRTUVPN_ROUTER_PORTAL 2>/dev/null; do :; done")
        checkedRun(
            "detach forward chain",
            "iptables -D FORWARD -j $FORWARD_CHAIN 2>/dev/null || true"
        )
        checkedRun(
            "detach output chain",
            "iptables -D OUTPUT -j $OUTPUT_CHAIN 2>/dev/null || true"
        )
        checkedRun(
            "detach IPv6 forward chain",
            "ip6tables -D FORWARD -j $IPV6_FORWARD_CHAIN 2>/dev/null || true"
        )
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN 2>/dev/null || true")
        checkedRun("delete NAT chain", "iptables -t nat -X $NAT_CHAIN 2>/dev/null || true")
        checkedRun("clear DNS chain", "iptables -t nat -F $DNS_CHAIN 2>/dev/null || true")
        checkedRun("delete DNS chain", "iptables -t nat -X $DNS_CHAIN 2>/dev/null || true")
        checkedRun("clear legacy portal chain", "iptables -t nat -F VIRTUVPN_ROUTER_PORTAL 2>/dev/null || true")
        checkedRun("delete legacy portal chain", "iptables -t nat -X VIRTUVPN_ROUTER_PORTAL 2>/dev/null || true")
        checkedRun("clear forward chain", "iptables -F $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("delete forward chain", "iptables -X $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("clear output chain", "iptables -F $OUTPUT_CHAIN 2>/dev/null || true")
        checkedRun("delete output chain", "iptables -X $OUTPUT_CHAIN 2>/dev/null || true")
        checkedRun("clear IPv6 forward chain", "ip6tables -F $IPV6_FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("delete IPv6 forward chain", "ip6tables -X $IPV6_FORWARD_CHAIN 2>/dev/null || true")
        restoreHotspotAutoShutdown()
        restoreTetherOffload()
    }

    private fun disableHotspotAutoShutdown(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WIFI_AP_TIMEOUT_PREVIOUS)) {
            prefs.edit().putString(KEY_WIFI_AP_TIMEOUT_PREVIOUS, readSecureSetting(WIFI_AP_TIMEOUT_SETTING) ?: "").apply()
        }
        checkedRun("disable hotspot auto shutdown", "settings put secure $WIFI_AP_TIMEOUT_SETTING 0")
    }

    private fun restoreHotspotAutoShutdown() {
        val prefs = Application.get().applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WIFI_AP_TIMEOUT_PREVIOUS)) return
        val previous = prefs.getString(KEY_WIFI_AP_TIMEOUT_PREVIOUS, "") ?: ""
        if (previous.isBlank() || previous == "null") {
            checkedRun("restore hotspot auto shutdown", "settings delete secure $WIFI_AP_TIMEOUT_SETTING 2>/dev/null || true")
        } else {
            checkedRun("restore hotspot auto shutdown", "settings put secure $WIFI_AP_TIMEOUT_SETTING $previous")
        }
        prefs.edit().remove(KEY_WIFI_AP_TIMEOUT_PREVIOUS).apply()
    }

    private fun disableTetherOffload(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TETHER_OFFLOAD_PREVIOUS)) {
            prefs.edit().putString(KEY_TETHER_OFFLOAD_PREVIOUS, readGlobalSetting(TETHER_OFFLOAD_DISABLED_SETTING) ?: "").apply()
        }
        checkedRun("disable tether offload", "settings put global $TETHER_OFFLOAD_DISABLED_SETTING 1")
    }

    private fun restoreTetherOffload() {
        val prefs = Application.get().applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TETHER_OFFLOAD_PREVIOUS)) return
        val previous = prefs.getString(KEY_TETHER_OFFLOAD_PREVIOUS, "") ?: ""
        if (previous.isBlank() || previous == "null") {
            checkedRun("restore tether offload", "settings delete global $TETHER_OFFLOAD_DISABLED_SETTING 2>/dev/null || true")
        } else {
            checkedRun("restore tether offload", "settings put global $TETHER_OFFLOAD_DISABLED_SETTING $previous")
        }
        prefs.edit().remove(KEY_TETHER_OFFLOAD_PREVIOUS).apply()
    }

    private fun overrideTetherDnsForwarders(resolvers: List<String>) {
        val netId = readTetherDnsNetworkId() ?: return
        val dnsArgs = resolvers
            .filter { resolver -> isIpv4Address(resolver) }
            .take(MAX_DNS_RESOLVERS)
            .joinToString(" ")
        if (dnsArgs.isBlank()) return
        checkedRun("override tether DNS forwarders", "ndc tether dns set $netId $dnsArgs")
    }

    private fun encryptedDnsBlocklist(activeResolvers: List<String>): List<String> {
        val allowed = activeResolvers
            .filter(::isIpv4Address)
            .flatMap(::resolverFamily)
            .toSet()
        return COMMON_ENCRYPTED_DNS_RESOLVERS.filterNot { resolver -> allowed.contains(resolver) }
    }

    private fun resolverFamily(resolver: String): List<String> {
        return when (resolver) {
            "1.1.1.1", "1.0.0.1" -> listOf("1.1.1.1", "1.0.0.1")
            "1.1.1.3", "1.0.0.3" -> listOf("1.1.1.3", "1.0.0.3")
            "9.9.9.9", "149.112.112.112" -> listOf("9.9.9.9", "149.112.112.112")
            else -> listOf(resolver)
        }
    }

    private fun checkedRun(label: String, command: String) {
        val exit = Application.getRootShell().run(null, command)
        if (exit != 0) throw IllegalStateException("$label failed with exit code $exit")
    }

    private fun checkedInterfaceName(name: String): String {
        if (!isValidInterfaceName(name)) throw IllegalArgumentException("invalid interface name: $name")
        return name
    }

    private fun readVpnOwnerUid(): Int? {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(
            output,
            "dumpsys connectivity 2>/dev/null | sed -n '/ni{VPN CONNECTED/,/factorySerialNumber/p' | sed -n 's/.*OwnerUid: \\([0-9][0-9]*\\).*/\\1/p' | head -1"
        )
        if (exit != 0) return null
        return output.firstOrNull()?.trim()?.toIntOrNull()
    }

    private fun readGlobalSetting(name: String): String? {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(output, "settings get global $name 2>/dev/null")
        if (exit != 0) return null
        return output.firstOrNull()?.trim()
    }

    private fun readSecureSetting(name: String): String? {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(output, "settings get secure $name 2>/dev/null")
        if (exit != 0) return null
        return output.firstOrNull()?.trim()
    }

    private fun readIpv4Address(interfaceName: String): String? {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(
            output,
            "ip -4 -o addr show dev ${checkedInterfaceName(interfaceName)} 2>/dev/null | sed -n 's/.* inet \\([0-9.]*\\)\\/.*/\\1/p' | head -1"
        )
        if (exit != 0) return null
        return output.firstOrNull()?.trim()?.takeIf(::isIpv4Address)
    }

    private fun readIpv4AddressWithRetry(interfaceName: String): String? {
        repeat(8) {
            readIpv4Address(interfaceName)?.let { address -> return address }
            Application.getRootShell().run(null, "sleep 0.25")
        }
        return readIpv4Address(interfaceName)
    }

    private fun readTetherDnsNetworkId(): String? {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(
            output,
            "dumpsys tethering 2>/dev/null | sed -n 's/.*SET DNS forwarders: network=\\([0-9][0-9]*\\).*/\\1/p' | tail -1"
        )
        if (exit != 0) return null
        return output.firstOrNull()?.trim()?.takeIf { id -> id.all { it.isDigit() } }
    }

    private fun isValidInterfaceName(name: String): Boolean {
        return name.length in 1..64 && INTERFACE_NAME_REGEX.matches(name)
    }

    private fun hasUsableLinkState(line: String): Boolean {
        val name = line.substringAfter(": ", "").substringBefore(":").substringBefore("@").trim()
        if (name == "lo") return true
        if (isVpnInterfaceCandidate(name)) return true
        return line.contains("LOWER_UP") && !line.contains("NO-CARRIER")
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
    private const val KEY_OPERATION_STAGE = "operation_stage"
    private const val KEY_OPERATION_DETAIL = "operation_detail"
    private const val KEY_TETHER_OFFLOAD_PREVIOUS = "tether_offload_previous"
    private const val KEY_WIFI_AP_TIMEOUT_PREVIOUS = "wifi_ap_timeout_previous"
    private const val TETHER_OFFLOAD_DISABLED_SETTING = "tether_offload_disabled"
    private const val WIFI_AP_TIMEOUT_SETTING = "wifi_ap_timeout_setting"
    private const val NAT_CHAIN = "VIRTUVPN_ROUTER"
    private const val DNS_CHAIN = "VIRTUVPN_ROUTER_DNS"
    private const val FORWARD_CHAIN = "VIRTUVPN_ROUTER_FWD"
    private const val OUTPUT_CHAIN = "VIRTUVPN_ROUTER_OUT"
    private const val IPV6_FORWARD_CHAIN = "VIRTUVPN_ROUTER6_FWD"
    private const val HOTSPOT_VPN_RULE_PRIORITY = 20900
    private const val HOTSPOT_BLOCK_RULE_PRIORITY = 20901
    private const val HOTSPOT_BLOCK_ROUTE_TABLE = 1048
    private const val MAX_DNS_RESOLVERS = 2
    private val COMMON_ENCRYPTED_DNS_RESOLVERS = listOf(
        "1.0.0.1",
        "1.0.0.3",
        "1.1.1.1",
        "1.1.1.3",
        "8.8.4.4",
        "8.8.8.8",
        "9.9.9.9",
        "45.90.28.0",
        "45.90.30.0",
        "64.6.64.6",
        "64.6.65.6",
        "76.76.2.0",
        "76.76.10.0",
        "94.140.14.14",
        "94.140.15.15",
        "149.112.112.112",
        "185.228.168.9",
        "185.228.169.9",
        "208.67.220.220",
        "208.67.222.222"
    )
    private val routerMutex = Mutex()
    private val INTERFACE_NAME_REGEX = Regex("^[A-Za-z0-9_.:=-]+$")
}
