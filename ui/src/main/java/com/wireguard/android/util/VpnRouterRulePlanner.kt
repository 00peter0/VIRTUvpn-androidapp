/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

object VpnRouterRulePlanner {
    data class Snapshot(
        val rulesVersion: Int,
        val tunnel: String,
        val downstreams: List<String>,
        val dnsResolvers: List<String>,
        val compatibilityMode: String,
        val uplinks: List<String>,
        val vpnOwnerUid: Int?,
        val vpnProviderUids: List<Int>,
        val localBrowserUids: List<Int>
    ) {
        fun signature(): String {
            return listOf(
                "v$rulesVersion",
                tunnel,
                downstreams.sorted().joinToString(","),
                dnsResolvers.joinToString(","),
                compatibilityMode,
                uplinks.sorted().joinToString(","),
                vpnOwnerUid?.toString().orEmpty(),
                vpnProviderUids.sorted().joinToString(","),
                localBrowserUids.sorted().joinToString(",")
            ).joinToString("|")
        }
    }

    fun needsFullRebuild(
        previousSignature: String?,
        current: Snapshot,
        rulesHealthy: Boolean
    ): Boolean {
        return previousSignature != current.signature() || !rulesHealthy
    }
}
