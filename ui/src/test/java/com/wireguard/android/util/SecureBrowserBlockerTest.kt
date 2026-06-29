/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBrowserBlockerTest {
    @Test
    fun blocksKnownTrackerSubresources() {
        assertTrue(SecureBrowserBlocker.shouldBlockHost("www.google-analytics.com", false))
        assertTrue(SecureBrowserBlocker.shouldBlockHost("cdn.doubleclick.net", false))
    }

    @Test
    fun allowsTopLevelNavigationToBlockedHost() {
        assertFalse(SecureBrowserBlocker.shouldBlockHost("www.google-analytics.com", true))
    }

    @Test
    fun allowsOrdinarySubresources() {
        assertFalse(SecureBrowserBlocker.shouldBlockHost("example.com", false))
    }
}
