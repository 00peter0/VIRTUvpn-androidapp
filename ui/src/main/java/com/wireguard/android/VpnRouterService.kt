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
import android.graphics.Color
import android.net.ConnectivityManager
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
    private var serverStartedUnbound = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.vcs_vpn_router_service_starting)))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_RESTORE_REQUEST, false) == true) {
            Log.i(TAG, "VPN router service restore requested")
        }
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
                    VpnRouterAttestationServer.stop()
                    stopSelf()
                    return@launch
                }
                delay(RECONCILE_INTERVAL_MS)
            }
        }
    }

    private suspend fun reconcileOnce(): Boolean {
        clearProcessNetworkBinding()
        if (!serverStartedUnbound) {
            VpnRouterAttestationServer.stop()
            serverStartedUnbound = true
        }
        val initial = VpnRouterManager.getStatus(applicationContext)
        if (initial.routerActive) VpnRouterAttestationServer.start(applicationContext, initial)
        // After a reboot the rules are gone (routerActive=false) but the user's
        // intent persists. Re-assert protection fail-closed instead of letting the
        // service idle out. restoreRouterIfDesired reconciles when already active.
        val desiredActive = VpnRouterManager.isRouterDesiredActive(applicationContext)
        val status = if (desiredActive && !initial.routerActive) {
            VpnRouterManager.restoreRouterIfDesired(applicationContext)
            VpnRouterManager.getStatus(applicationContext)
        } else if (initial.needsReconcile || initial.availability == VpnRouterManager.Availability.DEGRADED) {
            VpnRouterManager.reconcile(applicationContext)
        } else {
            initial
        }
        if (status.availability == VpnRouterManager.Availability.ENABLED ||
            status.availability == VpnRouterManager.Availability.DEGRADED) {
            VpnRouterAttestationServer.updateStatus(status)
            VpnRouterAttestationServer.start(applicationContext, status)
            updateNotification(status)
            inactiveTicks = 0
            return true
        }
        VpnRouterAttestationServer.updateStatus(status)
        // While the router is desired-active, keep the monitor alive even though it
        // is not yet installed — it is waiting for the tunnel/hotspot to return.
        if (desiredActive) {
            inactiveTicks = 0
            return true
        }
        inactiveTicks = if (status.needsReconcile) 0 else inactiveTicks + 1
        return inactiveTicks < MAX_INACTIVE_TICKS
    }

    private fun clearProcessNetworkBinding() {
        runCatching {
            getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null)
        }.onFailure {
            Log.d(TAG, "Unable to clear process network binding for VPN router service", it)
        }
    }

    private fun updateNotification(status: VpnRouterManager.Status) {
        val tunnel = status.activeTunnel ?: getString(R.string.vcs_vpn_status_no_tunnel)
        val interfaces = status.tetherInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: getString(R.string.vcs_vpn_router_no_interfaces)
        val text = if (status.availability == VpnRouterManager.Availability.DEGRADED) {
            status.detail ?: getString(R.string.vcs_vpn_router_service_degraded)
        } else {
            getString(R.string.vcs_vpn_router_service_active_detail, tunnel, interfaces)
        }
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
            .setSmallIcon(R.mipmap.ic_launcher)
            .setColor(Color.rgb(7, 16, 22))
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
        private const val EXTRA_RESTORE_REQUEST = "restore_request"

        fun startForRestore(context: Context) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, VpnRouterService::class.java)
                .putExtra(EXTRA_RESTORE_REQUEST, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appContext.startForegroundService(intent)
            } else {
                appContext.startService(intent)
            }
        }

        fun ensureForStatus(context: Context, status: VpnRouterManager.Status) {
            val appContext = context.applicationContext
            val intent = Intent(appContext, VpnRouterService::class.java)
            // Start when the router is active OR when the user wants it active but it
            // has not been restored yet (e.g. right after a reboot). The service loop
            // drives restoreRouterIfDesired and only stops on explicit disable.
            if (status.routerActive || VpnRouterManager.isRouterDesiredActive(appContext)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appContext.startForegroundService(intent)
                } else {
                    appContext.startService(intent)
                }
            }
        }

        fun stopNow(context: Context) {
            context.applicationContext.stopService(Intent(context.applicationContext, VpnRouterService::class.java))
        }
    }
}
