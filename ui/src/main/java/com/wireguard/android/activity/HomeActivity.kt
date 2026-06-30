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
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.HomeActivityBinding
import com.wireguard.android.databinding.VcsSignInDialogBinding
import com.wireguard.android.util.HotspotDetector
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.util.VpnRouterAttestation
import com.wireguard.android.util.VpnRouterManager
import com.wireguard.android.util.VpnRouterOperationFormatter
import com.wireguard.android.vcs.VcsManagedClient
import com.wireguard.android.widget.ToggleSwitch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.system.exitProcess

class HomeActivity : AppCompatActivity() {
    private lateinit var binding: HomeActivityBinding
    private val baseUrl = "https://vcs.virtucomputing.com"
    private var hotspotActive = false
    private var hotspotCallback: AutoCloseable? = null
    private var hotspotReceiver: BroadcastReceiver? = null
    private var updateCheckRunning = false
    private var lastAutomaticUpdateCheckAt = 0L
    private var promptedUpdateVersionName: String? = null
    private var vpnStatusJob: Job? = null
    private var vpnRouterActionRunning = false
    private var lastVpnRouterStatus: VpnRouterManager.Status? = null
    private var deviceHeartbeatRunning = false
    private var lastDeviceHeartbeatAt = 0L
    private var vpnStatusToggleTargetName: String? = null
    private var selectedHomeTunnelName: String? = null
    private var killSwitchExpanded = false
    private var vpnRouterExpanded = false
    private var routerOperationDialog: AlertDialog? = null
    private var routerOperationDialogMessage: TextView? = null

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
        binding.openVpnSettingsButton.setOnClickListener {
            if (lastVpnRouterStatus?.availability != VpnRouterManager.Availability.ENABLED) openVpnSettings()
        }
        binding.vpnRouterButton.setOnClickListener { toggleVpnRouter() }
        binding.vpnRouterPageButton.setOnClickListener { startActivity(Intent(this, VpnRouterActivity::class.java)) }
        binding.killSwitchPanel.setOnClickListener { setKillSwitchExpanded(!killSwitchExpanded) }
        binding.killSwitchHeader.setOnClickListener { setKillSwitchExpanded(!killSwitchExpanded) }
        binding.vpnRouterPanel.setOnClickListener { setVpnRouterExpanded(!vpnRouterExpanded) }
        binding.vpnRouterHeader.setOnClickListener { setVpnRouterExpanded(!vpnRouterExpanded) }
        binding.vpnRouterLogo.setOnClickListener { showVpnRouterLogoActions() }
        selectedHomeTunnelName = getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE).getString(KEY_HOME_SELECTED_TUNNEL, null)
        binding.vpnStatusTunnelSelector.setOnClickListener { showHomeTunnelSelector() }
        binding.vpnStatusToggle.setOnBeforeCheckedChangeListener(object : ToggleSwitch.OnBeforeCheckedChangeListener {
            override fun onBeforeCheckedChanged(toggleSwitch: ToggleSwitch?, checked: Boolean) {
                toggleHomeVpnStatus(checked)
            }
        })
        updateSignedInState()
        setKillSwitchExpanded(false)
        setVpnRouterExpanded(false)
        reportDeviceHeartbeatIfNeeded()
        updateVpnStatus()
        updateKillSwitchStatus()
        updateVpnRouterStatus()
    }

    override fun onStart() {
        super.onStart()
        startHotspotWarningMonitor()
        startVpnStatusMonitor()
    }

    override fun onStop() {
        stopVpnStatusMonitor()
        stopHotspotWarningMonitor()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        updateSignedInState()
        reportDeviceHeartbeatIfNeeded()
        refreshHotspotState()
        updateVpnStatus()
        updateKillSwitchStatus()
        updateVpnRouterStatus()
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
        updateVpnRouterStatus()
    }

    private fun startVpnStatusMonitor() {
        if (vpnStatusJob != null) return
        vpnStatusJob = lifecycleScope.launch {
            while (isActive) {
                refreshVpnStatus()
                delay(VPN_STATUS_REFRESH_INTERVAL_MS)
            }
        }
    }

    private fun stopVpnStatusMonitor() {
        vpnStatusJob?.cancel()
        vpnStatusJob = null
    }

    private fun updateVpnStatus() {
        lifecycleScope.launch {
            refreshVpnStatus()
        }
    }

    private suspend fun refreshVpnStatus() {
        val manager = Application.getTunnelManager()
        val tunnels = runCatching { manager.getTunnels().toList() }.getOrDefault(emptyList())
        val selectableTunnels = filterHomeSelectableTunnels(tunnels)
        val runningTunnels = tunnels.filter { tunnel -> tunnel.state == Tunnel.State.UP }
        val runningTunnelNames = runningTunnels.map { tunnel -> tunnel.name }
        val selectedTunnel = selectedHomeTunnelName?.let { selectedName ->
            selectableTunnels.firstOrNull { tunnel -> tunnel.name == selectedName }
        }
        val targetTunnel = selectedTunnel
            ?: runningTunnels.firstOrNull { tunnel -> selectableTunnels.any { it.name == tunnel.name } }
            ?: manager.lastUsedTunnel?.takeIf { tunnel -> selectableTunnels.any { it.name == tunnel.name } }
            ?: selectableTunnels.firstOrNull()
        vpnStatusToggleTargetName = targetTunnel?.name
        if (selectedHomeTunnelName == null && targetTunnel != null) {
            persistHomeTunnelSelection(targetTunnel.name)
        } else if (selectedHomeTunnelName != null && selectedTunnel == null) {
            clearHomeTunnelSelection()
        }

        if (runningTunnelNames.isNotEmpty()) {
            binding.vpnStatusValue.setText(R.string.vcs_vpn_status_connected)
            binding.vpnStatusValue.setTextColor(Color.parseColor("#86EFAC"))
            binding.vpnStatusTunnel.text = getString(R.string.vcs_vpn_status_tunnel, runningTunnelNames.joinToString(", "))
        } else {
            binding.vpnStatusValue.setText(R.string.vcs_vpn_status_disconnected)
            binding.vpnStatusValue.setTextColor(Color.parseColor("#FBBF24"))
            binding.vpnStatusTunnel.setText(R.string.vcs_vpn_status_no_tunnel)
        }
        binding.vpnStatusTunnel.setTextColor(Color.parseColor("#AFC0CC"))
        binding.vpnStatusTunnelSelector.isEnabled = selectableTunnels.isNotEmpty()
        binding.vpnStatusTunnelSelector.alpha = if (selectableTunnels.isNotEmpty()) 1f else 0.58f
        binding.vpnStatusTunnelSelectorValue.text = targetTunnel?.name ?: getString(R.string.vcs_vpn_status_select_tunnel_none)
        binding.vpnStatusToggle.isEnabled = targetTunnel != null
        binding.vpnStatusToggle.setCheckedInternal(targetTunnel?.state == Tunnel.State.UP)
        binding.vpnStatusToggleLabel.text = when {
            targetTunnel == null -> getString(R.string.vcs_vpn_status_toggle_unavailable)
            targetTunnel.state == Tunnel.State.UP -> getString(R.string.vcs_vpn_status_toggle_on, targetTunnel.name)
            else -> getString(R.string.vcs_vpn_status_toggle_off, targetTunnel.name)
        }
    }

    private fun showHomeTunnelSelector() {
        lifecycleScope.launch {
            val tunnels = runCatching { Application.getTunnelManager().getTunnels().toList() }
                .getOrDefault(emptyList())
                .let { filterHomeSelectableTunnels(it) }
                .sortedBy { tunnel -> tunnel.name.lowercase() }
            if (tunnels.isEmpty()) {
                Toast.makeText(this@HomeActivity, R.string.vcs_vpn_status_toggle_unavailable, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val labels = tunnels.map { tunnel ->
                if (tunnel.state == Tunnel.State.UP) {
                    getString(R.string.vcs_vpn_status_select_tunnel_running, tunnel.name)
                } else {
                    getString(R.string.vcs_vpn_status_select_tunnel_stopped, tunnel.name)
                }
            }
            val adapter = object : ArrayAdapter<String>(this@HomeActivity, android.R.layout.simple_list_item_1, labels) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        setBackgroundColor(Color.parseColor("#071018"))
                        (this as? TextView)?.apply {
                            setTextColor(Color.parseColor("#E5F2F7"))
                            textSize = 15f
                        }
                    }
                }
            }
            val dialog = AlertDialog.Builder(this@HomeActivity)
                .setTitle(R.string.vcs_vpn_status_select_tunnel_title)
                .setAdapter(adapter) { _, which ->
                    val selected = tunnels[which]
                    persistHomeTunnelSelection(selected.name)
                    vpnStatusToggleTargetName = selected.name
                    updateVpnStatus()
                }
                .show()
            VcsDialogs.applyDefaultStyle(dialog)
        }
    }

    private fun filterHomeSelectableTunnels(tunnels: List<com.wireguard.android.model.ObservableTunnel>): List<com.wireguard.android.model.ObservableTunnel> {
        val managedAccessNames = VcsManagedClient.localTunnelNamesForSection(this, MainActivity.TUNNEL_SECTION_MANAGED_ACCESS)
        if (managedAccessNames.isEmpty()) return tunnels
        return tunnels.filter { tunnel -> tunnel.name !in managedAccessNames }
    }

    private fun persistHomeTunnelSelection(name: String) {
        selectedHomeTunnelName = name
        getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOME_SELECTED_TUNNEL, name)
            .apply()
    }

    private fun clearHomeTunnelSelection() {
        selectedHomeTunnelName = null
        getSharedPreferences(HOME_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_HOME_SELECTED_TUNNEL)
            .apply()
    }

    private fun toggleHomeVpnStatus(checked: Boolean) {
        lifecycleScope.launch {
            try {
                val manager = Application.getTunnelManager()
                val tunnels = manager.getTunnels()
                val targetName = vpnStatusToggleTargetName
                    ?: manager.lastUsedTunnel?.name
                    ?: tunnels.firstOrNull()?.name
                val target = targetName?.let { tunnels[it] } ?: tunnels.firstOrNull()
                if (target == null) {
                    Toast.makeText(this@HomeActivity, R.string.vcs_vpn_status_toggle_unavailable, Toast.LENGTH_SHORT).show()
                    refreshVpnStatus()
                    return@launch
                }
                val requestedState = if (checked) Tunnel.State.UP else Tunnel.State.DOWN
                val actualState = target.setStateAsync(requestedState)
                VcsManagedClient.reportTunnelTransition(this@HomeActivity, target.name, requestedState, actualState)
                VcsManagedClient.reportCurrentStates(this@HomeActivity)
                reconcileVpnRouterFromHome(showProgress = true)
            } catch (e: Throwable) {
                val manager = Application.getTunnelManager()
                val targetName = vpnStatusToggleTargetName
                    ?: manager.lastUsedTunnel?.name
                val target = targetName?.let { manager.getTunnels()[it] }
                val requestedState = if (checked) Tunnel.State.UP else Tunnel.State.DOWN
                if (target != null) {
                    val verifiedState = runCatching { manager.getTunnelState(target) }.getOrDefault(target.state)
                    if (verifiedState == requestedState) {
                        VcsManagedClient.reportTunnelTransition(this@HomeActivity, target.name, requestedState, verifiedState)
                        VcsManagedClient.reportCurrentStates(this@HomeActivity)
                        reconcileVpnRouterFromHome(showProgress = true)
                        return@launch
                    }
                    VcsManagedClient.reportTunnelTransition(this@HomeActivity, target.name, requestedState, target.state, e)
                }
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_vpn_status_toggle_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            } finally {
                refreshVpnStatus()
                updateVpnRouterStatus()
            }
        }
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

    private fun enrollResultMessage(result: VcsManagedClient.EnrollResult): String {
        return getString(R.string.vcs_enroll_success, result.deviceName ?: getString(R.string.vcs_enroll_this_device))
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
                Toast.makeText(this@HomeActivity, enrollResultMessage(result), Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_enroll_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
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
        if (!VcsManagedClient.hasSession(this) && !VcsManagedClient.hasAccountSession(this)) return
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
                downloadLatestUpdate()
            }
        )
    }

    private fun downloadLatestUpdate() {
        if (updateCheckRunning) return
        updateCheckRunning = true
        lifecycleScope.launch {
            try {
                val update = VcsManagedClient.checkForManagedUpdate(this@HomeActivity)
                if (!update.available) {
                    Toast.makeText(this@HomeActivity, R.string.vcs_update_none, Toast.LENGTH_LONG).show()
                    return@launch
                }
                val download = VcsManagedClient.openManagedUpdate(this@HomeActivity)
                Toast.makeText(
                    this@HomeActivity,
                    if (download != null) getString(R.string.vcs_update_opened, download.fileName) else getString(R.string.vcs_update_download_failed),
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Throwable) {
                Toast.makeText(this@HomeActivity, getString(R.string.vcs_sync_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            } finally {
                updateCheckRunning = false
            }
        }
    }

    private fun requireSignedInForHome(): Boolean {
        if (VcsManagedClient.hasAccountSession(this)) return true
        Toast.makeText(this, R.string.vcs_sign_in_required, Toast.LENGTH_LONG).show()
        showSignInDialog()
        return false
    }

    private fun requireEnrolledForDeviceAction(): Boolean {
        if (VcsManagedClient.hasSession(this)) return true
        if (VcsManagedClient.hasAccountSession(this)) return true
        Toast.makeText(this, R.string.vcs_sign_in_required, Toast.LENGTH_LONG).show()
        showSignInDialog()
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
        ).forEach { setProtectedButtonState(it, signedIn || enrolled) }
        updateVpnRouterButtonState(lastVpnRouterStatus)
        binding.enrollButtonLabel.setText(R.string.vcs_enroll_device)
        invalidateOptionsMenu()
    }

    private fun reportDeviceHeartbeatIfNeeded() {
        if (deviceHeartbeatRunning) return
        if (!VcsManagedClient.hasSession(this) && !VcsManagedClient.hasAccountSession(this)) return
        val now = SystemClock.elapsedRealtime()
        if (lastDeviceHeartbeatAt > 0 && now - lastDeviceHeartbeatAt < DEVICE_HEARTBEAT_INTERVAL_MS) return
        deviceHeartbeatRunning = true
        lastDeviceHeartbeatAt = now
        lifecycleScope.launch {
            val reported = runCatching {
                VcsManagedClient.reportDeviceHeartbeat(this@HomeActivity)
            }.isSuccess
            deviceHeartbeatRunning = false
            if (!reported) lastDeviceHeartbeatAt = 0L
            if (reported) updateSignedInState()
        }
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

    private suspend fun reconcileVpnRouterFromHome(showProgress: Boolean): VpnRouterManager.Status? {
        val current = runCatching { VpnRouterManager.getStatus(this@HomeActivity) }.getOrNull()
        if (current?.needsReconcile != true) {
            current?.let { renderVpnRouterStatus(it) }
            return current
        }
        if (showProgress) showRouterOperationDialog()
        val progressJob = if (showProgress) lifecycleScope.launch {
            while (isActive) {
                renderRouterOperationStatus(VpnRouterManager.getOperationStatus(this@HomeActivity))
                delay(250)
            }
        } else null
        return try {
            val status = VpnRouterManager.reconcile(this@HomeActivity)
            renderVpnRouterStatus(status)
            status
        } catch (e: Throwable) {
            val status = VpnRouterManager.Status(
                availability = VpnRouterManager.Availability.ERROR,
                activeTunnel = current.activeTunnel,
                tetherInterfaces = current.tetherInterfaces,
                detail = e.message ?: e.javaClass.simpleName
            )
            renderVpnRouterStatus(status)
            status
        } finally {
            progressJob?.cancel()
            if (showProgress) {
                renderRouterOperationStatus(VpnRouterManager.getOperationStatus(this@HomeActivity))
                delay(450)
                dismissRouterOperationDialog()
            }
        }
    }

    private fun showRouterOperationDialog() {
        if (routerOperationDialog?.isShowing == true) return
        routerOperationDialogMessage = TextView(this).apply {
            text = VpnRouterOperationFormatter.message(this@HomeActivity, VpnRouterManager.getOperationStatus(this@HomeActivity))
            setTextColor(Color.parseColor("#AFC0CC"))
            textSize = 14f
            setLineSpacing(4f, 1.0f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        routerOperationDialog = VcsDialogs.show(
            this,
            title = getString(R.string.vcs_vpn_router_operation_title),
            customView = routerOperationDialogMessage,
            cancelable = false
        )
    }

    private fun renderRouterOperationStatus(status: VpnRouterManager.OperationStatus) {
        routerOperationDialogMessage?.text = VpnRouterOperationFormatter.message(this, status)
    }

    private fun dismissRouterOperationDialog() {
        routerOperationDialog?.dismiss()
        routerOperationDialog = null
        routerOperationDialogMessage = null
    }

    private fun updateVpnRouterStatus() {
        if (vpnRouterActionRunning) return
        binding.vpnRouterStatus.setText(R.string.vcs_vpn_router_checking)
        binding.vpnRouterStatus.setTextColor(Color.parseColor("#AFC0CC"))
        lifecycleScope.launch {
            val status = runCatching {
                val current = VpnRouterManager.getStatus(this@HomeActivity)
                if (current.needsReconcile) {
                    VpnRouterManager.reconcile(this@HomeActivity)
                } else {
                    current
                }
            }.getOrElse { e ->
                VpnRouterManager.Status(
                    availability = VpnRouterManager.Availability.ERROR,
                    detail = e.message ?: e.javaClass.simpleName
                )
            }
            renderVpnRouterStatus(status)
        }
    }

    private fun setKillSwitchExpanded(expanded: Boolean) {
        killSwitchExpanded = expanded
        binding.killSwitchBody.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.killSwitchChevron.rotation = if (expanded) 90f else 0f
    }

    private fun setVpnRouterExpanded(expanded: Boolean) {
        vpnRouterExpanded = expanded
        binding.vpnRouterBody.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.vpnRouterChevron.rotation = if (expanded) 90f else 0f
    }

    private fun openRouterPairingPage() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(VpnRouterAttestation.pairingLandingUrl(this))))
    }

    private fun toggleVpnRouter() {
        if (vpnRouterActionRunning) return
        vpnRouterActionRunning = true
        binding.vpnRouterButton.isEnabled = false
        binding.vpnRouterButton.alpha = 0.58f
        binding.vpnRouterStatus.setText(R.string.vcs_vpn_router_checking)
        binding.vpnRouterStatus.setTextColor(Color.parseColor("#AFC0CC"))
        lifecycleScope.launch {
            val current = lastVpnRouterStatus ?: runCatching {
                VpnRouterManager.getStatus(this@HomeActivity)
            }.getOrNull()
            if (current?.availability == VpnRouterManager.Availability.UNSUPPORTED) {
                vpnRouterActionRunning = false
                renderVpnRouterStatus(current)
                return@launch
            }
            val showProgress = current?.canDisable != true
            if (showProgress) showRouterOperationDialog()
            val progressJob = if (showProgress) launch {
                while (isActive) {
                    renderRouterOperationStatus(VpnRouterManager.getOperationStatus(this@HomeActivity))
                    delay(250)
                }
            } else null
            val status = runCatching {
                if (current?.canDisable == true) {
                    VpnRouterManager.disable(this@HomeActivity)
                } else {
                    VpnRouterManager.enable(this@HomeActivity)
                }
            }.getOrElse { e ->
                VpnRouterManager.Status(
                    availability = VpnRouterManager.Availability.ERROR,
                    activeTunnel = current?.activeTunnel,
                    tetherInterfaces = current?.tetherInterfaces.orEmpty(),
                    detail = e.message ?: e.javaClass.simpleName
                )
            }.also {
                progressJob?.cancel()
                if (showProgress) {
                    renderRouterOperationStatus(VpnRouterManager.getOperationStatus(this@HomeActivity))
                    delay(450)
                    dismissRouterOperationDialog()
                }
            }
            vpnRouterActionRunning = false
            renderVpnRouterStatus(status)
        }
    }

    private fun showRouterKernelRequirementDialog() {
        if (!VpnRouterManager.isKernelModuleAvailable()) {
            VcsDialogs.show(
                context = this,
                title = getString(R.string.vcs_vpn_router_kernel_missing_title),
                message = getString(R.string.vcs_vpn_router_kernel_missing_message),
                positive = VcsDialogs.action(this, android.R.string.ok, primary = true)
            )
            return
        }
        VcsDialogs.show(
            context = this,
            title = getString(R.string.vcs_vpn_router_kernel_backend_title),
            message = getString(R.string.vcs_vpn_router_kernel_backend_message),
            negative = VcsDialogs.action(this, android.R.string.cancel),
            positive = VcsDialogs.action(this, R.string.vcs_vpn_router_kernel_backend_action, primary = true) {
                lifecycleScope.launch {
                    VpnRouterManager.prepareKernelBackend(this@HomeActivity)
                    Toast.makeText(this@HomeActivity, R.string.vcs_vpn_router_kernel_backend_restarting, Toast.LENGTH_LONG).show()
                    packageManager.getLaunchIntentForPackage(packageName)?.let { intent ->
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        startActivity(intent)
                    }
                    exitProcess(0)
                }
            }
        )
    }

    private fun renderVpnRouterStatus(status: VpnRouterManager.Status) {
        lastVpnRouterStatus = status
        val tunnel = status.activeTunnel ?: getString(R.string.vcs_vpn_status_no_tunnel)
        val interfaces = status.tetherInterfaces.takeIf { it.isNotEmpty() }?.joinToString(", ")
            ?: getString(R.string.vcs_vpn_router_no_interfaces)
        when (status.availability) {
            VpnRouterManager.Availability.ENABLED -> {
                binding.vpnRouterStatus.text = getString(R.string.vcs_vpn_router_on, tunnel, interfaces)
                binding.vpnRouterStatus.setTextColor(Color.parseColor("#86EFAC"))
            }
            VpnRouterManager.Availability.READY -> {
                binding.vpnRouterStatus.text = getString(R.string.vcs_vpn_router_ready, tunnel, interfaces)
                binding.vpnRouterStatus.setTextColor(Color.parseColor("#AFC0CC"))
            }
            VpnRouterManager.Availability.WAITING_FOR_TUNNEL -> {
                binding.vpnRouterStatus.setText(R.string.vcs_vpn_router_waiting_tunnel)
                binding.vpnRouterStatus.setTextColor(Color.parseColor("#FBBF24"))
            }
            VpnRouterManager.Availability.WAITING_FOR_HOTSPOT -> {
                binding.vpnRouterStatus.setText(R.string.vcs_vpn_router_waiting_hotspot)
                binding.vpnRouterStatus.setTextColor(Color.parseColor("#FBBF24"))
            }
            VpnRouterManager.Availability.UNSUPPORTED -> {
                binding.vpnRouterStatus.text = status.detail ?: getString(R.string.vcs_vpn_router_unsupported)
                binding.vpnRouterStatus.setTextColor(Color.parseColor("#FBBF24"))
            }
            VpnRouterManager.Availability.ERROR -> {
                binding.vpnRouterStatus.text = getString(R.string.vcs_vpn_router_error, status.detail ?: "unknown error")
                binding.vpnRouterStatus.setTextColor(Color.parseColor("#F87171"))
            }
        }
        updateVpnRouterButtonState(status)
        updateOpenVpnSettingsButtonState(status)
        updateKillSwitchStatus()
    }

    private fun updateVpnRouterButtonState(status: VpnRouterManager.Status?) {
        binding.vpnRouterButton.setText(
            if (status?.canDisable == true) R.string.vcs_vpn_router_disable else R.string.vcs_vpn_router_enable
        )
        val canToggleRouter = status?.let { it.canEnable || it.canDisable } == true
        binding.vpnRouterButton.isEnabled = !vpnRouterActionRunning && canToggleRouter
        binding.vpnRouterButton.alpha = if (binding.vpnRouterButton.isEnabled) 1f else 0.58f
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
            if (activeHotspot && lastVpnRouterStatus?.availability == VpnRouterManager.Availability.ENABLED) {
                binding.killSwitchStatus.setText(
                    if (protected) {
                        R.string.vcs_hotspot_vpn_router_active
                    } else {
                        R.string.vcs_kill_switch_off_router_active
                    }
                )
                binding.killSwitchStatus.setTextColor(if (protected) Color.parseColor("#86EFAC") else Color.parseColor("#AFC0CC"))
            } else if (activeHotspot) {
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

    private fun updateOpenVpnSettingsButtonState(status: VpnRouterManager.Status? = lastVpnRouterStatus) {
        val routerActive = status?.availability == VpnRouterManager.Availability.ENABLED
        binding.openVpnSettingsButton.isEnabled = !routerActive
        binding.openVpnSettingsButton.isClickable = !routerActive
        binding.openVpnSettingsButton.isFocusable = !routerActive
        binding.openVpnSettingsButton.alpha = if (routerActive) 0.42f else 1f
    }

    private fun openVpnSettings() {
        startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
    }

    private fun openVpnApp(section: String) {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_TUNNEL_SECTION, section))
    }

    private fun showVpnRouterLogoActions() {
        val actions = arrayOf(
            getString(R.string.vcs_vpn_router_logo_action_client_page),
            getString(R.string.vcs_vpn_router_logo_action_add_tunnel)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.vcs_vpn_router_home_actions_title)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openRouterPairingPage()
                    1 -> openVpnMeshAddTunnelFlow()
                }
            }
            .show()
    }

    private fun openVpnMeshAddTunnelFlow() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_TUNNEL_SECTION, MainActivity.TUNNEL_SECTION_VPN_MESH)
                .putExtra(MainActivity.EXTRA_SHOW_ADD_TUNNEL_FLOW, true)
        )
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
        private const val VPN_STATUS_REFRESH_INTERVAL_MS = 2_000L
        private const val DEVICE_HEARTBEAT_INTERVAL_MS = 60_000L
        private const val HOME_PREFS = "vcs_home"
        private const val KEY_HOME_SELECTED_TUNNEL = "selected_tunnel"
    }
}
