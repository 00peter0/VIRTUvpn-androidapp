package com.wireguard.android.vcs

import android.content.Context
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.runBlocking

/** Negative tests against retained real profiles; never activates a foreign VPN or changes identity. */
internal object ManagedTunnelAccountChecks {
    fun verify(context: Context): List<String> = runBlocking {
        val manager = Application.getTunnelManager()
        val tunnels = manager.getTunnels().toList()
        val scope = VcsManagedClient.tunnelAccountScope(context)
        check(scope.valid && scope.currentDeviceId != null) { "A signed-in managed device is required" }
        val foreign = tunnels.filter { !scope.permits(it.name) }
        val own = tunnels.filter { it.name in scope.owners && scope.permits(it.name) }
        check(foreign.isNotEmpty() && own.isNotEmpty()) { "This acceptance check requires retained profiles from two accounts" }
        val backend = Application.getBackend()
        val runningBefore = backend.runningTunnelNames.toSet()
        check(foreign.none { it.name in runningBefore }) { "An out-of-account VPN is still running" }
        suspend fun blocked(action: suspend () -> Any?) {
            val error = runCatching { action() }.exceptionOrNull()
            check(error is IllegalStateException && error.message?.contains("another VCS account") == true) {
                "Foreign profile did not fail at the account authorization boundary: ${error?.javaClass?.simpleName}"
            }
        }
        for (tunnel in foreign) {
            check(!VcsManagedClient.supportsInternetDiagnostics(context, tunnel.name)) {
                "Foreign profile offered account-scoped internet diagnostics"
            }
            blocked { manager.setTunnelState(tunnel, Tunnel.State.UP) }
            blocked { manager.setTunnelState(tunnel, Tunnel.State.TOGGLE) }
            blocked { tunnel.setStateAsync(Tunnel.State.UP) }
            blocked { tunnel.getConfigAsync() }
            blocked { manager.getTunnelConfig(tunnel) }
            check(tunnel.config == null) { "Cached foreign private configuration was exposed" }
            check(manager.hasExistingConfig(tunnel.name)) { "A retained profile was deleted" }
        }
        check(backend.runningTunnelNames.toSet() == runningBefore) { "Negative tests changed the active VPN" }
        listOf(
            "Retained foreign profiles: ${foreign.size}; current managed profiles: ${own.size}",
            "Foreign UP, TOGGLE and observable activation rejected before backend activation",
            "Foreign direct/cached configuration reads and export inputs rejected",
            "Foreign profiles cannot offer internet diagnostics under the current account",
            "All retained configuration files preserved; active VPN unchanged by negative tests"
        )
    }
}
