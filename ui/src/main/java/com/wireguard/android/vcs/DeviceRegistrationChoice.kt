package com.wireguard.android.vcs

/** An explicit foreground decision, never saved or replayed after a restart. */
data class DeviceRegistrationChoice(val reconnectDeviceId: String? = null, val registerNewDevice: Boolean = false) {
    init {
        require(reconnectDeviceId == null || reconnectDeviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(reconnectDeviceId == null || !registerNewDevice)
    }
}

data class DeviceReconnectOption(val id: String, val name: String, val assignedTunnels: Int) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(name.isNotBlank() && name.length <= 256 && assignedTunnels >= 0)
    }
}
