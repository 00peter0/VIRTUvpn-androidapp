/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import android.util.Log
import com.wireguard.android.Application
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelConnectivityMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
    private val lock = Any()
    private val validatedUnderlays = mutableSetOf<Network>()
    private var callbackRegistered = false
    private var wasOffline = false
    private var restartJob: Job? = null
    private var lastRestartAt = 0L

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivityManager?.getNetworkCapabilities(network)?.let { updateNetwork(network, it) }
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            updateNetwork(network, networkCapabilities)
        }

        override fun onLost(network: Network) {
            val becameOffline = synchronized(lock) {
                val hadValidatedUnderlay = validatedUnderlays.isNotEmpty()
                validatedUnderlays.remove(network)
                val offline = hadValidatedUnderlay && validatedUnderlays.isEmpty()
                if (offline) wasOffline = true
                offline
            }
            if (becameOffline) {
                Log.i(TAG, "Underlying internet connectivity lost while monitoring VPN")
            }
        }
    }

    fun start() {
        val manager = connectivityManager ?: return
        refreshInitialState(manager)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.registerNetworkCallback(request, callback)
            callbackRegistered = true
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to start VPN connectivity monitor", e)
        }
    }

    fun stop() {
        if (!callbackRegistered) return
        try {
            connectivityManager?.unregisterNetworkCallback(callback)
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to stop VPN connectivity monitor", e)
        }
        callbackRegistered = false
        restartJob?.cancel()
    }

    private fun refreshInitialState(manager: ConnectivityManager) {
        val initialUnderlays = manager.allNetworks.filter { network ->
            manager.getNetworkCapabilities(network)?.isValidatedUnderlay() == true
        }
        synchronized(lock) {
            validatedUnderlays.clear()
            validatedUnderlays.addAll(initialUnderlays)
            wasOffline = validatedUnderlays.isEmpty()
        }
    }

    private fun updateNetwork(network: Network, capabilities: NetworkCapabilities) {
        val isValidatedUnderlay = capabilities.isValidatedUnderlay()
        val shouldRestart = synchronized(lock) {
            val hadValidatedUnderlay = validatedUnderlays.isNotEmpty()
            if (isValidatedUnderlay) validatedUnderlays.add(network) else validatedUnderlays.remove(network)
            val hasValidatedUnderlay = validatedUnderlays.isNotEmpty()
            val restored = !hadValidatedUnderlay && hasValidatedUnderlay && wasOffline
            if (!hasValidatedUnderlay) wasOffline = true
            if (restored) wasOffline = false
            restored
        }
        if (shouldRestart) scheduleTunnelRestart()
    }

    private fun scheduleTunnelRestart() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastRestartAt < MIN_RESTART_INTERVAL_MS) return
        restartJob?.cancel()
        restartJob = applicationScope.launch {
            delay(RESTART_DEBOUNCE_MS)
            if (!hasValidatedUnderlay()) return@launch
            lastRestartAt = SystemClock.elapsedRealtime()
            try {
                Application.getTunnelManager().restartRunningTunnelsAfterConnectivityRestored()
            } catch (e: Throwable) {
                Log.e(TAG, "Unable to restart VPN after connectivity restored", e)
            }
        }
    }

    private fun hasValidatedUnderlay(): Boolean = synchronized(lock) {
        validatedUnderlays.isNotEmpty()
    }

    private fun NetworkCapabilities.isValidatedUnderlay(): Boolean {
        return hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) &&
            !hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }

    companion object {
        private const val TAG = "VirtuVPN/Connectivity"
        private const val RESTART_DEBOUNCE_MS = 2_500L
        private const val MIN_RESTART_INTERVAL_MS = 30_000L
    }
}
