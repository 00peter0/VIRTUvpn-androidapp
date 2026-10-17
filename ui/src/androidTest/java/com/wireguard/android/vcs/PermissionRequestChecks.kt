package com.wireguard.android.vcs

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Build
import android.widget.LinearLayout
import com.wireguard.android.R
import com.wireguard.android.activity.PermissionsActivity
import com.wireguard.android.databinding.PermissionItemBinding
import com.wireguard.android.util.AppPermissionAccess
import com.wireguard.android.util.AppPermissionPolicy

/** Read-only UI checks: never presses a grant/settings control or changes a permission. */
internal object PermissionRequestChecks {
    fun verify(instrumentation: Instrumentation): List<String> {
        val context = instrumentation.targetContext
        val checks = mutableListOf<String>()
        val runtime = listOf(Manifest.permission.CAMERA, Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        fun access() = runtime.map { AppPermissionAccess.runtimeGranted(context, it) } + AppPermissionAccess.cameraAllowed(context)
        val before = access()
        val prompts = HashMap(context.getSharedPreferences("permission_requests", 0).all)
        var activity: Activity? = null
        fun close() {
            instrumentation.runOnMainSync { activity?.finish() }
            instrumentation.waitForIdleSync()
            activity = null
        }
        fun open(capability: String?, resumeAction: Boolean = false) {
            activity = instrumentation.startActivitySync(PermissionsActivity.intent(context, capability, resumeAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
        }
        try {
            for (capability in AppPermissionPolicy.capabilities(Build.VERSION.SDK_INT, null)) {
                open(capability)
                instrumentation.runOnMainSync {
                    check(activity?.isFinishing == false) { "Managing existing access must remain available: $capability" }
                    val rows = activity!!.findViewById<LinearLayout>(R.id.permissions_rows)
                    check(rows.childCount == 1) { "Unrelated access shown for $capability" }
                    val row = PermissionItemBinding.bind(rows.getChildAt(0))
                    val expected = when (capability) {
                        PermissionsActivity.VPN -> R.string.vcs_permission_vpn
                        PermissionsActivity.KILL_SWITCH -> R.string.vcs_permission_kill_switch
                        PermissionsActivity.CAMERA -> R.string.vcs_permission_camera
                        PermissionsActivity.NOTIFICATIONS -> R.string.vcs_permission_notifications
                        PermissionsActivity.INSTALL -> R.string.vcs_permission_install
                        PermissionsActivity.USAGE -> R.string.vcs_permission_usage
                        PermissionsActivity.OVERLAY -> R.string.vcs_permission_overlay
                        PermissionsActivity.FILES -> R.string.vcs_permission_files
                        PermissionsActivity.BATTERY -> R.string.vcs_permission_battery
                        else -> error("Unexpected capability")
                    }
                    check(row.permissionTitle.text.toString() == context.getString(expected))
                }
                close()
                checks += "$capability shows only its own access and can be cancelled"
            }
            open(null)
            instrumentation.runOnMainSync {
                check(activity!!.findViewById<LinearLayout>(R.id.permissions_rows).childCount ==
                    AppPermissionPolicy.capabilities(Build.VERSION.SDK_INT, null).size)
            }
            close()
            checks += "Explicit Home overview retains the individual settings"
            if (!AppPermissionAccess.cameraAllowed(context)) {
                open(PermissionsActivity.CAMERA, resumeAction = true)
                instrumentation.runOnMainSync { check(activity?.isFinishing == false) { "Blocked camera must not resume a scan" } }
                close()
                checks += "Blocked camera request stays pending instead of claiming a grant"
            }
            check(access() == before) { "Permission status changed during observation" }
            check(context.getSharedPreferences("permission_requests", 0).all == prompts) { "Opening a screen must not issue a runtime request" }
            checks += "No runtime access or request history changed by opening or cancelling screens"
            return checks
        } finally {
            close()
        }
    }
}
