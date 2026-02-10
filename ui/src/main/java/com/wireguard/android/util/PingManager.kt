/*
 * Copyright © 2025-2026 Virtu VPN (virtuvpn.ch). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.util

import android.util.Log
import com.wireguard.android.model.ObservableTunnel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

object PingManager {
    private const val TAG = "WireGuard/PingManager"

    suspend fun pingAll(tunnels: List<ObservableTunnel>) {
        val tasks = mutableListOf<Pair<ObservableTunnel, String>>()
        
        for (tunnel in tunnels) {
            try {
                val config = tunnel.getConfigAsync()
                val ep = config.peers.firstOrNull()?.endpoint
                if (ep != null && ep.isPresent) {
                    tasks.add(Pair(tunnel, ep.get().host))
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to get config for ${tunnel.name}", e)
            }
        }

        withContext(Dispatchers.IO) {
            tasks.map { (tunnel, host) ->
                async {
                    val latency = measurePing(host)
                    withContext(Dispatchers.Main) {
                        tunnel.latencyMs = latency
                        Log.d(TAG, "${tunnel.name} -> $host = ${latency}ms")
                    }
                }
            }.awaitAll()
        }
    }

    private fun measurePing(host: String): Long {
        return try {
            val process = Runtime.getRuntime().exec("/system/bin/ping -c 1 -W 3 $host")
            val reader = process.inputStream.bufferedReader()
            val output = reader.readText()
            process.waitFor()
            
            val regex = "time=([\\d.]+)".toRegex()
            val match = regex.find(output)
            if (match != null) {
                match.groupValues[1].toDouble().toLong()
            } else {
                -1L
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed for $host", e)
            -1L
        }
    }

    fun findFastest(tunnels: List<ObservableTunnel>): ObservableTunnel? {
        return tunnels.filter { it.latencyMs > 0 }
            .minByOrNull { it.latencyMs }
    }
}
