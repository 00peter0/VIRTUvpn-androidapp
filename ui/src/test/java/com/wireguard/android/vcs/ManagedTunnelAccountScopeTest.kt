package com.wireguard.android.vcs

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManagedTunnelAccountScopeTest {
    private val owners = mapOf(
        "main-vpn" to setOf("device-main"),
        "test-vpn" to setOf("device-test"),
        "legacy-collision" to setOf("device-main", "device-test")
    )

    @Test fun accountSwitchRejectsThePreviousAccountsProfiles() {
        val scope = ManagedTunnelAccountScope("device-test", owners)
        assertTrue(scope.permits("test-vpn"))
        assertFalse(scope.permits("main-vpn"))
        assertFalse(scope.permits("legacy-collision"))
    }

    @Test fun signedOutDeviceCannotUseRetainedManagedProfiles() {
        val scope = ManagedTunnelAccountScope(null, owners)
        owners.keys.forEach { assertFalse(scope.permits(it)) }
        assertTrue(scope.permits("third-party"))
    }

    @Test fun returningToOriginalDeviceRestoresItsOwnAccessWithoutDeletingProfiles() {
        assertTrue(ManagedTunnelAccountScope("device-main", owners).permits("main-vpn"))
        assertFalse(ManagedTunnelAccountScope("device-main", owners).permits("test-vpn"))
    }

    @Test fun identityDoesNotDependOnMutableEmailOrModelName() {
        val before = ManagedTunnelAccountScope("stable-device-id", mapOf("own" to setOf("stable-device-id")))
        assertTrue(before.permits("own"))
        assertFalse(before.copy(currentDeviceId = "another-device-with-the-same-model").permits("own"))
    }

    @Test(expected = IllegalStateException::class)
    fun activationCannotBypassOwnershipByRequestingARetainedName() {
        ManagedTunnelAccountScope("device-test", owners).requireAccess("main-vpn")
    }

    @Test fun unreadableOwnershipFailsClosed() {
        val scope = ManagedTunnelAccountScope("device-test", owners, valid = false)
        assertFalse(scope.permits("test-vpn"))
        assertFalse(scope.permits("main-vpn"))
    }

    @Test fun onlyUnmanagedProfilesAreIndependent() {
        val scope = ManagedTunnelAccountScope("device-test", owners)
        assertTrue(scope.isIndependent("third-party"))
        owners.keys.forEach { assertFalse(scope.isIndependent(it)) }
    }

    @Test fun signedOutLocalVpnDoesNotUnlockAnyManagedProfile() {
        val scope = ManagedTunnelAccountScope(null, owners)
        assertTrue(scope.isIndependent("third-party"))
        owners.keys.forEach {
            assertFalse(scope.permits(it))
            assertFalse(scope.isIndependent(it))
        }
    }

    @Test fun damagedOwnershipCannotPermitLocalDeletion() {
        val scope = ManagedTunnelAccountScope("device-test", owners, valid = false)
        assertFalse(scope.isIndependent("third-party"))
        assertFalse(scope.isIndependent("test-vpn"))
        assertFalse(scope.isIndependent(""))
    }

    @Test fun newlyManagedNameLosesIndependentPermission() {
        val scope = ManagedTunnelAccountScope("device-test", owners)
        assertTrue(scope.isIndependent("third-party"))
        assertFalse(scope.copy(owners = owners + ("third-party" to setOf("device-test"))).isIndependent("third-party"))
        assertFalse(scope.copy(owners = owners + ("third-party" to setOf("device-main"))).isIndependent("third-party"))
    }

    @Test fun managedSyncCannotClaimExistingIndependentConfig() {
        val scope = ManagedTunnelAccountScope("device-test", owners)
        assertFalse(scope.mayClaimManagedName("third-party", configExists = true))
        assertTrue(scope.mayClaimManagedName("unused-name", configExists = false))
        assertTrue(scope.mayClaimManagedName("test-vpn", configExists = true))
        assertFalse(scope.mayClaimManagedName("main-vpn", configExists = true))
        assertFalse(scope.mayClaimManagedName("main-vpn", configExists = false))
        assertFalse(scope.mayClaimManagedName("legacy-collision", configExists = true))
        assertFalse(scope.copy(valid = false).mayClaimManagedName("unused-name", configExists = false))
    }
}
