package com.wireguard.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.util.Log
import android.widget.RemoteViews
import com.wireguard.android.R
import com.wireguard.android.activity.SecureBrowserActivity
import com.wireguard.android.activity.VpnRouterActivity
import com.wireguard.android.util.VpnRouterManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class VpnRouterWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_STATUS -> updateAllWidgets(context)
            ACTION_BROWSER -> {
                context.startActivity(
                    Intent(context, SecureBrowserActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
            }
            ACTION_CLIENT_PAGE -> openClientPage(context)
            ACTION_TOGGLE -> {
                val pendingResult = goAsync()
                Thread {
                    pendingResult.finish()
                    runBlocking(Dispatchers.IO) {
                        val status = runCatching { VpnRouterManager.getStatus(context.applicationContext) }.getOrNull()
                        Log.i(TAG, "Widget toggle requested: ${status?.availability ?: "unknown"}")
                        when {
                            status?.canDisable == true -> runCatching { VpnRouterManager.disable(context.applicationContext) }
                                .onFailure { Log.w(TAG, "Unable to disable router from widget", it) }
                            status?.canEnable == true -> runCatching {
                                VpnRouterManager.requestRouterActive(context.applicationContext)
                            }.onFailure {
                                Log.w(TAG, "Unable to request router enable from widget", it)
                            }
                            status?.availability == VpnRouterManager.Availability.WAITING_FOR_HOTSPOT &&
                                status.activeTunnel != null -> runCatching {
                                    VpnRouterManager.requestRouterActive(context.applicationContext)
                                }.onFailure {
                                    Log.w(TAG, "Unable to request router restore from widget", it)
                                }
                            else -> Log.i(TAG, "Widget toggle ignored; router is not toggleable")
                        }
                    }
                    updateAllWidgets(context)
                    Thread.sleep(3_000L)
                    updateAllWidgets(context)
                    Thread.sleep(7_000L)
                    updateAllWidgets(context)
                }.start()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_vpn_router_path)
            val status = updateStatus(context, views)
            bindClicks(context, views, appWidgetId)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context.applicationContext)
        val component = android.content.ComponentName(context, VpnRouterWidgetProvider::class.java)
        val ids = appWidgetManager.getAppWidgetIds(component)
        onUpdate(context.applicationContext, appWidgetManager, ids)
    }

    private fun bindClicks(context: Context, views: RemoteViews, appWidgetId: Int) {
        val refreshIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId + REQUEST_STATUS_OFFSET,
            Intent(context, VpnRouterWidgetProvider::class.java).setAction(ACTION_STATUS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val toggleIntent = PendingIntent.getBroadcast(
            context,
            appWidgetId + REQUEST_TOGGLE_OFFSET,
            Intent(context, VpnRouterWidgetProvider::class.java).setAction(ACTION_TOGGLE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, toggleIntent)
        views.setOnClickPendingIntent(R.id.widget_router_mark, refreshIntent)
        views.setOnClickPendingIntent(R.id.widget_status, refreshIntent)
        views.setOnClickPendingIntent(R.id.widget_primary_button, toggleIntent)
        views.setOnClickPendingIntent(R.id.widget_client_button, clientPageIntent(context, appWidgetId))
        views.setOnClickPendingIntent(R.id.widget_browser_button, browserIntent(context, appWidgetId))
    }

    private fun updateStatus(context: Context, views: RemoteViews): VpnRouterManager.Status? {
        val status = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(6_000L) {
                runCatching { VpnRouterManager.getStatus(context.applicationContext) }.getOrNull()
            }
        }
        val protected = status?.securityProtected == true
        val degraded = status?.availability == VpnRouterManager.Availability.DEGRADED
        val tunnelOnline = if (protected && !degraded) status.activeTunnel != null else false
        val statusText = when {
            status == null -> context.getString(R.string.vcs_widget_vpn_router_open_to_check)
            protected -> context.getString(R.string.vcs_widget_vpn_router_protected)
            status.availability == VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> context.getString(R.string.vcs_widget_vpn_router_waiting_tunnel)
            status.availability == VpnRouterManager.Availability.WAITING_FOR_HOTSPOT -> context.getString(R.string.vcs_widget_vpn_router_waiting_hotspot)
            status.availability == VpnRouterManager.Availability.READY -> context.getString(R.string.vcs_widget_vpn_router_ready)
            else -> context.getString(R.string.vcs_widget_vpn_router_disabled)
        }
        val detailText = when {
            status?.securityProtected == true && status.activeTunnel != null ->
                context.getString(R.string.vcs_widget_vpn_router_tunnel, status.activeTunnel)
            status?.detail != null -> status.detail
            else -> context.getString(R.string.vcs_widget_vpn_router_tap_detail)
        }
        val toggleText = when {
            status?.canDisable == true -> context.getString(R.string.vcs_widget_vpn_router_disable)
            status?.canEnable == true || status.canRequestHotspotRestore() -> context.getString(R.string.vcs_widget_vpn_router_enable)
            status == null -> context.getString(R.string.vcs_widget_vpn_router_check)
            else -> context.getString(R.string.vcs_widget_vpn_router_check)
        }
        val dnsText = context.getString(R.string.vcs_widget_vpn_router_dns, dnsLabel(context))
        val clientCount = hotspotClientCount(status?.tetherInterfaces.orEmpty())
        val clientCountText = clientCount?.let {
            context.getString(R.string.vcs_widget_vpn_router_clients_count, it)
        } ?: context.getString(R.string.vcs_widget_vpn_router_clients_unknown)
        val clientsText = status?.tetherInterfaces?.takeIf { it.isNotEmpty() }?.joinToString(", ") {
            context.getString(R.string.vcs_widget_vpn_router_hotspot_iface, it)
        } ?: context.getString(R.string.vcs_widget_vpn_router_hotspot_off)
        val checkedText = context.getString(
            R.string.vcs_widget_vpn_router_checked,
            SimpleDateFormat("HH:mm", Locale.US).format(Date())
        )
        val tunnelBadgeText = when {
            tunnelOnline -> context.getString(R.string.vcs_widget_vpn_router_tunnel_online)
            protected && degraded -> context.getString(R.string.vcs_widget_vpn_router_tunnel_offline)
            status?.availability == VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> context.getString(R.string.vcs_widget_vpn_router_waiting_tunnel)
            else -> context.getString(R.string.vcs_widget_vpn_router_tunnel_unknown)
        }
        val warningText = when {
            protected && degraded -> context.getString(R.string.vcs_widget_vpn_router_warning_tunnel_offline)
            protected -> context.getString(R.string.vcs_widget_vpn_router_warning_protected)
            status?.availability == VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> context.getString(R.string.vcs_widget_vpn_router_warning_waiting_vpn)
            status?.availability == VpnRouterManager.Availability.WAITING_FOR_HOTSPOT -> context.getString(R.string.vcs_widget_vpn_router_warning_hotspot_off)
            else -> context.getString(R.string.vcs_widget_vpn_router_warning_default)
        }
        val statusColor = when {
            protected -> GREEN
            status?.availability == VpnRouterManager.Availability.READY -> TEAL
            status?.availability == VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> YELLOW
            status?.availability == VpnRouterManager.Availability.WAITING_FOR_HOTSPOT -> YELLOW
            else -> RED
        }
        val vpnSegmentColor = when {
            tunnelOnline -> GREEN
            protected && degraded -> YELLOW
            status?.availability == VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> YELLOW
            protected -> GREEN
            else -> RED
        }
        val protectedSegmentColor = if (protected) GREEN else statusColor
        views.setTextViewText(R.id.widget_status, statusText)
        views.setTextViewText(R.id.widget_detail, detailText)
        views.setTextViewText(R.id.widget_checked, checkedText)
        views.setTextViewText(R.id.widget_tunnel_badge, tunnelBadgeText)
        views.setTextViewText(R.id.widget_clients_count, clientCountText)
        views.setTextViewText(R.id.widget_primary_button, toggleText)
        views.setTextViewText(R.id.widget_dns, dnsText)
        views.setTextViewText(R.id.widget_clients, clientsText)
        views.setTextViewText(R.id.widget_warning, warningText)
        views.setTextColor(R.id.widget_status, statusColor)
        views.setTextColor(R.id.widget_tunnel_badge, vpnSegmentColor)
        views.setTextColor(R.id.widget_path_vpn, vpnSegmentColor)
        views.setTextColor(R.id.widget_path_router, protectedSegmentColor)
        views.setTextColor(R.id.widget_path_hotspot, protectedSegmentColor)
        views.setTextColor(R.id.widget_path_clients, protectedSegmentColor)
        views.setTextColor(R.id.widget_warning, if (protected && degraded) YELLOW else if (protected) GREEN else statusColor)
        return status
    }

    private fun clientPageIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            appWidgetId + REQUEST_CLIENT_OFFSET,
            Intent(context, VpnRouterWidgetProvider::class.java).setAction(ACTION_CLIENT_PAGE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun browserIntent(context: Context, appWidgetId: Int): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            appWidgetId + REQUEST_BROWSER_OFFSET,
            Intent(context, VpnRouterWidgetProvider::class.java).setAction(ACTION_BROWSER),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun openClientPage(context: Context) {
        context.startActivity(
            Intent(context, VpnRouterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        )
    }

    private fun VpnRouterManager.Status?.canRequestHotspotRestore(): Boolean =
        this?.availability == VpnRouterManager.Availability.WAITING_FOR_HOTSPOT &&
            activeTunnel != null

    private fun dnsLabel(context: Context): String =
        when (VpnRouterManager.getDnsMode(context.applicationContext)) {
            VpnRouterManager.DnsMode.COPY_TUNNEL -> context.getString(R.string.vcs_widget_vpn_router_dns_tunnel)
            VpnRouterManager.DnsMode.CLOUDFLARE -> context.getString(R.string.vcs_widget_vpn_router_dns_cloudflare)
            VpnRouterManager.DnsMode.QUAD9 -> context.getString(R.string.vcs_widget_vpn_router_dns_quad9)
            VpnRouterManager.DnsMode.FAMILY -> context.getString(R.string.vcs_widget_vpn_router_dns_family)
        }

    private fun hotspotClientCount(tetherInterfaces: List<String>): Int? {
        if (tetherInterfaces.isEmpty()) return null
        return runCatching {
            val tethers = tetherInterfaces.toSet()
            File("/proc/net/arp").readLines()
                .drop(1)
                .map { it.trim().split(Regex("\\s+")) }
                .count { columns ->
                    columns.size >= 6 &&
                        columns[2] != "0x0" &&
                        columns[3] != "00:00:00:00:00:00" &&
                        columns[5] in tethers
                }
        }.getOrNull()
    }

    companion object {
        private const val ACTION_STATUS = "com.virtuvpn.android.widget.VPN_ROUTER_STATUS"
        private const val ACTION_TOGGLE = "com.virtuvpn.android.widget.VPN_ROUTER_TOGGLE"
        private const val ACTION_CLIENT_PAGE = "com.virtuvpn.android.widget.VPN_ROUTER_CLIENT_PAGE"
        private const val ACTION_BROWSER = "com.virtuvpn.android.widget.VPN_ROUTER_BROWSER"
        private const val TAG = "VpnRouterWidget"
        private const val REQUEST_STATUS_OFFSET = 10_000
        private const val REQUEST_TOGGLE_OFFSET = 20_000
        private const val REQUEST_CLIENT_OFFSET = 30_000
        private const val REQUEST_BROWSER_OFFSET = 40_000
        private val GREEN = Color.parseColor("#76F2A1")
        private val TEAL = Color.parseColor("#49E3F0")
        private val YELLOW = Color.parseColor("#FBBF24")
        private val RED = Color.parseColor("#F87171")
    }
}
