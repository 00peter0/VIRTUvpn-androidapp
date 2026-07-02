/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import java.util.Locale

object SecureBrowserUrlPolicy {
    fun isAllowed(scheme: String?, host: String?, isTopLevel: Boolean): Boolean {
        return when (scheme?.lowercase(Locale.ROOT)) {
            "https", "wss" -> !host.isNullOrBlank() && (isTopLevel || !isPrivateOrLocalHost(host))
            "about", "blob" -> true
            else -> false
        }
    }

    internal fun isPrivateOrLocalHost(rawHost: String?): Boolean {
        return PrivateAddressClassifier.isPrivateOrLocalHost(rawHost)
    }
}
