/*
 * Copyright © 2017-2025 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.activity

import android.os.Bundle
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity.Companion.EXTRA_TUNNEL_SECTION
import com.wireguard.android.activity.MainActivity.Companion.TUNNEL_SECTION_VPN_MESH
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.vcs.VcsAuthGate
import com.wireguard.android.vcs.VcsManagedClient

/**
 * Standalone activity for creating tunnels.
 */
class TunnelCreatorActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!VcsAuthGate.requireSignedIn(this)) return
        setContentView(R.layout.tunnel_creator_activity)
    }

    override fun onSelectedTunnelChanged(oldTunnel: ObservableTunnel?, newTunnel: ObservableTunnel?): Boolean {
        if (newTunnel != null && intent?.getStringExtra(EXTRA_TUNNEL_SECTION) == TUNNEL_SECTION_VPN_MESH) {
            VcsManagedClient.rememberExternalVpnMeshTunnels(this, setOf(newTunnel.name))
        }
        finish()
        return true
    }
}
