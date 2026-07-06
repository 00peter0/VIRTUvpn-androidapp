package com.wireguard.android.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.wireguard.android.R
import com.wireguard.android.activity.SecureBrowserWidgetSearchActivity

class SecuredBrowserWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { appWidgetId ->
            val layoutId = appWidgetManager.getAppWidgetInfo(appWidgetId)?.initialLayout
                ?: R.layout.widget_secured_browser_quick
            val views = RemoteViews(context.packageName, layoutId)
            val launchIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                Intent(context, SecureBrowserWidgetSearchActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, launchIntent)
            views.setOnClickPendingIntent(R.id.widget_search_box, launchIntent)
            views.setOnClickPendingIntent(R.id.widget_button, launchIntent)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}
