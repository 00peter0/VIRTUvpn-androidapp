/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object VpnRouterAttestation {
    const val PORT = 8788
    private const val PATH = "/virtuvpn-router/attestation"
    private const val ALG = "HMAC-SHA256"
    private const val MAX_AGE_MS = 30_000L
    private const val PREFS = "virtuvpn_router_attestation"
    private const val KEY_ROUTER_ID = "router_id"
    private const val KEY_ROUTER_SECRET = "router_secret"
    private const val KEY_PAIRED_ROUTER_ID = "paired_router_id"
    private const val KEY_PAIRED_SECRET = "paired_secret"
    private val NONCE_REGEX = Regex("^[A-Za-z0-9_-]{24,96}$")

    data class Result(
        val routerId: String
    )

    data class Pairing(
        val routerId: String,
        val secret: String
    )

    suspend fun verifyFromCurrentGateway(context: Context): Result? {
        val secret = pairedSecret(context.applicationContext) ?: return null
        val gateway = currentWifiGateway(context.applicationContext) ?: return null
        val nonce = randomNonce()
        val body = fetchAttestation(gateway.hostAddress ?: return null, nonce) ?: return null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (json.optString("app") != "VirtuVPN") return null
        if (json.optString("kind") != "vpn-router-attestation") return null
        if (json.optString("alg") != ALG) return null
        if (json.optString("nonce") != nonce) return null
        if (!json.optBoolean("protected", false)) return null
        val routerId = json.optString("routerId").takeIf { it.isNotBlank() } ?: return null
        if (routerId != pairedRouterId(context.applicationContext)) return null
        val timestamp = json.optLong("timestamp", 0L)
        if (timestamp <= 0L || kotlin.math.abs(System.currentTimeMillis() - timestamp) > MAX_AGE_MS) return null
        val signature = json.optString("signature")
        val payload = payload(routerId, nonce, timestamp, protected = true)
        if (!constantTimeEquals(signature, sign(payload, secret))) return null
        return Result(routerId)
    }

    fun responseJson(context: Context, nonce: String): String? {
        if (!NONCE_REGEX.matches(nonce)) return null
        val status = VpnRouterAttestationServer.cachedStatus(context.applicationContext) ?: return null
        if (status.availability != VpnRouterManager.Availability.ENABLED) return null
        val timestamp = System.currentTimeMillis()
        val routerId = routerId(context.applicationContext)
        val secret = routerSecret(context.applicationContext)
        val payload = payload(routerId, nonce, timestamp, protected = true)
        return JSONObject()
            .put("app", "VirtuVPN")
            .put("kind", "vpn-router-attestation")
            .put("version", 1)
            .put("alg", ALG)
            .put("routerId", routerId)
            .put("nonce", nonce)
            .put("timestamp", timestamp)
            .put("protected", true)
            .put("signature", sign(payload, secret))
            .toString()
    }

    fun pathMatches(path: String): Boolean = path == PATH

    fun pairingUri(context: Context): String {
        val routerId = routerId(context.applicationContext)
        val secret = routerSecret(context.applicationContext)
        return Uri.Builder()
            .scheme("virtuvpn")
            .authority("router-pair")
            .appendQueryParameter("id", routerId)
            .appendQueryParameter("secret", secret)
            .build()
            .toString()
    }

    fun isPairingUri(uri: Uri): Boolean =
        uri.scheme == "virtuvpn" && uri.host == "router-pair"

    fun parsePairingUri(uri: Uri): Pairing? {
        if (!isPairingUri(uri)) return null
        val routerId = uri.getQueryParameter("id")?.takeIf { it.length in 12..96 } ?: return null
        val secret = uri.getQueryParameter("secret")?.takeIf { NONCE_REGEX.matches(it) } ?: return null
        return Pairing(routerId, secret)
    }

    fun importPairing(context: Context, pairing: Pairing) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRED_ROUTER_ID, pairing.routerId)
            .putString(KEY_PAIRED_SECRET, pairing.secret)
            .apply()
    }

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

    private fun routerId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ROUTER_ID, null)?.let { return it }
        val id = randomNonce()
        prefs.edit().putString(KEY_ROUTER_ID, id).apply()
        return id
    }

    private fun routerSecret(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_ROUTER_SECRET, null)?.let { return it }
        val secret = randomNonce()
        prefs.edit().putString(KEY_ROUTER_SECRET, secret).apply()
        return secret
    }

    private fun pairedRouterId(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAIRED_ROUTER_ID, null)

    private fun pairedSecret(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PAIRED_SECRET, null)

    private fun payload(routerId: String, nonce: String, timestamp: Long, protected: Boolean): String =
        listOf("v2", routerId, nonce, timestamp.toString(), protected.toString()).joinToString("|")

    private fun sign(payload: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
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
    private const val MAX_REQUEST_LINE = 2048
    private const val STATUS_TTL_MS = 1_500L
    @Volatile
    private var serverSocket: ServerSocket? = null
    @Volatile
    private var serverThread: Thread? = null
    private val workerPool = Executors.newFixedThreadPool(3)
    private val permits = Semaphore(8)
    @Volatile
    private var cachedStatus: VpnRouterManager.Status? = null
    @Volatile
    private var cachedAt: Long = 0L

    fun start(context: Context) {
        if (serverSocket != null) return
        val appContext = context.applicationContext
        runCatching {
            val socket = ServerSocket(VpnRouterAttestation.PORT, 16, InetAddress.getByName("0.0.0.0"))
            serverSocket = socket
            serverThread = Thread {
                while (!socket.isClosed) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: continue
                    if (!permits.tryAcquire()) {
                        runCatching { writeResponse(client.getOutputStream(), 503, "Busy") }
                        runCatching { client.close() }
                        continue
                    }
                    workerPool.execute {
                        try {
                            handleClient(appContext, client)
                        } finally {
                            permits.release()
                        }
                    }
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

    fun cachedStatus(context: Context): VpnRouterManager.Status? {
        val now = System.currentTimeMillis()
        cachedStatus?.takeIf { now - cachedAt <= STATUS_TTL_MS }?.let { return it }
        val status = runBlocking { VpnRouterManager.getStatus(context.applicationContext) }
        cachedStatus = status
        cachedAt = now
        return status
    }

    private fun handleClient(context: Context, socket: Socket) {
        socket.use { client ->
            client.soTimeout = 1_500
            if (!VpnRouterAttestation.isAllowedClientAddress(client.inetAddress)) {
                writeResponse(client.getOutputStream(), 403, "Forbidden")
                return
            }
            val request = readRequestLine(client) ?: run {
                writeResponse(client.getOutputStream(), 413, "Request Too Large")
                return
            }
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

    private fun readRequestLine(socket: Socket): String? {
        val input = socket.getInputStream()
        val bytes = ArrayList<Byte>(128)
        while (bytes.size <= MAX_REQUEST_LINE) {
            val value = input.read()
            if (value == -1) break
            if (value == '\n'.code) break
            if (value != '\r'.code) bytes.add(value.toByte())
        }
        if (bytes.size > MAX_REQUEST_LINE) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII)
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
        413 -> "Request Entity Too Large"
        503 -> "Service Unavailable"
        else -> "OK"
    }
}
