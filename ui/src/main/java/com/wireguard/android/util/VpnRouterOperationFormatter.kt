/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import com.wireguard.android.R

object VpnRouterOperationFormatter {
    fun message(context: Context, status: VpnRouterManager.OperationStatus): String {
        val activeStage = when (status.stage) {
            VpnRouterManager.OperationStage.LOCKING_HOTSPOT -> 0
            VpnRouterManager.OperationStage.DETECTING_TUNNEL -> 1
            VpnRouterManager.OperationStage.APPLYING_DNS -> 2
            VpnRouterManager.OperationStage.APPLYING_FIREWALL -> 3
            VpnRouterManager.OperationStage.VERIFYING_RULES -> 4
            VpnRouterManager.OperationStage.CHECKING_HEALTH -> 5
            VpnRouterManager.OperationStage.FALLING_BACK -> 6
            VpnRouterManager.OperationStage.COMPLETE -> 7
            VpnRouterManager.OperationStage.ERROR -> 8
            VpnRouterManager.OperationStage.IDLE -> -1
        }
        val labels = listOf(
            context.getString(R.string.vcs_vpn_router_operation_locking),
            context.getString(R.string.vcs_vpn_router_operation_detecting),
            context.getString(R.string.vcs_vpn_router_operation_dns),
            context.getString(R.string.vcs_vpn_router_operation_firewall),
            context.getString(R.string.vcs_vpn_router_operation_verify),
            context.getString(R.string.vcs_vpn_router_operation_health),
            context.getString(R.string.vcs_vpn_router_operation_fallback)
        )
        val prefix = labels.mapIndexed { index, label ->
            val marker = when {
                activeStage > index -> "[done]"
                activeStage == index -> "[now]"
                else -> "[wait]"
            }
            "$marker $label"
        }.joinToString("\n")
        val detail = status.detail?.takeIf { it.isNotBlank() }
            ?: context.getString(R.string.vcs_vpn_router_operation_default_detail)
        return "$prefix\n\n$detail"
    }
}
