/*
 * Copyright © 2025-2026 Virtu VPN (vcs.virtucomputing.com). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.fragment

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.SecureBrowserActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.vcs.VcsManagedClient
import com.wireguard.config.Config
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelDetailFragment : BaseFragment(), MenuProvider {
    private var binding: TunnelDetailFragmentBinding? = null

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

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        if (newTunnel == null) {
            binding.config = null
            updateWebTerminalInfo(null)
        } else {
            updateWebTerminalInfo(newTunnel.name)
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
            addresses.joinToString("\n") { it.toString() }
        } else getString(R.string.stat_no_data)

        val allowedIps = config.peers
            .flatMap { peer -> peer.allowedIps }
            .map { it.toString() }
            .distinct()
            .sortedWith(compareBy<String> { !it.contains(":") }.thenBy { it })
            .joinToString("\n")
        binding.serverIpLabel.text = getString(R.string.stat_wg_routes)
        binding.serverIpValue.text = if (allowedIps.isNotEmpty()) {
            allowedIps
        } else {
            val endpoint = config.peers.firstOrNull()?.endpoint
            if (endpoint != null && endpoint.isPresent) endpoint.get().host else getString(R.string.stat_no_data)
        }
    }

    private fun updateWebTerminalInfo(tunnelName: String?) {
        val binding = binding ?: return
        val terminal = VcsManagedClient.webTerminalForTunnel(requireContext(), tunnelName)
        if (terminal == null) {
            binding.webTerminalCard.visibility = View.GONE
            binding.webTerminalOpen.setOnClickListener(null)
            binding.webTerminalCopy.setOnClickListener(null)
            return
        }

        binding.webTerminalCard.visibility = View.VISIBLE
        binding.webTerminalTitle.text = terminal.serverName?.let { "${getString(R.string.web_terminal)} · $it" }
            ?: getString(R.string.web_terminal)
        binding.webTerminalUrl.text = terminal.url
        binding.webTerminalCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.web_terminal), terminal.url))
            Toast.makeText(requireContext(), R.string.terminal_url_copied, Toast.LENGTH_SHORT).show()
        }
        binding.webTerminalOpen.setOnClickListener {
            lifecycleScope.launch {
                val requiredTunnelName = terminal.requiredTunnelName
                if (!requiredTunnelName.isNullOrBlank()) {
                    val requiredTunnel = Application.getTunnelManager().getTunnels()[requiredTunnelName]
                    if (requiredTunnel == null) {
                        Toast.makeText(requireContext(), getString(R.string.web_terminal_tunnel_missing, requiredTunnelName), Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    if (requiredTunnel.state != Tunnel.State.UP) {
                        Toast.makeText(requireContext(), getString(R.string.web_terminal_starting_tunnel, requiredTunnelName), Toast.LENGTH_SHORT).show()
                        try {
                            requiredTunnel.setStateAsync(Tunnel.State.UP)
                            delay(1200)
                        } catch (e: Throwable) {
                            Toast.makeText(requireContext(), getString(R.string.web_terminal_tunnel_start_failed, e.message ?: getString(R.string.unknown_error)), Toast.LENGTH_LONG).show()
                            return@launch
                        }
                    }
                }
                openWebTerminalUrl(terminal.url)
            }
        }
    }

    private fun openWebTerminalUrl(url: String) {
        val intent = Intent(requireContext(), SecureBrowserActivity::class.java)
            .putExtra(SecureBrowserActivity.EXTRA_INITIAL_URL, url)
        startActivity(intent)
    }
}
