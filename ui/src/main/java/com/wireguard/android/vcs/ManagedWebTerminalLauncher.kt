package com.wireguard.android.vcs

import android.content.Context
import android.content.Intent
import android.widget.Toast
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.WebTerminalBrowserActivity
import com.wireguard.android.backend.Tunnel

/** The same VPN activation and private terminal activity for list and detail. */
object ManagedWebTerminalLauncher {
    suspend fun open(context: Context, terminal: VcsManagedClient.WebTerminalLink) {
        if (!VcsManagedClient.isCurrentWebTerminalUrl(context, terminal.url) ||
            terminal.requiredTunnelName?.let { !VcsManagedClient.canUseLocalTunnel(context, it) } == true) {
            Toast.makeText(context, R.string.web_terminal_current_account_required, Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(context, R.string.web_terminal_opening, Toast.LENGTH_SHORT).show()
        val requiredTunnelName = terminal.requiredTunnelName
        if (!requiredTunnelName.isNullOrBlank()) {
            val requiredTunnel = Application.getTunnelManager().getTunnels()[requiredTunnelName]
            if (requiredTunnel == null) {
                Toast.makeText(context, context.getString(R.string.web_terminal_tunnel_missing, requiredTunnelName), Toast.LENGTH_LONG).show()
                return
            }
            if (requiredTunnel.state != Tunnel.State.UP) {
                Toast.makeText(context, context.getString(R.string.web_terminal_starting_tunnel, requiredTunnelName), Toast.LENGTH_SHORT).show()
                try {
                    requiredTunnel.setStateAsync(Tunnel.State.UP)
                } catch (e: Throwable) {
                    Toast.makeText(context, context.getString(R.string.web_terminal_tunnel_start_failed, e.message ?: context.getString(R.string.unknown_error)), Toast.LENGTH_LONG).show()
                    return
                }
            }
        }
        context.startActivity(Intent(context, WebTerminalBrowserActivity::class.java)
            .putExtra(WebTerminalBrowserActivity.EXTRA_INITIAL_URL, terminal.url))
    }
}
