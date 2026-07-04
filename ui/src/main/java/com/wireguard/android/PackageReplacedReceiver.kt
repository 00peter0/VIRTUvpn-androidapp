/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.wireguard.android.util.VpnRouterManager
import com.wireguard.android.util.applicationScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        applicationScope.launch {
            try {
                delay(POST_REPLACE_SETTLE_MS)
                val appContext = context.applicationContext
                val status = VpnRouterManager.getStatus(appContext)
                if (status.routerActive) {
                    Log.i(TAG, "Restoring VPN router service after package replace")
                    VpnRouterService.ensureForStatus(appContext, status)
                } else {
                    Log.d(TAG, "VPN router service restore skipped after package replace: ${status.availability}")
                }
            } catch (e: Throwable) {
                Log.d(TAG, "VPN router service restore failed after package replace", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "WireGuard/PackageReplacedReceiver"
        private const val POST_REPLACE_SETTLE_MS = 1_000L
    }
}
