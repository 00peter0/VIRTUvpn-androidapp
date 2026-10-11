package com.wireguard.android.vcs

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Unavailable measurements remain null, rather than becoming zeros or green results. */
data class DeviceResources(
    val logicalCpus: Int, val appCpuOneCorePercent: Double?, val cpuSampleMillis: Long,
    val memoryTotal: Long?, val memoryAvailable: Long?, val memoryLow: Boolean?, val appMemoryPss: Long?,
    val storageTotal: Long?, val storageAvailable: Long?, val ownStorage: AppStorage?,
    val batteryPercent: Int?, val batteryCelsius: Double?, val thermalStatus: Int?
)

data class AppStorage(val label: String, val packageName: String, val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
    // Android includes cache in dataBytes; adding it again would overstate usage.
    val totalBytes: Long get() = appBytes + dataBytes
}
data class AppStorageReport(
    val apps: List<AppStorage>, val visible: Int, val measured: Int, val complete: Boolean, val collectedAt: Long,
    val measuredTotalBytes: Long = apps.sumOf { it.totalBytes }
)
data class AppUsage(val label: String, val foregroundMillis: Long)
data class AppUsageReport(
    val apps: List<AppUsage>, val usedApps: Int, val totalForegroundMillis: Long,
    val recordBegin: Long, val recordEnd: Long, val collectedAt: Long
)
data class AppResourceReport(val storage: AppStorageReport, val usage: AppUsageReport?)
data class DeviceSecurity(
    val androidVersion: String, val securityPatch: String, val patchAgeDays: Long?,
    val screenLock: Boolean?, val usbDebugging: Boolean?, val developerOptions: Boolean?
)
data class DeviceDiagnosticReport(
    val collectedAt: Long, val resources: DeviceResources, val security: DeviceSecurity
)

object DeviceDiagnosticMath {
    fun sharePercent(amount: Long?, total: Long?): Double? =
        if (amount == null || total == null || total <= 0 || amount < 0 || amount > total) null
        else amount.toDouble() * 100 / total
    fun usedPercent(total: Long?, available: Long?): Int? {
        if (total == null || available == null || total <= 0 || available < 0 || available > total) return null
        return ((total - available).toDouble() * 100 / total).toInt().coerceIn(0, 100)
    }
    fun cpuOneCorePercent(cpuMillis: Long, elapsedMillis: Long): Double? =
        if (cpuMillis < 0 || elapsedMillis <= 0) null else cpuMillis.toDouble() * 100 / elapsedMillis
    fun patchAgeDays(patch: String, today: LocalDate): Long? = runCatching {
        ChronoUnit.DAYS.between(LocalDate.parse(patch), today).takeIf { it >= 0 }
    }.getOrNull()
}
