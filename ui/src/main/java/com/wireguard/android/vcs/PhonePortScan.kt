package com.wireguard.android.vcs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

data class PhonePort(val family: String, val port: Int)
data class PhonePortReport(val accepting: List<PhonePort>, val completed: Int, val total: Int, val unknown: Int, val collectedAt: Long) {
    val complete: Boolean get() = completed == total && unknown == 0
}
enum class PhonePortResult { ACCEPTING, REFUSED, UNKNOWN }

/** Fixed numeric loopback targets; no DNS, payloads, banners or remote hosts. */
object PhonePortScan {
    val PORTS: List<Int> = listOf(21, 22, 23, 53, 80, 443, 445, 1080, 1883, 3000, 3389, 5000, 5037, 5432, 5555, 5900,
        5901, 6379, 6667, 7547, 8000, 8008, 8022, 8080, 8081, 8443, 8888, 9000, 9090, 9200, 11211, 27017)
    private val loopback = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)),
        InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 }))
    suspend fun scan(progress: suspend (Int, Int) -> Unit): PhonePortReport = withContext(Dispatchers.IO) {
        val completed = AtomicInteger(); val slots = Semaphore(6); val total = PORTS.size * loopback.size
        val results = coroutineScope {
            loopback.flatMapIndexed { family, address -> PORTS.map { port -> async {
                slots.withPermit {
                    currentCoroutineContext().ensureActive()
                    val result = probe(address, port)
                    progress(completed.incrementAndGet(), total)
                    PhonePort(if (family == 0) "IPv4" else "IPv6", port) to result
                }
            } } }.awaitAll()
        }
        PhonePortReport(results.filter { it.second == PhonePortResult.ACCEPTING }.map { it.first }, completed.get(), total,
            results.count { it.second == PhonePortResult.UNKNOWN }, System.currentTimeMillis())
    }
    internal fun probe(address: InetAddress, port: Int): PhonePortResult {
        require(address.isLoopbackAddress && port in 1..65535)
        return try {
            Socket().use { it.connect(InetSocketAddress(address, port), 180) }
            PhonePortResult.ACCEPTING
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", ignoreCase = true) == true) PhonePortResult.REFUSED else PhonePortResult.UNKNOWN
        } catch (_: Exception) { PhonePortResult.UNKNOWN }
    }
}
