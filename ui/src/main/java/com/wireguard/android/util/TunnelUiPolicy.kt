/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

/** UI-only policy: Managed Access assignments are removed through synchronization. */
object TunnelUiPolicy {
    // Retain the manual-delete implementation for a future reviewed workflow.
    // Re-enabling also requires fixing positional selection in TunnelListFragment.
    @JvmField
    val manualDeletionEnabled: Boolean = false
}
