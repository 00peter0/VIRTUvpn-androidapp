/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
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
import com.wireguard.android.vcs.VcsManagedClient
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: HomeActivityBinding
    private val baseUrl = "https://vcs.virtucomputing.com"

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

    override fun onResume() {
        super.onResume()
        updateSignedInState()
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
        if (!requireSignedInForHome()) return
        lifecycleScope.launch {
            try {
                Toast.makeText(this@HomeActivity, R.string.vcs_sync_running, Toast.LENGTH_SHORT).show()
                val result = VcsManagedClient.syncManagedTunnels(this@HomeActivity)
                VcsManagedClient.reportCurrentStates(this@HomeActivity)
                Toast.makeText(this@HomeActivity, syncResultMessage(result), Toast.LENGTH_LONG).show()
                result.updateVersionName?.let { version ->
                    Toast.makeText(this@HomeActivity, getString(R.string.vcs_update_available, version), Toast.LENGTH_LONG).show()
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
            setPadding(32, 20, 32, 20)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.vcs_enroll_device)
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_enroll_submit) { _, _ ->
                enrollFromText(input.text?.toString().orEmpty())
            }
            .show()
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
        if (!requireSignedInForHome()) return
        lifecycleScope.launch {
            try {
                val update = VcsManagedClient.checkForManagedUpdate(this@HomeActivity)
                if (update.available && update.versionName != null) {
                    showUpdateDownloadDialog(update.versionName)
                } else {
                    Toast.makeText(this@HomeActivity, R.string.vcs_update_none, Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_sync_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showUpdateDownloadDialog(versionName: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.vcs_update_download_title)
            .setMessage(getString(R.string.vcs_update_download_message, versionName))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.stat_download) { _, _ ->
                val opened = VcsManagedClient.openManagedUpdate(this)
                Toast.makeText(
                    this,
                    if (opened) R.string.vcs_update_opened else R.string.vcs_update_download_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
            .show()
    }

    private fun requireSignedInForHome(): Boolean {
        if (VcsManagedClient.hasAccountSession(this)) return true
        Toast.makeText(this, R.string.vcs_sign_in_required, Toast.LENGTH_LONG).show()
        showSignInDialog()
        return false
    }

    private fun updateSignedInState() {
        val signedIn = VcsManagedClient.hasAccountSession(this)
        listOf(
            binding.vpnMeshButton,
            binding.managedAccessButton,
            binding.secureBrowserButton,
            binding.syncButton,
            binding.checkUpdatesButton
        ).forEach { setProtectedButtonState(it, signedIn) }
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
        AlertDialog.Builder(this)
            .setTitle(R.string.vcs_account_title)
            .setMessage(message)
            .setNegativeButton(R.string.vcs_account_sign_out) { _, _ -> showSignOutConfirmDialog() }
            .setNeutralButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_account_open_dashboard) { _, _ -> openSection("/dashboard") }
            .show()
    }

    private fun showSignOutConfirmDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.vcs_sign_out_confirm_title)
            .setMessage(R.string.vcs_sign_out_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_account_sign_out) { _, _ ->
                VcsManagedClient.clearAllVcsState(this)
                updateSignedInState()
                Toast.makeText(this, R.string.vcs_signed_out, Toast.LENGTH_LONG).show()
            }
            .show()
    }

    private fun updateKillSwitchStatus() {
        binding.killSwitchStatus.setText(R.string.vcs_kill_switch_checking)
        binding.killSwitchStatus.setTextColor(Color.parseColor("#AFC0CC"))
        lifecycleScope.launch {
            val protected = runCatching {
                val backend = Application.getBackend()
                backend.isAlwaysOn && backend.isLockdownEnabled
            }.getOrDefault(false)
            if (protected) {
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
}
