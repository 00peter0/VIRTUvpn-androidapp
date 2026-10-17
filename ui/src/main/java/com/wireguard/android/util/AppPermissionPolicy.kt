package com.wireguard.android.util

/** Each feature can request only its own access. Unknown requests never become an overview. */
object AppPermissionPolicy {
    const val VPN = "vpn"
    const val KILL_SWITCH = "kill_switch"
    const val CAMERA = "camera"
    const val NOTIFICATIONS = "notifications"
    const val INSTALL = "install"
    const val USAGE = "usage"
    const val OVERLAY = "overlay"
    const val FILES = "files"
    const val BATTERY = "battery"

    fun capabilities(sdk: Int, requested: String?): List<String> {
        val available = buildList {
            add(VPN); add(KILL_SWITCH); add(CAMERA); add(NOTIFICATIONS)
            if (sdk >= 26) { add(INSTALL); add(USAGE) }
            if (sdk >= 34) add(OVERLAY)
            add(FILES); add(BATTERY)
        }
        return if (requested == null) available else available.filter { it == requested }
    }

    fun runtimePermission(capability: String, sdk: Int, cameraAvailable: Boolean = true): String? = when {
        capability == CAMERA && cameraAvailable -> "android.permission.CAMERA"
        capability == NOTIFICATIONS && sdk >= 33 -> "android.permission.POST_NOTIFICATIONS"
        capability == FILES && sdk <= 28 -> "android.permission.WRITE_EXTERNAL_STORAGE"
        else -> null
    }
}
