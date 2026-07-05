/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.backend.WgQuickBackend
import com.wireguard.android.util.VpnRouterManager
import com.wireguard.android.util.applicationScope
import com.wireguard.android.vcs.VcsManagedClient
import kotlinx.coroutines.launch

class BootShutdownReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        // Router restore is independent of the WireGuard backend and VCS session:
        // if the user left the VPN router on, re-assert its fail-closed protection
        // as early as possible after boot.
        if (Intent.ACTION_BOOT_COMPLETED == action &&
            VpnRouterManager.isRouterDesiredActive(context)
        ) {
            Log.i(TAG, "Boot: VPN router was desired-active, starting router service")
            runCatching { VpnRouterService.startForRestore(context) }
                .onFailure { Log.w(TAG, "Boot: unable to start VPN router service", it) }
        }
        applicationScope.launch {
            if (Application.getBackend() !is WgQuickBackend) return@launch
            val tunnelManager = Application.getTunnelManager()
            if (Intent.ACTION_BOOT_COMPLETED == action) {
                if (!VcsManagedClient.hasAccountSession(context)) {
                    Log.i(TAG, "Broadcast receiver skipped restore: VCS sign-in required")
                    return@launch
                }
                Log.i(TAG, "Broadcast receiver restoring state (boot)")
                tunnelManager.restoreState(false)
            } else if (Intent.ACTION_SHUTDOWN == action) {
                Log.i(TAG, "Broadcast receiver saving state (shutdown)")
                tunnelManager.saveState()
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/BootShutdownReceiver"
    }
}
