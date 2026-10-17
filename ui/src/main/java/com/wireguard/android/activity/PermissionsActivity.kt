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
import com.wireguard.android.util.AppPermissionPolicy
import com.wireguard.android.vcs.ManagedDiagnostics
import com.wireguard.android.vcs.ManagedUpdateFlow
import kotlinx.coroutines.launch

/** Feature requests show only their own access. The full overview is opened explicitly from Home. */
class PermissionsActivity : AppCompatActivity() {
    private lateinit var binding: PermissionsActivityBinding
    private val rows = linkedMapOf<String, PermissionItemBinding>()
    private val requested get() = intent.getStringExtra(EXTRA_CAPABILITY)
    private val settingsFallback = mutableSetOf<String>()
    private var pendingRuntimePermission: String? = null
    private var initialRequestStarted = false
    private val settings = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshAndResume() }
    private val runtime = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        pendingRuntimePermission?.let { if (!allowed) settingsFallback.add(it) }
        pendingRuntimePermission = null
        refreshAndResume()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark("#071018".toColorInt()), navigationBarStyle = SystemBarStyle.dark("#071018".toColorInt()))
        super.onCreate(savedInstanceState)
        initialRequestStarted = savedInstanceState?.getBoolean(STATE_INITIAL_REQUEST_STARTED) ?: false
        pendingRuntimePermission = savedInstanceState?.getString(STATE_PENDING_RUNTIME_PERMISSION)
        settingsFallback.addAll(savedInstanceState?.getStringArrayList(STATE_SETTINGS_FALLBACK).orEmpty())
        binding = PermissionsActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom); insets
        }
        binding.permissionsToolbar.setNavigationOnClickListener { finish() }
        if (AppPermissionPolicy.capabilities(Build.VERSION.SDK_INT, requested).isEmpty()) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }
        add(VPN, R.string.vcs_permission_vpn, R.string.vcs_permission_vpn_help, R.string.vcs_permission_vpn_reason) {
            val consent = VpnService.prepare(this)
            open(consent ?: Intent(Settings.ACTION_VPN_SETTINGS))
        }
        add(KILL_SWITCH, R.string.vcs_permission_kill_switch, R.string.vcs_permission_kill_switch_help, R.string.vcs_permission_kill_switch_reason) { open(Intent(Settings.ACTION_VPN_SETTINGS)) }
        add(CAMERA, R.string.vcs_permission_camera, R.string.vcs_permission_camera_help, R.string.vcs_permission_camera_reason) { openAppSettings() }
        add(NOTIFICATIONS, R.string.vcs_permission_notifications, R.string.vcs_permission_notifications_help, R.string.vcs_permission_notifications_reason) {
            if (Build.VERSION.SDK_INT >= 26) open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)) else openAppSettings()
        }
        if (Build.VERSION.SDK_INT >= 26) {
            add(INSTALL, R.string.vcs_permission_install, R.string.vcs_permission_install_help, R.string.vcs_permission_install_reason) { open(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri())) }
            add(USAGE, R.string.vcs_permission_usage, R.string.vcs_permission_usage_help, R.string.vcs_permission_usage_reason) { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, "package:$packageName".toUri())) }
        }
        if (Build.VERSION.SDK_INT >= 34) add(OVERLAY, R.string.vcs_permission_overlay, R.string.vcs_permission_overlay_help, R.string.vcs_permission_overlay_reason) { open(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri())) }
        add(FILES, R.string.vcs_permission_files, R.string.vcs_permission_files_help, R.string.vcs_permission_files_reason) { openAppSettings() }
        add(BATTERY, R.string.vcs_permission_battery, R.string.vcs_permission_battery_help, R.string.vcs_permission_battery_reason) { openAppSettings() }
    }

    override fun onResume() { super.onResume(); if (::binding.isInitialized && !isFinishing) refreshAndResume() }

    override fun onPostResume() {
        super.onPostResume()
        // A fresh QR action may ask again after a dismissed dialog. Never infer a permanent
        // Android denial from a preference written by an earlier request or app version.
        if (!isFinishing && requested == CAMERA && intent.getBooleanExtra(EXTRA_REQUEST_ON_OPEN, false) && !initialRequestStarted) {
            initialRequestStarted = true
            rows[CAMERA]?.permissionAction?.takeIf { it.isEnabled }?.performClick()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_INITIAL_REQUEST_STARTED, initialRequestStarted)
        outState.putString(STATE_PENDING_RUNTIME_PERMISSION, pendingRuntimePermission)
        outState.putStringArrayList(STATE_SETTINGS_FALLBACK, ArrayList(settingsFallback))
        super.onSaveInstanceState(outState)
    }

    private fun add(key: String, title: Int, help: Int, reason: Int, action: () -> Unit) {
        if (key !in AppPermissionPolicy.capabilities(Build.VERSION.SDK_INT, requested)) return
        val row = PermissionItemBinding.inflate(layoutInflater, binding.permissionsRows, false)
        row.permissionTitle.setText(title)
        row.permissionInfo.setInfo(getString(title), getString(help))
        row.permissionAction.setOnClickListener {
            val permission = requestableRuntimePermission(key)
            if (permission != null) {
                pendingRuntimePermission = permission
                runtime.launch(permission)
            } else action()
        }
        if (requested != null) {
            binding.permissionsToolbar.setTitle(title)
            binding.permissionsIntro.setText(reason)
            binding.permissionsInfo.isVisible = false // The single row already has this feature's help.
        }
        binding.permissionsRows.addView(row.root); rows[key] = row
    }

    private fun requestableRuntimePermission(key: String): String? {
        val permission = AppPermissionPolicy.runtimePermission(key, Build.VERSION.SDK_INT,
            packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) ?: return null
        if (AppPermissionAccess.runtimeGranted(this, permission)) return null
        return permission.takeIf { it !in settingsFallback || shouldShowRequestPermissionRationale(it) }
    }

    private fun refreshAndResume() {
        if (isFinishing) return
        // Recheck actual access, including camera AppOps. An Android RESULT_OK alone is not a grant.
        if (requested != null && requested != KILL_SWITCH && requested != BATTERY &&
            intent.getBooleanExtra(EXTRA_RESUME_ACTION, true) &&
            rows.containsKey(requested) && granted(requested) == true) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }
        refresh()
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
            val runtimePermission = requestableRuntimePermission(key)
            if (requested == CAMERA) binding.permissionsIntro.setText(
                if (runtimePermission == null && allowed != true) R.string.vcs_permission_camera_settings_reason
                else R.string.vcs_permission_camera_reason)
            row.permissionAction.setText(when {
                runtimePermission != null && key == CAMERA -> R.string.vcs_permissions_allow_camera
                runtimePermission != null && key == NOTIFICATIONS -> R.string.vcs_permissions_allow_notifications
                runtimePermission != null && key == FILES -> R.string.vcs_permissions_allow_downloads
                key == VPN && allowed == false -> R.string.vcs_permissions_allow_vpn
                else -> R.string.vcs_permissions_open_settings
            })
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
    }

    private fun openAppSettings() = open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
    private fun open(intent: Intent) {
        try { settings.launch(intent) }
        catch (_: Exception) { Toast.makeText(this, R.string.vcs_permissions_settings_unavailable, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_CAPABILITY = "permission_capability"
        private const val EXTRA_RESUME_ACTION = "permission_resume_action"
        private const val EXTRA_REQUEST_ON_OPEN = "permission_request_on_open"
        private const val STATE_INITIAL_REQUEST_STARTED = "initial_request_started"
        private const val STATE_PENDING_RUNTIME_PERMISSION = "pending_runtime_permission"
        private const val STATE_SETTINGS_FALLBACK = "settings_fallback"
        const val VPN = AppPermissionPolicy.VPN
        const val KILL_SWITCH = AppPermissionPolicy.KILL_SWITCH
        const val CAMERA = AppPermissionPolicy.CAMERA
        const val NOTIFICATIONS = AppPermissionPolicy.NOTIFICATIONS
        const val INSTALL = AppPermissionPolicy.INSTALL
        const val USAGE = AppPermissionPolicy.USAGE
        const val OVERLAY = AppPermissionPolicy.OVERLAY
        const val FILES = AppPermissionPolicy.FILES
        const val BATTERY = AppPermissionPolicy.BATTERY
        fun intent(context: Context, capability: String? = null, resumeAction: Boolean = true, requestOnOpen: Boolean = false): Intent = Intent(context, PermissionsActivity::class.java).apply {
            capability?.let { putExtra(EXTRA_CAPABILITY, it) }
            putExtra(EXTRA_RESUME_ACTION, resumeAction)
            putExtra(EXTRA_REQUEST_ON_OPEN, requestOnOpen)
        }
    }
}
