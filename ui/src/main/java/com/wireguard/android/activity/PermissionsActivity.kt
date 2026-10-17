package com.wireguard.android.activity

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.toColorInt
import androidx.core.net.toUri
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.databinding.PermissionItemBinding
import com.wireguard.android.databinding.PermissionsActivityBinding
import com.wireguard.android.util.AppPermissionAccess
import com.wireguard.android.vcs.ManagedDiagnostics
import com.wireguard.android.vcs.ManagedUpdateFlow
import kotlinx.coroutines.launch

/** A single user-controlled permission overview. Merely opening it grants or changes nothing. */
class PermissionsActivity : AppCompatActivity() {
    private lateinit var binding: PermissionsActivityBinding
    private val rows = linkedMapOf<String, PermissionItemBinding>()
    private val requested get() = intent.getStringExtra(EXTRA_CAPABILITY)
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }
    private val runtime = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark("#071018".toColorInt()), navigationBarStyle = SystemBarStyle.dark("#071018".toColorInt()))
        super.onCreate(savedInstanceState)
        binding = PermissionsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        binding.permissionsToolbar.setNavigationOnClickListener { finish() }
        binding.permissionsAllowApps.setOnClickListener {
            val missing = AppPermissionAccess.runtimePermissions(this).filterNot { AppPermissionAccess.runtimeGranted(this, it) }
            if (missing.isNotEmpty()) runtime.launch(missing.toTypedArray())
            else openAppSettings()
        }
        add(VPN, R.string.vcs_permission_vpn, R.string.vcs_permission_vpn_help) {
            val consent = VpnService.prepare(this)
            open(consent ?: Intent(Settings.ACTION_VPN_SETTINGS))
        }
        add(KILL_SWITCH, R.string.vcs_permission_kill_switch, R.string.vcs_permission_kill_switch_help) { open(Intent(Settings.ACTION_VPN_SETTINGS)) }
        add(CAMERA, R.string.vcs_permission_camera, R.string.vcs_permission_camera_help) { openAppSettings() }
        add(NOTIFICATIONS, R.string.vcs_permission_notifications, R.string.vcs_permission_notifications_help) {
            if (Build.VERSION.SDK_INT >= 26) open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) else openAppSettings()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            add(INSTALL, R.string.vcs_permission_install, R.string.vcs_permission_install_help) { open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())) }
            add(USAGE, R.string.vcs_permission_usage, R.string.vcs_permission_usage_help) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())) }
        }
        if (Build.VERSION.SDK_INT >= 34) add(OVERLAY, R.string.vcs_permission_overlay, R.string.vcs_permission_overlay_help) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) }
        add(FILES, R.string.vcs_permission_files, R.string.vcs_permission_files_help) { openAppSettings() }
        add(BATTERY, R.string.vcs_permission_battery, R.string.vcs_permission_battery_help) { openAppSettings() }
        rows[requested]?.let {
            binding.permissionsRequested.text = getString(R.string.vcs_permissions_for_action, it.permissionTitle.text)
            binding.permissionsRequested.isVisible = true
        }
        binding.permissionsContinue.isVisible = requested != null && requested != KILL_SWITCH && requested != BATTERY
        binding.permissionsContinue.setOnClickListener {
            if (granted(requested) == true) { setResult(Activity.RESULT_OK); finish() }
            else Toast.makeText(this, R.string.vcs_permissions_requested_missing, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() { super.onResume(); if (::binding.isInitialized) refresh() }

    private fun add(key: String, title: Int, help: Int, action: () -> Unit) {
        val row = PermissionItemBinding.inflate(layoutInflater, binding.permissionsRows, false)
        row.permissionTitle.setText(title)
        row.permissionInfo.setInfo(getString(title), getString(help))
        row.permissionAction.setOnClickListener { action() }
        binding.permissionsRows.addView(row.root); rows[key] = row
    }

    private fun granted(key: String?): Boolean? = runCatching {
        when (key) {
            VPN -> VpnService.prepare(this) == null
            CAMERA -> AppPermissionAccess.cameraAllowed(this)
            NOTIFICATIONS -> NotificationManagerCompat.from(this).areNotificationsEnabled()
            INSTALL -> ManagedUpdateFlow.canInstall(this)
            USAGE -> ManagedDiagnostics.hasUsageAccess(this)
            OVERLAY -> Settings.canDrawOverlays(this)
            FILES -> Build.VERSION.SDK_INT >= 29 || AppPermissionAccess.runtimeGranted(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            BATTERY -> getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            else -> null
        }
    }.getOrNull()

    private fun refresh() {
        for ((key, row) in rows) {
            val allowed = granted(key)
            row.permissionStatus.setText(if (allowed == true) R.string.vcs_permissions_enabled else if (allowed == false) R.string.vcs_permissions_not_enabled else R.string.vcs_permissions_check_settings)
            row.permissionStatus.setTextColor((if (allowed == true) "#86EFAC" else if (allowed == false) "#FBBF24" else "#AFC0CC").toColorInt())
            if (key == FILES && Build.VERSION.SDK_INT >= 29) {
                row.permissionStatus.setText(R.string.vcs_permissions_files_scoped)
                row.permissionAction.isVisible = false
            }
            if (key == CAMERA && !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
                row.permissionStatus.setText(R.string.vcs_permissions_unavailable); row.permissionAction.isEnabled = false
            }
        }
        rows[KILL_SWITCH]?.let { row ->
            lifecycleScope.launch {
                val status = runCatching {
                    val backend = Application.getBackend()
                    if (Build.VERSION.SDK_INT < 29 || backend !is GoBackend) null
                    else Pair(backend.isAlwaysOn, backend.isLockdownEnabled)
                }.getOrNull()
                row.permissionStatus.text = status?.let { getString(R.string.vcs_permissions_kill_status, getString(if (it.first) R.string.vcs_permissions_enabled else R.string.vcs_permissions_not_enabled), getString(if (it.second) R.string.vcs_permissions_enabled else R.string.vcs_permissions_not_enabled)) }
                    ?: getString(R.string.vcs_permissions_check_settings)
                row.permissionStatus.setTextColor((if (status?.let { it.first && it.second } == true) "#86EFAC" else "#AFC0CC").toColorInt())
            }
        }
        binding.permissionsContinue.isEnabled = granted(requested) == true
        binding.permissionsContinue.alpha = if (binding.permissionsContinue.isEnabled) 1f else 0.45f
    }

    private fun openAppSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    private fun open(intent: Intent) {
        try { settings.launch(intent) }
        catch (_: Exception) { Toast.makeText(this, R.string.vcs_permissions_settings_unavailable, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_CAPABILITY = "permission_capability"
        const val VPN = "vpn"
        const val KILL_SWITCH = "kill_switch"
        const val CAMERA = "camera"
        const val NOTIFICATIONS = "notifications"
        const val INSTALL = "install"
        const val USAGE = "usage"
        const val OVERLAY = "overlay"
        const val FILES = "files"
        const val BATTERY = "battery"
        fun intent(context: Context, capability: String? = null): Intent = Intent(context, PermissionsActivity::class.java).apply {
            capability?.let { putExtra(EXTRA_CAPABILITY, it) }
        }
    }
}
