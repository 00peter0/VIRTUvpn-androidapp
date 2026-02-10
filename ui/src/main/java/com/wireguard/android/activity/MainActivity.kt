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
import androidx.activity.OnBackPressedCallback
import androidx.activity.addCallback
import androidx.appcompat.app.ActionBar
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import androidx.fragment.app.commit
import com.wireguard.android.R
import com.wireguard.android.fragment.TunnelDetailFragment
import com.wireguard.android.fragment.TunnelEditorFragment
import com.wireguard.android.model.ObservableTunnel

class MainActivity : BaseActivity(), FragmentManager.OnBackStackChangedListener {
    private var actionBar: ActionBar? = null
    private var isTwoPaneLayout = false
    private var backPressedCallback: OnBackPressedCallback? = null

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
        setContentView(R.layout.main_activity)
        actionBar = supportActionBar
        isTwoPaneLayout = findViewById<View?>(R.id.master_detail_wrapper) != null
        supportFragmentManager.addOnBackStackChangedListener(this)
        backPressedCallback = onBackPressedDispatcher.addCallback(this) { handleBackPressed() }
        onBackStackChanged()

        actionBar?.apply {
            setDisplayShowHomeEnabled(true)
            setIcon(R.drawable.ic_logo)
            title = "  Virtu VPN"
        }
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
            R.id.menu_web_dashboard -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://virtuvpn.ch/dashboard")))
                true
            }
            R.id.menu_my_account -> {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://virtuvpn.ch/account")))
                true
            }
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
}
