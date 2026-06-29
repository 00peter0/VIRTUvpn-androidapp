/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.net.Uri
import java.util.Locale

object SecureBrowserBlocker {
    fun shouldBlock(uri: Uri, isTopLevel: Boolean): Boolean {
        return shouldBlockHost(uri.host, isTopLevel)
    }

    internal fun shouldBlockHost(rawHost: String?, isTopLevel: Boolean): Boolean {
        if (isTopLevel) return false
        val host = rawHost
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?: return false
        if (host.isBlank()) return false
        if (host in EXACT_BLOCKED_HOSTS) return true
        return BLOCKED_SUFFIXES.any { suffix -> host == suffix || host.endsWith(".$suffix") }
    }

    private val EXACT_BLOCKED_HOSTS = setOf(
        "ad.doubleclick.net",
        "analytics.google.com",
        "app-measurement.com",
        "connect.facebook.net",
        "googleads.g.doubleclick.net",
        "googletagmanager.com",
        "googletagservices.com",
        "graph.facebook.com",
        "pagead2.googlesyndication.com",
        "partner.googleadservices.com",
        "sb.scorecardresearch.com",
        "ssl.google-analytics.com",
        "static.ads-twitter.com",
        "www.google-analytics.com",
        "www.googletagmanager.com"
    )

    private val BLOCKED_SUFFIXES = setOf(
        "2mdn.net",
        "adform.net",
        "adnxs.com",
        "adsafeprotected.com",
        "adsrvr.org",
        "amazon-adsystem.com",
        "bat.bing.com",
        "bluekai.com",
        "bounceexchange.com",
        "branch.io",
        "chartbeat.com",
        "clarity.ms",
        "criteo.com",
        "criteo.net",
        "demdex.net",
        "doubleclick.net",
        "facebook.net",
        "flashtalking.com",
        "google-analytics.com",
        "googleadservices.com",
        "googlesyndication.com",
        "hotjar.com",
        "hotjar.io",
        "mathtag.com",
        "mixpanel.com",
        "moatads.com",
        "mouseflow.com",
        "newrelic.com",
        "omtrdc.net",
        "optimizely.com",
        "outbrain.com",
        "pubmatic.com",
        "quantserve.com",
        "scorecardresearch.com",
        "segment.io",
        "serving-sys.com",
        "taboola.com",
        "teads.tv",
        "tiqcdn.com",
        "zedo.com"
    )
}
