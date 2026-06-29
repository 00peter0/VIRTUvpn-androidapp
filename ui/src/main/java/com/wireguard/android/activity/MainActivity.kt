/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import com.wireguard.android.R
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.android.fragment.TunnelEditorFragment
import com.wireguard.android.fragment.TunnelListFragment
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.vcs.VcsAuthGate
import com.wireguard.android.vcs.VcsManagedClient
import com.wireguard.android.util.VpnRouterAttestation
import kotlinx.coroutines.launch

class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null
    private var enrollmentIntentPending = false

    private fun handleBackPressed() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        if (isTwoPaneLayout && backStackEntries <= 1) {
            finish()
            return
        }
        if (backStackEntries >= 1)
            supportFragmentManager.popBackStack()
        if (backStackEntries == 1)
            selectedTunnel = null
    }

    override fun onBackStackChanged() {
        val backStackEntries = supportFragmentManager.backStackEntryCount
        backPressedCallback?.isEnabled = backStackEntries >= 1
        if (actionBar == null) return
        val minBackStackEntries = if (isTwoPaneLayout) 2 else 1
        actionBar!!.setDisplayHomeAsUpEnabled(backStackEntries >= minBackStackEntries)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!allowsUnsignedEnrollment(intent) && !VcsAuthGate.requireSignedIn(this)) return
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()

        actionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.ic_logo)
            title = "  ${titleForTunnelSection(intent?.getStringExtra(EXTRA_TUNNEL_SECTION))}"
        }
        if (shouldShowHomeOnAppStart(intent, savedInstanceState)) {
            showHomePage()
        }
        handleVcsEnrollmentIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!enrollmentIntentPending) VcsAuthGate.requireSignedIn(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!allowsUnsignedEnrollment(intent) && !VcsAuthGate.requireSignedIn(this)) return
        if (shouldShowHomeOnAppStart(intent, null)) {
            showHomePage()
        }
        actionBar?.title = "  ${titleForTunnelSection(intent.getStringExtra(EXTRA_TUNNEL_SECTION))}"
        refreshTunnelSection()
        handleVcsEnrollmentIntent(intent)
    }

    private fun allowsUnsignedEnrollment(intent: Intent?): Boolean {
        return VcsManagedClient.isEnrollmentUri(intent?.data)
    }

    fun titleForTunnelSection(section: String?): String {
        return when (section) {
            TUNNEL_SECTION_VPN_MESH -> getString(R.string.vcs_home_vpn_mesh)
            TUNNEL_SECTION_AGENT_GATEWAY -> getString(R.string.vcs_home_agent_gateway)
            TUNNEL_SECTION_MANAGED_ACCESS -> getString(R.string.vcs_home_managed_access)
            else -> getString(R.string.vcs_tunnels_title)
        }
    }

    private fun shouldShowHomeOnAppStart(intent: Intent?, savedInstanceState: Bundle?): Boolean {
        if (savedInstanceState != null || intent?.data != null) return false
        if (intent?.hasExtra("selected_tunnel") == true) return false
        if (intent?.hasExtra(EXTRA_TUNNEL_SECTION) == true) return false
        return intent?.action == null || intent.action == Intent.ACTION_MAIN
    }

    private fun showHomePage() {
        selectedTunnel = null
        supportFragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
    }

    private fun refreshTunnelSection() {
        (supportFragmentManager.findFragmentByTag("LIST") as? TunnelListFragment)?.refreshTunnelFilter()
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

    private fun handleVcsEnrollmentIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (VpnRouterAttestation.isPairingUri(uri)) {
            if (VpnRouterAttestation.importPairingUri(this, uri)) {
                Toast.makeText(this, R.string.vcs_secure_browser_router_pair_success, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(this, R.string.vcs_secure_browser_router_pair_error, Toast.LENGTH_LONG).show()
            }
            finishEnrollmentFlow()
            return
        }
        if (!VcsManagedClient.isEnrollmentUri(uri)) return
        enrollmentIntentPending = true
        lifecycleScope.launch {
            try {
                val result = VcsManagedClient.handleEnrollmentUri(this@MainActivity, uri) ?: return@launch
                Toast.makeText(this@MainActivity, enrollResultMessage(result), Toast.LENGTH_LONG).show()
            } catch (e: Throwable) {
                Toast.makeText(this@MainActivity, getString(R.string.vcs_enroll_error, e.message ?: e.javaClass.simpleName), Toast.LENGTH_LONG).show()
            } finally {
                finishEnrollmentFlow()
            }
        }
    }

    private fun finishEnrollmentFlow() {
        enrollmentIntentPending = false
        startActivity(
            Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_activity, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressedDispatcher.onBackPressed()
                true
            }
            R.id.menu_action_edit -> {
                supportFragmentManager.commit {
                    replace(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelEditorFragment())
                    setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                    addToBackStack(null)
                }
                true
            }
            R.id.menu_action_save -> false
            R.id.menu_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSelectedTunnelChanged(
        oldTunnel: ObservableTunnel?,
        newTunnel: ObservableTunnel?
    ): Boolean {
        val fragmentManager = supportFragmentManager
        if (fragmentManager.isStateSaved) {
            return false
        }
        val backStackEntries = fragmentManager.backStackEntryCount
        if (newTunnel == null) {
            fragmentManager.popBackStackImmediate(0, FragmentManager.POP_BACK_STACK_INCLUSIVE)
            return true
        }
        if (backStackEntries == 2) {
            fragmentManager.popBackStackImmediate()
        } else if (backStackEntries == 0) {
            fragmentManager.commit {
                add(if (isTwoPaneLayout) R.id.detail_container else R.id.list_detail_container, TunnelDetailFragment())
                setTransition(FragmentTransaction.TRANSIT_FRAGMENT_FADE)
                addToBackStack(null)
            }
        }
        return true
    }

    companion object {
        const val EXTRA_TUNNEL_SECTION = "com.wireguard.android.extra.TUNNEL_SECTION"
        const val TUNNEL_SECTION_VPN_MESH = "vpn_mesh"
        const val TUNNEL_SECTION_AGENT_GATEWAY = "agent_gateway"
        const val TUNNEL_SECTION_MANAGED_ACCESS = "managed_access"
    }
}
