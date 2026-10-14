package com.wireguard.android.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegacyInstallationHandoffTest {
    private val installation = "00000000-0000-4000-8000-000000000001"
    private val secret = "a".repeat(64)

    @Test fun matchesServerFixedVectorWithoutKeepingAnyRefreshToken() {
        assertEquals("8180c17b2c40572508c0e938281a194be44be5ddb739d69e829126060d4ed733",
            LegacyInstallationHandoff.proof("original-device", installation, secret, "old-refresh"))
    }

    @Test fun continuityIsBoundToExactlyOneDeviceInstallationAndSecret() {
        val original = LegacyInstallationHandoff.proof("original-device", installation, secret, "old-refresh")
        assertNotEquals(original, LegacyInstallationHandoff.proof("other-device", installation, secret, "old-refresh"))
        assertNotEquals(original, LegacyInstallationHandoff.proof("original-device", installation.replace("0001", "0002"), secret, "old-refresh"))
        assertNotEquals(original, LegacyInstallationHandoff.proof("original-device", installation, "b".repeat(64), "old-refresh"))
        assertNotEquals(original, LegacyInstallationHandoff.proof("original-device", installation, secret, "rotated-refresh"))
        assertNotEquals("old-refresh", original)
    }

    @Test fun malformedIdentifiersCannotChangeMessageBoundaries() {
        assertThrows(IllegalArgumentException::class.java) { LegacyInstallationHandoff.proof("original\ndevice", installation, secret, "old-refresh") }
        assertThrows(IllegalArgumentException::class.java) { LegacyInstallationHandoff.proof("original-device", "bad", secret, "old-refresh") }
        assertThrows(IllegalArgumentException::class.java) { LegacyInstallationHandoff.proof("original-device", installation, "short", "old-refresh") }
        assertThrows(IllegalArgumentException::class.java) { LegacyInstallationHandoff.proof("original-device", installation, secret, " ") }
    }
}
