package com.wireguard.android.vcs

import android.content.Context
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.backend.Tunnel
import org.json.JSONArray
import org.json.JSONObject

/** Explicit, bounded support export. Configuration keys, endpoints, account tokens and browsing data are excluded. */
object ManagedDiagnostics {
    suspend fun collect(context: Context): JSONObject {
        val result = JSONObject().put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("accountSessionPresent", VcsManagedClient.hasAccountSession(context))
            .put("deviceSessionPresent", VcsManagedClient.hasSession(context)).put("sync", VcsManagedClient.syncStatus(context))
            .put("update", ManagedApkUpdates.progress(context)).put("backgroundSync", "Periodic, subject to Android battery/network scheduling")
        val tunnels = JSONArray()
        for (tunnel in Application.getTunnelManager().getTunnels().take(32)) {
            val config = runCatching { tunnel.getConfigAsync() }.getOrNull()
            val stats = runCatching { tunnel.getStatisticsAsync() }.getOrNull()
            val handshake = stats?.peers()?.mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis()?.takeIf { time -> time > 0 } }?.maxOrNull()
            val routes = config?.peers?.flatMap { it.allowedIps }?.map { it.toString() }.orEmpty()
            tunnels.put(JSONObject().put("index", tunnels.length() + 1).put("state", tunnel.state.name)
                .put("dnsServersConfigured", config?.`interface`?.dnsServers?.size ?: 0)
                .put("defaultIpv4RouteConfigured", "0.0.0.0/0" in routes).put("defaultIpv6RouteConfigured", "::/0" in routes)
                .put("handshakeAgeSeconds", handshake?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) } ?: JSONObject.NULL)
                .put("transportEvidence", if (tunnel.state == Tunnel.State.UP && handshake != null) "Handshake observed; reachability and DNS leaks are not externally verified" else "No handshake evidence"))
        }
        return result.put("tunnels", tunnels).put("limits", "Tunnel UP is a local state; DNS configuration and default routes do not prove public IP hiding or leak prevention.")
    }
}
