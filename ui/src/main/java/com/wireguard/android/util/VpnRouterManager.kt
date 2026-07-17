/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.util.Log
import androidx.core.content.edit
import com.wireguard.android.Application
import com.wireguard.android.VpnRouterService
import com.wireguard.android.backend.Tunnel
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

    enum class CompatibilityMode(val preferenceValue: String) {
        STRICT("strict"),
        NESTED("nested");

        companion object {
            fun fromPreference(value: String?): CompatibilityMode =
                values().firstOrNull { it.preferenceValue == value } ?: STRICT
        }
    }

    enum class UplinkPreference(val preferenceValue: String) {
        AUTOMATIC("automatic"),
        PREFER_WIFI("prefer_wifi"),
        PREFER_MOBILE("prefer_mobile");

        companion object {
            fun fromPreference(value: String?): UplinkPreference =
                values().firstOrNull { it.preferenceValue == value } ?: AUTOMATIC
        }
    }

    enum class Availability {
        ENABLED,
        DEGRADED,
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
        val detail: String? = null,
        val securityProtected: Boolean = false,
        val tunnelInterfaceMissing: Boolean = false
    ) {
        val canEnable: Boolean
            get() = availability == Availability.READY
        val canDisable: Boolean
            get() = availability == Availability.ENABLED ||
                availability == Availability.DEGRADED ||
                availability == Availability.ERROR
        val needsReconcile: Boolean
            get() = availability == Availability.ENABLED ||
                availability == Availability.DEGRADED ||
                availability == Availability.ERROR
        val routerActive: Boolean
            get() = canDisable
    }

    enum class OperationStage {
        IDLE,
        LOCKING_HOTSPOT,
        DETECTING_TUNNEL,
        APPLYING_DNS,
        APPLYING_FIREWALL,
        VERIFYING_RULES,
        CHECKING_HEALTH,
        FALLING_BACK,
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
        if (status.availability == Availability.DEGRADED) {
            val probed = probeDegradedLocked(appContext, status)
            syncAttestationServer(appContext, probed)
            return@withLock probed
        }
        if (status.availability != Availability.ENABLED && status.availability != Availability.ERROR) {
            syncAttestationServer(appContext, status)
            return@withLock status
        }
        if (status.tunnelInterfaceMissing) {
            // The VPN interface is gone (provider renegotiation or blip). There is
            // nothing to install rules against, and a rebuild attempt would tear
            // down live fail-closed rules only to fail at the routing step. Keep
            // the installed rules untouched and wait for the interface to return.
            return@withLock status.also { syncAttestationServer(appContext, it) }
        }
        disableHotspotAutoShutdown(appContext)
        enableWifiSharing(appContext)
        val tunnelName = status.activeTunnel ?: return@withLock status.also { syncAttestationServer(appContext, it) }
        if (status.tetherInterfaces.isEmpty()) return@withLock status.also { syncAttestationServer(appContext, it) }
        try {
            installRules(appContext, tunnelName, status.tetherInterfaces, allowFastPath = true)
            val repaired = detect(appContext)
            if (repaired.availability == Availability.ENABLED) {
                rememberVirtuFallbackTunnel(appContext)
                updateAlwaysOnProvider(appContext)
            }
            syncAttestationServer(appContext, repaired)
            repaired
        } catch (e: TunnelHealthException) {
            Log.w(TAG, "VPN router tunnel is unhealthy; keeping hotspot clients fail-closed", e)
            markTunnelDegraded(appContext, status, tunnelName, e.message ?: TUNNEL_HEALTH_FAILED_DETAIL)
                .also {
                    setOperation(appContext, OperationStage.ERROR, it.detail)
                    syncAttestationServer(appContext, it)
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to reconcile VPN router", e)
            setOperation(appContext, OperationStage.ERROR, e.message ?: e.javaClass.simpleName)
            status.copy(availability = Availability.ERROR, detail = e.message ?: e.javaClass.simpleName)
                .also { syncAttestationServer(appContext, it) }
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

    fun getCompatibilityMode(context: Context): CompatibilityMode {
        return CompatibilityMode.fromPreference(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_COMPATIBILITY_MODE, CompatibilityMode.STRICT.preferenceValue)
        )
    }

    fun setCompatibilityMode(context: Context, mode: CompatibilityMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_COMPATIBILITY_MODE, mode.preferenceValue)
            .apply()
    }

    fun getUplinkPreference(context: Context): UplinkPreference =
        UplinkPreference.fromPreference(
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_UPLINK_PREFERENCE, UplinkPreference.AUTOMATIC.preferenceValue)
        )

    fun setUplinkPreference(context: Context, preference: UplinkPreference) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UPLINK_PREFERENCE, preference.preferenceValue)
            .apply()
        TunnelConnectivityMonitor.applyCurrentUplinkPreference()
    }

    fun isKernelModuleAvailable(): Boolean = WgQuickBackend.hasKernelSupport()

    suspend fun prepareKernelBackend(context: Context) = withContext(Dispatchers.IO) {
        routerMutex.withLock {
            val appContext = context.applicationContext
            runCatching { removeRules(appContext) }
                .onFailure { Log.w(TAG, "Unable to clear router rules before backend switch", it) }
            clearLastRuleSignature(appContext)
            UserKnobs.setEnableKernelModule(true)
            Application.getTunnelManager().getTunnels().forEach { tunnel ->
                runCatching { tunnel.setStateAsync(Tunnel.State.DOWN) }
                    .onFailure { Log.w(TAG, "Unable to stop tunnel before backend switch: ${tunnel.name}", it) }
            }
        }
    }

    suspend fun enable(context: Context): Status = withContext(Dispatchers.IO) {
        routerMutex.withLock {
        val appContext = context.applicationContext
        val status = detect(appContext)
        if (!status.canEnable && !status.canDisable) {
            syncAttestationServer(appContext, status)
            return@withLock status
        }
        if (status.tunnelInterfaceMissing) {
            // The VPN interface is gone; installing against it would tear down
            // live fail-closed rules. Keep them untouched until it returns.
            return@withLock status.also { syncAttestationServer(appContext, it) }
        }
        val tunnelName = status.activeTunnel ?: return@withLock status.copy(availability = Availability.WAITING_FOR_TUNNEL)
            .also { syncAttestationServer(appContext, it) }
        val tetherInterfaces = status.tetherInterfaces
        if (tetherInterfaces.isEmpty()) return@withLock status.copy(availability = Availability.WAITING_FOR_HOTSPOT)
            .also { syncAttestationServer(appContext, it) }
        try {
            setOperation(appContext, OperationStage.LOCKING_HOTSPOT, "Blocking hotspot fallback before enabling router")
            installRules(appContext, tunnelName, tetherInterfaces)
            setOperation(appContext, OperationStage.COMPLETE, "VPN router is protected")
            val enabled = detect(appContext)
            if (enabled.availability == Availability.ENABLED) {
                rememberVirtuFallbackTunnel(appContext)
                updateAlwaysOnProvider(appContext)
            }
            syncAttestationServer(appContext, enabled)
            enabled
        } catch (e: TunnelHealthException) {
            Log.w(TAG, "VPN router enabled fail-closed because tunnel health failed", e)
            markTunnelDegraded(appContext, status, tunnelName, e.message ?: TUNNEL_HEALTH_FAILED_DETAIL)
                .also {
                    setOperation(appContext, OperationStage.ERROR, it.detail)
                    syncAttestationServer(appContext, it)
                }
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to enable VPN router", e)
            setOperation(appContext, OperationStage.ERROR, e.message ?: e.javaClass.simpleName)
            status.copy(availability = Availability.ERROR, detail = e.message ?: e.javaClass.simpleName)
                .also { syncAttestationServer(appContext, it) }
        }
        }
    }

    suspend fun disable(context: Context): Status = withContext(Dispatchers.IO) {
        routerMutex.withLock {
        val appContext = context.applicationContext
        val prior = runCatching { detect(appContext) }.getOrNull()
        try {
            removeRules(appContext)
            clearAlwaysOnProviderIfOwned(appContext)
            clearRouterState(appContext)
            detect(appContext).also {
                VpnRouterAttestationServer.stop()
                VpnRouterService.stopNow(appContext)
                syncAttestationServer(appContext, it)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to disable VPN router", e)
            setOperation(appContext, OperationStage.ERROR, e.message ?: e.javaClass.simpleName)
            Status(
                availability = Availability.ERROR,
                activeTunnel = prior?.activeTunnel,
                tetherInterfaces = prior?.tetherInterfaces.orEmpty(),
                detail = e.message ?: e.javaClass.simpleName
            ).also { syncAttestationServer(appContext, it) }
        }
        }
    }

    fun isRouterDesiredActive(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.contains(KEY_ROUTER_DESIRED_ACTIVE)) {
            return prefs.getBoolean(KEY_ROUTER_DESIRED_ACTIVE, false)
        }
        return !prefs.getString(KEY_LAST_ACTIVE_ROUTER_TUNNEL, null).isNullOrBlank()
    }

    suspend fun requestRouterActive(context: Context): Status = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        setRouterDesiredActive(appContext, true)
        val status = detect(appContext)
        syncAttestationServer(appContext, status)
        VpnRouterService.startForRestore(appContext)
        status
    }

    private fun setRouterDesiredActive(context: Context, desired: Boolean) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ROUTER_DESIRED_ACTIVE, desired)
            .apply()
    }

    /**
     * Re-assert router protection after a reboot or process restart when the
     * user previously enabled it. Returns true while the router is (or should
     * remain) desired-active so the caller keeps its monitor alive. Fail-closed
     * is preserved end to end: enable() installs the block layer before opening
     * any path, and a not-yet-satisfiable restore leaves existing rules intact.
     */
    suspend fun restoreRouterIfDesired(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        if (!isRouterDesiredActive(appContext)) return@withContext false
        val status = detect(appContext)
        when (status.availability) {
            Availability.ENABLED, Availability.DEGRADED -> {
                // Already protected (or protected-but-degraded); normal reconcile owns it.
                reconcile(appContext)
            }
            Availability.READY, Availability.ERROR -> {
                // Tunnel and hotspot are present but rules are not installed yet
                // (typical post-boot state). Re-enable now.
                Log.i(TAG, "Restoring desired VPN router protection after restart")
                enable(appContext)
            }
            else -> {
                // WAITING_FOR_TUNNEL / WAITING_FOR_HOTSPOT / UNSUPPORTED: nothing to
                // install against yet. Keep waiting; the boot helper is bringing the
                // tunnel and hotspot up in parallel.
                syncAttestationServer(appContext, status)
            }
        }
        true
    }

    private fun syncAttestationServer(context: Context, status: Status) {
        if (status.routerActive) {
            rememberRouterActiveStatus(context, status)
            VpnRouterAttestationServer.updateStatus(status)
            VpnRouterAttestationServer.start(context.applicationContext, status)
        } else {
            VpnRouterAttestationServer.updateStatus(status)
        }
        VpnRouterService.ensureForStatus(context.applicationContext, status)
    }

    private suspend fun detect(context: Context): Status {
        val installed = runCatching { isRuleChainInstalled() }.getOrElse { e ->
            val transient = transientActiveStatusForRootMiss(context, e)
            if (transient != null) return transient
            return Status(
                availability = Availability.UNSUPPORTED,
                detail = e.message ?: "root required"
            )
        }
        if (installed && !enforceRouterLocalEgressOwnership(context)) {
            Log.w(TAG, "Unable to enforce router-local browser egress ownership; keeping status fail-closed")
        }
        pruneMissingStoredTunnelReferences(context)

        val runningTunnel = readVpnInterfaces().firstOrNull()
        val allUpInterfaces = readUpInterfaces()
        val defaultRouteInterfaces = readDefaultRouteInterfaces().toSet()
        val presentTetherInterfaces = readTetherInterfaces(
            runningTunnel ?: "",
            allUpInterfaces,
            defaultRouteInterfaces
        )
        assertHotspotBackstop(presentTetherInterfaces)
        if (runningTunnel == null) {
            val lastActive = lastActiveRouterSnapshot(context)
            val dnsResolvers = resolveDnsResolvers(context, null).ifEmpty { lastActive.dnsResolvers }
            val statusTethers = presentTetherInterfaces.ifEmpty { lastActive.tetherInterfaces }
            val securityProtected = installed && routerSecurityInvariantHolds(null, statusTethers)
            return Status(
                availability = if (installed) Availability.ERROR else Availability.WAITING_FOR_TUNNEL,
                activeTunnel = lastActive.tunnel,
                tetherInterfaces = statusTethers,
                uplinkInterfaces = readUplinkInterfaces(allUpInterfaces, null, statusTethers),
                dnsResolvers = dnsResolvers,
                detail = if (installed) "router rules installed; no active VPN interface detected" else null,
                securityProtected = securityProtected,
                tunnelInterfaceMissing = true
            )
        }
        val tetherInterfaces = presentTetherInterfaces
        val uplinkInterfaces = readUplinkInterfaces(allUpInterfaces, runningTunnel, tetherInterfaces)
        val dnsResolvers = resolveDnsResolvers(context, runningTunnel)
        val hotspotActive = HotspotDetector.isWifiHotspotActive(context) || tetherInterfaces.isNotEmpty()
        if (installed) {
            val securityProtected = routerSecurityInvariantHolds(runningTunnel, tetherInterfaces)
            val healthy = verifyRouterRules(runningTunnel, tetherInterfaces)
            if (!healthy) {
                if (recordRouterVerifyFailure(context) < VERIFY_FAILURES_BEFORE_ERROR) {
                    val availability = degradedAvailability(context, runningTunnel)
                    return Status(
                        availability = availability,
                        activeTunnel = runningTunnel,
                        tetherInterfaces = tetherInterfaces,
                        uplinkInterfaces = uplinkInterfaces,
                        dnsResolvers = dnsResolvers,
                        detail = if (availability == Availability.DEGRADED) {
                            degradedDetail(context, runningTunnel)
                        } else {
                            "router rule verification missed once; keeping last protected state"
                        },
                        securityProtected = securityProtected
                    )
                }
                return Status(
                    availability = Availability.ERROR,
                    activeTunnel = runningTunnel,
                    tetherInterfaces = tetherInterfaces,
                    uplinkInterfaces = uplinkInterfaces,
                    dnsResolvers = dnsResolvers,
                    detail = "router rules incomplete; waiting for reconcile",
                    securityProtected = securityProtected
                )
            }
            clearRouterVerifyFailures(context)
            return Status(
                availability = degradedAvailability(context, runningTunnel),
                activeTunnel = runningTunnel,
                tetherInterfaces = tetherInterfaces,
                uplinkInterfaces = uplinkInterfaces,
                dnsResolvers = dnsResolvers,
                detail = degradedDetail(context, runningTunnel),
                securityProtected = securityProtected
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
        if (backend is WgQuickBackend) names += backend.runningTunnelNames
        names += readUpInterfaces()
            .filter { name -> isValidInterfaceName(name) }
            .filter { name -> isVpnInterfaceCandidate(name) }
        return names.toList().sortedWith(compareBy(::vpnInterfacePriority, { it }))
    }

    private fun readTetherInterfaces(
        activeTunnel: String,
        upInterfaces: List<String>,
        defaultRouteInterfaces: Set<String>
    ): List<String> {
        return upInterfaces.asSequence()
            .filter { name -> isValidInterfaceName(name) }
            .filterNot { name -> name == activeTunnel || name == "lo" }
            // In STA+AP concurrency the Wi-Fi station (normally wlan0) and
            // hotspot (for example swlan0) are both UP. The station is an
            // uplink, never a tether downstream while it owns a default route.
            .filterNot { name -> name in defaultRouteInterfaces && isPhysicalUplinkCandidate(name) }
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
        return tryReadUpInterfaces()?.interfaces.orEmpty()
    }

    private data class InterfaceReadResult(
        val interfaces: List<String>
    )

    private fun tryReadUpInterfaces(): InterfaceReadResult? {
        val output = mutableListOf<String>()
        val exit = Application.getRootShell().run(output, "ip -o link show up 2>/dev/null || ip -o link show 2>/dev/null")
        if (exit != 0) return null
        val interfaces = output.asSequence()
            .filter { line -> hasUsableLinkState(line) }
            .mapNotNull { line -> line.substringAfter(": ", "").substringBefore(":").substringBefore("@").trim() }
            .distinct()
            .toList()
        return InterfaceReadResult(interfaces)
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

    private suspend fun installRules(
        context: Context,
        activeTunnel: String,
        tetherInterfaces: List<String>,
        allowFastPath: Boolean = false
    ) {
        val tunnel = checkedInterfaceName(activeTunnel)
        val downstreams = tetherInterfaces.map(::checkedInterfaceName)
        val uplinks = readUplinkInterfaces(readUpInterfaces(), activeTunnel, downstreams)
            .map { uplink -> checkedInterfaceName(uplink.interfaceName) }
        val dnsResolvers = runCatching { resolveDnsResolvers(context, activeTunnel) }
            .getOrElse { DnsMode.QUAD9.resolvers }
            .ifEmpty { DnsMode.QUAD9.resolvers }
        val dnsResolver = dnsResolvers.first()
        val compatibilityMode = getCompatibilityMode(context)
        val appUid = context.applicationInfo.uid
        val vpnOwnerUid = readVpnOwnerUid()?.takeUnless { uid -> uid == appUid }
        val vpnProviderUids = readVpnProviderUids(context)
        val localBrowserUids = readRouterLocalBrowserUids(context)
        val snapshot = VpnRouterRulePlanner.Snapshot(
            rulesVersion = ROUTER_RULES_VERSION,
            tunnel = tunnel,
            downstreams = downstreams,
            dnsResolvers = dnsResolvers,
            compatibilityMode = compatibilityMode.preferenceValue,
            uplinks = uplinks,
            vpnOwnerUid = vpnOwnerUid,
            vpnProviderUids = vpnProviderUids,
            localBrowserUids = localBrowserUids
        )
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lastSignature = prefs.getString(KEY_LAST_RULE_SIGNATURE, null)
        val rulesHealthy = verifyRouterRules(tunnel, downstreams, vpnOwnerUid, vpnProviderUids)

        disableHotspotAutoShutdown(context)
        enableWifiSharing(context)
        disableTetherOffload(context)
        if (allowFastPath && !VpnRouterRulePlanner.needsFullRebuild(lastSignature, snapshot, rulesHealthy)) {
            setOperation(context, OperationStage.CHECKING_HEALTH, "Checking internet through VPN interface $tunnel")
            if (!checkTunnelHealth(tunnel, dnsResolver)) {
                if (recordTunnelHealthFailure(context) >= HEALTH_FAILURES_BEFORE_DEGRADED) {
                    throw TunnelHealthException(tunnelHealthFailureDetail(context))
                }
                setOperation(context, OperationStage.CHECKING_HEALTH, tunnelHealthTransientDetail(context))
                return
            }
            recordTunnelHealthSuccess(context)
            clearDegradedTunnel(context)
            setOperation(context, OperationStage.COMPLETE, "VPN router is protected")
            return
        }
        setOperation(context, OperationStage.LOCKING_HOTSPOT, "Blocking hotspot fallback before changing VPN routes")
        setOperation(context, OperationStage.DETECTING_TUNNEL, "Using VPN interface $activeTunnel")
        setOperation(context, OperationStage.APPLYING_FIREWALL, "Installing fail-closed firewall and VPN routes")
        // Keep a healthy attestation proxy serving across the rebuild so paired
        // browsers never see the attestation endpoint disappear.
        val attestationProxyHealthy = downstreams.isNotEmpty() && downstreams.all { downstream ->
            commandSucceeds(verifyAttestationProxyCommand(downstream))
        }
        if (!attestationProxyHealthy) {
            checkedRun("stop router attestation hotspot proxy", stopAttestationProxyCommand())
        }
        checkedRun("prepare NAT chain", "iptables -t nat -N $NAT_CHAIN 2>/dev/null || true")
        checkedRun("prepare DNS chain", "iptables -t nat -N $DNS_CHAIN 2>/dev/null || true")
        checkedRun("prepare forward chain", "iptables -N $FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("prepare output chain", "iptables -N $OUTPUT_CHAIN 2>/dev/null || true")
        checkedRun("prepare IPv6 forward chain", "ip6tables -N $IPV6_FORWARD_CHAIN 2>/dev/null || true")
        checkedRun("prepare IPv6 output chain", "ip6tables -N $IPV6_OUTPUT_CHAIN 2>/dev/null || true")
        checkedRun("clear NAT chain", "iptables -t nat -F $NAT_CHAIN")
        checkedRun("clear DNS chain", "iptables -t nat -F $DNS_CHAIN")
        checkedRun("clear forward chain", "iptables -F $FORWARD_CHAIN")
        checkedRun("remove legacy access chain", "iptables -F VIRTUVPN_ROUTER_ACCESS 2>/dev/null || true; iptables -X VIRTUVPN_ROUTER_ACCESS 2>/dev/null || true")
        checkedRun("clear IPv6 forward chain", "ip6tables -F $IPV6_FORWARD_CHAIN")
        downstreams.forEach { downstream ->
            checkedRun(
                "deny hotspot forwarding before enabling router",
                "iptables -A $FORWARD_CHAIN -i $downstream -j REJECT"
            )
            checkedRun(
                "deny hotspot IPv6 forwarding before enabling router",
                "ip6tables -A $IPV6_FORWARD_CHAIN -i $downstream -j REJECT"
            )
        }
        checkedRun(
            "clear phone-to-hotspot local routes",
            "while ip rule del pref $HOTSPOT_LOCAL_RULE_PRIORITY 2>/dev/null; do :; done; " +
                "ip route flush table $HOTSPOT_LOCAL_ROUTE_TABLE 2>/dev/null || true"
        )
        checkedRun("prepare hotspot fallback block route", "ip route replace unreachable default table $HOTSPOT_BLOCK_ROUTE_TABLE")
        checkedRun("prepare hotspot VPN route", "ip route replace default dev $tunnel table $HOTSPOT_VPN_ROUTE_TABLE")
        downstreams.forEach { downstream ->
            checkedRun(
                "route phone replies to hotspot clients locally",
                localHotspotRouteCommand(downstream)
            )
            checkedRun(
                "install hotspot fallback block first",
                ensureRuleCommand(
                    HOTSPOT_BLOCK_RULE_PRIORITY,
                    downstream,
                    HOTSPOT_BLOCK_ROUTE_TABLE.toString()
                )
            )
            checkedRun(
                "remove hotspot VPN route while firewall is rebuilding",
                "ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream 2>/dev/null || true"
            )
        }
        // Older builds could classify the Wi-Fi station as a tether downstream.
        // Remove only policy rules for confirmed physical uplinks after the real
        // downstream backstop above is armed, so migration remains fail-closed.
        uplinks.forEach { uplink ->
            checkedRun(
                "remove stale hotspot policy from uplink",
                "while ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY iif $uplink 2>/dev/null; do :; done; " +
                    "while ip rule del pref $HOTSPOT_BLOCK_RULE_PRIORITY iif $uplink 2>/dev/null; do :; done"
            )
        }
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
            "attach IPv6 output chain",
            "while ip6tables -D OUTPUT -j $IPV6_OUTPUT_CHAIN 2>/dev/null; do :; done; " +
                "ip6tables -I OUTPUT 1 -j $IPV6_OUTPUT_CHAIN"
        )
        checkedRun(
            "attach IPv6 forward chain",
            "while ip6tables -D FORWARD -j $IPV6_FORWARD_CHAIN 2>/dev/null; do :; done; " +
                "ip6tables -I FORWARD 1 -j $IPV6_FORWARD_CHAIN"
        )
        checkedRun("enable IPv4 forwarding after hotspot deny rules", "sysctl -w net.ipv4.ip_forward=1 >/dev/null")
        setOperation(context, OperationStage.APPLYING_DNS, "Applying router DNS for hotspot clients")
        overrideTetherDnsForwarders(dnsResolvers)
        checkedRun("masquerade VPN egress", "iptables -t nat -A $NAT_CHAIN -o $tunnel -j MASQUERADE")
        // The previous fail-closed egress rules stay in force until this point;
        // flushing just before the rewrite keeps the unguarded window minimal.
        checkedRun("clear output chain", "iptables -F $OUTPUT_CHAIN")
        checkedRun("clear IPv6 output chain", "ip6tables -F $IPV6_OUTPUT_CHAIN")
        checkedRun("allow phone loopback egress", "iptables -A $OUTPUT_CHAIN -o lo -j RETURN")
        checkedRun("allow phone VPN egress", "iptables -A $OUTPUT_CHAIN -o $tunnel -j RETURN")
        checkedRun("allow WireGuard fwmark transport", "iptables -A $OUTPUT_CHAIN -m mark --mark 0x20000 -j RETURN || true")
        checkedRun("allow phone IPv6 loopback egress", "ip6tables -A $IPV6_OUTPUT_CHAIN -o lo -j RETURN")
        checkedRun("allow phone IPv6 VPN egress", "ip6tables -A $IPV6_OUTPUT_CHAIN -o $tunnel -j RETURN")
        checkedRun("allow WireGuard IPv6 fwmark transport", "ip6tables -A $IPV6_OUTPUT_CHAIN -m mark --mark 0x20000 -j RETURN || true")
        if (vpnOwnerUid != null) {
            checkedRun("allow active VPN provider transport", "iptables -A $OUTPUT_CHAIN -m owner --uid-owner $vpnOwnerUid -j RETURN")
            checkedRun("allow active VPN provider IPv6 transport", "ip6tables -A $IPV6_OUTPUT_CHAIN -m owner --uid-owner $vpnOwnerUid -j RETURN || true")
        }
        vpnProviderUids.filterNot { uid -> uid == vpnOwnerUid }.forEach { uid ->
            checkedRun("allow installed VPN provider bootstrap $uid", "iptables -A $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN")
            checkedRun("allow installed VPN provider IPv6 bootstrap $uid", "ip6tables -A $IPV6_OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN || true")
        }
        localBrowserUids.forEach { uid ->
            checkedRun("allow router-local browser $uid", "iptables -A $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN")
            checkedRun("allow router-local browser IPv6 $uid", "ip6tables -A $IPV6_OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN || true")
        }
        VPN_BOOTSTRAP_SYSTEM_UIDS.forEach { uid ->
            checkedRun("allow Android VPN bootstrap system UID $uid", "iptables -A $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN || true")
            checkedRun("allow Android VPN bootstrap IPv6 system UID $uid", "ip6tables -A $IPV6_OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN || true")
        }
        checkedRun("allow Android VPN bootstrap UDP DNS", "iptables -A $OUTPUT_CHAIN -p udp --dport 53 -j RETURN")
        checkedRun("allow Android VPN bootstrap TCP DNS", "iptables -A $OUTPUT_CHAIN -p tcp --dport 53 -j RETURN")
        checkedRun("allow Android VPN bootstrap IPv6 UDP DNS", "ip6tables -A $IPV6_OUTPUT_CHAIN -p udp --dport 53 -j RETURN || true")
        checkedRun("allow Android VPN bootstrap IPv6 TCP DNS", "ip6tables -A $IPV6_OUTPUT_CHAIN -p tcp --dport 53 -j RETURN || true")
        downstreams.forEach { downstream ->
            checkedRun(
                "allow phone egress to hotspot client subnets",
                allowHotspotClientSubnetEgressCommand(downstream)
            )
            checkedRun("allow phone egress to hotspot clients", "iptables -A $OUTPUT_CHAIN -o $downstream -j RETURN")
            checkedRun("allow phone IPv6 egress to hotspot clients", "ip6tables -A $IPV6_OUTPUT_CHAIN -o $downstream -j RETURN")
        }
        checkedRun(
            "clear attestation input guard",
            "while iptables -D INPUT -p tcp --dport ${VpnRouterAttestation.PORT} -j REJECT 2>/dev/null; do :; done"
        )
        downstreams.forEach { downstream ->
            checkedRun(
                "clear attestation input allow",
                "while iptables -D INPUT -i $downstream -p tcp --dport ${VpnRouterAttestation.PORT} -j ACCEPT 2>/dev/null; do :; done"
            )
        }
        downstreams.asReversed().forEach { downstream ->
            checkedRun(
                "allow router attestation from hotspot",
                "iptables -I INPUT 1 -i $downstream -p tcp --dport ${VpnRouterAttestation.PORT} -j ACCEPT"
            )
            if (!attestationProxyHealthy) {
                checkedRun(
                    "start router attestation hotspot proxy",
                    startAttestationProxyCommand(downstream)
                )
            }
        }
        checkedRun(
            "block router attestation outside hotspot",
            "iptables -A INPUT -p tcp --dport ${VpnRouterAttestation.PORT} -j REJECT"
        )
        uplinks.forEach { uplink ->
            checkedRun("block phone uplink bypass", "iptables -A $OUTPUT_CHAIN -o $uplink -j REJECT")
            checkedRun("block phone IPv6 uplink bypass", "ip6tables -A $IPV6_OUTPUT_CHAIN -o $uplink -j REJECT")
        }
        checkedRun("finish output chain fail-closed", "iptables -A $OUTPUT_CHAIN -j REJECT")
        checkedRun("finish IPv6 output chain fail-closed", "ip6tables -A $IPV6_OUTPUT_CHAIN -j REJECT")
        checkedRun("clear temporary forward deny rules", "iptables -F $FORWARD_CHAIN")
        checkedRun("clear temporary IPv6 forward deny rules", "ip6tables -F $IPV6_FORWARD_CHAIN")
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
                ensureRuleCommand(
                    HOTSPOT_BLOCK_RULE_PRIORITY,
                    downstream,
                    HOTSPOT_BLOCK_ROUTE_TABLE.toString()
                )
            )
            checkedRun(
                "block hotspot DNS over TLS",
                "iptables -A $FORWARD_CHAIN -i $downstream -p tcp --dport 853 -j REJECT --reject-with tcp-reset"
            )
            checkedRun(
                "block hotspot DNS over QUIC",
                "iptables -A $FORWARD_CHAIN -i $downstream -p udp --dport 853 -j REJECT --reject-with icmp-port-unreachable"
            )
            if (compatibilityMode == CompatibilityMode.STRICT) {
                checkedRun(
                    "block hotspot QUIC",
                    "iptables -A $FORWARD_CHAIN -i $downstream -p udp --dport 443 -j REJECT --reject-with icmp-port-unreachable"
                )
            }
            encryptedDnsBlocklist(dnsResolvers).forEach { resolver ->
                checkedRun(
                    "block hotspot DoH TCP $resolver",
                    "iptables -A $FORWARD_CHAIN -i $downstream -d $resolver -p tcp --dport 443 -j REJECT --reject-with tcp-reset"
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
            checkedRun(
                "route hotspot traffic to VPN after firewall is ready",
                "ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream 2>/dev/null || true; " +
                    "ip rule add pref $HOTSPOT_VPN_RULE_PRIORITY iif $downstream lookup $HOTSPOT_VPN_ROUTE_TABLE"
            )
        }
        checkedRun("finish IPv6 forwarding fail-closed", "ip6tables -A $IPV6_FORWARD_CHAIN -j REJECT")
        setOperation(context, OperationStage.VERIFYING_RULES, "Verifying VPN route and mobile fallback block")
        downstreams.forEach { downstream ->
            checkedRun(
                "verify hotspot routing",
                ruleExistsCommand(HOTSPOT_VPN_RULE_PRIORITY, downstream, HOTSPOT_VPN_ROUTE_TABLE.toString()) + " && " +
                    "ip route show table $HOTSPOT_VPN_ROUTE_TABLE | grep -q \"default dev $tunnel\" && " +
                    ruleExistsCommand(HOTSPOT_BLOCK_RULE_PRIORITY, downstream, HOTSPOT_BLOCK_ROUTE_TABLE.toString()) + " && " +
                    "ip route show table $HOTSPOT_BLOCK_ROUTE_TABLE | grep -q \"unreachable default\""
                )
        }
        setOperation(context, OperationStage.CHECKING_HEALTH, "Checking internet through VPN interface $tunnel")
        if (!checkTunnelHealth(tunnel, dnsResolver)) {
            val fallbackAttempted = tryVirtuFallback(context, tunnel)
            val fallbackTunnel = if (fallbackAttempted) readVpnInterfaces().firstOrNull() else null
            if (!fallbackAttempted || fallbackTunnel == null || !checkTunnelHealth(fallbackTunnel, dnsResolver)) {
                throw TunnelHealthException(tunnelHealthFailureDetail(context))
            }
            if (fallbackTunnel != tunnel) {
                setOperation(context, OperationStage.FALLING_BACK, "Fallback tunnel $fallbackTunnel is healthy; rebuilding router rules")
                installRules(context, fallbackTunnel, downstreams)
                return
            }
        }
        checkedRun("flush route cache", "ip route flush cache 2>/dev/null || true")
        prefs.edit()
            .putString(KEY_LAST_RULE_SIGNATURE, snapshot.signature())
            .remove(KEY_DEGRADED_TUNNEL)
            .remove(KEY_DEGRADED_DETAIL)
            .remove(KEY_HEALTH_FAILURES)
            .remove(KEY_HEALTH_SUCCESSES)
            .remove(KEY_VERIFY_FAILURES)
            .putString(KEY_OPERATION_STAGE, OperationStage.IDLE.name)
            .putString(KEY_OPERATION_DETAIL, "")
            .apply()
    }

    private fun routerSecurityInvariantHolds(tunnel: String?, downstreams: List<String>): Boolean {
        if (downstreams.isEmpty()) return false
        if (!commandSucceeds("iptables -S $FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -S $OUTPUT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -S $IPV6_FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -S $IPV6_OUTPUT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C FORWARD -j $FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C OUTPUT -j $OUTPUT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C FORWARD -j $IPV6_FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C OUTPUT -j $IPV6_OUTPUT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -j REJECT >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C $IPV6_OUTPUT_CHAIN -j REJECT >/dev/null 2>&1")) return false
        val appContext = Application.get().applicationContext
        if (commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner ${appContext.applicationInfo.uid} -j RETURN >/dev/null 2>&1")) return false
        readRouterLocalBrowserUids(appContext).forEach { uid ->
            if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN >/dev/null 2>&1")) return false
        }
        if (!commandSucceeds("ip6tables -C $IPV6_FORWARD_CHAIN -j REJECT >/dev/null 2>&1")) return false
        return downstreams.all { downstream ->
            val coreRulesHold = commandSucceeds(
                ruleExistsCommand(HOTSPOT_BLOCK_RULE_PRIORITY, downstream, HOTSPOT_BLOCK_ROUTE_TABLE.toString()) + " && " +
                    "ip route show table $HOTSPOT_BLOCK_ROUTE_TABLE | grep -q \"unreachable default\" && " +
                    "iptables -C $FORWARD_CHAIN -i $downstream -j REJECT >/dev/null 2>&1 && " +
                    "ip6tables -C $IPV6_FORWARD_CHAIN -i $downstream -j REJECT >/dev/null 2>&1"
            )
            coreRulesHold && (tunnel == null || commandSucceeds("iptables -C $FORWARD_CHAIN -i $downstream -o $tunnel -j ACCEPT >/dev/null 2>&1"))
        }
    }

    private fun verifyRouterRules(
        tunnel: String,
        downstreams: List<String>,
        vpnOwnerUid: Int? = readVpnOwnerUid()?.takeUnless { uid -> uid == Application.get().applicationInfo.uid },
        vpnProviderUids: List<Int> = readVpnProviderUids(Application.get().applicationContext),
        localBrowserUids: List<Int> = readRouterLocalBrowserUids(Application.get().applicationContext)
    ): Boolean {
        if (downstreams.isEmpty()) return false
        if (!commandSucceeds("iptables -t nat -S $NAT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -S $FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -t nat -C POSTROUTING -j $NAT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -t nat -C PREROUTING -j $DNS_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C FORWARD -j $FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C OUTPUT -j $OUTPUT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C FORWARD -j $IPV6_FORWARD_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C OUTPUT -j $IPV6_OUTPUT_CHAIN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -j REJECT >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C $IPV6_OUTPUT_CHAIN -j REJECT >/dev/null 2>&1")) return false
        if (!commandSucceeds("ip6tables -C $IPV6_FORWARD_CHAIN -j REJECT >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C INPUT -p tcp --dport ${VpnRouterAttestation.PORT} -j REJECT >/dev/null 2>&1")) return false
        if (vpnOwnerUid != null && !commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner $vpnOwnerUid -j RETURN >/dev/null 2>&1")) return false
        vpnProviderUids.forEach { uid ->
            if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN >/dev/null 2>&1")) return false
        }
        localBrowserUids.forEach { uid ->
            if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN >/dev/null 2>&1")) return false
        }
        if (commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner ${Application.get().applicationInfo.uid} -j RETURN >/dev/null 2>&1")) return false
        VPN_BOOTSTRAP_SYSTEM_UIDS.forEach { uid ->
            if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN >/dev/null 2>&1")) return false
        }
        if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -p udp --dport 53 -j RETURN >/dev/null 2>&1")) return false
        if (!commandSucceeds("iptables -C $OUTPUT_CHAIN -p tcp --dport 53 -j RETURN >/dev/null 2>&1")) return false
        return downstreams.all { downstream ->
            commandSucceeds(
                verifyLocalHotspotRouteCommand(downstream) + " && " +
                    ruleExistsCommand(HOTSPOT_VPN_RULE_PRIORITY, downstream, HOTSPOT_VPN_ROUTE_TABLE.toString()) + " && " +
                    "ip route show table $HOTSPOT_VPN_ROUTE_TABLE | grep -q \"default dev $tunnel\" && " +
                    ruleExistsCommand(HOTSPOT_BLOCK_RULE_PRIORITY, downstream, HOTSPOT_BLOCK_ROUTE_TABLE.toString()) + " && " +
                    "ip route show table $HOTSPOT_BLOCK_ROUTE_TABLE | grep -q \"unreachable default\" && " +
                    "iptables -C INPUT -i $downstream -p tcp --dport ${VpnRouterAttestation.PORT} -j ACCEPT >/dev/null 2>&1 && " +
                    verifyAttestationProxyCommand(downstream) + " && " +
                    verifyHotspotClientSubnetEgressCommand(downstream) + " && " +
                    "iptables -C $OUTPUT_CHAIN -o $downstream -j RETURN >/dev/null 2>&1 && " +
                    "ip6tables -C $IPV6_OUTPUT_CHAIN -o $downstream -j RETURN >/dev/null 2>&1 && " +
                    "iptables -C $FORWARD_CHAIN -i $downstream -o $tunnel -j ACCEPT >/dev/null 2>&1 && " +
                    "iptables -C $FORWARD_CHAIN -i $downstream -j REJECT >/dev/null 2>&1 && " +
                    "ip6tables -C $IPV6_FORWARD_CHAIN -i $downstream -j REJECT >/dev/null 2>&1"
            )
        }
    }

    private fun commandSucceeds(command: String): Boolean {
        return Application.getRootShell().run(null, XTABLES_LOCK_WAIT_PREAMBLE + command) == 0
    }

    private fun ensureRuleCommand(priority: Int, inputInterface: String, table: String): String {
        return ruleExistsCommand(priority, inputInterface, table) + " || " +
            "ip rule add pref $priority iif $inputInterface lookup $table"
    }

    private fun ruleExistsCommand(priority: Int, inputInterface: String, table: String): String {
        return "ip rule show pref $priority iif $inputInterface table $table | grep -q ."
    }

    private fun localHotspotRouteCommand(downstream: String): String {
        return "ip -4 route show dev $downstream scope link | awk '{print ${'$'}1}' | grep / | while read cidr; do " +
            "[ -n \"${'$'}cidr\" ] || continue; " +
            "ip route replace \"${'$'}cidr\" dev $downstream table $HOTSPOT_LOCAL_ROUTE_TABLE; " +
            "ip rule del pref $HOTSPOT_LOCAL_RULE_PRIORITY to \"${'$'}cidr\" 2>/dev/null || true; " +
            "ip rule add pref $HOTSPOT_LOCAL_RULE_PRIORITY to \"${'$'}cidr\" lookup $HOTSPOT_LOCAL_ROUTE_TABLE; " +
            "done"
    }

    private fun verifyLocalHotspotRouteCommand(downstream: String): String {
        return "ip -4 route show dev $downstream scope link | awk '{print ${'$'}1}' | grep / | while read cidr; do " +
            "[ -n \"${'$'}cidr\" ] || continue; " +
            "ip rule show | grep -q \"^$HOTSPOT_LOCAL_RULE_PRIORITY:.*to ${'$'}cidr .*lookup $HOTSPOT_LOCAL_ROUTE_TABLE\" && " +
            "ip route show table $HOTSPOT_LOCAL_ROUTE_TABLE | grep -q \"^${'$'}cidr dev $downstream\" || exit 1; " +
            "done"
    }

    private fun allowHotspotClientSubnetEgressCommand(downstream: String): String {
        return "ip -4 route show dev $downstream scope link | awk '{print ${'$'}1}' | grep / | while read cidr; do " +
            "[ -n \"${'$'}cidr\" ] || continue; " +
            "iptables -A $OUTPUT_CHAIN -d \"${'$'}cidr\" -j RETURN; " +
            "done"
    }

    private fun verifyHotspotClientSubnetEgressCommand(downstream: String): String {
        return "ip -4 route show dev $downstream scope link | awk '{print ${'$'}1}' | grep / | while read cidr; do " +
            "[ -n \"${'$'}cidr\" ] || continue; " +
            "iptables -C $OUTPUT_CHAIN -d \"${'$'}cidr\" -j RETURN >/dev/null 2>&1 || exit 1; " +
            "done"
    }

    private fun startAttestationProxyCommand(downstream: String): String {
        return "pidfile=$ATTESTATION_PROXY_PIDFILE; " +
            "host=${'$'}(ip -4 -o addr show dev $downstream scope global | awk '{split(${'$'}4,a,\"/\"); print a[1]; exit}'); " +
            "[ -n \"${'$'}host\" ] || exit 1; " +
            stopAttestationProxyCommand() + "; " +
            "sh -c 'toybox nc -4 -s \"${'$'}1\" -p ${VpnRouterAttestation.PORT} -L toybox nc -4 127.0.0.1 ${VpnRouterAttestation.LOCAL_PORT}' virtuvpn-router-proxy \"${'$'}host\" >/dev/null 2>&1 & " +
            "echo ${'$'}! > \"${'$'}pidfile\"; " +
            "sleep 0.1; kill -0 ${'$'}(cat \"${'$'}pidfile\") 2>/dev/null"
    }

    private fun verifyAttestationProxyCommand(downstream: String): String {
        return "pidfile=$ATTESTATION_PROXY_PIDFILE; " +
            "host=${'$'}(ip -4 -o addr show dev $downstream scope global | awk '{split(${'$'}4,a,\"/\"); print a[1]; exit}'); " +
            "[ -n \"${'$'}host\" ] || exit 1; " +
            "[ -f \"${'$'}pidfile\" ] && kill -0 ${'$'}(cat \"${'$'}pidfile\") 2>/dev/null && " +
            "ss -ltn 2>/dev/null | grep -q \"${'$'}host:${VpnRouterAttestation.PORT}\""
    }

    private fun stopAttestationProxyCommand(): String {
        return "pidfile=$ATTESTATION_PROXY_PIDFILE; " +
            "if [ -f \"${'$'}pidfile\" ]; then kill ${'$'}(cat \"${'$'}pidfile\") 2>/dev/null || true; rm -f \"${'$'}pidfile\"; fi; " +
            "pkill -f 'virtuvpn-router-proxy' 2>/dev/null || true; " +
            "pkill -f 'toybox nc -4 -s .* -p ${VpnRouterAttestation.PORT} -[lL] toybox nc -4 127.0.0.1 ${VpnRouterAttestation.LOCAL_PORT}' 2>/dev/null || true"
    }

    private fun clearLastRuleSignature(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_RULE_SIGNATURE)
            .apply()
    }

    private fun clearRouterState(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LAST_RULE_SIGNATURE)
            .remove(KEY_LAST_VIRTU_TUNNEL)
            .putBoolean(KEY_ROUTER_DESIRED_ACTIVE, false)
            .remove(KEY_TUNNEL_RESTORE_MODE)
            .remove(KEY_LAST_ACTIVE_ROUTER_TUNNEL)
            .remove(KEY_LAST_ACTIVE_ROUTER_TETHERS)
            .remove(KEY_LAST_ACTIVE_ROUTER_DNS)
            .remove(KEY_ROOT_CHECK_FAILURES)
            .remove(KEY_DEGRADED_TUNNEL)
            .remove(KEY_DEGRADED_DETAIL)
            .remove(KEY_HEALTH_FAILURES)
            .remove(KEY_HEALTH_SUCCESSES)
            .remove(KEY_VERIFY_FAILURES)
            .putString(KEY_OPERATION_STAGE, OperationStage.IDLE.name)
            .putString(KEY_OPERATION_DETAIL, "")
            .apply()
    }

    private fun rememberRouterActiveStatus(context: Context, status: Status) {
        if (!status.routerActive) return
        val activeTunnel = status.activeTunnel ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_ACTIVE_ROUTER_TUNNEL, activeTunnel)
            .putString(KEY_LAST_ACTIVE_ROUTER_TETHERS, status.tetherInterfaces.joinToString(","))
            .putString(KEY_LAST_ACTIVE_ROUTER_DNS, status.dnsResolvers.joinToString(","))
            .putBoolean(KEY_ROUTER_DESIRED_ACTIVE, true)
            .putInt(KEY_ROOT_CHECK_FAILURES, 0)
            .apply()
    }

    /**
     * Optional boot hardening: help a third-party VPN provider reconnect faster
     * after a reboot by recording it as the Android always-on VPN target for the
     * boot watchdog to re-assert. This never affects fail-closed security (the
     * root pre-block already guarantees no leak without a tunnel); it only speeds
     * up tunnel restore. Deliberately scoped:
     *  - Our own WgQuick tunnel is skipped — always-on is an Android VpnService
     *    mechanism and would target GoBackend, not the root router.
     *  - Only a single, unambiguously-resolved third-party owner is recorded.
     *  - Lockdown is never enabled (a failed provider must not brick the uplink).
     */
    private fun updateAlwaysOnProvider(context: Context) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ownerUid = readVpnOwnerUid()
        val packages = ownerUid
            ?.let { runCatching { appContext.packageManager.getPackagesForUid(it) }.getOrNull() }
            ?.toList()
            .orEmpty()
        when {
            packages.size == 1 && packages[0] == appContext.packageName -> {
                // Our own tunnel: rely on the app's WgQuick boot restore instead.
                clearAlwaysOnProviderIfOwned(appContext)
                prefs.edit().putString(KEY_TUNNEL_RESTORE_MODE, "app").apply()
            }
            packages.size == 1 -> {
                val provider = packages[0]
                val ok = runCatching {
                    Application.getRootShell().run(
                        null,
                        "settings put secure $ALWAYS_ON_INTENT_SETTING ${shellQuote(provider)}"
                    ) == 0
                }.getOrDefault(false)
                if (ok) {
                    prefs.edit()
                        .putString(KEY_TUNNEL_RESTORE_MODE, "provider")
                        .putBoolean(KEY_ALWAYS_ON_OWNED, true)
                        .apply()
                    Log.i(TAG, "Recorded VPN router always-on provider $provider for boot restore")
                } else {
                    clearAlwaysOnProviderIfOwned(appContext)
                    Log.w(TAG, "Unable to record always-on provider $provider")
                }
            }
            else -> {
                // Unknown or ambiguous owner: never touch always-on.
                clearAlwaysOnProviderIfOwned(appContext)
                prefs.edit().remove(KEY_TUNNEL_RESTORE_MODE).apply()
            }
        }
    }

    private fun clearAlwaysOnProviderIfOwned(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ALWAYS_ON_OWNED, false)) return
        runCatching {
            // Clear both our recorded intent and the Android always-on slot we set;
            // only reached when Virtu owns the setting, so no user choice is lost.
            Application.getRootShell().run(
                null,
                "settings delete secure $ALWAYS_ON_INTENT_SETTING; settings delete secure always_on_vpn_app"
            )
        }.onFailure { Log.w(TAG, "Unable to clear always-on provider setting", it) }
        prefs.edit().putBoolean(KEY_ALWAYS_ON_OWNED, false).apply()
    }

    private data class LastActiveRouterSnapshot(
        val tunnel: String?,
        val tetherInterfaces: List<String>,
        val dnsResolvers: List<String>
    )

    private fun lastActiveRouterSnapshot(context: Context): LastActiveRouterSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return LastActiveRouterSnapshot(
            tunnel = prefs.getString(KEY_LAST_ACTIVE_ROUTER_TUNNEL, null)?.takeIf { it.isNotBlank() },
            tetherInterfaces = prefs.getString(KEY_LAST_ACTIVE_ROUTER_TETHERS, null)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty(),
            dnsResolvers = prefs.getString(KEY_LAST_ACTIVE_ROUTER_DNS, null)
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        )
    }

    private fun transientActiveStatusForRootMiss(context: Context, error: Throwable): Status? {
        val snapshot = lastActiveRouterSnapshot(context)
        val tunnel = snapshot.tunnel ?: return null
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val failures = (prefs.getInt(KEY_ROOT_CHECK_FAILURES, 0) + 1).coerceAtMost(ROOT_CHECK_FAILURES_BEFORE_UNSUPPORTED)
        prefs.edit()
            .putInt(KEY_ROOT_CHECK_FAILURES, failures)
            .apply()
        if (failures >= ROOT_CHECK_FAILURES_BEFORE_UNSUPPORTED) return null
        Log.w(TAG, "VPN router root check missed; keeping last active attestation state (${failures}/$ROOT_CHECK_FAILURES_BEFORE_UNSUPPORTED)", error)
        return Status(
            availability = Availability.DEGRADED,
            activeTunnel = tunnel,
            tetherInterfaces = snapshot.tetherInterfaces,
            dnsResolvers = snapshot.dnsResolvers,
            detail = "Router root check missed; keeping fail-closed protection while verification recovers",
            securityProtected = true
        )
    }

    private fun clearDegradedTunnel(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_DEGRADED_TUNNEL)
            .remove(KEY_DEGRADED_DETAIL)
            .remove(KEY_HEALTH_FAILURES)
            .remove(KEY_HEALTH_SUCCESSES)
            .remove(KEY_VERIFY_FAILURES)
            .remove(KEY_ROOT_CHECK_FAILURES)
            .apply()
    }

    private fun degradedAvailability(context: Context, tunnel: String): Availability {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (prefs.getString(KEY_DEGRADED_TUNNEL, null) == tunnel) Availability.DEGRADED else Availability.ENABLED
    }

    private fun degradedDetail(context: Context, tunnel: String): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DEGRADED_TUNNEL, null) != tunnel) return null
        return prefs.getString(KEY_DEGRADED_DETAIL, TUNNEL_HEALTH_FAILED_DETAIL)
    }

    private fun markTunnelDegraded(context: Context, base: Status, tunnel: String, detail: String): Status {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_DEGRADED_TUNNEL, tunnel)
            .putString(KEY_DEGRADED_DETAIL, detail)
            .putInt(KEY_HEALTH_FAILURES, HEALTH_FAILURES_BEFORE_DEGRADED)
            .putInt(KEY_HEALTH_SUCCESSES, 0)
            .remove(KEY_VERIFY_FAILURES)
            .apply()
        return base.copy(
            availability = Availability.DEGRADED,
            activeTunnel = base.activeTunnel ?: tunnel,
            detail = detail,
            securityProtected = base.securityProtected
        )
    }

    private suspend fun probeDegradedLocked(context: Context, status: Status): Status {
        val tunnel = status.activeTunnel ?: return status
        val dnsResolver = status.dnsResolvers.firstOrNull()?.takeIf(::isIpv4Address) ?: DnsMode.QUAD9.resolvers.first()
        setOperation(context, OperationStage.CHECKING_HEALTH, "Checking internet through VPN interface $tunnel")
        return if (checkTunnelHealth(tunnel, dnsResolver)) {
            if (recordTunnelHealthSuccess(context) >= HEALTH_SUCCESSES_BEFORE_RECOVERY) {
                clearDegradedTunnel(context)
                setOperation(context, OperationStage.COMPLETE, "VPN router is protected")
                status.copy(availability = Availability.ENABLED, detail = null).also { rememberVirtuFallbackTunnel(context) }
            } else {
                setOperation(context, OperationStage.CHECKING_HEALTH, "VPN tunnel recovered once; waiting for stable confirmation")
                status.copy(availability = Availability.DEGRADED, detail = status.detail ?: tunnelHealthFailureDetail(context))
            }
        } else {
            recordTunnelHealthFailure(context)
            val detail = status.detail ?: tunnelHealthFailureDetail(context)
            setOperation(context, OperationStage.ERROR, detail)
            status.copy(availability = Availability.DEGRADED, detail = detail)
        }
    }

    private fun recordTunnelHealthSuccess(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val successes = (prefs.getInt(KEY_HEALTH_SUCCESSES, 0) + 1).coerceAtMost(HEALTH_SUCCESSES_BEFORE_RECOVERY)
        prefs.edit()
            .putInt(KEY_HEALTH_SUCCESSES, successes)
            .putInt(KEY_HEALTH_FAILURES, 0)
            .remove(KEY_VERIFY_FAILURES)
            .apply()
        return successes
    }

    private fun recordTunnelHealthFailure(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val failures = (prefs.getInt(KEY_HEALTH_FAILURES, 0) + 1).coerceAtMost(HEALTH_FAILURES_BEFORE_DEGRADED)
        prefs.edit()
            .putInt(KEY_HEALTH_FAILURES, failures)
            .putInt(KEY_HEALTH_SUCCESSES, 0)
            .apply()
        return failures
    }

    private fun recordRouterVerifyFailure(context: Context): Int {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val failures = (prefs.getInt(KEY_VERIFY_FAILURES, 0) + 1).coerceAtMost(VERIFY_FAILURES_BEFORE_ERROR)
        prefs.edit()
            .putInt(KEY_VERIFY_FAILURES, failures)
            .apply()
        return failures
    }

    private fun clearRouterVerifyFailures(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_VERIFY_FAILURES)
            .apply()
    }

    private fun setOperation(context: Context, stage: OperationStage, detail: String? = null) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OPERATION_STAGE, stage.name)
            .putString(KEY_OPERATION_DETAIL, detail ?: "")
            .apply()
    }

    private fun assertHotspotBackstop(tetherInterfaces: List<String>) {
        val downstreams = tetherInterfaces.mapNotNull { name ->
            runCatching { checkedInterfaceName(name) }.getOrNull()
        }
        if (downstreams.isEmpty()) return
        val commands = buildString {
            append("ip route replace unreachable default table $HOTSPOT_BLOCK_ROUTE_TABLE; ")
            downstreams.forEach { downstream ->
                append(ensureRuleCommand(HOTSPOT_BLOCK_RULE_PRIORITY, downstream, HOTSPOT_BLOCK_ROUTE_TABLE.toString()))
                append("; ")
            }
        }
        runCatching {
            checkedRun("assert hotspot fail-closed backstop", commands)
        }.onFailure { e ->
            Log.w(TAG, "Unable to assert hotspot fail-closed backstop", e)
        }
    }

    private fun removeRules(context: Context) {
        val interfaceRead = runCatching { tryReadUpInterfaces() }.getOrNull()
        val liveTethers = interfaceRead?.let { read ->
            readTetherInterfaces(
                activeTunnel = "",
                upInterfaces = read.interfaces,
                defaultRouteInterfaces = readDefaultRouteInterfaces().toSet()
            )
        }.orEmpty()
        if (liveTethers.isNotEmpty()) {
            assertHotspotBackstop(liveTethers)
        }
        val hotspotConfirmedDown = interfaceRead != null && interfaceRead.interfaces.isNotEmpty() && liveTethers.isEmpty()
        val blockCleanup = if (hotspotConfirmedDown) {
            "while ip rule del pref $HOTSPOT_BLOCK_RULE_PRIORITY 2>/dev/null; do :; done; " +
                "ip route flush table $HOTSPOT_BLOCK_ROUTE_TABLE 2>/dev/null || true; "
        } else {
            ""
        }
        checkedRun(
            "remove hotspot VPN routes",
            "while ip rule del pref $HOTSPOT_VPN_RULE_PRIORITY 2>/dev/null; do :; done; " +
                "while ip rule del pref $HOTSPOT_LOCAL_RULE_PRIORITY 2>/dev/null; do :; done; " +
                "ip route flush table $HOTSPOT_LOCAL_ROUTE_TABLE 2>/dev/null || true; " +
                "ip route flush table $HOTSPOT_VPN_ROUTE_TABLE 2>/dev/null || true; " +
                blockCleanup
        )
        if (liveTethers.isNotEmpty()) {
            assertHotspotBackstop(liveTethers)
        }
        checkedRun("flush route cache", "ip route flush cache 2>/dev/null || true")
        checkedRun(
            "remove attestation input guard",
            "while iptables -D INPUT -p tcp --dport ${VpnRouterAttestation.PORT} -j REJECT 2>/dev/null; do :; done; " +
                "for iface in $(ip -o link show | awk -F': ' '{print $2}' | cut -d@ -f1); do " +
                "while iptables -D INPUT -i \"${'$'}iface\" -p tcp --dport ${VpnRouterAttestation.PORT} -j ACCEPT 2>/dev/null; do :; done; " +
                "done"
        )
        checkedRun(
            "stop router attestation hotspot proxy",
            stopAttestationProxyCommand()
        )
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
            "detach IPv6 output chain",
            "ip6tables -D OUTPUT -j $IPV6_OUTPUT_CHAIN 2>/dev/null || true"
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
        checkedRun("clear IPv6 output chain", "ip6tables -F $IPV6_OUTPUT_CHAIN 2>/dev/null || true")
        checkedRun("delete IPv6 output chain", "ip6tables -X $IPV6_OUTPUT_CHAIN 2>/dev/null || true")
        restoreHotspotAutoShutdown()
        restoreWifiSharing()
        restoreTetherOffload()
    }

    private fun disableHotspotAutoShutdown(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WIFI_AP_TIMEOUT_PREVIOUS)) {
            prefs.edit().putString(KEY_WIFI_AP_TIMEOUT_PREVIOUS, readSecureSetting(WIFI_AP_TIMEOUT_SETTING) ?: "").apply()
        }
        if (readSecureSetting(WIFI_AP_TIMEOUT_SETTING) != "0") {
            checkedRun("disable hotspot auto shutdown", "settings put secure $WIFI_AP_TIMEOUT_SETTING 0")
        }
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

    private fun enableWifiSharing(context: Context) {
        val current = readSecureSetting(WIFI_AP_WIFI_SHARING_SETTING) ?: return
        if (current == "null") return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WIFI_AP_WIFI_SHARING_PREVIOUS)) {
            prefs.edit().putString(KEY_WIFI_AP_WIFI_SHARING_PREVIOUS, current).apply()
        }
        if (current != "1") {
            checkedRun("enable concurrent WiFi hotspot", "settings put secure $WIFI_AP_WIFI_SHARING_SETTING 1")
        }
    }

    private fun restoreWifiSharing() {
        val prefs = Application.get().applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_WIFI_AP_WIFI_SHARING_PREVIOUS)) return
        val previous = prefs.getString(KEY_WIFI_AP_WIFI_SHARING_PREVIOUS, "") ?: ""
        if (previous.isBlank() || previous == "null") {
            checkedRun("restore concurrent WiFi hotspot", "settings delete secure $WIFI_AP_WIFI_SHARING_SETTING 2>/dev/null || true")
        } else {
            checkedRun("restore concurrent WiFi hotspot", "settings put secure $WIFI_AP_WIFI_SHARING_SETTING $previous")
        }
        prefs.edit().remove(KEY_WIFI_AP_WIFI_SHARING_PREVIOUS).apply()
    }

    private fun disableTetherOffload(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_TETHER_OFFLOAD_PREVIOUS)) {
            prefs.edit().putString(KEY_TETHER_OFFLOAD_PREVIOUS, readGlobalSetting(TETHER_OFFLOAD_DISABLED_SETTING) ?: "").apply()
        }
        if (readGlobalSetting(TETHER_OFFLOAD_DISABLED_SETTING) != "1") {
            checkedRun("disable tether offload", "settings put global $TETHER_OFFLOAD_DISABLED_SETTING 1")
        }
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
        val exit = Application.getRootShell().run(null, XTABLES_LOCK_WAIT_PREAMBLE + command)
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

    private suspend fun rememberVirtuFallbackTunnel(context: Context) {
        val tunnelName = runCatching {
            Application.getTunnelManager().getTunnels().firstOrNull { tunnel -> tunnel.state == Tunnel.State.UP }?.name
        }.getOrNull() ?: return
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_VIRTU_TUNNEL, tunnelName)
            .apply()
    }

    private suspend fun tryVirtuFallback(context: Context, failedTunnel: String): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fallbackName = prefs
            .getString(KEY_LAST_VIRTU_TUNNEL, null)
            ?.takeIf { name -> name.isNotBlank() }
            ?: return false
        setOperation(context, OperationStage.FALLING_BACK, "New tunnel $failedTunnel failed health check; restoring Virtu tunnel $fallbackName")
        return runCatching {
            val tunnels = Application.getTunnelManager().getTunnels()
            val fallback = tunnels[fallbackName] ?: run {
                prefs.edit { remove(KEY_LAST_VIRTU_TUNNEL) }
                return false
            }
            val state = fallback.setStateAsync(Tunnel.State.UP)
            state == Tunnel.State.UP
        }.onFailure { e ->
            Log.w(TAG, "Unable to restore Virtu VPN router fallback tunnel $fallbackName", e)
        }.getOrDefault(false)
    }

    private suspend fun pruneMissingStoredTunnelReferences(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val configuredTunnelNames = runCatching {
            Application.getTunnelManager().getTunnels().map { tunnel -> tunnel.name }.toSet()
        }.getOrDefault(emptySet())
        val editor = prefs.edit()
        var changed = false
        val lastVirtuTunnel = prefs.getString(KEY_LAST_VIRTU_TUNNEL, null)
        if (!lastVirtuTunnel.isNullOrBlank() && lastVirtuTunnel !in configuredTunnelNames) {
            editor.remove(KEY_LAST_VIRTU_TUNNEL)
            changed = true
        }
        val degradedTunnel = prefs.getString(KEY_DEGRADED_TUNNEL, null)
        if (!degradedTunnel.isNullOrBlank() && configuredTunnelNames.none { name -> name == degradedTunnel }) {
            editor
                .remove(KEY_DEGRADED_TUNNEL)
                .remove(KEY_DEGRADED_DETAIL)
                .remove(KEY_HEALTH_FAILURES)
                .remove(KEY_HEALTH_SUCCESSES)
                .remove(KEY_VERIFY_FAILURES)
            changed = true
        }
        if (changed) editor.apply()
    }

    private fun checkTunnelHealth(tunnel: String, dnsResolver: String): Boolean {
        val checkedTunnel = checkedInterfaceName(tunnel)
        val curlTargets = listOf(
            "https://1.1.1.1/cdn-cgi/trace",
            "https://cloudflare.com/cdn-cgi/trace",
            "https://quad9.net/"
        )
        val successfulTcpProbes = curlTargets.count { url ->
            commandSucceeds(
                "curl --interface $checkedTunnel -4 -k -L --connect-timeout 3 --max-time 6 " +
                    "-fsS -o /dev/null ${shellQuote(url)} >/dev/null 2>&1"
            )
        }
        if (successfulTcpProbes >= 1) return true

        val targets = listOfNotNull(
            dnsResolver.takeIf(::isIpv4Address),
            "1.1.1.1",
            "9.9.9.9",
            "8.8.8.8"
        ).distinct()
        val successfulProbes = targets.count { target ->
            commandSucceeds("ping -I $checkedTunnel -c 1 -W 2 $target >/dev/null 2>&1")
        }
        if (successfulProbes >= 2) return true
        return successfulProbes >= 1 &&
            commandSucceeds("ping -I $checkedTunnel -c 1 -W 3 example.com >/dev/null 2>&1")
    }

    // Passive check (no packets sent, no fail-closed bypass): does any non-VPN
    // uplink have validated internet per Android's own connectivity/captive
    // probes? Used ONLY to word the failure message (tunnel-down vs
    // upstream/ISP-down). It never influences the fail-closed decision: with no
    // healthy tunnel the router stays blocked regardless of this result.
    private fun uplinkHasInternet(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return false
        return runCatching {
            cm.allNetworks.any { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@any false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            }
        }.getOrDefault(false)
    }

    private fun tunnelHealthFailureDetail(context: Context): String =
        if (uplinkHasInternet(context)) TUNNEL_HEALTH_FAILED_DETAIL else UPSTREAM_INTERNET_FAILED_DETAIL

    private fun tunnelHealthTransientDetail(context: Context): String =
        if (uplinkHasInternet(context)) TUNNEL_HEALTH_TRANSIENT_DETAIL else UPSTREAM_INTERNET_TRANSIENT_DETAIL

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\"'\"'") + "'"
    }

    private class TunnelHealthException(message: String) : IllegalStateException(message)

    private fun readVpnProviderUids(context: Context): List<Int> {
        val ownUid = context.applicationInfo.uid
        return runCatching {
            val flags = PackageManager.GET_META_DATA
            context.packageManager
                .queryIntentServices(Intent(VpnService.SERVICE_INTERFACE), flags)
                .asSequence()
                .mapNotNull { info -> info.serviceInfo?.applicationInfo?.uid }
                .filter { uid -> uid > 0 && uid != ownUid }
                .distinct()
                .sorted()
                .toList()
        }.getOrElse { e ->
            Log.w(TAG, "Unable to enumerate VPN provider UIDs", e)
            emptyList()
        }
    }

    private fun readRouterLocalBrowserUids(context: Context): List<Int> {
        return ROUTER_LOCAL_BROWSER_PACKAGES.mapNotNull { packageName ->
            runCatching { context.packageManager.getApplicationInfo(packageName, 0).uid }.getOrNull()
        }.distinct().sorted()
    }

    private fun enforceRouterLocalEgressOwnership(context: Context): Boolean {
        val ownUid = context.applicationInfo.uid
        val commands = mutableListOf(
            "while iptables -D $OUTPUT_CHAIN -m owner --uid-owner $ownUid -j RETURN 2>/dev/null; do :; done",
            "while ip6tables -D $IPV6_OUTPUT_CHAIN -m owner --uid-owner $ownUid -j RETURN 2>/dev/null; do :; done"
        )
        readRouterLocalBrowserUids(context).forEach { uid ->
            commands += "iptables -C $OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN >/dev/null 2>&1 || iptables -I $OUTPUT_CHAIN 1 -m owner --uid-owner $uid -j RETURN"
            commands += "ip6tables -C $IPV6_OUTPUT_CHAIN -m owner --uid-owner $uid -j RETURN >/dev/null 2>&1 || ip6tables -I $IPV6_OUTPUT_CHAIN 1 -m owner --uid-owner $uid -j RETURN"
        }
        return commandSucceeds(commands.joinToString("; "))
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
        return name.length in 1..64 && !name.startsWith("-") && INTERFACE_NAME_REGEX.matches(name)
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
    private const val KEY_COMPATIBILITY_MODE = "compatibility_mode"
    private const val KEY_OPERATION_STAGE = "operation_stage"
    private const val KEY_OPERATION_DETAIL = "operation_detail"
    private const val KEY_LAST_RULE_SIGNATURE = "last_rule_signature"
    private const val KEY_UPLINK_PREFERENCE = "uplink_preference"
    private const val ROUTER_RULES_VERSION = 11
    private const val ATTESTATION_PROXY_PIDFILE = "/data/local/tmp/virtuvpn-router-attestation-proxy.pid"
    private const val KEY_LAST_VIRTU_TUNNEL = "last_virtu_tunnel"
    private const val KEY_DEGRADED_TUNNEL = "degraded_tunnel"
    private const val KEY_DEGRADED_DETAIL = "degraded_detail"
    private const val KEY_HEALTH_FAILURES = "health_failures"
    private const val KEY_HEALTH_SUCCESSES = "health_successes"
    private const val KEY_VERIFY_FAILURES = "verify_failures"
    private const val KEY_ROOT_CHECK_FAILURES = "root_check_failures"
    private const val KEY_ROUTER_DESIRED_ACTIVE = "router_desired_active"
    private const val KEY_ALWAYS_ON_OWNED = "always_on_owned"
    private const val KEY_TUNNEL_RESTORE_MODE = "tunnel_restore_mode"
    // Secure setting the boot watchdog reads to re-assert Android always-on VPN.
    private const val ALWAYS_ON_INTENT_SETTING = "virtu_router_always_on_pkg"
    private const val KEY_LAST_ACTIVE_ROUTER_TUNNEL = "last_active_router_tunnel"
    private const val KEY_LAST_ACTIVE_ROUTER_TETHERS = "last_active_router_tethers"
    private const val KEY_LAST_ACTIVE_ROUTER_DNS = "last_active_router_dns"
    private const val KEY_TETHER_OFFLOAD_PREVIOUS = "tether_offload_previous"
    private const val KEY_WIFI_AP_TIMEOUT_PREVIOUS = "wifi_ap_timeout_previous"
    private const val KEY_WIFI_AP_WIFI_SHARING_PREVIOUS = "wifi_ap_wifi_sharing_previous"
    private const val TUNNEL_HEALTH_FAILED_DETAIL = "Selected VPN tunnel has no internet; hotspot clients remain fail-closed. Try another tunnel on the router."
    private const val UPSTREAM_INTERNET_FAILED_DETAIL = "Upstream internet unavailable (mobile data / ISP / captive portal); hotspot clients remain fail-closed. Restore the router's internet connection."
    private const val TUNNEL_HEALTH_TRANSIENT_DETAIL = "VPN tunnel probe missed; keeping router active until failures are sustained"
    private const val UPSTREAM_INTERNET_TRANSIENT_DETAIL = "Upstream internet probe missed; keeping router active until failures are sustained"
    private const val HEALTH_FAILURES_BEFORE_DEGRADED = 3
    private const val HEALTH_SUCCESSES_BEFORE_RECOVERY = 2
    private const val VERIFY_FAILURES_BEFORE_ERROR = 3

    // netd rewrites tether rules on link changes; without the xtables lock wait
    // a concurrent iptables invocation fails with exit code 4 mid-rebuild.
    private const val XTABLES_LOCK_WAIT_PREAMBLE =
        "iptables() { command iptables -w 5 \"${'$'}@\"; }; " +
            "ip6tables() { command ip6tables -w 5 \"${'$'}@\"; }; "
    private const val ROOT_CHECK_FAILURES_BEFORE_UNSUPPORTED = 3
    private const val TETHER_OFFLOAD_DISABLED_SETTING = "tether_offload_disabled"
    private const val WIFI_AP_TIMEOUT_SETTING = "wifi_ap_timeout_setting"
    private const val WIFI_AP_WIFI_SHARING_SETTING = "wifi_ap_wifi_sharing"
    private const val NAT_CHAIN = "VIRTUVPN_ROUTER"
    private const val DNS_CHAIN = "VIRTUVPN_ROUTER_DNS"
    private const val FORWARD_CHAIN = "VIRTUVPN_ROUTER_FWD"
    private const val OUTPUT_CHAIN = "VIRTUVPN_ROUTER_OUT"
    private const val IPV6_FORWARD_CHAIN = "VIRTUVPN_ROUTER6_FWD"
    private const val IPV6_OUTPUT_CHAIN = "VIRTUVPN_ROUTER6_OUT"
    private const val HOTSPOT_LOCAL_RULE_PRIORITY = 12050
    private const val HOTSPOT_VPN_RULE_PRIORITY = 20900
    private const val HOTSPOT_BLOCK_RULE_PRIORITY = 20901
    private const val HOTSPOT_LOCAL_ROUTE_TABLE = 1046
    private const val HOTSPOT_VPN_ROUTE_TABLE = 1047
    private const val HOTSPOT_BLOCK_ROUTE_TABLE = 1048
    private const val MAX_DNS_RESOLVERS = 2
    private val VPN_BOOTSTRAP_SYSTEM_UIDS = listOf(
        1000, // system_server / Android VPN and connectivity orchestration
        1051, // dns resolver on Android builds that use AID_DNS
        1052, // tether/system DNS helper on Samsung builds
        1073 // network stack validation and bootstrap plumbing
    )
    private val ROUTER_LOCAL_BROWSER_PACKAGES = listOf("com.android.chrome")
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
