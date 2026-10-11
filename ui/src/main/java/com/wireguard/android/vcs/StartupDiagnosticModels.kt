package com.wireguard.android.vcs

/** Declared boot capability is evidence to review, not proof of execution or malware. */
enum class StartupAppStatus { ENABLED_RECEIVER, DISABLED, STOPPED, PERMISSION_ONLY, PERMISSION_NOT_GRANTED, UNVERIFIED }

data class StartupApp(
    val label: String, val packageName: String, val systemApp: Boolean?,
    val status: StartupAppStatus, val afterUnlock: Boolean, val beforeUnlock: Boolean
)

data class StartupAppReport(
    val apps: List<StartupApp>, val visibleApps: Int, val checked: Int,
    val complete: Boolean, val collectedAt: Long, val identified: Int = apps.size
)

object StartupDiagnosticPolicy {
    fun status(appEnabled: Boolean?, stopped: Boolean?, bootPermissionGranted: Boolean?, receiversEnabled: List<Boolean?>): StartupAppStatus = when {
        appEnabled == false -> StartupAppStatus.DISABLED
        stopped == true -> StartupAppStatus.STOPPED
        bootPermissionGranted == false -> StartupAppStatus.PERMISSION_NOT_GRANTED
        appEnabled == null || stopped == null || bootPermissionGranted == null -> StartupAppStatus.UNVERIFIED
        receiversEnabled.any { it == true } -> StartupAppStatus.ENABLED_RECEIVER
        receiversEnabled.isEmpty() -> StartupAppStatus.PERMISSION_ONLY
        receiversEnabled.any { it == null } -> StartupAppStatus.UNVERIFIED
        else -> StartupAppStatus.DISABLED
    }
}
