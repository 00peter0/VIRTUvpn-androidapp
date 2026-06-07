/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.net.TetheringInterface
import android.net.TetheringManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.Executor

object HotspotDetector {
    fun isWifiHotspotActive(context: Context): Boolean {
        return isWifiHotspotActiveByWifiManager(context)
    }

    fun registerWifiHotspotCallback(context: Context, onChanged: (Boolean) -> Unit): AutoCloseable? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            registerTetheringCallback(context.applicationContext, onChanged)
        } catch (e: Throwable) {
            Log.d(TAG, "Unable to register tethering callback", e)
            null
        }
    }

    private fun hasWifiTetheredInterface(interfaces: Set<TetheringInterface>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        return interfaces.any { it.type == TetheringManager.TETHERING_WIFI }
    }

    private fun registerTetheringCallback(context: Context, onChanged: (Boolean) -> Unit): AutoCloseable? {
        val manager = context.getSystemService(TetheringManager::class.java) ?: return null
        val executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
        val callback = object : TetheringManager.TetheringEventCallback {
            override fun onTetheredInterfacesChanged(interfaces: Set<TetheringInterface>) {
                onChanged(hasWifiTetheredInterface(interfaces))
            }
        }
        manager.registerTetheringEventCallback(executor, callback)
        return AutoCloseable {
            runCatching { manager.unregisterTetheringEventCallback(callback) }
        }
    }

    private fun isWifiHotspotActiveByWifiManager(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return try {
            val enabledMethod = wifiManager.javaClass.getDeclaredMethod("isWifiApEnabled")
            enabledMethod.isAccessible = true
            enabledMethod.invoke(wifiManager) as? Boolean ?: false
        } catch (_: Throwable) {
            isWifiApStateEnabled(wifiManager)
        }
    }

    private fun isWifiApStateEnabled(wifiManager: WifiManager): Boolean {
        return try {
            val stateMethod = wifiManager.javaClass.getDeclaredMethod("getWifiApState")
            stateMethod.isAccessible = true
            val state = stateMethod.invoke(wifiManager) as? Int ?: return false
            state == WIFI_AP_STATE_ENABLING || state == WIFI_AP_STATE_ENABLED
        } catch (e: Throwable) {
            Log.d(TAG, "Unable to read Wi-Fi hotspot state", e)
            false
        }
    }

    private const val TAG = "VirtuVPN/Hotspot"
    private const val WIFI_AP_STATE_ENABLING = 12
    private const val WIFI_AP_STATE_ENABLED = 13
}
