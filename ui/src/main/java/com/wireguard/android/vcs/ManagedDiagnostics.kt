package com.wireguard.android.vcs

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.Environment
import android.os.PowerManager
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** User-initiated local observations. No root, configuration changes, telemetry or automatic sharing. */
object ManagedDiagnostics {
    suspend fun collect(context: Context): DeviceDiagnosticReport = withContext(Dispatchers.IO) {
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = read { ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) } }
        val ownMemory = read { Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss.toLong() * 1024 }
        val storage = read { StatFs(Environment.getDataDirectory().path) }
        val ownStorage = if (Build.VERSION.SDK_INT >= 26) read { packageStorage(context, context.packageName, "VirtuVPN") } else null
        val battery = read { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
        val batteryLevel = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val batteryScale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val power = context.getSystemService(PowerManager::class.java)
        val started = SystemClock.elapsedRealtime()
        val cpuStarted = Process.getElapsedCpuTime()
        delay(750) // Own process, not device-wide CPU load or another app's usage.
        val cpuSample = SystemClock.elapsedRealtime() - started
        val cpu = DeviceDiagnosticMath.cpuOneCorePercent(Process.getElapsedCpuTime() - cpuStarted, cpuSample)
        val network = context.getSystemService(ConnectivityManager::class.java)
        val security = DeviceSecurity(Build.VERSION.RELEASE, Build.VERSION.SECURITY_PATCH,
            DeviceDiagnosticMath.patchAgeDays(Build.VERSION.SECURITY_PATCH, LocalDate.now()),
            read { context.getSystemService(KeyguardManager::class.java).isDeviceSecure },
            read { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1 },
            read { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1 },
            read { !power.isIgnoringBatteryOptimizations(context.packageName) },
            if (Build.VERSION.SDK_INT >= 28) read { activity.isBackgroundRestricted } else null,
            read { network.getNetworkCapabilities(network.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) })
        val resources = DeviceResources(Runtime.getRuntime().availableProcessors(), cpu, cpuSample,
            memory?.totalMem, memory?.availMem, memory?.lowMemory, ownMemory,
            storage?.totalBytes, storage?.availableBytes, ownStorage,
            if (batteryLevel != null && batteryScale != null && batteryLevel >= 0 && batteryScale > 0)
                (batteryLevel * 100 / batteryScale).coerceIn(0, 100) else null,
            battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0),
            if (Build.VERSION.SDK_INT >= 29) read { power.currentThermalStatus } else null)
        var tunnelsComplete = true
        val tunnels = withTimeoutOrNull(4000) {
            val all = Application.getTunnelManager().getTunnels()
            tunnelsComplete = all.size <= 32
            all.take(32).mapIndexed { index, tunnel ->
                val config = readSuspend { tunnel.getConfigAsync() }
                val stats = readSuspend { tunnel.getStatisticsAsync() }
                if (config == null || stats == null) tunnelsComplete = false
                val handshake = stats?.peers()?.mapNotNull { stats.peer(it)?.latestHandshakeEpochMillis()?.takeIf { time -> time > 0 } }?.maxOrNull()
                val routes = config?.peers?.flatMap { it.allowedIps }?.map { it.toString() }
                TunnelDiagnostic(index + 1, tunnel.state.name, config?.`interface`?.dnsServers?.size,
                    routes?.contains("0.0.0.0/0"), routes?.contains("::/0"),
                    handshake?.let { ((System.currentTimeMillis() - it) / 1000).coerceAtLeast(0) })
            }
        } ?: emptyList<TunnelDiagnostic>().also { tunnelsComplete = false }
        DeviceDiagnosticReport(System.currentTimeMillis(), resources, security,
            VcsManagedClient.hasAccountSession(context), VcsManagedClient.hasSession(context),
            VcsManagedClient.syncStatus(context).optString("phase", "No synchronization recorded"),
            ManagedApkUpdates.progress(context), tunnels, tunnelsComplete)
    }

    fun hasUsageAccess(context: Context): Boolean = read {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } ?: false

    /** Optional bounded launcher-app sample. App labels stay in UI; no inventory is exported. */
    @Suppress("DEPRECATION")
    suspend fun appStorage(context: Context, progress: suspend (Int, Int) -> Unit): AppStorageReport = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) return@withContext AppStorageReport(emptyList(), 0, 0, false, System.currentTimeMillis())
        check(hasUsageAccess(context))
        val packages = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.distinct().sorted()
        val candidates = packages.take(64)
        val deadline = SystemClock.elapsedRealtime() + 15_000
        val rows = mutableListOf<AppStorage>()
        var attempted = 0
        for (name in candidates) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline || !hasUsageAccess(context)) break
            val label = read { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(name, 0)).toString() }
            read { packageStorage(context, name, label ?: "Application") }?.let(rows::add)
            attempted++
            progress(attempted, candidates.size)
        }
        AppStorageReport(rows.sortedByDescending { it.totalBytes }.take(8), packages.size, rows.size,
            attempted == packages.size && rows.size == packages.size, System.currentTimeMillis())
    }

    @RequiresApi(26)
    @Suppress("DEPRECATION")
    private fun packageStorage(context: Context, name: String, label: String): AppStorage {
        val info = context.packageManager.getApplicationInfo(name, 0)
        val stats = context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(info.storageUuid ?: StorageManager.UUID_DEFAULT, name, Process.myUserHandle())
        return AppStorage(label, name, stats.appBytes, stats.dataBytes, stats.cacheBytes)
    }

    /** Allowlist: no app/VPN names, addresses, identifiers, credentials or browser history. */
    fun export(report: DeviceDiagnosticReport, ports: PhonePortReport?, apps: AppStorageReport?): JSONObject {
        val r = report.resources; val s = report.security
        val result = JSONObject().put("schemaVersion", 2).put("collectedAt", report.collectedAt)
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("accountSessionPresent", report.accountSessionPresent).put("deviceSessionPresent", report.deviceSessionPresent)
            .put("syncPhase", report.syncPhase).put("update", report.updatePhase)
            .put("resources", JSONObject().put("logicalCpusAvailableToApp", r.logicalCpus)
                .putNullable("appCpuOneCorePercent", r.appCpuOneCorePercent).put("cpuSampleMillis", r.cpuSampleMillis)
                .putNullable("memoryTotalBytes", r.memoryTotal).putNullable("memoryAvailableBytes", r.memoryAvailable)
                .putNullable("androidLowMemory", r.memoryLow).putNullable("appMemoryPssBytes", r.appMemoryPss)
                .putNullable("storageTotalBytes", r.storageTotal).putNullable("storageAvailableBytes", r.storageAvailable)
                .putNullable("appStorageBytes", r.ownStorage?.totalBytes).putNullable("appCacheBytes", r.ownStorage?.cacheBytes)
                .putNullable("batteryPercent", r.batteryPercent).putNullable("batteryCelsius", r.batteryCelsius)
                .putNullable("thermalStatus", r.thermalStatus))
            .put("security", JSONObject().put("androidVersion", s.androidVersion).put("securityPatch", s.securityPatch)
                .putNullable("patchAgeDays", s.patchAgeDays).putNullable("secureScreenLock", s.screenLock)
                .putNullable("usbDebuggingEnabled", s.usbDebugging).putNullable("developerOptionsEnabled", s.developerOptions)
                .putNullable("batteryOptimized", s.batteryOptimized).putNullable("backgroundRestricted", s.backgroundRestricted)
                .putNullable("activeNetworkVpnTransport", s.vpnTransport))
            .put("tunnelEvidenceComplete", report.tunnelEvidenceComplete)
            .put("tunnels", JSONArray().also { array -> report.tunnels.forEach { t -> array.put(JSONObject().put("index", t.index)
                .put("state", t.state).putNullable("dnsServersConfigured", t.dnsCount).putNullable("defaultIpv4RouteConfigured", t.defaultIpv4)
                .putNullable("defaultIpv6RouteConfigured", t.defaultIpv6).putNullable("handshakeAgeSeconds", t.handshakeAgeSeconds)) } })
            .put("limits", "Local observations only. Per-app CPU/RAM outside VirtuVPN is unavailable. Tunnel UP does not prove reachability or DNS leak prevention. Port probes cover selected TCP ports on loopback only, not LAN/public exposure, UDP, service identity or malware. App storage is a bounded visible-app sample and does not cover the whole filesystem. No data is deleted.")
        ports?.let { p -> result.put("portScan", JSONObject().put("collectedAt", p.collectedAt).put("scope", "IPv4 and IPv6 loopback TCP")
            .put("portsPerAddress", JSONArray(PhonePortScan.PORTS)).put("attempted", p.completed).put("total", p.total)
            .put("complete", p.complete).put("unknown", p.unknown).put("accepting", JSONArray().also { a ->
                p.accepting.forEach { a.put(JSONObject().put("family", it.family).put("port", it.port)) } })) }
        apps?.let { a -> result.put("appStorageSample", JSONObject().put("collectedAt", a.collectedAt).put("visibleApps", a.visible)
            .put("measuredApps", a.measured).put("complete", a.complete).put("largestMeasured", JSONArray().also { j ->
                a.apps.forEachIndexed { i, app -> j.put(JSONObject().put("index", i + 1).put("totalBytes", app.totalBytes).put("cacheBytes", app.cacheBytes)) } })) }
        return result
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private inline fun <T> read(block: () -> T): T? = try { block() } catch (_: Exception) { null }
    private suspend fun <T> readSuspend(block: suspend () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
