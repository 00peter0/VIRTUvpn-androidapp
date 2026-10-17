/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.util

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet6Address
import java.net.URL

/** A snapshot of the selected tunnel's Android network, never its physical uplink. */
data class VpnIdentityPath(val network: Network, val links: LinkProperties) {
    fun supports(ipv6: Boolean): Boolean = links.routes.any {
        it.isDefaultRoute && (it.destination.address is Inet6Address) == ipv6
    }

    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            // Network.openConnection binds DNS and sockets to this exact network. There is
            // deliberately no URL.openConnection fallback and no process-wide binding.
            connection = (network.openConnection(URL(url)) as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                useCaches = false
                instanceFollowRedirects = false
                setRequestProperty("Accept", "application/json, text/plain")
                setRequestProperty("User-Agent", "VirtuVPN")
                setRequestProperty("Cache-Control", "no-store")
                setRequestProperty("Connection", "close")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return@withContext null
            connection.inputStream.bufferedReader().use { reader ->
                val buffer = CharArray(8193)
                var count = 0
                while (count < buffer.size) {
                    val read = reader.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 8192) null else String(buffer, 0, count).trim()
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        @Suppress("DEPRECATION") // API 24+: include the VPN even when this service UID's default is its uplink.
        fun find(manager: ConnectivityManager, config: Config): VpnIdentityPath? {
            val expected = config.`interface`.addresses.map { it.address }.toSet()
            if (expected.isEmpty()) return null
            return manager.allNetworks.mapNotNull { network ->
                val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
                val links = manager.getLinkProperties(network) ?: return@mapNotNull null
                if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                    !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                    !links.linkAddresses.map { it.address }.containsAll(expected) ||
                    links.routes.none { it.isDefaultRoute }) return@mapNotNull null
                VpnIdentityPath(network, links)
            }.singleOrNull()
        }
    }
}

data class PublicIdentityResult(val ipv4: String?, val ipv6: String?, val geo: String?)

/** Each result belongs to one uninterrupted check of one tunnel/network snapshot. */
object PublicIdentityProbe {
    suspend fun read(
        ipv4: Boolean,
        ipv6: Boolean,
        isCurrent: () -> Boolean,
        fetch: suspend (String) -> String?
    ): PublicIdentityResult? {
        suspend fun valid(): Boolean {
            currentCoroutineContext().ensureActive()
            return isCurrent()
        }
        if (!valid()) return null
        val ip4 = if (ipv4) parseIp(fetch("https://ipv4.icanhazip.com"), false) else null
        if (!valid()) return null
        val ip6 = if (ipv6) parseIp(fetch("https://ipv6.icanhazip.com"), true) else null
        if (!valid()) return null
        val primary = ip4 ?: ip6
        val geo = primary?.let { fetch("https://ipwho.is/$it") }
        if (!valid()) return null
        return PublicIdentityResult(ip4, ip6, geo)
    }

    internal fun parseIp(raw: String?, ipv6: Boolean): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() && it.length <= 45 } ?: return null
        if (ipv6) {
            // The character gate prevents hostname lookup, zones, URLs and response markup.
            if (':' !in value || value.any { it !in "0123456789abcdefABCDEF:." }) return null
            return runCatching { java.net.InetAddress.getByName(value) }
                .getOrNull()?.takeIf { it is Inet6Address }?.let { value }
        }
        val parts = value.split('.')
        return value.takeIf {
            parts.size == 4 && parts.all { part ->
                part.isNotEmpty() && part.length <= 3 && part.all { it in '0'..'9' } &&
                    (part.length == 1 || part.first() != '0') && part.toInt() in 0..255
            }
        }
    }
}
