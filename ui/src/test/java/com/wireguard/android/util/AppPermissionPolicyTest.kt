package com.wireguard.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPermissionPolicyTest {
    @Test fun everyFeatureShowsOnlyItsOwnAccessAcrossSupportedAndroidVersions() {
        for (sdk in 24..37) for (capability in AppPermissionPolicy.capabilities(sdk, null)) {
            assertEquals(listOf(capability), AppPermissionPolicy.capabilities(sdk, capability))
        }
    }

    @Test fun invalidAndUnsupportedRequestsCannotOpenTheFullOverview() {
        for (sdk in 24..37) {
            assertTrue(AppPermissionPolicy.capabilities(sdk, "unknown").isEmpty())
            assertTrue(AppPermissionPolicy.capabilities(sdk, "").isEmpty())
        }
        assertTrue(AppPermissionPolicy.capabilities(25, AppPermissionPolicy.INSTALL).isEmpty())
        assertTrue(AppPermissionPolicy.capabilities(33, AppPermissionPolicy.OVERLAY).isEmpty())
    }

    @Test fun qrScanCannotRequestNotificationsStorageOrSpecialAccess() {
        for (sdk in 24..37) {
            assertEquals("android.permission.CAMERA", AppPermissionPolicy.runtimePermission(AppPermissionPolicy.CAMERA, sdk))
            assertNull(AppPermissionPolicy.runtimePermission(AppPermissionPolicy.CAMERA, sdk, cameraAvailable = false))
        }
    }

    @Test fun storageAndNotificationsRespectAndroidVersionBoundaries() {
        assertEquals("android.permission.WRITE_EXTERNAL_STORAGE", AppPermissionPolicy.runtimePermission(AppPermissionPolicy.FILES, 28))
        assertNull(AppPermissionPolicy.runtimePermission(AppPermissionPolicy.FILES, 29))
        assertNull(AppPermissionPolicy.runtimePermission(AppPermissionPolicy.NOTIFICATIONS, 32))
        assertEquals("android.permission.POST_NOTIFICATIONS", AppPermissionPolicy.runtimePermission(AppPermissionPolicy.NOTIFICATIONS, 33))
    }

    @Test fun specialSettingsAndUnknownRequestsNeverLaunchARuntimeGrant() {
        for (sdk in 24..37) for (capability in listOf(AppPermissionPolicy.VPN, AppPermissionPolicy.KILL_SWITCH,
            AppPermissionPolicy.INSTALL, AppPermissionPolicy.USAGE, AppPermissionPolicy.OVERLAY, AppPermissionPolicy.BATTERY, "unknown")) {
            assertNull(AppPermissionPolicy.runtimePermission(capability, sdk))
        }
    }
}
