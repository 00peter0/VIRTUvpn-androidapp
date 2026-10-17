package com.wireguard.android.util

/** Runtime grants are separate from Android's individually approved special access screens. */
object AppPermissionPolicy {
    fun runtimePermissions(sdk: Int, cameraAvailable: Boolean = true): List<String> = buildList {
        if (cameraAvailable) add("android.permission.CAMERA")
        if (sdk >= 33) add("android.permission.POST_NOTIFICATIONS")
        if (sdk <= 28) add("android.permission.WRITE_EXTERNAL_STORAGE")
    }
}
