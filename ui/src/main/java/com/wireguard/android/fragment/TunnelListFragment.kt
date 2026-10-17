/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.fragment

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.google.zxing.qrcode.QRCodeReader
import com.wireguard.android.util.NativeQrScanContract
import com.wireguard.android.Application
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.activity.PermissionsActivity
import com.wireguard.android.util.AppPermissionAccess
import com.wireguard.android.activity.TunnelCreatorActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.databinding.ObservableKeyedArrayList
import com.wireguard.android.databinding.ObservableKeyedRecyclerViewAdapter.RowConfigurationHandler
import com.wireguard.android.databinding.TunnelListFragmentBinding
import com.wireguard.android.databinding.TunnelListItemBinding
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.ErrorMessages
import com.wireguard.android.util.PingManager
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.android.util.TunnelUiPolicy
import com.wireguard.android.util.VcsDialogs
import com.wireguard.android.util.VpnRouterAttestation
import com.wireguard.android.vcs.VcsManagedClient
import com.wireguard.android.vcs.ManagedWebTerminalLauncher
import com.wireguard.android.widget.MultiselectableRelativeLayout
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TunnelListFragment : BaseFragment() {
    private val actionModeListener = ActionModeListener()
    private var actionMode: ActionMode? = null
    private var backPressedCallback: OnBackPressedCallback? = null
    private var binding: TunnelListFragmentBinding? = null
    private var displayedTunnels: ObservableKeyedArrayList<String, ObservableTunnel>? = null
    private var pingActive = false
    private var managedSyncJob: kotlinx.coroutines.Job? = null
    private var pingLoopJob: kotlinx.coroutines.Job? = null
    private var managedSyncActive = false

    fun hasWebTerminal(tunnel: ObservableTunnel?): Boolean {
        if (tunnel == null) return false
        val ctx = context ?: return false
        return VcsManagedClient.canUseLocalTunnel(ctx, tunnel.name) &&
            VcsManagedClient.webTerminalForTunnel(ctx, tunnel.name) != null
    }

    fun onWebTerminalClick(view: View, tunnel: ObservableTunnel?) {
        if (tunnel == null) return
        val ctx = context ?: return
        if (!hasWebTerminal(tunnel)) return
        val terminal = VcsManagedClient.webTerminalForTunnel(ctx, tunnel.name) ?: return
        view.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try { ManagedWebTerminalLauncher.open(ctx, terminal) }
            finally { view.isEnabled = true }
        }
    }

    private val tunnelFileImportResultLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { data ->
        if (data == null) return@registerForActivityResult
        if (!isOtherTunnelsSection()) return@registerForActivityResult
        val activity = activity ?: return@registerForActivityResult
        val contentResolver = activity.contentResolver ?: return@registerForActivityResult
        activity.lifecycleScope.launch {
            if (QrCodeFromFileScanner.validContentType(contentResolver, data)) {
                try {
                    val qrCodeFromFileScanner = QrCodeFromFileScanner(contentResolver, QRCodeReader())
                    val result = qrCodeFromFileScanner.scan(data)
                    TunnelImporter.importTunnel(parentFragmentManager, result.text, { showSnackbar(it) })
                } catch (e: Exception) {
                    val error = ErrorMessages[e]
                    val message = Application.get().resources.getString(R.string.import_error, error)
                    Log.e(TAG, message, e)
                    showSnackbar(message)
                }
            } else {
                TunnelImporter.importTunnel(contentResolver, data, { showSnackbar(it) }) {
                    refreshTunnelFilter()
                }
            }
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && isOtherTunnelsSection() && AppPermissionAccess.cameraAllowed(requireContext())) scanTunnelQr()
        else if (isOtherTunnelsSection()) showSnackbar(getString(R.string.vcs_permissions_requested_missing))
    }

    private val qrImportResultLauncher = registerForActivityResult(NativeQrScanContract()) { result ->
        if (!isOtherTunnelsSection()) return@registerForActivityResult
        val qrCode = result
        val activity = activity
        if (qrCode != null && activity != null) {
            activity.lifecycleScope.launch {
                val routerPairing = VpnRouterAttestation.parsePairingValue(qrCode)
                if (routerPairing != null || qrCode.startsWith("virtuvpn://router-pair") || qrCode.contains("/router/pair#")) {
                    val pairing = routerPairing
                    if (pairing != null) {
                        confirmRouterPairing(pairing)
                    } else {
                        showSnackbar(getString(R.string.vcs_secure_browser_router_pair_error))
                    }
                } else if (qrCode.contains("vcs_android_enrollment") || qrCode.startsWith("virtuvpn://enroll")) {
                    try {
                        confirmEnrollment(VcsManagedClient.parseEnrollmentPayloadForConfirmation(qrCode))
                    } catch (e: Throwable) {
                        showSnackbar(getString(R.string.vcs_enroll_error, e.message ?: e.javaClass.simpleName))
                    }
                } else {
                    TunnelImporter.importTunnel(parentFragmentManager, qrCode, { showSnackbar(it) })
                }
            }
        }
    }

    private fun confirmRouterPairing(pairing: VpnRouterAttestation.Pairing) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vcs_secure_browser_router_pair_title)
            .setMessage(getString(R.string.vcs_secure_browser_router_pair_confirm, pairing.routerId.take(8)))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_secure_browser_router_pair_action) { _, _ ->
                VpnRouterAttestation.importPairing(requireContext(), pairing)
                showSnackbar(getString(R.string.vcs_secure_browser_router_pair_success))
            }
            .show()
    }

    private fun confirmEnrollment(request: VcsManagedClient.EnrollmentRequest) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.vcs_enroll_confirm_title)
            .setMessage(getString(R.string.vcs_enroll_confirm_message, request.host))
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.vcs_enroll_submit) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val result = VcsManagedClient.completeEnrollmentRequest(requireContext(), request)
                        showSnackbar(enrollResultMessage(result))
                    } catch (e: Throwable) {
                        showSnackbar(getString(R.string.vcs_enroll_error, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            .show()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (TunnelUiPolicy.manualDeletionEnabled && savedInstanceState != null) {
            val checkedItems = savedInstanceState.getIntegerArrayList(CHECKED_ITEMS)
            if (checkedItems != null) {
                for (i in checkedItems) actionModeListener.setItemChecked(i, true)
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        super.onCreateView(inflater, container, savedInstanceState)
        binding = TunnelListFragmentBinding.inflate(inflater, container, false)
        binding?.apply {
            fastestButton.setOnClickListener { onFastestButtonClicked() }
            parentFragmentManager.setFragmentResultListener(ConfigNamingDialogFragment.RESULT_IMPORTED, viewLifecycleOwner) { _, _ ->
                refreshTunnelFilter()
            }

            // Local imports are available only in Other tunnels; managed sections keep sync.
            createFab.setOnClickListener {
                if (isOtherTunnelsSection()) {
                    showAddTunnelChoices()
                    return@setOnClickListener
                }
                val ctx = requireContext()
                showSnackbar(getString(R.string.vcs_sync_running))
                lifecycleScope.launch {
                    try {
                        val result = VcsManagedClient.syncManagedTunnels(ctx)
                        VcsManagedClient.reportCurrentStates(ctx)
                        showSnackbar(
                            when {
                                result.skippedRunning > 0 -> resources.getQuantityString(R.plurals.vcs_sync_skipped_running, result.skippedRunning, result.skippedRunning)
                                result.pendingBundleAssignments > 0 -> getString(R.string.vcs_sync_bundle_pending)
                                result.assigned == 0 -> getString(R.string.vcs_sync_no_assignments)
                                result.imported == 0 -> resources.getQuantityString(R.plurals.vcs_sync_checked_no_imports, result.assigned, result.assigned)
                                else -> resources.getQuantityString(R.plurals.vcs_sync_success, result.assigned, result.imported, result.assigned)
                            }
                        )
                    } catch (e: Throwable) {
                        showSnackbar(getString(R.string.vcs_sync_error, e.message ?: e.javaClass.simpleName))
                    }
                }
            }
            executePendingBindings()
        }
        backPressedCallback = requireActivity().onBackPressedDispatcher.addCallback(this) { actionMode?.finish() }
        backPressedCallback?.isEnabled = false

        return binding?.root
    }

    private fun currentTunnelSection(): String? {
        return requireActivity().intent?.getStringExtra(MainActivity.EXTRA_TUNNEL_SECTION)
    }

    private fun isOtherTunnelsSection(): Boolean =
        currentTunnelSection() == MainActivity.TUNNEL_SECTION_AGENT_GATEWAY

    private fun showAddTunnelChoices() {
        if (!isOtherTunnelsSection()) return
        val choices = mutableListOf(getString(R.string.create_empty), getString(R.string.create_from_file))
        if (requireActivity().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY))
            choices += getString(R.string.create_from_qr_code)
        VcsDialogs.showChoice(requireContext(), getString(R.string.create_tunnel), choices) { choice ->
            if (!isOtherTunnelsSection()) return@showChoice
            when (choice) {
                0 -> startActivity(Intent(requireContext(), TunnelCreatorActivity::class.java)
                    .putExtra(MainActivity.EXTRA_TUNNEL_SECTION, MainActivity.TUNNEL_SECTION_AGENT_GATEWAY))
                1 -> tunnelFileImportResultLauncher.launch("*/*")
                2 -> scanTunnelQr()
            }
        }
    }

    private fun scanTunnelQr() {
        if (!AppPermissionAccess.cameraAllowed(requireContext())) {
            cameraPermissionLauncher.launch(PermissionsActivity.intent(requireContext(), PermissionsActivity.CAMERA, requestOnOpen = true))
            return
        }
        qrImportResultLauncher.launch(getString(R.string.import_from_qr_code))
    }

    // ── Smart Server Picker ──

    private fun onFastestButtonClicked() {
        lifecycleScope.launch {
            val tunnels = displayedTunnels ?: Application.getTunnelManager().getTunnels()
            val fastest = PingManager.findFastest(tunnels)
            if (fastest != null) {
                selectedTunnel = fastest
                try {
                    if (fastest.state != Tunnel.State.UP) {
                        fastest.setStateAsync(Tunnel.State.UP)
                    }
                    showSnackbar("⚡ ${fastest.name} • ${fastest.latencyMs} ms")
                } catch (e: Throwable) {
                    val error = ErrorMessages[e]
                    showSnackbar(getString(R.string.toggle_error, error))
                }
            } else {
                showSnackbar(getString(R.string.ping_measuring))
                // Trigger immediate ping if not measured yet
                startPingTest()
            }
        }
    }

    private fun startPingTest() {
        lifecycleScope.launch {
            try {
                val tunnels = displayedTunnels ?: Application.getTunnelManager().getTunnels()
                PingManager.pingAll(tunnels)
            } catch (e: Throwable) {
                Log.e(TAG, "Ping test failed", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pingActive = true
        managedSyncActive = true
        refreshTunnelFilter()
        // Initial ping immediately
        startPingTest()
        startManagedSyncLoop()
        // Refresh every 30 seconds
        pingLoopJob?.cancel()
        pingLoopJob = lifecycleScope.launch {
            while (pingActive) {
                delay(30_000)
                if (pingActive) startPingTest()
            }
        }
    }

    override fun onStop() {
        pingActive = false
        managedSyncActive = false
        managedSyncJob?.cancel()
        pingLoopJob?.cancel()
        super.onStop()
    }

    private fun startManagedSyncLoop() {
        managedSyncJob?.cancel()
        managedSyncJob = lifecycleScope.launch {
            while (managedSyncActive) {
                try {
                    if (VcsManagedClient.hasAccountSession(requireContext())) {
                        VcsManagedClient.syncManagedTunnels(requireContext())
                        VcsManagedClient.reportCurrentStates(requireContext())
                    }
                    refreshTunnelFilter()
                } catch (e: Throwable) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.d(TAG, "Managed access sync skipped", e)
                }
                delay(15_000)
            }
        }
    }

    private fun syncResultMessage(result: VcsManagedClient.SyncResult): String {
        return when {
            result.failures > 0 -> getString(R.string.vcs_sync_partial, result.failures)
            result.skippedRunning > 0 -> resources.getQuantityString(R.plurals.vcs_sync_skipped_running, result.skippedRunning, result.skippedRunning)
            result.pendingBundleAssignments > 0 -> getString(R.string.vcs_sync_bundle_pending)
            result.assigned == 0 -> getString(R.string.vcs_sync_no_assignments)
            result.imported == 0 -> resources.getQuantityString(R.plurals.vcs_sync_checked_no_imports, result.assigned, result.assigned)
            else -> resources.getQuantityString(R.plurals.vcs_sync_success, result.assigned, result.imported, result.assigned)
        }
    }

    private fun enrollResultMessage(result: VcsManagedClient.EnrollResult): String {
        return getString(R.string.vcs_enroll_success, result.deviceName ?: getString(R.string.vcs_enroll_this_device))
    }

    // ── Lifecycle ──

    override fun onDestroyView() {
        binding = null
        super.onDestroyView()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (TunnelUiPolicy.manualDeletionEnabled) {
            outState.putIntegerArrayList(CHECKED_ITEMS, actionModeListener.getCheckedItems())
        } else {
            outState.remove(CHECKED_ITEMS)
        }
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?) {
        binding ?: return
        lifecycleScope.launch {
            val tunnels = Application.getTunnelManager().getTunnels()
            val visibleTunnels = displayedTunnels ?: tunnels
            if (newTunnel != null) viewForTunnel(newTunnel, visibleTunnels)?.setSingleSelected(true)
            if (oldTunnel != null) viewForTunnel(oldTunnel, visibleTunnels)?.setSingleSelected(false)
        }
    }

    private fun openTunnelDetail(tunnel: ObservableTunnel) {
        if (selectedTunnel == tunnel) selectedTunnel = null
        selectedTunnel = tunnel
    }

    private fun onTunnelDeletionFinished(count: Int, throwable: Throwable?) {
        val message: String
        val ctx = activity ?: Application.get()
        if (throwable == null) {
            message = ctx.resources.getQuantityString(R.plurals.delete_success, count, count)
        } else {
            val error = ErrorMessages[throwable]
            message = ctx.resources.getQuantityString(R.plurals.delete_error, count, count, error)
            Log.e(TAG, message, throwable)
        }
        showSnackbar(message)
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        binding ?: return
        binding!!.fragment = this
        lifecycleScope.launch { refreshTunnelFilter() }
        if (requireActivity().intent?.getBooleanExtra(MainActivity.EXTRA_SHOW_ADD_TUNNEL_FLOW, false) == true) {
            requireActivity().intent?.removeExtra(MainActivity.EXTRA_SHOW_ADD_TUNNEL_FLOW)
            binding!!.root.post { showAddTunnelChoices() }
        }
        binding!!.rowConfigurationHandler = object : RowConfigurationHandler<TunnelListItemBinding, ObservableTunnel> {
            override fun onConfigureRow(binding: TunnelListItemBinding, item: ObservableTunnel, position: Int) {
                binding.fragment = this@TunnelListFragment
                val openClick = View.OnClickListener {
                    if (!TunnelUiPolicy.manualDeletionEnabled || actionMode == null) {
                        openTunnelDetail(item)
                    } else {
                        actionModeListener.toggleItemChecked(position)
                    }
                }
                val deleteLongClick = if (TunnelUiPolicy.manualDeletionEnabled) {
                    View.OnLongClickListener {
                        actionModeListener.toggleItemChecked(position)
                        true
                    }
                } else null
                binding.root.setOnClickListener(openClick)
                binding.tunnelListItem.setOnClickListener(openClick)
                binding.tunnelName.setOnClickListener(openClick)
                binding.chevron.setOnClickListener(openClick)
                binding.root.setOnLongClickListener(deleteLongClick)
                binding.tunnelListItem.setOnLongClickListener(deleteLongClick)
                binding.tunnelName.setOnLongClickListener(deleteLongClick)
                binding.chevron.setOnLongClickListener(deleteLongClick)
                binding.root.isLongClickable = TunnelUiPolicy.manualDeletionEnabled
                binding.tunnelListItem.isLongClickable = TunnelUiPolicy.manualDeletionEnabled
                binding.tunnelName.isLongClickable = TunnelUiPolicy.manualDeletionEnabled
                binding.chevron.isLongClickable = TunnelUiPolicy.manualDeletionEnabled
                val displayName = VcsManagedClient.displayNameForLocalTunnel(requireContext(), item.name)
                binding.tunnelName.text = displayName ?: item.name
                if (TunnelUiPolicy.manualDeletionEnabled && actionMode != null)
                    binding.tunnelListItem.setMultiSelected(actionModeListener.checkedItems.contains(position))
                else
                    binding.tunnelListItem.setSingleSelected(selectedTunnel == item)
            }
        }
    }

    private fun showSnackbar(message: CharSequence) {
        val binding = binding
        if (binding != null)
            Snackbar.make(binding.mainContainer, message, Snackbar.LENGTH_LONG)
                .setAnchorView(binding.createFab)
                .show()
        else
            Toast.makeText(activity ?: Application.get(), message, Toast.LENGTH_SHORT).show()
    }

    fun refreshTunnelFilter() {
        val activeBinding = binding ?: return
        lifecycleScope.launch {
            val allTunnels = Application.getTunnelManager().getTunnels()
            val section = requireActivity().intent?.getStringExtra(MainActivity.EXTRA_TUNNEL_SECTION)
            val sectionTitle = (requireActivity() as? MainActivity)?.titleForTunnelSection(section)
                ?: getString(R.string.vcs_tunnels_title)
            val names = VcsManagedClient.localTunnelNamesForSection(requireContext(), section)
            val scope = VcsManagedClient.tunnelAccountScope(requireContext())
            val otherTunnels = section == MainActivity.TUNNEL_SECTION_AGENT_GATEWAY
            val signedIn = VcsManagedClient.hasAccountSession(requireContext())
            val filtered = ObservableKeyedArrayList<String, ObservableTunnel>().apply {
                addAll(allTunnels.filter {
                    scope.permits(it.name) && (if (otherTunnels) scope.isIndependent(it.name) || (signedIn && it.name in names)
                        else section.isNullOrBlank() || it.name in names)
                })
            }
            displayedTunnels = filtered
            activeBinding.tunnels = filtered
            activeBinding.sectionTitle.text = sectionTitle
            activeBinding.otherTunnelsInfo.visibility = if (otherTunnels) View.VISIBLE else View.GONE
            activeBinding.createFab.contentDescription = getString(if (otherTunnels) R.string.create_tunnel else R.string.vcs_sync_now)
            val pendingManagedAccess = if (section == MainActivity.TUNNEL_SECTION_MANAGED_ACCESS || section == MainActivity.TUNNEL_SECTION_MANAGED_ACCESS_VPN_CLUSTER) {
                VcsManagedClient.pendingManagedAccessAssignments(requireContext())
            } else {
                0
            }
            activeBinding.emptyStateText.text = if (pendingManagedAccess > 0 && filtered.isEmpty()) {
                getString(R.string.vcs_managed_access_pending_empty)
            } else if (section == MainActivity.TUNNEL_SECTION_SINGLE_SERVER) {
                getString(R.string.vcs_single_server_empty)
            } else if (otherTunnels) {
                getString(R.string.vcs_other_tunnels_empty)
            } else {
                getString(R.string.tunnel_list_placeholder)
            }
            activeBinding.fastestButton.visibility =
                if (section == MainActivity.TUNNEL_SECTION_VPN_MESH && filtered.isNotEmpty()) View.VISIBLE else View.GONE
            activeBinding.tunnelList.adapter?.notifyDataSetChanged()
        }
    }

    private fun viewForTunnel(tunnel: ObservableTunnel, tunnels: List<*>): MultiselectableRelativeLayout? {
        return binding?.tunnelList?.findViewHolderForAdapterPosition(tunnels.indexOf(tunnel))?.itemView as? MultiselectableRelativeLayout
    }

    // ── Action Mode (delete/select) ──

    private inner class ActionModeListener : ActionMode.Callback {
        val checkedItems: MutableCollection<Int> = HashSet()
        private var resources: Resources? = null

        fun getCheckedItems(): ArrayList<Int> = ArrayList(checkedItems)

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            if (!TunnelUiPolicy.manualDeletionEnabled) return false
            return when (item.itemId) {
                R.id.menu_action_delete -> {
                    val activity = activity ?: return true
                    val copyCheckedItems = HashSet(checkedItems)
                    binding?.createFab?.apply {
                        visibility = View.VISIBLE
                        scaleX = 1f
                        scaleY = 1f
                    }
                    activity.lifecycleScope.launch {
                        try {
                            val tunnels = displayedTunnels ?: Application.getTunnelManager().getTunnels()
                            val tunnelsToDelete = ArrayList<ObservableTunnel>()
                            for (position in copyCheckedItems) tunnelsToDelete.add(tunnels[position])
                            val futures = tunnelsToDelete.map { async(SupervisorJob()) { it.deleteAsync() } }
                            onTunnelDeletionFinished(futures.awaitAll().size, null)
                        } catch (e: Throwable) {
                            onTunnelDeletionFinished(0, e)
                        }
                    }
                    checkedItems.clear()
                    mode.finish()
                    true
                }
                R.id.menu_action_select_all -> {
                    lifecycleScope.launch {
                        val tunnels = displayedTunnels ?: Application.getTunnelManager().getTunnels()
                        for (i in 0 until tunnels.size) setItemChecked(i, true)
                    }
                    true
                }
                else -> false
            }
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            if (!TunnelUiPolicy.manualDeletionEnabled) return false
            actionMode = mode
            backPressedCallback?.isEnabled = true
            if (activity != null) resources = activity!!.resources
            animateFab(binding?.createFab, false)
            mode.menuInflater.inflate(R.menu.tunnel_list_action_mode, menu)
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            actionMode = null
            backPressedCallback?.isEnabled = false
            resources = null
            animateFab(binding?.createFab, true)
            checkedItems.clear()
            binding?.tunnelList?.adapter?.notifyDataSetChanged()
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            updateTitle(mode)
            return false
        }

        fun setItemChecked(position: Int, checked: Boolean) {
            if (!TunnelUiPolicy.manualDeletionEnabled) return
            if (checked) checkedItems.add(position) else checkedItems.remove(position)
            val adapter = if (binding == null) null else binding!!.tunnelList.adapter
            if (actionMode == null && checkedItems.isNotEmpty() && activity != null) {
                (activity as AppCompatActivity).startSupportActionMode(this)
            } else if (actionMode != null && checkedItems.isEmpty()) {
                actionMode!!.finish()
            }
            adapter?.notifyItemChanged(position)
            updateTitle(actionMode)
        }

        fun toggleItemChecked(position: Int) = setItemChecked(position, !checkedItems.contains(position))

        private fun updateTitle(mode: ActionMode?) {
            if (mode == null) return
            val count = checkedItems.size
            mode.title = if (count == 0) "" else resources!!.getQuantityString(R.plurals.delete_title, count, count)
        }

        private fun animateFab(view: View?, show: Boolean) {
            view ?: return
            val animation = AnimationUtils.loadAnimation(
                context, if (show) R.anim.scale_up else R.anim.scale_down
            )
            animation.setAnimationListener(object : Animation.AnimationListener {
                override fun onAnimationRepeat(animation: Animation?) {}
                override fun onAnimationEnd(animation: Animation?) { if (!show) view.visibility = View.GONE }
                override fun onAnimationStart(animation: Animation?) { if (show) view.visibility = View.VISIBLE }
            })
            view.startAnimation(animation)
        }
    }

    companion object {
        private const val CHECKED_ITEMS = "CHECKED_ITEMS"
        private const val TAG = "WireGuard/TunnelListFragment"
    }
}
