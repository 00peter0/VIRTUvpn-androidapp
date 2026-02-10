/*
 * Copyright © 2025-2026 Virtu VPN (virtuvpn.ch). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.fragment

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.PrivacyChecker
import com.wireguard.android.util.QuantityFormatter
import com.wireguard.config.Config
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelDetailFragment : BaseFragment(), MenuProvider {
    private var binding: TunnelDetailFragmentBinding? = null
    private var lastState = Tunnel.State.TOGGLE
    private var timerActive = true
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastStatsTime = 0L
    private var connectedSince = 0L

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean = false
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.tunnel_detail, menu)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelDetailFragmentBinding.inflate(inflater, container, false)
        binding?.executePendingBindings()
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        timerActive = true
        lifecycleScope.launch {
            while (timerActive) {
                updateStats()
                updatePrivacyShield()
                delay(1000)
            }
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        if (newTunnel == null) {
            binding.config = null
        } else {
            lifecycleScope.launch {
                try {
                    val config = newTunnel.getConfigAsync()
                    binding.config = config
                    updateIpInfo(config)
                } catch (_: Throwable) {
                    binding.config = null
                }
            }
        }
        lastState = Tunnel.State.TOGGLE
        lastRxBytes = 0L
        lastTxBytes = 0L
        lastStatsTime = 0L
        connectedSince = 0L
        binding.sparklineView.clear()
        lifecycleScope.launch { updateStats() }
    }

    override fun onStop() {
        timerActive = false
        super.onStop()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        binding ?: return
        binding!!.fragment = this
        onSelectedTunnelChanged(null, selectedTunnel)
        super.onViewStateRestored(savedInstanceState)
    }

    fun toggleTunnel() {
        val tunnel = binding?.tunnel ?: return
        lifecycleScope.launch {
            try { tunnel.setStateAsync(Tunnel.State.TOGGLE) } catch (_: Throwable) {}
        }
    }

    private fun updateIpInfo(config: Config?) {
        val binding = binding ?: return
        if (config == null) return
        val addresses = config.`interface`.addresses
        binding.yourIpValue.text = if (addresses.isNotEmpty()) {
            addresses.joinToString("\n") { it.address.hostAddress ?: "?" }
        } else getString(R.string.stat_no_data)

        val endpoint = config.peers.firstOrNull()?.endpoint
        binding.serverIpValue.text = if (endpoint != null && endpoint.isPresent) {
            endpoint.get().host
        } else getString(R.string.stat_no_data)
    }

    private fun formatDuration(millis: Long): String {
        val seconds = millis / 1000
        val h = seconds / 3600; val m = (seconds % 3600) / 60; val s = seconds % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%02d:%02d", m, s)
    }

    // ── Privacy Shield ──

    private fun updatePrivacyShield() {
        val binding = binding ?: return
        val ctx = context ?: return
        val tunnel = binding.tunnel
        val config = binding.config

        val result = PrivacyChecker.check(ctx, tunnel?.state, config)

        binding.privacyScoreText.text = "${result.score}%"
        binding.privacyProgress.progress = result.score

        val green = Color.parseColor("#00BFA5")
        val red = Color.parseColor("#FF5252")
        val gray = Color.parseColor("#667788")

        binding.privacyScoreText.setTextColor(if (result.score >= 80) green else if (result.score >= 40) Color.parseColor("#FFB300") else red)

        binding.checkVpn.text = if (result.vpnActive) "✅  VPN Active" else "❌  VPN Inactive"
        binding.checkVpn.setTextColor(if (result.vpnActive) green else red)

        binding.checkDns.text = if (result.dnsProtected) "✅  DNS Protected" else "❌  DNS Exposed"
        binding.checkDns.setTextColor(if (result.dnsProtected) green else red)

        binding.checkIp.text = if (result.ipHidden) "✅  IP Hidden" else "❌  IP Visible"
        binding.checkIp.setTextColor(if (result.ipHidden) green else red)

        binding.checkEncrypted.text = if (result.encrypted) "✅  Traffic Encrypted" else "❌  Unencrypted"
        binding.checkEncrypted.setTextColor(if (result.encrypted) green else red)

        val wifiText = when {
            result.wifiName == null -> "No network"
            result.wifiTrusted -> "📶  ${result.wifiName} (trusted)"
            else -> "⚠️  ${result.wifiName} (untrusted)"
        }
        binding.wifiInfoText.text = wifiText
        binding.wifiInfoText.setTextColor(if (result.wifiTrusted || result.wifiName == null) gray else Color.parseColor("#FFB300"))
    }

    // ── Latency & Quality ──

    private fun updateLatencyAndQuality() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return

        if (tunnel.state == Tunnel.State.UP && tunnel.latencyMs > 0) {
            binding.latencyValue.text = "${tunnel.latencyMs} ms"
            val quality = when {
                tunnel.latencyMs < 50 -> "Excellent"
                tunnel.latencyMs < 100 -> "Good"
                tunnel.latencyMs < 200 -> "Fair"
                else -> "Poor"
            }
            binding.qualityValue.text = quality

            val color = when {
                tunnel.latencyMs < 50 -> Color.parseColor("#00BFA5")
                tunnel.latencyMs < 100 -> Color.parseColor("#FFB300")
                else -> Color.parseColor("#FF5252")
            }
            binding.latencyValue.setTextColor(color)
            binding.qualityValue.setTextColor(color)
        } else {
            binding.latencyValue.text = getString(R.string.stat_no_data)
            binding.qualityValue.text = getString(R.string.stat_no_data)
            binding.latencyValue.setTextColor(Color.parseColor("#667788"))
            binding.qualityValue.setTextColor(Color.parseColor("#667788"))
        }
    }

    // ── Stats + Sparkline ──

    private suspend fun updateStats() {
        val binding = binding ?: return
        val tunnel = binding.tunnel ?: return
        if (!isResumed) return
        val state = tunnel.state

        if (state == Tunnel.State.UP) {
            if (connectedSince == 0L) connectedSince = System.currentTimeMillis()
            binding.durationValue.text = formatDuration(System.currentTimeMillis() - connectedSince)
            binding.durationValue.visibility = View.VISIBLE
        } else {
            connectedSince = 0L
            binding.durationValue.visibility = View.GONE
            binding.downloadSpeedValue.text = getString(R.string.stat_no_data)
            binding.uploadSpeedValue.text = getString(R.string.stat_no_data)
            binding.totalDownValue.text = getString(R.string.stat_no_data)
            binding.totalUpValue.text = getString(R.string.stat_no_data)
            lastRxBytes = 0L; lastTxBytes = 0L; lastStatsTime = 0L; lastState = state
            binding.sparklineView.addDataPoint(0, 0)
            updateLatencyAndQuality()
            return
        }
        lastState = state

        try {
            val statistics = tunnel.getStatisticsAsync()
            val config = binding.config
            var totalRx = 0L; var totalTx = 0L
            if (config != null) {
                for (peer in config.peers) {
                    val peerStats = statistics.peer(peer.publicKey) ?: continue
                    totalRx += peerStats.rxBytes; totalTx += peerStats.txBytes
                }
            }
            binding.totalDownValue.text = QuantityFormatter.formatBytes(totalRx)
            binding.totalUpValue.text = QuantityFormatter.formatBytes(totalTx)

            val now = System.currentTimeMillis()
            if (lastStatsTime > 0) {
                val timeDelta = (now - lastStatsTime) / 1000.0
                if (timeDelta > 0) {
                    val rxSpeed = ((totalRx - lastRxBytes) / timeDelta).toLong().coerceAtLeast(0)
                    val txSpeed = ((totalTx - lastTxBytes) / timeDelta).toLong().coerceAtLeast(0)
                    binding.downloadSpeedValue.text = getString(R.string.stat_speed_format, QuantityFormatter.formatBytes(rxSpeed))
                    binding.uploadSpeedValue.text = getString(R.string.stat_speed_format, QuantityFormatter.formatBytes(txSpeed))
                    binding.sparklineView.addDataPoint(rxSpeed, txSpeed)
                }
            }
            lastRxBytes = totalRx; lastTxBytes = totalTx; lastStatsTime = now
        } catch (_: Throwable) {
            binding.downloadSpeedValue.text = getString(R.string.stat_no_data)
            binding.uploadSpeedValue.text = getString(R.string.stat_no_data)
            binding.totalDownValue.text = getString(R.string.stat_no_data)
            binding.totalUpValue.text = getString(R.string.stat_no_data)
        }
        updateLatencyAndQuality()
    }
}
