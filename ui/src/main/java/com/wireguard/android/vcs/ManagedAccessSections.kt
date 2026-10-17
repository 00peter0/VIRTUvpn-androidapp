package com.wireguard.android.vcs

/** Presentation only: never changes a tunnel's name, configuration or delivery. */
object ManagedAccessSections {
    const val SINGLE_SERVER = "single_server"

    fun terminalBundle(mode: String?, explicitBundle: String?, fallbackBundle: String?): String? =
        explicitBundle ?: if (mode == SINGLE_SERVER) null else fallbackBundle

    fun mode(explicit: String?, profileType: String?, capability: String?, bundled: Boolean): String? {
        if (explicit == SINGLE_SERVER || (profileType == "local_pc_cli" && capability == SINGLE_SERVER)) {
            return if (bundled || explicit == "vpn_cluster") "vpn_cluster" else SINGLE_SERVER
        }
        if (explicit != null) return explicit
        if (bundled) return "vpn_cluster"
        return when (profileType) {
            "server_to_server_cli" -> "s2s_cluster"
            "local_pc_cli" -> "standalone_pc"
            else -> null
        }
    }
}
