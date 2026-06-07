/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.databinding.HomeActivityBinding
import com.wireguard.android.databinding.VcsSignInDialogBinding
import com.wireguard.android.util.HotspotDetector
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.vcs.VcsManagedClient
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: HomeActivityBinding
    private val baseUrl = "https://vcs.virtucomputing.com"
    private var hotspotActive = false
    private var hotspotCallback: AutoCloseable? = null
    private var hotspotReceiver: BroadcastReceiver? = null
    private var updateCheckRunning = false
    private var lastAutomaticUpdateCheckAt = 0L
    private var promptedUpdateVersionName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = HomeActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.ic_logo)
            title = "  ${getString(R.string.vcs_home_title)}"
        }

        binding.vpnMeshButton.setOnClickListener { if (requireSignedInForHome()) openVpnApp(MainActivity.TUNNEL_SECTION_VPN_MESH) }
        binding.secureBrowserButton.setOnClickListener { if (requireSignedInForHome()) startActivity(Intent(this, SecureBrowserActivity::class.java)) }
        binding.managedAccessButton.setOnClickListener { if (requireSignedInForHome()) openVpnApp(MainActivity.TUNNEL_SECTION_MANAGED_ACCESS) }
        binding.enrollButton.setOnClickListener { showEnrollDialog() }
        binding.syncButton.setOnClickListener { syncManagedAccess() }
        binding.checkUpdatesButton.setOnClickListener { checkUpdates() }
        binding.openVpnSettingsButton.setOnClickListener { openVpnSettings() }
        updateSignedInState()
        updateKillSwitchStatus()
    }

    override fun onStart() {
        super.onStart()
        startHotspotWarningMonitor()
    }

    override fun onStop() {
        stopHotspotWarningMonitor()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateSignedInState()
        refreshHotspotState()
        updateKillSwitchStatus()
        detectVpnAppUpdateIfNeeded()
    }

    private fun startHotspotWarningMonitor() {
        refreshHotspotState()
        hotspotCallback = HotspotDetector.registerWifiHotspotCallback(this) { active ->
            setHotspotActive(active)
        }
        if (hotspotReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getIntExtra(EXTRA_WIFI_AP_STATE, -1)
                val active = state == WIFI_AP_STATE_ENABLING || state == WIFI_AP_STATE_ENABLED ||
                    HotspotDetector.isWifiHotspotActive(context)
                setHotspotActive(active)
            }
        }
        val filter = IntentFilter(ACTION_WIFI_AP_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
        hotspotReceiver = receiver
    }

    private fun stopHotspotWarningMonitor() {
        hotspotCallback?.close()
        hotspotCallback = null
        hotspotReceiver?.let { receiver ->
            runCatching { unregisterReceiver(receiver) }
        }
        hotspotReceiver = null
    }

    private fun refreshHotspotState() {
        hotspotActive = HotspotDetector.isWifiHotspotActive(this)
    }

    private fun setHotspotActive(active: Boolean) {
        if (hotspotActive == active) return
        hotspotActive = active
        updateKillSwitchStatus()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.home_activity, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.menu_vcs_account)?.setTitle(
            if (VcsManagedClient.hasAccountSession(this)) R.string.vcs_account_title else R.string.vcs_home_account
        )
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_vcs_account -> {
                if (VcsManagedClient.hasAccountSession(this)) showAccountDialog() else showSignInDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun syncManagedAccess() {
        if (!requireEnrolledForDeviceAction()) return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@HomeActivity, R.string.vcs_sync_running, Toast.LENGTH_SHORT).show()
                val result = VcsManagedClient.syncManagedTunnels(this@HomeActivity)
                VcsManagedClient.reportCurrentStates(this@HomeActivity)
                Toast.makeText(this@HomeActivity, syncResultMessage(result), Toast.LENGTH_LONG).show()
                result.updateVersionName?.let { version ->
                    showUpdateDownloadDialog(version)
                }
            } catch (e: Throwable) {
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_sync_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun syncResultMessage(result: VcsManagedClient.SyncResult): String {
        return when {
            result.skippedRunning > 0 -> getString(R.string.vcs_sync_skipped_running, result.skippedRunning)
            result.pendingBundleAssignments > 0 -> getString(R.string.vcs_sync_bundle_pending, result.pendingBundleAssignments)
            result.assigned == 0 -> getString(R.string.vcs_sync_no_assignments)
            result.imported == 0 -> getString(R.string.vcs_sync_checked_no_imports, result.assigned)
            else -> getString(R.string.vcs_sync_success, result.imported, result.assigned)
        }
    }

    private fun showEnrollDialog() {
        val input = EditText(this).apply {
            hint = getString(R.string.vcs_enroll_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 3
            setSingleLine(false)
            setBackgroundResource(R.drawable.vcs_dialog_input_background)
            setHintTextColor(Color.parseColor("#8EA2AE"))
            setPadding(32, 24, 32, 24)
            setTextColor(Color.WHITE)
        }
        val dialog = VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_enroll_device),
            customView = input,
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.vcs_enroll_submit, primary = true) {
                enrollFromText(input.text?.toString().orEmpty())
            },
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
        )
        input.requestFocus()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    }

    private fun enrollFromText(value: String) {
        lifecycleScope.launch {
            try {
                val result = VcsManagedClient.handleEnrollmentPayload(this@HomeActivity, value)
                Toast.makeText(this@HomeActivity, syncResultMessage(result), Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_sync_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            } finally {
                updateSignedInState()
            }
        }
    }

    private fun checkUpdates() {
        if (!requireEnrolledForDeviceAction()) return
        detectVpnAppUpdate(showNoUpdate = true, forcePrompt = true)
    }

    private fun detectVpnAppUpdateIfNeeded() {
        if (!VcsManagedClient.hasSession(this)) return
        val now = SystemClock.elapsedRealtime()
        if (lastAutomaticUpdateCheckAt > 0 && now - lastAutomaticUpdateCheckAt < AUTO_UPDATE_CHECK_INTERVAL_MS) return
        lastAutomaticUpdateCheckAt = now
        detectVpnAppUpdate(showNoUpdate = false, forcePrompt = false)
    }

    private fun detectVpnAppUpdate(showNoUpdate: Boolean, forcePrompt: Boolean) {
        if (updateCheckRunning) return
        updateCheckRunning = true
        lifecycleScope.launch {
            try {
                val update = VcsManagedClient.checkForManagedUpdate(this@HomeActivity)
                if (update.available && update.versionName != null) {
                    if (forcePrompt || promptedUpdateVersionName != update.versionName) {
                        promptedUpdateVersionName = update.versionName
                        showUpdateDownloadDialog(update.versionName)
                    } else if (showNoUpdate) {
                        Toast.makeText(this@HomeActivity, getString(R.string.vcs_update_available, update.versionName), Toast.LENGTH_LONG).show()
                    }
                } else {
                    if (showNoUpdate) Toast.makeText(this@HomeActivity, R.string.vcs_update_none, Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                if (showNoUpdate) {
                    Toast.makeText(this@HomeActivity, getString(R.string.vcs_sync_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
                }
            } finally {
                updateCheckRunning = false
            }
        }
    }

    private fun showUpdateDownloadDialog(versionName: String) {
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_update_download_title),
            message = getString(R.string.vcs_update_download_message, versionName),
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.stat_download, primary = true) {
                val opened = VcsManagedClient.openManagedUpdate(this)
                Toast.makeText(
                    this,
                    if (opened) R.string.vcs_update_opened else R.string.vcs_update_download_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        )
    }

    private fun requireSignedInForHome(): Boolean {
        if (VcsManagedClient.hasAccountSession(this)) return true
        Toast.makeText(this, R.string.vcs_sign_in_required, Toast.LENGTH_LONG).show()
        showSignInDialog()
        return false
    }

    private fun requireEnrolledForDeviceAction(): Boolean {
        if (VcsManagedClient.hasSession(this)) return true
        Toast.makeText(this, R.string.vcs_enroll_required, Toast.LENGTH_LONG).show()
        showEnrollDialog()
        return false
    }

    private fun updateSignedInState() {
        val signedIn = VcsManagedClient.hasAccountSession(this)
        val enrolled = VcsManagedClient.hasSession(this)
        listOf(
            binding.vpnMeshButton,
            binding.managedAccessButton,
            binding.secureBrowserButton
        ).forEach { setProtectedButtonState(it, signedIn) }
        listOf(
            binding.syncButton,
            binding.checkUpdatesButton
        ).forEach { setProtectedButtonState(it, enrolled) }
        binding.enrollButtonLabel.setText(R.string.vcs_enroll_device)
        invalidateOptionsMenu()
    }

    private fun setProtectedButtonState(view: View, signedIn: Boolean) {
        view.isEnabled = true
        view.alpha = if (signedIn) 1f else 0.58f
    }

    private fun showSignInDialog() {
        val dialogBinding = VcsSignInDialogBinding.inflate(layoutInflater)
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        val submit = {
            val email = dialogBinding.emailInput.text?.toString().orEmpty()
            val password = dialogBinding.passwordInput.text?.toString().orEmpty()
            if (email.isBlank() || password.isBlank()) {
                Toast.makeText(this, R.string.vcs_sign_in_required_fields, Toast.LENGTH_LONG).show()
            } else {
                setSignInDialogBusy(dialogBinding, true)
                signIn(
                    email,
                    password,
                    onSuccess = { dialog.dismiss() },
                    onFinished = { setSignInDialogBusy(dialogBinding, false) }
                )
            }
        }
        dialogBinding.cancelButton.setOnClickListener { dialog.dismiss() }
        dialogBinding.signInButton.setOnClickListener { submit() }
        dialogBinding.passwordInput.setOnEditorActionListener { _, _, _ ->
            submit()
            true
        }
        dialog.show()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.9f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT)
        dialogBinding.emailInput.requestFocus()
    }

    private fun setSignInDialogBusy(dialogBinding: VcsSignInDialogBinding, busy: Boolean) {
        dialogBinding.signInButton.isEnabled = !busy
        dialogBinding.cancelButton.isEnabled = !busy
        dialogBinding.signInButton.alpha = if (busy) 0.62f else 1f
        dialogBinding.signInButton.setText(if (busy) R.string.vcs_sign_in_running else R.string.vcs_sign_in)
    }

    private fun signIn(email: String, password: String, onSuccess: () -> Unit = {}, onFinished: () -> Unit = {}) {
        lifecycleScope.launch {
            try {
                val account = VcsManagedClient.signInAccount(this@HomeActivity, baseUrl, email, password)
                updateSignedInState()
                Toast.makeText(
                    this@HomeActivity,
                    getString(R.string.vcs_signed_in_as, account.email ?: getString(R.string.vcs_account_title)),
                    Toast.LENGTH_LONG
                ).show()
                onSuccess()
            } catch (e: Throwable) {
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_sign_in_failed, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            } finally {
                onFinished()
            }
        }
    }

    private fun showAccountDialog() {
        val account = VcsManagedClient.accountInfo(this)
        val message = buildString {
            append(getString(R.string.vcs_account_signed_in))
            account?.email?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(getString(R.string.vcs_account_email, it))
            }
            account?.tenantName?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(getString(R.string.vcs_account_tenant, it))
            }
            account?.apiBase?.takeIf { it.isNotBlank() }?.let {
                append("\n")
                append(getString(R.string.vcs_account_api_base, it))
            }
        }
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_account_title),
            message = message,
            negative = VcsDialogs.action(this, R.string.vcs_account_sign_out) { showSignOutConfirmDialog() },
            neutral = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.vcs_account_open_dashboard, primary = true) { openSection("/dashboard") }
        )
    }

    private fun showSignOutConfirmDialog() {
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_sign_out_confirm_title),
            message = getString(R.string.vcs_sign_out_confirm_message),
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.vcs_account_sign_out, primary = true) {
                VcsManagedClient.clearAllVcsState(this)
                updateSignedInState()
                Toast.makeText(this, R.string.vcs_signed_out, Toast.LENGTH_LONG).show()
            }
        )
    }

    private fun updateKillSwitchStatus() {
        binding.killSwitchStatus.setText(R.string.vcs_kill_switch_checking)
        binding.killSwitchStatus.setTextColor(Color.parseColor("#AFC0CC"))
        lifecycleScope.launch {
            val protected = runCatching {
                val backend = Application.getBackend()
                backend.isAlwaysOn && backend.isLockdownEnabled
            }.getOrDefault(false)
            val activeHotspot = hotspotActive || HotspotDetector.isWifiHotspotActive(this@HomeActivity)
            if (activeHotspot) {
                binding.killSwitchStatus.setText(R.string.vcs_hotspot_vpn_bypass_warning)
                binding.killSwitchStatus.setTextColor(Color.parseColor("#F87171"))
            } else if (protected) {
                binding.killSwitchStatus.setText(R.string.vcs_kill_switch_protected)
                binding.killSwitchStatus.setTextColor(Color.parseColor("#86EFAC"))
            } else {
                binding.killSwitchStatus.setText(R.string.vcs_kill_switch_unprotected)
                binding.killSwitchStatus.setTextColor(Color.parseColor("#FBBF24"))
            }
        }
    }

    private fun openVpnSettings() {
        startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    }

    private fun openVpnApp(section: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TUNNEL_SECTION, section))
    }

    private fun openSection(path: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$baseUrl$path")))
    }

    companion object {
        private const val ACTION_WIFI_AP_STATE_CHANGED = "android.net.wifi.WIFI_AP_STATE_CHANGED"
        private const val EXTRA_WIFI_AP_STATE = "wifi_state"
        private const val WIFI_AP_STATE_ENABLING = 12
        private const val WIFI_AP_STATE_ENABLED = 13
        private const val AUTO_UPDATE_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
