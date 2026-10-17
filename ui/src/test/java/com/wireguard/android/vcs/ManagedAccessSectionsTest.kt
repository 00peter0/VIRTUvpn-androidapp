package com.wireguard.android.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManagedAccessSectionsTest {
    @Test fun legacySingleServerIsSeparatedWithoutChangingItsTunnel() {
        assertEquals("single_server", ManagedAccessSections.mode("standalone_pc", "local_pc_cli", "single_server", false))
        assertEquals("single_server", ManagedAccessSections.mode("single_server", "local_pc_cli", null, false))
    }
    @Test fun aSingleServerClusterStaysInTheClusterSection() {
        assertEquals("vpn_cluster", ManagedAccessSections.mode("vpn_cluster", "local_pc_cli", "single_server", true))
        assertEquals("vpn_cluster", ManagedAccessSections.mode("standalone_pc", "local_pc_cli", "single_server", true))
    }
    @Test fun legacyLocalPcAndGpuAreNotSingleServers() {
        for (capability in listOf(null, "studio_gpu", "single_server_typo")) {
            assertEquals("standalone_pc", ManagedAccessSections.mode(null, "local_pc_cli", capability, false))
        }
    }
    @Test fun s2sAndExplicitModesRemainCompatible() {
        assertEquals("s2s_cluster", ManagedAccessSections.mode(null, "server_to_server_cli", null, false))
        assertEquals("standalone_vpn", ManagedAccessSections.mode("standalone_vpn", null, null, false))
        assertEquals("vpn_cluster", ManagedAccessSections.mode(null, "local_pc_cli", null, true))
        assertNull(ManagedAccessSections.mode(null, null, null, false))
    }
    @Test fun anUnrelatedVpnClusterCannotBecomeASingleServerTerminalPath() {
        assertNull(ManagedAccessSections.terminalBundle("single_server", null, "unrelated-cluster"))
        assertEquals("own-cluster", ManagedAccessSections.terminalBundle("vpn_cluster", "own-cluster", "other"))
        assertEquals("legacy-cluster", ManagedAccessSections.terminalBundle("s2s_cluster", null, "legacy-cluster"))
    }
}
