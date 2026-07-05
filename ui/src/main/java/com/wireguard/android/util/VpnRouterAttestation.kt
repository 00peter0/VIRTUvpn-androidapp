/*
 * Copyright 2026 Virtu VPN. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.wireguard.android.util

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.system.Os
import android.system.OsConstants
import android.system.StructTimeval
import android.util.Base64
import android.util.Log
import com.wireguard.android.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object VpnRouterAttestation {
    const val PORT = 8788
    internal const val LOCAL_PORT = 8789
    private const val PATH = "/virtuvpn-router/attestation"
    private const val PAIR_PAGE_PATH = "/router/pair"
    private const val APK_PATH = "/virtuvpn.apk"
    private const val ALG = "HMAC-SHA256"
    private const val MAX_AGE_MS = 30_000L
    private const val PREFS = "virtuvpn_router_attestation"
    private const val KEY_ROUTER_ID = "router_id"
    private const val KEY_ROUTER_SECRET = "router_secret"
    private const val KEY_PAIRED_ROUTER_ID = "paired_router_id"
    private const val KEY_PAIRED_SECRET = "paired_secret"
    private const val KEY_PAIRED_ROUTERS = "paired_routers"
    private const val GUEST_PAIRING_TTL_MS = 7L * 24L * 60L * 60L * 1000L
    private val NONCE_REGEX = Regex("^[A-Za-z0-9_-]{24,96}$")

    data class Result(
        val routerId: String,
        val tunnel: String? = null,
        val tunnelOnline: Boolean? = null
    )

    data class Pairing(
        val routerId: String,
        val secret: String,
        val pairedAt: Long = System.currentTimeMillis(),
        val expiresAt: Long = pairedAt + GUEST_PAIRING_TTL_MS
    )

    enum class FailureReason {
        NO_PAIRING,
        NO_WIFI_GATEWAY,
        UNREACHABLE,
        INVALID_RESPONSE,
        ROUTER_DEGRADED,
        ROUTER_NOT_PAIRED,
        EXPIRED_PAIRING,
        CLOCK_SKEW,
        BAD_SIGNATURE
    }

    data class Verification(
        val result: Result?,
        val failureReason: FailureReason? = null
    )

    suspend fun verifyFromCurrentGateway(context: Context): Result? {
        return verifyFromCurrentGatewayDetailed(context).result
    }

    suspend fun verifyFromCurrentGatewayDetailed(context: Context): Verification {
        val appContext = context.applicationContext
        val pairings = pairedRouters(appContext)
        if (pairings.isEmpty()) return Verification(null, FailureReason.NO_PAIRING)
        val gateway = currentWifiGateway(appContext) ?: return Verification(null, FailureReason.NO_WIFI_GATEWAY)
        val nonce = randomNonce()
        val response = fetchAttestation(gateway.hostAddress ?: return Verification(null, FailureReason.NO_WIFI_GATEWAY), nonce)
            ?: return Verification(null, FailureReason.UNREACHABLE)
        if (response.statusCode != 200) return Verification(null, FailureReason.INVALID_RESPONSE)
        val body = response.body
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return Verification(null, FailureReason.INVALID_RESPONSE)
        if (json.optString("app") != "VirtuVPN") return Verification(null, FailureReason.INVALID_RESPONSE)
        if (json.optString("kind") != "vpn-router-attestation") return Verification(null, FailureReason.INVALID_RESPONSE)
        if (json.optString("alg") != ALG) return Verification(null, FailureReason.INVALID_RESPONSE)
        if (json.optString("nonce") != nonce) return Verification(null, FailureReason.INVALID_RESPONSE)
        val routerId = json.optString("routerId").takeIf { it.isNotBlank() }
            ?: return Verification(null, FailureReason.INVALID_RESPONSE)
        val pairing = pairings.firstOrNull { it.routerId == routerId }
            ?: return Verification(null, FailureReason.ROUTER_NOT_PAIRED)
        if (pairing.expiresAt <= System.currentTimeMillis()) {
            removeExpiredPairings(appContext)
            return Verification(null, FailureReason.EXPIRED_PAIRING)
        }
        val timestamp = json.optLong("timestamp", 0L)
        if (timestamp <= 0L || kotlin.math.abs(System.currentTimeMillis() - timestamp) > MAX_AGE_MS) {
            return Verification(null, FailureReason.CLOCK_SKEW)
        }
        val signature = json.optString("signature")
        val protected = json.optBoolean("protected", false)
        val payload = payload(routerId, nonce, timestamp, protected)
        if (!constantTimeEquals(signature, sign(payload, pairing.secret))) return Verification(null, FailureReason.BAD_SIGNATURE)
        if (!protected) return Verification(null, FailureReason.ROUTER_DEGRADED)
        val tunnel = json.optString("tunnel").takeIf { it.isNotBlank() }
        val verifiedTunnel = tunnel?.takeIf {
            constantTimeEquals(json.optString("tunnelSignature"), sign(tunnelPayload(routerId, nonce, timestamp, it), pairing.secret))
        }
        val tunnelOnline = json.optString("tunnelOnline").takeIf { it.isNotBlank() }?.toBooleanStrictOrNull()?.takeIf { value ->
            constantTimeEquals(json.optString("tunnelOnlineSignature"), sign(tunnelOnlinePayload(routerId, nonce, timestamp, value), pairing.secret))
        }
        return Verification(Result(routerId, verifiedTunnel, tunnelOnline))
    }

    fun responseJson(context: Context, nonce: String): String? {
        if (!NONCE_REGEX.matches(nonce)) return null
        val status = VpnRouterAttestationServer.cachedStatus(context.applicationContext) ?: return null
        val timestamp = System.currentTimeMillis()
        val routerId = routerId(context.applicationContext)
        val secret = routerSecret(context.applicationContext)
        val protected = status.securityProtected
        val tunnelOnline = status.availability == VpnRouterManager.Availability.ENABLED
        val payload = payload(routerId, nonce, timestamp, protected)
        val json = JSONObject()
            .put("app", "VirtuVPN")
            .put("kind", "vpn-router-attestation")
            .put("version", 2)
            .put("alg", ALG)
            .put("routerId", routerId)
            .put("nonce", nonce)
            .put("timestamp", timestamp)
            .put("protected", protected)
            .put("availability", status.availability.name)
            .put("detail", status.detail ?: "")
            .put("tunnelOnline", tunnelOnline)
            .put("tunnelOnlineSignature", sign(tunnelOnlinePayload(routerId, nonce, timestamp, tunnelOnline), secret))
            .put("signature", sign(payload, secret))
        // Optional, separately-signed metadata for a richer client status.
        // Older clients ignore it; the security gate above stays unchanged.
        val tunnel = status.activeTunnel?.takeIf { it.isNotBlank() }
        if (tunnel != null) {
            json.put("tunnel", tunnel)
                .put("tunnelSignature", sign(tunnelPayload(routerId, nonce, timestamp, tunnel), secret))
        }
        return json.toString()
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

    fun pairingLandingUrl(context: Context): String {
        val routerId = routerId(context.applicationContext)
        val secret = routerSecret(context.applicationContext)
        val host = VpnRouterAttestationServer.localRouterHost() ?: "192.168.115.186"
        return Uri.Builder()
            .scheme("http")
            .encodedAuthority("$host:$PORT")
            .path(PAIR_PAGE_PATH)
            .encodedFragment("id=${Uri.encode(routerId)}&secret=${Uri.encode(secret)}")
            .build()
            .toString()
    }

    fun legacyPairingLandingUrl(context: Context): String {
        val routerId = routerId(context.applicationContext)
        val secret = routerSecret(context.applicationContext)
        return Uri.Builder()
            .scheme("https")
            .authority("vcs.virtucomputing.com")
            .path("/router/pair")
            .encodedFragment("id=${Uri.encode(routerId)}&secret=${Uri.encode(secret)}")
            .build()
            .toString()
    }

    fun isPairingUri(uri: Uri): Boolean =
        uri.scheme == "virtuvpn" && uri.host == "router-pair"

    fun pairPagePathMatches(path: String): Boolean = path == PAIR_PAGE_PATH

    fun apkPathMatches(path: String): Boolean = path == APK_PATH

    fun parsePairingUri(uri: Uri): Pairing? {
        if (!isPairingUri(uri)) return null
        val routerId = uri.getQueryParameter("id")?.takeIf { it.length in 12..96 } ?: return null
        val secret = uri.getQueryParameter("secret")?.takeIf { NONCE_REGEX.matches(it) } ?: return null
        return Pairing(routerId, secret)
    }

    fun parsePairingValue(value: String): Pairing? {
        val trimmed = value.trim()
        val direct = runCatching { parsePairingUri(Uri.parse(trimmed)) }.getOrNull()
        if (direct != null) return direct
        val hash = trimmed.substringAfter('#', missingDelimiterValue = "")
        if (hash.isNotBlank()) {
            return runCatching {
                parsePairingUri(Uri.parse("virtuvpn://router-pair?$hash"))
            }.getOrNull()
        }
        return null
    }

    fun importPairing(context: Context, pairing: Pairing) {
        val appContext = context.applicationContext
        val updated = (pairedRouters(appContext).filterNot { it.routerId == pairing.routerId } + pairing)
            .sortedByDescending { it.pairedAt }
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRED_ROUTERS, pairingsJson(updated))
            .remove(KEY_PAIRED_ROUTER_ID)
            .remove(KEY_PAIRED_SECRET)
            .apply()
    }

    fun pairedRouters(context: Context): List<Pairing> {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val stored = prefs.getString(KEY_PAIRED_ROUTERS, null)
        val parsed = if (!stored.isNullOrBlank()) {
            runCatching {
                val array = JSONArray(stored)
                (0 until array.length()).mapNotNull { index ->
                    val obj = array.optJSONObject(index) ?: return@mapNotNull null
                    val routerId = obj.optString("routerId").takeIf { it.length in 12..96 } ?: return@mapNotNull null
                    val secret = obj.optString("secret").takeIf { NONCE_REGEX.matches(it) } ?: return@mapNotNull null
                    val pairedAt = obj.optLong("pairedAt", now)
                    val expiresAt = obj.optLong("expiresAt", pairedAt + GUEST_PAIRING_TTL_MS)
                    Pairing(routerId, secret, pairedAt, expiresAt)
                }
            }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val legacyRouterId = prefs.getString(KEY_PAIRED_ROUTER_ID, null)
        val legacySecret = prefs.getString(KEY_PAIRED_SECRET, null)
        val withLegacy = if (legacyRouterId != null && legacySecret != null && NONCE_REGEX.matches(legacySecret)) {
            parsed + Pairing(legacyRouterId, legacySecret, expiresAt = now + GUEST_PAIRING_TTL_MS)
        } else {
            parsed
        }
        val active = withLegacy
            .filter { it.expiresAt > now }
            .distinctBy { it.routerId }
            .sortedByDescending { it.pairedAt }
        if (active.size != parsed.size || legacyRouterId != null || legacySecret != null) {
            prefs.edit()
                .putString(KEY_PAIRED_ROUTERS, pairingsJson(active))
                .remove(KEY_PAIRED_ROUTER_ID)
                .remove(KEY_PAIRED_SECRET)
                .apply()
        }
        return active
    }

    fun clearPairings(context: Context) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PAIRED_ROUTERS)
            .remove(KEY_PAIRED_ROUTER_ID)
            .remove(KEY_PAIRED_SECRET)
            .apply()
    }

    private fun removeExpiredPairings(context: Context) {
        val active = pairedRouters(context)
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PAIRED_ROUTERS, pairingsJson(active))
            .apply()
    }

    private fun pairingsJson(pairings: List<Pairing>): String {
        val array = JSONArray()
        pairings.forEach { pairing ->
            array.put(
                JSONObject()
                    .put("routerId", pairing.routerId)
                    .put("secret", pairing.secret)
                    .put("pairedAt", pairing.pairedAt)
                    .put("expiresAt", pairing.expiresAt)
            )
        }
        return array.toString()
    }

    private fun currentWifiGateway(context: Context): InetAddress? {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val networks = buildList {
            connectivityManager.activeNetwork?.let { add(it) }
            addAll(connectivityManager.allNetworks.filterNot { it == connectivityManager.activeNetwork })
        }
        networks.forEach { network ->
            val caps = connectivityManager.getNetworkCapabilities(network) ?: return@forEach
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return@forEach
            val gateway = connectivityManager.getLinkProperties(network)
                ?.routes
                ?.firstOrNull { route -> route.isDefaultRoute && route.gateway != null }
                ?.gateway
            if (gateway is Inet4Address) return gateway
        }
        return dhcpWifiGateway(context)
    }

    private fun dhcpWifiGateway(context: Context): InetAddress? {
        val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val gateway = wifiManager.dhcpInfo?.gateway ?: return null
        if (gateway == 0) return null
        val bytes = byteArrayOf(
            (gateway and 0xff).toByte(),
            (gateway shr 8 and 0xff).toByte(),
            (gateway shr 16 and 0xff).toByte(),
            (gateway shr 24 and 0xff).toByte()
        )
        return runCatching { InetAddress.getByAddress(bytes) as? Inet4Address }.getOrNull()
    }

    private data class HttpResponse(
        val statusCode: Int,
        val body: String
    )

    private fun fetchAttestation(host: String, nonce: String): HttpResponse? {
        val encodedNonce = URLEncoder.encode(nonce, "UTF-8")
        return try {
            Socket().use { socket ->
                socket.soTimeout = 4_000
                socket.connect(InetSocketAddress(host, PORT), 2_500)
                val request =
                    "GET $PATH?nonce=$encodedNonce HTTP/1.1\r\n" +
                        "Host: $host:$PORT\r\n" +
                        "Connection: close\r\n\r\n"
                socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                socket.getOutputStream().flush()
                val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText() }
                val headerEnd = response.indexOf("\r\n\r\n")
                if (headerEnd <= 0) return null
                val statusLine = response.substringBefore("\r\n")
                val statusCode = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: return null
                HttpResponse(statusCode, response.substring(headerEnd + 4))
            }
        } catch (_: Throwable) {
            null
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

    private fun payload(routerId: String, nonce: String, timestamp: Long, protected: Boolean): String =
        listOf("v2", routerId, nonce, timestamp.toString(), protected.toString()).joinToString("|")

    private fun tunnelPayload(routerId: String, nonce: String, timestamp: Long, tunnel: String): String =
        listOf("tunnel", routerId, nonce, timestamp.toString(), tunnel).joinToString("|")

    private fun tunnelOnlinePayload(routerId: String, nonce: String, timestamp: Long, tunnelOnline: Boolean): String =
        listOf("tunnel-online", routerId, nonce, timestamp.toString(), tunnelOnline.toString()).joinToString("|")

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
        return PrivateAddressClassifier.isPrivateAddress(address)
    }
}

object VpnRouterAttestationServer {
    private const val TAG = "VirtuVPN/RouterAttest"
    private const val MAX_REQUEST_LINE = 2048
    private const val LAUNCHER_LOGO_PATH = "/launcher-logo.png"
    // Kept above the foreground service interval (2 s). Requests must never run
    // root probes; they only read this warmed cache and sign quickly.
    private const val STATUS_TTL_MS = 30_000L
    @Volatile
    private var serverFd: FileDescriptor? = null
    @Volatile
    private var serverThread: Thread? = null
    @Volatile
    private var boundHost: String? = null
    private val lifecycleLock = Any()
    private val workerPool = Executors.newFixedThreadPool(8)
    private val permits = Semaphore(8)
    @Volatile
    private var cachedStatus: VpnRouterManager.Status? = null
    @Volatile
    private var cachedAt: Long = 0L

    fun start(context: Context, status: VpnRouterManager.Status? = cachedStatus) {
        val host = localRouterHost(status) ?: "0.0.0.0"
        synchronized(lifecycleLock) {
            if (serverFd != null) {
                boundHost = host
                return
            }
            val appContext = context.applicationContext
            var openedFd: FileDescriptor? = null
            runCatching {
                val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_STREAM, OsConstants.IPPROTO_TCP)
                openedFd = fd
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_REUSEADDR, 1)
                }
                Os.bind(fd, InetAddress.getByName("127.0.0.1"), VpnRouterAttestation.LOCAL_PORT)
                Os.listen(fd, 16)
                serverFd = fd
                boundHost = host
                serverThread = Thread {
                    while (serverFd === fd) {
                        val clientFd = runCatching { Os.accept(fd, InetSocketAddress(0)) }.getOrNull() ?: continue
                        if (!permits.tryAcquire()) {
                            runCatching {
                                FileOutputStream(clientFd).use { output -> writeResponse(output, 503, "Busy") }
                            }
                            closeQuietly(clientFd)
                            continue
                        }
                        workerPool.execute {
                            try {
                                runCatching { handleClient(appContext, clientFd) }
                                    .onFailure { error ->
                                        if (error is SocketTimeoutException || error is IOException) {
                                            Log.d(TAG, "Router attestation client disconnected", error)
                                        } else {
                                            Log.e(TAG, "Router attestation request failed", error)
                                        }
                                    }
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
                openedFd?.let { fd ->
                    if (serverFd === fd) serverFd = null
                    closeQuietly(fd)
                }
                if (serverFd == null) {
                    serverThread = null
                    boundHost = null
                }
            }
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            val fd = serverFd
            serverFd = null
            if (fd != null) closeQuietly(fd)
            serverThread = null
            boundHost = null
            cachedStatus = null
            cachedAt = 0L
        }
    }

    /**
     * Pre-warms the status cache from the reconcile monitor so attestation
     * requests are served without a blocking root-shell probe (which can take
     * 1-3 s and blow past the client's socket timeout, making attestation over
     * the hotspot fail consistently).
     */
    fun updateStatus(status: VpnRouterManager.Status) {
        cachedStatus = status
        cachedAt = System.currentTimeMillis()
    }

    fun cachedStatus(context: Context): VpnRouterManager.Status? {
        val now = System.currentTimeMillis()
        return cachedStatus?.takeIf { now - cachedAt <= STATUS_TTL_MS }
    }

    fun localRouterHost(): String? {
        return localRouterHost(cachedStatus)
    }

    private fun localRouterHost(status: VpnRouterManager.Status?): String? {
        val interfaces = status?.tetherInterfaces.orEmpty()
        for (name in interfaces) {
            val address = runCatching {
                NetworkInterface.getByName(name)
                    ?.inetAddresses
                    ?.asSequence()
                    ?.filterIsInstance<Inet4Address>()
                    ?.firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            }.getOrNull()
            if (address != null) return address.hostAddress
        }
        return null
    }

    private fun handleClient(context: Context, clientFd: FileDescriptor) {
        clientFd.useFd { fd ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                Os.setsockoptTimeval(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVTIMEO, StructTimeval.fromMillis(1_500))
            }
            val remoteAddress = (runCatching { Os.getpeername(fd) }.getOrNull() as? InetSocketAddress)?.address
            val input = FileInputStream(fd)
            val output = FileOutputStream(fd)
            if (remoteAddress == null || !VpnRouterAttestation.isAllowedClientAddress(remoteAddress)) {
                writeResponse(output, 403, "Forbidden")
                return
            }
            val request = readRequestLine(input) ?: return
            val parts = request.split(' ')
            if (parts.size < 2 || parts[0] != "GET") {
                drainRequestHeaders(input)
                writeResponse(output, 405, "Method Not Allowed")
                return
            }
            drainRequestHeaders(input)
            val target = parts[1]
            val path = target.substringBefore('?')
            if (VpnRouterAttestation.pairPagePathMatches(path)) {
                writeResponse(output, 200, localPairingPage(), "text/html")
                return
            }
            if (path == LAUNCHER_LOGO_PATH) {
                writeLauncherLogoResponse(context, output)
                return
            }
            if (VpnRouterAttestation.apkPathMatches(path)) {
                writeApkResponse(context, output)
                return
            }
            if (!VpnRouterAttestation.pathMatches(path)) {
                writeResponse(output, 404, "Not Found")
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
                writeResponse(output, 503, "Unavailable")
            } else {
                writeResponse(output, 200, json, "application/json")
            }
        }
    }

    private fun readRequestLine(input: InputStream): String? {
        val bytes = ArrayList<Byte>(128)
        try {
            while (bytes.size <= MAX_REQUEST_LINE) {
                val value = input.read()
                if (value == -1) break
                if (value == '\n'.code) break
                if (value != '\r'.code) bytes.add(value.toByte())
            }
        } catch (_: SocketTimeoutException) {
            return null
        }
        if (bytes.size > MAX_REQUEST_LINE) return null
        return bytes.toByteArray().toString(Charsets.US_ASCII)
    }

    private fun drainRequestHeaders(input: InputStream) {
        var previous = -1
        var current: Int
        var lineBytes = 0
        try {
            while (lineBytes <= MAX_REQUEST_LINE) {
                current = input.read()
                if (current == -1) return
                lineBytes++
                if (previous == '\r'.code && current == '\n'.code) {
                    if (lineBytes == 2) return
                    lineBytes = 0
                }
                previous = current
            }
        } catch (_: SocketTimeoutException) {
            return
        }
    }

    private inline fun FileDescriptor.useFd(block: (FileDescriptor) -> Unit) {
        try {
            block(this)
        } finally {
            closeQuietly(this)
        }
    }

    private fun closeQuietly(fd: FileDescriptor) {
        runCatching { Os.close(fd) }
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

    private fun writeApkResponse(context: Context, output: OutputStream) {
        val apk = File(context.applicationInfo.sourceDir)
        if (!apk.isFile) {
            writeResponse(output, 404, "APK not available")
            return
        }
        val headers =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/vnd.android.package-archive\r\n" +
                "Content-Disposition: attachment; filename=\"virtuvpn-router-local.apk\"\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${apk.length()}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        apk.inputStream().use { input -> input.copyTo(output) }
        output.flush()
    }

    @SuppressLint("ResourceType")
    private fun writeLauncherLogoResponse(context: Context, output: OutputStream) {
        val bytes = context.resources.openRawResource(R.mipmap.ic_launcher).use { input ->
            input.readBytes()
        }
        val headers =
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: image/png\r\n" +
                "Cache-Control: no-store\r\n" +
                "Content-Length: ${bytes.size}\r\n" +
                "Connection: close\r\n\r\n"
        output.write(headers.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    private fun localPairingPage(): String = """
        <!doctype html>
        <html lang="en">
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover" />
          <title>VirtuVPN Router</title>
          <style>
            :root{color-scheme:dark;--bg:#030607;--panel:#07100d;--line:rgba(255,255,255,.13);--text:#eefdf7;--muted:#8aa49a;--green:#10b981;--cyan:#67e8f9}
            *{box-sizing:border-box} body{margin:0;min-height:100svh;display:grid;place-items:center;background:radial-gradient(circle at 50% 0%,rgba(16,185,129,.18),transparent 36%),linear-gradient(180deg,#07100d 0%,#020403 100%);color:var(--text);font-family:Inter,ui-sans-serif,system-ui,-apple-system,Segoe UI,sans-serif;padding:22px}
            main{width:min(430px,100%);border:1px solid var(--line);border-radius:18px;background:linear-gradient(180deg,rgba(12,25,20,.92),rgba(3,7,6,.96));padding:24px 20px 22px;text-align:center;box-shadow:0 24px 70px rgba(0,0,0,.55),inset 0 1px 0 rgba(255,255,255,.09),inset 0 -2px 0 rgba(0,0,0,.45)}
            .iconButton{width:124px;height:124px;margin:0 auto 18px;border-radius:26px;border:1px solid rgba(110,231,183,.6);background:linear-gradient(180deg,rgba(16,185,129,.28),rgba(3,7,6,.98));display:grid;place-items:center;box-shadow:0 18px 42px rgba(0,0,0,.52),0 0 34px rgba(16,185,129,.22),inset 0 1px 0 rgba(255,255,255,.18),inset 0 -4px 0 rgba(0,0,0,.38);overflow:hidden}
            .iconButton img{width:124px;height:124px;object-fit:cover;display:block;filter:drop-shadow(0 3px 8px rgba(0,0,0,.45))}
            .eyebrow{margin:0 0 6px;text-transform:uppercase;letter-spacing:.18em;color:rgba(110,231,183,.86);font-size:11px;font-weight:800}.title{margin:0;font-size:25px;line-height:1.08;font-weight:850;letter-spacing:0}.copy{margin:12px auto 0;max-width:330px;color:var(--muted);font-size:14px;line-height:1.5}.actions{display:grid;gap:10px;margin-top:20px}
            .safeNote{margin:2px 0 0;border:1px solid rgba(103,232,249,.18);border-radius:12px;background:rgba(8,145,178,.08);color:#b7d8d0;font-size:12px;line-height:1.45;padding:10px 12px;text-align:left}
            .primary,.secondary{display:flex;align-items:center;justify-content:center;min-height:48px;border-radius:13px;text-decoration:none;font-weight:850;font-size:15px;letter-spacing:0}.button{appearance:none;width:100%;cursor:pointer}.primary{background:linear-gradient(180deg,#34d399,#059669);color:#02120b;border:1px solid rgba(167,243,208,.72);box-shadow:0 14px 28px rgba(5,150,105,.24),inset 0 1px 0 rgba(255,255,255,.38),inset 0 -2px 0 rgba(0,0,0,.22)}.secondary{color:#cffafe;border:1px solid rgba(103,232,249,.28);background:rgba(8,145,178,.08);box-shadow:inset 0 1px 0 rgba(255,255,255,.08)}.secondary.copied{color:#d1fae5;border-color:rgba(110,231,183,.42);background:rgba(16,185,129,.14)}
            .pairKey{display:block;max-height:88px;overflow:auto;border:1px solid rgba(255,255,255,.12);border-radius:12px;background:rgba(0,0,0,.26);color:#bbf7d0;font-family:ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,"Liberation Mono",monospace;font-size:11px;line-height:1.45;overflow-wrap:anywhere;padding:10px;text-align:left;user-select:all}.status{margin-top:14px;color:rgba(238,253,247,.45);font-size:12px}
          </style>
        </head>
        <body>
          <main>
            <div class="iconButton" aria-hidden="true"><img src="$LAUNCHER_LOGO_PATH" alt=""></div>
            <p class="eyebrow">VirtuVPN</p>
            <h1 class="title" id="title">Pair VPN Router</h1>
            <p class="copy" id="copy">Use this page on a device connected to the VirtuVPN Router hotspot. Install or update VirtuVPN from this router, copy the browser pair key, then paste it in Secured Browser.</p>
            <div class="actions">
              <a class="primary" href="/virtuvpn.apk">Install or update VirtuVPN APK</a>
              <button class="secondary button" id="copyPair" type="button">Copy browser pair key</button>
              <p class="safeNote">For safe browsing through this hotspot, use VirtuVPN Secured Browser. It verifies VPN Router protection before loading pages. No VCS sign-in or enroll is required for this guest browser flow.</p>
              <code class="pairKey" id="pairKey">Pair key missing</code>
            </div>
            <div class="status" id="status">Ready to pair Secured Browser with this VPN Router.</div>
          </main>
          <script>
            const params = new URLSearchParams(window.location.hash.replace(/^#/, ""));
            const id = params.get("id") || "";
            const secret = params.get("secret") || "";
            const hasPairing = id.length > 0 && secret.length > 0;
            const pairKey = hasPairing ? `virtuvpn://router-pair?id=${'$'}{encodeURIComponent(id)}&secret=${'$'}{encodeURIComponent(secret)}` : "";
            const pairNode = document.getElementById("pairKey");
            const copyButton = document.getElementById("copyPair");
            const status = document.getElementById("status");
            if (hasPairing) {
              pairNode.textContent = pairKey;
            } else {
              document.getElementById("title").textContent = "Pairing link missing";
              document.getElementById("copy").textContent = "This QR link is missing router pairing data. Open the QR again from the VPN Router page.";
              copyButton.disabled = true;
              status.textContent = "Open the QR again from the router.";
            }
            async function copyText(value) {
              if (navigator.clipboard && navigator.clipboard.writeText) {
                try { await navigator.clipboard.writeText(value); return true; } catch (_) {}
              }
              const input = document.createElement("textarea");
              input.value = value;
              input.setAttribute("readonly", "");
              input.style.position = "fixed";
              input.style.left = "-9999px";
              document.body.appendChild(input);
              input.select();
              const ok = document.execCommand("copy");
              document.body.removeChild(input);
              return ok;
            }
            copyButton.addEventListener("click", async () => {
              if (!pairKey) return;
              const ok = await copyText(pairKey);
              if (ok) {
                copyButton.textContent = "Browser pair key copied";
                copyButton.classList.add("copied");
                status.textContent = "Open VirtuVPN Secured Browser and paste the copied pair key.";
                window.setTimeout(() => { copyButton.textContent = "Copy browser pair key"; copyButton.classList.remove("copied"); }, 1800);
              } else {
                status.textContent = "Copy failed. Select and copy the browser pair key manually.";
              }
            });
          </script>
        </body>
        </html>
    """.trimIndent()

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
