package com.wireguard.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wireguard.android.R
import com.wireguard.android.activity.VpnRouterActivity
import com.wireguard.android.util.VpnRouterManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class VpnRouterWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_STATUS -> updateAllWidgets(context)
            ACTION_TOGGLE -> {
                val pendingResult = goAsync()
                Thread {
                    try {
                        runBlocking(Dispatchers.IO) {
                            val status = runCatching { VpnRouterManager.getStatus(context.applicationContext) }.getOrNull()
                            when {
                                status?.canDisable == true -> runCatching { VpnRouterManager.disable(context.applicationContext) }
                                status?.canEnable == true -> runCatching { VpnRouterManager.enable(context.applicationContext) }
                                else -> Unit
                            }
                        }
                        updateAllWidgets(context)
                    } finally {
                        pendingResult.finish()
                    }
                }.start()
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_vpn_router_path)
            bindClicks(context, views, appWidgetId)
            updateStatus(context, views)
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
        val launchIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            Intent(context, VpnRouterActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
        views.setOnClickPendingIntent(R.id.widget_root, launchIntent)
        views.setOnClickPendingIntent(R.id.widget_router_logo, launchIntent)
        views.setOnClickPendingIntent(R.id.widget_status, refreshIntent)
        views.setOnClickPendingIntent(R.id.widget_toggle_button, toggleIntent)
    }

    private fun updateStatus(context: Context, views: RemoteViews) {
        val status = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(1_500L) {
                runCatching { VpnRouterManager.getStatus(context.applicationContext) }.getOrNull()
            }
        }
        val statusText = when {
            status == null -> context.getString(R.string.vcs_widget_vpn_router_open_to_check)
            status.securityProtected -> context.getString(R.string.vcs_widget_vpn_router_protected)
            status.availability == VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> context.getString(R.string.vcs_widget_vpn_router_waiting_tunnel)
            status.availability == VpnRouterManager.Availability.WAITING_FOR_HOTSPOT -> context.getString(R.string.vcs_widget_vpn_router_waiting_hotspot)
            status.availability == VpnRouterManager.Availability.READY -> context.getString(R.string.vcs_widget_vpn_router_ready)
            else -> context.getString(R.string.vcs_widget_vpn_router_blocked)
        }
        val detailText = when {
            status?.securityProtected == true && status.activeTunnel != null ->
                context.getString(R.string.vcs_widget_vpn_router_tunnel, status.activeTunnel)
            status?.detail != null -> status.detail
            else -> context.getString(R.string.vcs_widget_vpn_router_tap_detail)
        }
        val toggleText = when {
            status?.canDisable == true -> context.getString(R.string.vcs_widget_vpn_router_disable)
            status?.canEnable == true -> context.getString(R.string.vcs_widget_vpn_router_enable)
            else -> context.getString(R.string.vcs_widget_vpn_router_open)
        }
        views.setTextViewText(R.id.widget_status, statusText)
        views.setTextViewText(R.id.widget_detail, detailText)
        views.setTextViewText(R.id.widget_toggle_button, toggleText)
    }

    companion object {
        private const val ACTION_STATUS = "com.virtuvpn.android.widget.VPN_ROUTER_STATUS"
        private const val ACTION_TOGGLE = "com.virtuvpn.android.widget.VPN_ROUTER_TOGGLE"
        private const val REQUEST_STATUS_OFFSET = 10_000
        private const val REQUEST_TOGGLE_OFFSET = 20_000
    }
}
