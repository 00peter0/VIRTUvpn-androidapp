package com.wireguard.android.vcs

import android.security.NetworkSecurityPolicy
import com.wireguard.android.util.SecureBrowserUrlPolicy

/** Read-only platform checks; does not touch login, tunnels, updater sessions or app data. */
internal object TerminalNetworkPolicyChecks {
    fun verify(): List<String> {
        val policy = NetworkSecurityPolicy.getInstance()
        val hosts = (2..253).map { "10.75.$it.1" } + (77..254).map { "10.$it.1.1" }
        for (host in hosts) {
            check(policy.isCleartextTrafficPermitted(host)) { "Allocated terminal blocked: $host" }
            check(!SecureBrowserUrlPolicy.isAllowed("http", host, isTopLevel = true)) {
                "Secured Browser must still reject HTTP: $host"
            }
        }
        for (host in listOf(
            "example.com", "vcs.virtucomputing.com", "sub.vcs.virtucomputing.com", "8.8.8.8",
            "127.0.0.1", "192.168.1.1", "10.75.0.1", "10.75.1.1", "10.75.254.1",
            "10.75.2.2", "10.76.2.1", "10.77.2.2", "10.254.254.1", "10.255.1.1",
            "10.75.2.1.example.com", "fd00::1"
        )) check(!policy.isCleartextTrafficPermitted(host)) { "Unallocated host permitted: $host" }
        check(!policy.isCleartextTrafficPermitted) { "Global cleartext default must remain denied" }
        return listOf(
            "Android permits all 430 exact allocated Single server and S2S terminal gateways",
            "Android rejects public HTTP, VCS API HTTP, arbitrary LAN and allocation boundaries",
            "Secured Browser rejects HTTP/cleartext despite terminal-specific exceptions",
            "No login, WireGuard, managed identity or updater state was modified"
        )
    }
}
