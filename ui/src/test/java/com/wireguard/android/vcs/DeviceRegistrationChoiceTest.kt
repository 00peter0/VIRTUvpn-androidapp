package com.wireguard.android.vcs

import org.junit.Assert.*
import org.junit.Test

class DeviceRegistrationChoiceTest {
    @Test fun normalSignInContainsNoImpliedRegistrationOrReconnectConsent() {
        val normal = DeviceRegistrationChoice()
        assertNull(normal.reconnectDeviceId)
        assertFalse(normal.registerNewDevice)
    }
    @Test fun reconnectAndAdditionalDeviceAreDistinctExplicitChoices() {
        assertEquals("original", DeviceRegistrationChoice(reconnectDeviceId = "original").reconnectDeviceId)
        assertTrue(DeviceRegistrationChoice(registerNewDevice = true).registerNewDevice)
        assertThrows(IllegalArgumentException::class.java) { DeviceRegistrationChoice("original", true) }
    }
    @Test fun invalidIdsCannotEnterTheConfirmationFlow() {
        for (id in listOf("", "../foreign", "a\nb", "x".repeat(129)))
            assertThrows(IllegalArgumentException::class.java) { DeviceRegistrationChoice(id) }
    }
    @Test fun optionsRejectMalformedServerData() {
        assertThrows(IllegalArgumentException::class.java) { DeviceReconnectOption("../foreign", "Phone", 2) }
        assertThrows(IllegalArgumentException::class.java) { DeviceReconnectOption("original", "", 2) }
        assertThrows(IllegalArgumentException::class.java) { DeviceReconnectOption("original", "Phone", -1) }
        assertEquals(4, DeviceReconnectOption("original", "Phone", 4).assignedTunnels)
    }
}
