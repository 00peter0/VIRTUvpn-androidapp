package com.wireguard.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppPermissionPolicyTest {
    @Test fun legacyStorageIsRequestedOnlyOnSupportedOlderAndroid() {
        assertEquals(listOf("android.permission.CAMERA", "android.permission.WRITE_EXTERNAL_STORAGE"), AppPermissionPolicy.runtimePermissions(24))
        assertFalse(AppPermissionPolicy.runtimePermissions(29).contains("android.permission.WRITE_EXTERNAL_STORAGE"))
    }
    @Test fun notificationRuntimeGrantStartsOnAndroid13() {
        assertEquals(listOf("android.permission.CAMERA"), AppPermissionPolicy.runtimePermissions(32))
        assertEquals(listOf("android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS"), AppPermissionPolicy.runtimePermissions(33))
    }
    @Test fun specialAccessIsNeverIncludedInTheBulkRuntimeGrant() {
        for (sdk in 24..37) assertFalse(AppPermissionPolicy.runtimePermissions(sdk).any {
            it.contains("INSTALL") || it.contains("OVERLAY") || it.contains("USAGE") || it.contains("VPN")
        })
    }
    @Test fun devicesWithoutCamerasDoNotRequestCameraAccess() {
        assertEquals(listOf("android.permission.POST_NOTIFICATIONS"), AppPermissionPolicy.runtimePermissions(37, cameraAvailable = false))
    }
}
