/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureBrowserUrlPolicyTest {
    @Test
    fun allowsTopLevelHttpsToPublicAndPrivateHosts() {
        assertTrue(SecureBrowserUrlPolicy.isAllowed("https", "example.com", isTopLevel = true))
        assertTrue(SecureBrowserUrlPolicy.isAllowed("https", "192.168.1.1", isTopLevel = true))
        assertTrue(SecureBrowserUrlPolicy.isAllowed("https", "::ffff:192.168.1.1", isTopLevel = true))
    }

    @Test
    fun blocksUnsafeSchemes() {
        assertFalse(SecureBrowserUrlPolicy.isAllowed("http", "example.com", isTopLevel = true))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("javascript", "example.com", isTopLevel = true))
        assertFalse(SecureBrowserUrlPolicy.isAllowed(null, "example.com", isTopLevel = true))
    }

    @Test
    fun allowsBrowserInternalSchemes() {
        assertTrue(SecureBrowserUrlPolicy.isAllowed("about", null, isTopLevel = true))
        assertTrue(SecureBrowserUrlPolicy.isAllowed("blob", null, isTopLevel = false))
    }

    @Test
    fun blocksPrivateIpv4Subresources() {
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "10.0.0.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "127.0.0.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "169.254.10.20", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "172.16.1.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "192.168.1.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "100.64.1.1", isTopLevel = false))
    }

    @Test
    fun blocksPrivateIpv6Subresources() {
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "::1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "fc00::1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "fd12:3456::1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "fe80::1", isTopLevel = false))
    }

    @Test
    fun blocksIpv4MappedIpv6Subresources() {
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "::ffff:127.0.0.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "::ffff:192.168.0.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "[::ffff:10.0.0.1]", isTopLevel = false))
    }

    @Test
    fun blocksObfuscatedIpv4Subresources() {
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "2130706433", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "0x7f000001", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "0177.0.0.1", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "0300.0250.0001.0001", isTopLevel = false))
    }

    @Test
    fun blocksLocalHostnamesAsSubresources() {
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "localhost", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "router", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "printer.local", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "gateway.lan", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "service.internal", isTopLevel = false))
        assertFalse(SecureBrowserUrlPolicy.isAllowed("https", "lab.vcs", isTopLevel = false))
    }

    @Test
    fun allowsPublicSubresources() {
        assertTrue(SecureBrowserUrlPolicy.isAllowed("https", "cdn.example.com", isTopLevel = false))
        assertTrue(SecureBrowserUrlPolicy.isAllowed("wss", "socket.example.com", isTopLevel = false))
        assertTrue(SecureBrowserUrlPolicy.isAllowed("https", "8.8.8.8", isTopLevel = false))
        assertTrue(SecureBrowserUrlPolicy.isAllowed("https", "2001:4860:4860::8888", isTopLevel = false))
    }
}
