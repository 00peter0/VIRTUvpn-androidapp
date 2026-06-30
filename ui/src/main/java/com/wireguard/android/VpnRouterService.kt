/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.wireguard.android.activity.HomeActivity
import com.wireguard.android.util.VpnRouterAttestationServer
import com.wireguard.android.util.VpnRouterManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class VpnRouterService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var monitorJob: Job? = null
    private var inactiveTicks = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vcs_vpn_router_service_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMonitor()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch {
            while (isActive) {
                val keepRunning = runCatching { reconcileOnce() }.getOrElse {
                    Log.d(TAG, "VPN router service monitor skipped", it)
                    true
                }
                if (!keepRunning) {
                    stopSelf()
                    return@launch
                }
                delay(RECONCILE_INTERVAL_MS)
            }
        }
    }

    private suspend fun reconcileOnce(): Boolean {
        VpnRouterAttestationServer.start(applicationContext)
        val initial = VpnRouterManager.getStatus(applicationContext)
        val status = if (initial.needsReconcile) {
            VpnRouterManager.reconcile(applicationContext)
        } else {
            initial
        }
        if (status.availability == VpnRouterManager.Availability.ENABLED) {
            VpnRouterAttestationServer.updateStatus(status)
            updateNotification(status)
            inactiveTicks = 0
            return true
        }
        VpnRouterAttestationServer.updateStatus(status)
        inactiveTicks = if (status.needsReconcile) 0 else inactiveTicks + 1
        return inactiveTicks < MAX_INACTIVE_TICKS
    }

    private fun updateNotification(status: VpnRouterManager.Status) {
        val tunnel = status.activeTunnel ?: getString(R.string.vcs_vpn_status_no_tunnel)
        val interfaces = status.tetherInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: getString(R.string.vcs_vpn_router_no_interfaces)
        val text = getString(R.string.vcs_vpn_router_service_active_detail, tunnel, interfaces)
        val notification = buildNotification(text)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(getString(R.string.vcs_vpn_router_service_title))
            .setContentText(text)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vcs_vpn_router_service_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.vcs_vpn_router_service_channel_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "WireGuard/VpnRouterService"
        private const val CHANNEL_ID = "vpn_router"
        private const val NOTIFICATION_ID = 8608
        private const val RECONCILE_INTERVAL_MS = 2_000L
        private const val MAX_INACTIVE_TICKS = 5

        fun ensureForStatus(context: Context, status: VpnRouterManager.Status) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, VpnRouterService::class.java)
            if (status.needsReconcile) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            } else {
                appContext.stopService(intent)
            }
        }
    }
}
