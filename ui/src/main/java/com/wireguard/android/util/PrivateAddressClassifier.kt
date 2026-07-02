/*
 * Copyright © 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.Locale

object PrivateAddressClassifier {
    fun isPrivateOrLocalHost(rawHost: String?): Boolean {
        val host = normalizeHost(rawHost) ?: return false
        if (host == "localhost") return true
        if (!host.contains('.') && !host.contains(':')) return true
        if (LOCAL_SUFFIXES.any { suffix -> host.endsWith(suffix) }) return true
        return parseIpv4Literal(host)?.let { isPrivateIpv4(it) } == true ||
            parseIpv6Literal(host)?.let { isPrivateAddress(it) } == true
    }

    fun isPrivateAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet4Address) {
            val bytes = address.address
            val value = ((bytes[0].toLong() and 0xff) shl 24) or
                ((bytes[1].toLong() and 0xff) shl 16) or
                ((bytes[2].toLong() and 0xff) shl 8) or
                (bytes[3].toLong() and 0xff)
            return isPrivateIpv4(value)
        }
        if (address is Inet6Address) {
            val bytes = address.address
            val first = bytes[0].toInt() and 0xff
            val second = bytes[1].toInt() and 0xff
            return first == 0xfc ||
                first == 0xfd ||
                first == 0xfe && (second and 0xc0) == 0x80
        }
        return false
    }

    private fun normalizeHost(rawHost: String?): String? {
        val normalized = rawHost
            ?.trim()
            ?.lowercase(Locale.ROOT)
            ?.removePrefix("[")
            ?.removeSuffix("]")
            ?.removeSuffix(".")
            ?: return null
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun parseIpv4Literal(host: String): Long? {
        if (host.contains(':')) return null
        val parts = host.split('.')
        if (parts.isEmpty() || parts.size > 4 || parts.any { it.isBlank() }) return null
        val values = parts.map { parseIpv4Part(it) ?: return null }
        return when (values.size) {
            1 -> values[0].takeIf { it <= 0xffffffffL }
            2 -> {
                val a = values[0]
                val b = values[1]
                if (a <= 0xffL && b <= 0xffffffL) (a shl 24) or b else null
            }
            3 -> {
                val a = values[0]
                val b = values[1]
                val c = values[2]
                if (a <= 0xffL && b <= 0xffL && c <= 0xffffL) (a shl 24) or (b shl 16) or c else null
            }
            4 -> {
                if (values.all { it <= 0xffL }) {
                    (values[0] shl 24) or (values[1] shl 16) or (values[2] shl 8) or values[3]
                } else {
                    null
                }
            }
            else -> null
        }
    }

    private fun parseIpv4Part(part: String): Long? {
        val (digits, radix) = when {
            part.startsWith("0x", ignoreCase = true) -> part.drop(2) to 16
            part.length > 1 && part.startsWith("0") -> part.drop(1) to 8
            else -> part to 10
        }
        if (digits.isBlank()) return 0L
        return digits.toLongOrNull(radix)
    }

    private fun parseIpv6Literal(host: String): InetAddress? {
        if (!host.contains(':')) return null
        if (!host.all { it in '0'..'9' || it in 'a'..'f' || it == ':' || it == '.' }) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }

    private fun isPrivateIpv4(value: Long): Boolean {
        val first = ((value ushr 24) and 0xff).toInt()
        val second = ((value ushr 16) and 0xff).toInt()
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first == 169 && second == 254 ||
            first == 172 && second in 16..31 ||
            first == 192 && second == 168 ||
            first == 100 && second in 64..127
    }

    private val LOCAL_SUFFIXES = setOf(
        ".local",
        ".lan",
        ".internal",
        ".home",
        ".test",
        ".vcs"
    )
}
