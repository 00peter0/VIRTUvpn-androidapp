/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.net.Uri
import androidx.core.net.toUri
import java.util.Locale

object SecureBrowserUrlPolicy {
    fun normalizeHttpsUrl(value: String): String? {
        return normalizeUrl(value, defaultScheme = "https") { uri ->
            uri.scheme?.lowercase(Locale.ROOT) == "https" && !uri.host.isNullOrBlank()
        }
    }

    fun normalizeHttpOrHttpsUrl(value: String, isAllowed: (Uri) -> Boolean): String? {
        return normalizeUrl(value, defaultScheme = "http") { uri ->
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank() && isAllowed(uri)
        }
    }

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

    private fun normalizeUrl(value: String, defaultScheme: String, isAllowed: (Uri) -> Boolean): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val withScheme = if (trimmed.contains("://")) trimmed else "$defaultScheme://$trimmed"
        val uri = withScheme.toUri()
        if (!isAllowed(uri)) return null
        return uri.toString()
    }
}
