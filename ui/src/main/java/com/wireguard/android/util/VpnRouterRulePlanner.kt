/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

object VpnRouterRulePlanner {
    data class Snapshot(
        val tunnel: String,
        val downstreams: List<String>,
        val dnsResolvers: List<String>,
        val uplinks: List<String>,
        val vpnOwnerUid: Int?,
        val vpnProviderUids: List<Int>
    ) {
        fun signature(): String {
            return listOf(
                tunnel,
                downstreams.sorted().joinToString(","),
                dnsResolvers.joinToString(","),
                uplinks.sorted().joinToString(","),
                vpnOwnerUid?.toString().orEmpty(),
                vpnProviderUids.sorted().joinToString(",")
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
