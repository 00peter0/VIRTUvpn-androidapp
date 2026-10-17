package com.wireguard.android.vcs

/** Local managed profiles are retained, but their device identity remains the access boundary. */
data class ManagedTunnelAccountScope(
    val currentDeviceId: String?,
    val owners: Map<String, Set<String>>,
    val valid: Boolean = true
) {
    fun permits(name: String): Boolean {
        if (!valid) return false
        val devices = owners[name] ?: return true // An independently imported third-party VPN.
        return currentDeviceId != null && devices == setOf(currentDeviceId)
    }

    fun requireAccess(name: String) {
        check(permits(name)) {
            "This VPN belongs to another VCS account. Sign in to its owner account and sync."
        }
    }
}
