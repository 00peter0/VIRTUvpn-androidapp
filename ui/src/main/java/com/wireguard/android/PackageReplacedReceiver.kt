/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            Log.i(TAG, "Starting VPN router service after package replace")
            VpnRouterService.startForRestore(context.applicationContext)
        }.onFailure {
            Log.d(TAG, "VPN router service restore failed after package replace", it)
        }
    }

    companion object {
        private const val TAG = "WireGuard/PackageReplacedReceiver"
    }
}
