/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnRouterRulePlannerTest {
    private val baseline = VpnRouterRulePlanner.Snapshot(
        tunnel = "tun0",
        downstreams = listOf("swlan0"),
        dnsResolvers = listOf("9.9.9.9", "149.112.112.112"),
        uplinks = listOf("rmnet_data0", "rmnet_data1"),
        vpnOwnerUid = 10084,
        vpnProviderUids = listOf(10084, 10123)
    )

    @Test
    fun matchingHealthySnapshotUsesFastPath() {
        assertFalse(
            VpnRouterRulePlanner.needsFullRebuild(
                previousSignature = baseline.signature(),
                current = baseline,
                rulesHealthy = true
            )
        )
    }

    @Test
    fun missingSignatureRequiresFullRebuild() {
        assertTrue(
            VpnRouterRulePlanner.needsFullRebuild(
                previousSignature = null,
                current = baseline,
                rulesHealthy = true
            )
        )
    }

    @Test
    fun unhealthyRulesRequireFullRebuildEvenWhenSignatureMatches() {
        assertTrue(
            VpnRouterRulePlanner.needsFullRebuild(
                previousSignature = baseline.signature(),
                current = baseline,
                rulesHealthy = false
            )
        )
    }

    @Test
    fun tunnelChangeRequiresFullRebuild() {
        val changed = baseline.copy(tunnel = "tun1")
        assertTrue(VpnRouterRulePlanner.needsFullRebuild(baseline.signature(), changed, true))
    }

    @Test
    fun dnsChangeRequiresFullRebuild() {
        val changed = baseline.copy(dnsResolvers = listOf("1.1.1.1", "1.0.0.1"))
        assertTrue(VpnRouterRulePlanner.needsFullRebuild(baseline.signature(), changed, true))
    }

    @Test
    fun downstreamChangeRequiresFullRebuild() {
        val changed = baseline.copy(downstreams = listOf("swlan0", "rndis0"))
        assertTrue(VpnRouterRulePlanner.needsFullRebuild(baseline.signature(), changed, true))
    }

    @Test
    fun uplinkChangeRequiresFullRebuild() {
        val changed = baseline.copy(uplinks = listOf("eth0"))
        assertTrue(VpnRouterRulePlanner.needsFullRebuild(baseline.signature(), changed, true))
    }

    @Test
    fun vpnProviderUidChangeRequiresFullRebuild() {
        val changed = baseline.copy(vpnProviderUids = listOf(10084, 10555))
        assertTrue(VpnRouterRulePlanner.needsFullRebuild(baseline.signature(), changed, true))
    }

    @Test
    fun signatureIsStableForUnorderedInterfaces() {
        val reordered = baseline.copy(
            downstreams = listOf("swlan0"),
            uplinks = listOf("rmnet_data1", "rmnet_data0")
        )
        assertFalse(VpnRouterRulePlanner.needsFullRebuild(baseline.signature(), reordered, true))
    }

    @Test
    fun signatureKeepsDnsPriorityOrder() {
        val reorderedDns = baseline.copy(dnsResolvers = listOf("149.112.112.112", "9.9.9.9"))
        assertNotEquals(baseline.signature(), reorderedDns.signature())
    }
}
