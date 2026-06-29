/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object VpnRouterAttestation {
    const val PORT = 8788
    private const val PATH = "/virtuvpn-router/attestation"
    private const val ALG = "HMAC-SHA256"
    private const val MAX_AGE_MS = 30_000L
    private const val SECRET = "VirtuVPN router attestation v1; vcsinstall secure browser trust root"
    private val NONCE_REGEX = Regex("^[A-Za-z0-9_-]{24,96}$")

    data class Result(
        val tunnel: String?
    )

    suspend fun verifyFromCurrentGateway(context: Context): Result? {
        val gateway = currentWifiGateway(context.applicationContext) ?: return null
        val nonce = randomNonce()
        val body = fetchAttestation(gateway.hostAddress ?: return null, nonce) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("app") != "VirtuVPN") return null
        if (json.optString("kind") != "vpn-router-attestation") return null
        if (json.optString("alg") != ALG) return null
        if (json.optString("nonce") != nonce) return null
        if (!json.optBoolean("protected", false)) return null
        val timestamp = json.optLong("timestamp", 0L)
        if (timestamp <= 0L || kotlin.math.abs(System.currentTimeMillis() - timestamp) > MAX_AGE_MS) return null
        val tunnel = json.optString("tunnel").takeIf { it.isNotBlank() }
        val signature = json.optString("signature")
        val payload = payload(nonce, timestamp, protected = true, tunnel = tunnel)
        if (!constantTimeEquals(signature, sign(payload))) return null
        return Result(tunnel)
    }

    fun responseJson(context: Context, nonce: String): String? {
        if (!NONCE_REGEX.matches(nonce)) return null
        val status = runBlocking { VpnRouterManager.getStatus(context.applicationContext) }
        if (status.availability != VpnRouterManager.Availability.ENABLED) return null
        val timestamp = System.currentTimeMillis()
        val tunnel = status.activeTunnel
        val payload = payload(nonce, timestamp, protected = true, tunnel = tunnel)
        return JSONObject()
            .put("app", "VirtuVPN")
            .put("kind", "vpn-router-attestation")
            .put("version", 1)
            .put("alg", ALG)
            .put("nonce", nonce)
            .put("timestamp", timestamp)
            .put("protected", true)
            .put("tunnel", tunnel ?: "")
            .put("signature", sign(payload))
            .toString()
    }

    fun pathMatches(path: String): Boolean = path == PATH

    private fun currentWifiGateway(context: Context): InetAddress? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivityManager.activeNetwork ?: return null
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val linkProperties = connectivityManager.getLinkProperties(network) ?: return null
        return linkProperties.routes
            .firstOrNull { route -> route.isDefaultRoute && route.gateway != null }
            ?.gateway
    }

    private fun fetchAttestation(host: String, nonce: String): String? {
        val encodedHost = if (host.contains(':')) "[$host]" else host
        val encodedNonce = URLEncoder.encode(nonce, "UTF-8")
        val conn = (URL("http://$encodedHost:$PORT$PATH?nonce=$encodedNonce").openConnection() as HttpURLConnection).apply {
            connectTimeout = 900
            readTimeout = 900
            requestMethod = "GET"
            useCaches = false
        }
        return try {
            if (conn.responseCode != 200) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Throwable) {
            null
        } finally {
            conn.disconnect()
        }
    }

    private fun randomNonce(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun payload(nonce: String, timestamp: Long, protected: Boolean, tunnel: String?): String =
        listOf("v1", nonce, timestamp.toString(), protected.toString(), tunnel.orEmpty()).joinToString("|")

    private fun sign(payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(payload.toByteArray(Charsets.UTF_8)), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        val a = left.toByteArray(Charsets.UTF_8)
        val b = right.toByteArray(Charsets.UTF_8)
        var diff = a.size xor b.size
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            diff = diff or ((a.getOrNull(i)?.toInt() ?: 0) xor (b.getOrNull(i)?.toInt() ?: 0))
        }
        return diff == 0
    }

    fun isAllowedClientAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress) return true
        if (address is Inet4Address) {
            val bytes = address.address.map { it.toInt() and 0xff }
            return bytes[0] == 100 && bytes[1] in 64..127
        }
        if (address is Inet6Address) {
            val first = address.address.first().toInt() and 0xff
            return first and 0xfe == 0xfc
        }
        return false
    }
}

object VpnRouterAttestationServer {
    private const val TAG = "VirtuVPN/RouterAttest"
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var serverThread: Thread? = null

    fun start(context: Context) {
        if (serverSocket != null) return
        val appContext = context.applicationContext
        runCatching {
            val socket = ServerSocket(VpnRouterAttestation.PORT, 16, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            serverThread = Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: continue
                    Thread { handleClient(appContext, client) }.start()
                }
            }.apply {
                name = "VirtuVPN-Router-Attestation"
                isDaemon = true
                start()
            }
        }.onFailure {
            Log.d(TAG, "Unable to start router attestation server", it)
            stop()
        }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        serverThread = null
    }

    private fun handleClient(context: Context, socket: Socket) {
        socket.use { client ->
            if (!VpnRouterAttestation.isAllowedClientAddress(client.inetAddress)) {
                writeResponse(client.getOutputStream(), 403, "Forbidden")
                return
            }
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val request = reader.readLine().orEmpty()
            val parts = request.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                writeResponse(client.getOutputStream(), 405, "Method Not Allowed")
                return
            }
            val target = parts[1]
            val path = target.substringBefore('?')
            if (!VpnRouterAttestation.pathMatches(path)) {
                writeResponse(client.getOutputStream(), 404, "Not Found")
                return
            }
            val nonce = target.substringAfter("?", "")
                .split('&')
                .firstNotNullOfOrNull { pair ->
                    val key = pair.substringBefore('=')
                    if (key == "nonce") URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8") else null
                }
            val json = nonce?.let { VpnRouterAttestation.responseJson(context, it) }
            if (json == null) {
                writeResponse(client.getOutputStream(), 503, "Unavailable")
            } else {
                writeResponse(client.getOutputStream(), 200, json, "application/json")
            }
        }
    }

    private fun writeResponse(output: OutputStream, status: Int, body: String, contentType: String = "text/plain") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val headers =
            "HTTP/1.1 $status ${statusText(status)}\r\n" +
                "Content-Type: $contentType; charset=utf-8\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        503 -> "Service Unavailable"
        else -> "OK"
    }
}
