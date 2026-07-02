/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAddressClassifierTest {
    @Test
    fun classifiesPrivateIpv4Hosts() {
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("10.0.0.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("127.0.0.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("169.254.10.20"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("172.16.1.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("172.31.255.255"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("192.168.1.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("100.64.1.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("100.127.255.255"))
    }

    @Test
    fun classifiesPrivateIpv6Hosts() {
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("::1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("fc00::1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("fd12:3456::1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("fe80::1"))
    }

    @Test
    fun classifiesIpv4MappedIpv6Hosts() {
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("::ffff:127.0.0.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("::ffff:10.0.0.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("[::ffff:192.168.0.1]"))
    }

    @Test
    fun classifiesObfuscatedIpv4Hosts() {
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("2130706433"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("0x7f000001"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("0177.0.0.1"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("0300.0250.0001.0001"))
    }

    @Test
    fun classifiesLocalNames() {
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("localhost"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("router"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("printer.local"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("gateway.lan"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("service.internal"))
        assertTrue(PrivateAddressClassifier.isPrivateOrLocalHost("lab.vcs"))
    }

    @Test
    fun allowsPublicHosts() {
        assertFalse(PrivateAddressClassifier.isPrivateOrLocalHost("example.com"))
        assertFalse(PrivateAddressClassifier.isPrivateOrLocalHost("8.8.8.8"))
        assertFalse(PrivateAddressClassifier.isPrivateOrLocalHost("2001:4860:4860::8888"))
    }

    @Test
    fun classifiesInetAddressesForAttestationClients() {
        assertTrue(PrivateAddressClassifier.isPrivateAddress(InetAddress.getByName("127.0.0.1")))
        assertTrue(PrivateAddressClassifier.isPrivateAddress(InetAddress.getByName("192.168.115.153")))
        assertTrue(PrivateAddressClassifier.isPrivateAddress(InetAddress.getByName("100.64.0.10")))
        assertTrue(PrivateAddressClassifier.isPrivateAddress(InetAddress.getByName("fc00::1")))
        assertFalse(PrivateAddressClassifier.isPrivateAddress(InetAddress.getByName("8.8.8.8")))
        assertFalse(PrivateAddressClassifier.isPrivateAddress(InetAddress.getByName("2001:4860:4860::8888")))
    }
}
