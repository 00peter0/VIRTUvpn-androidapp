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
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            val views = RemoteViews(context.packageName, R.layout.widget_vpn_router_path)
            val launchIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                Intent(context, VpnRouterActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, launchIntent)
            views.setOnClickPendingIntent(R.id.widget_router_logo, launchIntent)
            updateStatus(context, views)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
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
        views.setTextViewText(R.id.widget_status, statusText)
        views.setTextViewText(R.id.widget_detail, detailText)
    }
}
