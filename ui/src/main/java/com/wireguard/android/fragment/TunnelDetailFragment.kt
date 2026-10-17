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
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.HomeActivity
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.activity.SecureBrowserActivity
import com.wireguard.android.vcs.ManagedWebTerminalLauncher
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.TunnelDetailFragmentBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.vcs.VcsManagedClient
import com.wireguard.android.util.VcsDialogs
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class TunnelDetailFragment : BaseFragment(), MenuProvider {
    private var binding: TunnelDetailFragmentBinding? = null
    private var publicIdentityJob: Job? = null

    override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
        if (menuItem.itemId != R.id.menu_action_delete_local) return false
        val tunnel = selectedTunnel ?: return true
        if (!VcsManagedClient.tunnelAccountScope(requireContext()).isIndependent(tunnel.name)) return true
        VcsDialogs.show(
            requireContext(),
            title = getString(R.string.delete),
            message = getString(R.string.vcs_other_tunnel_delete_confirm, tunnel.name),
            negative = VcsDialogs.action(requireContext(), android.R.string.cancel),
            positive = VcsDialogs.action(requireContext(), R.string.delete, primary = true) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        tunnel.deleteAsync()
                        selectedTunnel = null
                    } catch (error: Throwable) {
                        Toast.makeText(context, error.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
        return true
    }
    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
        menuInflater.inflate(R.menu.tunnel_detail, menu)
    }

    override fun onPrepareMenu(menu: Menu) {
        menu.findItem(R.id.menu_action_delete_local)?.isVisible =
            selectedTunnel?.let { VcsManagedClient.tunnelAccountScope(requireContext()).isIndependent(it.name) } == true
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
        binding?.homeButton?.setOnClickListener {
            startActivity(Intent(requireContext(), HomeActivity::class.java))
        }
        binding?.secureBrowserButton?.setOnClickListener {
            startActivity(Intent(requireContext(), SecureBrowserActivity::class.java))
        }
        binding?.publicIdentityCard?.setOnClickListener { refreshPublicIdentity() }
        binding?.dnsCard?.setOnClickListener {
            if (VcsManagedClient.supportsInternetDiagnostics(requireContext(), selectedTunnel?.name)) {
                startActivity(Intent(requireContext(), SecureBrowserActivity::class.java)
                    .putExtra(SecureBrowserActivity.EXTRA_INITIAL_URL, "https://dnscheck.tools/"))
            }
        }
    }

    override fun onDestroyView() {
        publicIdentityJob?.cancel()
        publicIdentityJob = null
        binding = null
        super.onDestroyView()
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        val binding = binding ?: return
        binding.tunnel = newTunnel
        requireActivity().invalidateOptionsMenu()
        publicIdentityJob?.cancel()
        val internetDiagnostics = VcsManagedClient.supportsInternetDiagnostics(requireContext(), newTunnel?.name)
        binding.publicIdentityCard.visibility = if (internetDiagnostics) View.VISIBLE else View.GONE
        binding.dnsCard.visibility = if (internetDiagnostics) View.VISIBLE else View.GONE
        val privateAccess = !internetDiagnostics && newTunnel != null && listOf(
            MainActivity.TUNNEL_SECTION_SINGLE_SERVER,
            MainActivity.TUNNEL_SECTION_MANAGED_ACCESS_S2S_CLUSTER,
            MainActivity.TUNNEL_SECTION_AGENT_GATEWAY
        ).any { newTunnel.name in VcsManagedClient.localTunnelNamesForSection(requireContext(), it) }
        binding.connectionScopeInfo.visibility = if (privateAccess) View.VISIBLE else View.GONE
        if (newTunnel == null) {
            binding.config = null
            updateWebTerminalInfo(null)
        } else {
            updateWebTerminalInfo(newTunnel.name)
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    val config = newTunnel.getConfigAsync()
                    if (this@TunnelDetailFragment.binding !== binding || binding.tunnel !== newTunnel) return@launch
                    binding.config = config
                    updateIpInfo(config)
                } catch (_: Throwable) {
                    if (this@TunnelDetailFragment.binding === binding && binding.tunnel === newTunnel) binding.config = null
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
        // Public IP of the VPN mesh: the endpoint host the device connects to,
        // not the internal WireGuard route addresses.
        binding.serverIpLabel.text = getString(R.string.stat_public_ip)
        val endpoint = config.peers.firstOrNull()?.endpoint
        binding.serverIpValue.text = if (endpoint != null && endpoint.isPresent) {
            endpoint.get().host
        } else getString(R.string.stat_no_data)

        // Configuration alone does not verify DNS egress or browser-side IP privacy.
        val dnsServers = config.`interface`.dnsServers
        if (dnsServers.isNotEmpty()) {
            binding.dnsValue.text = dnsServers.joinToString(", ") { it.hostAddress ?: it.toString() }
            binding.dnsStatus.text = getString(R.string.dns_configured)
            binding.dnsStatus.setTextColor("#AFC0CC".toColorInt())
        } else {
            binding.dnsValue.text = getString(R.string.dns_not_set)
            binding.dnsStatus.text = getString(R.string.dns_not_configured)
            binding.dnsStatus.setTextColor("#FBBF24".toColorInt())
        }

        refreshPublicIdentity()
    }

    // "Internet sees you as" — the apparent public identity as seen from the
    // public internet through the current tunnel (egress IP + geo). Reflects the
    // VPN exit for full-tunnel routes, or the device's own connection otherwise.
    private fun refreshPublicIdentity() {
        val tunnelName = selectedTunnel?.name ?: return
        if (!VcsManagedClient.supportsInternetDiagnostics(requireContext(), tunnelName)) return
        val binding = binding ?: return
        publicIdentityJob?.cancel()
        binding.publicIdentityIp.text = getString(R.string.stat_checking)
        binding.publicIdentityIpv6.text = ""
        binding.publicIdentityGeo.text = ""
        publicIdentityJob = viewLifecycleOwner.lifecycleScope.launch {
            // Force IPv4 and IPv6 egress separately (plain-text echo endpoints).
            val ip4 = withContext(Dispatchers.IO) { fetchText("https://ipv4.icanhazip.com") }
            val ip6 = withContext(Dispatchers.IO) { fetchText("https://ipv6.icanhazip.com") }
            val b = this@TunnelDetailFragment.binding?.takeIf { it.tunnel?.name == tunnelName } ?: return@launch
            if (ip4.isNullOrBlank() && ip6.isNullOrBlank()) {
                b.publicIdentityIp.text = getString(R.string.stat_no_data)
                b.publicIdentityIpv6.text = ""
                b.publicIdentityGeo.text = ""
                return@launch
            }
            b.publicIdentityIp.text = ip4?.takeIf { it.isNotBlank() } ?: getString(R.string.stat_no_data)
            b.publicIdentityIpv6.text = if (!ip6.isNullOrBlank()) "IPv6  $ip6" else getString(R.string.stat_ipv6_not_confirmed)

            // Geo + ISP for the primary egress IP, as the internet sees you.
            val geoIp = ip4?.takeIf { it.isNotBlank() } ?: ip6
            if (!geoIp.isNullOrBlank()) {
                val geo = withContext(Dispatchers.IO) { fetchText("https://ipwho.is/$geoIp") }
                val b2 = this@TunnelDetailFragment.binding?.takeIf { it.tunnel?.name == tunnelName } ?: return@launch
                if (geo != null) {
                    try {
                        val json = JSONObject(geo)
                        if (json.optBoolean("success", false)) {
                            val country = json.optString("country")
                            val iso = json.optString("country_code")
                            val city = json.optString("city")
                            val isp = json.optJSONObject("connection")?.optString("isp") ?: ""
                            b2.publicIdentityGeo.text = listOf("${flagEmoji(iso)} $country".trim(), city, isp)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                        }
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    private fun fetchText(url: String): String? = try {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 6000
            readTimeout = 6000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "VirtuVPN")
        }
        conn.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (_: Throwable) {
        null
    }

    private fun flagEmoji(iso2: String): String {
        if (iso2.length != 2) return ""
        val cc = iso2.uppercase()
        if (!cc.all { it in 'A'..'Z' }) return ""
        val base = 0x1F1E6
        return String(Character.toChars(base + (cc[0] - 'A'))) + String(Character.toChars(base + (cc[1] - 'A')))
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
        binding.webTerminalHealthDot.setBackgroundResource(R.drawable.status_dot_disconnected)
        binding.webTerminalTitle.text = terminal.serverName?.let { "${getString(R.string.web_terminal)} · $it" }
            ?: getString(R.string.web_terminal)
        binding.webTerminalUrl.text = terminal.url
        lifecycleScope.launch {
            val reachable = isWebTerminalReachable(terminal.url)
            this@TunnelDetailFragment.binding?.webTerminalHealthDot?.setBackgroundResource(
                if (reachable) R.drawable.status_dot_connected else R.drawable.status_dot_disconnected
            )
        }
        binding.webTerminalCopy.setOnClickListener {
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.web_terminal), terminal.url))
            Toast.makeText(requireContext(), R.string.terminal_url_copied, Toast.LENGTH_SHORT).show()
        }
        binding.webTerminalOpen.setOnClickListener {
            lifecycleScope.launch {
                val activeBinding = binding ?: return@launch
                activeBinding.webTerminalOpen.isEnabled = false
                activeBinding.webTerminalOpen.text = getString(R.string.web_terminal_checking)
                try {
                    ManagedWebTerminalLauncher.open(requireContext(), terminal)
                } finally {
                    binding?.webTerminalOpen?.isEnabled = true
                    binding?.webTerminalOpen?.text = getString(R.string.open_terminal)
                }
            }
        }
    }

    private suspend fun waitForWebTerminal(url: String): Boolean {
        repeat(10) {
            if (isWebTerminalReachable(url)) return true
            delay(700)
        }
        return false
    }

    private suspend fun isWebTerminalReachable(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val healthUrl = if (url.endsWith("/")) "${url}healthz" else "$url/healthz"
            val connection = URL(healthUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 1200
            connection.readTimeout = 1200
            connection.requestMethod = "GET"
            try {
                connection.responseCode in 200..299
            } finally {
                connection.disconnect()
            }
        }.getOrDefault(false)
    }


}
