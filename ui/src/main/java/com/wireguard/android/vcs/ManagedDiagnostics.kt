package com.wireguard.android.vcs

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.usage.StorageStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import com.wireguard.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
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
        val security = DeviceSecurity(Build.VERSION.RELEASE, Build.VERSION.SECURITY_PATCH,
            DeviceDiagnosticMath.patchAgeDays(Build.VERSION.SECURITY_PATCH, LocalDate.now()),
            read { context.getSystemService(KeyguardManager::class.java).isDeviceSecure },
            read { Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED) == 1 },
            read { Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1 })
        val resources = DeviceResources(Runtime.getRuntime().availableProcessors(), cpu, cpuSample,
            memory?.totalMem, memory?.availMem, memory?.lowMemory, ownMemory,
            storage?.totalBytes, storage?.availableBytes, ownStorage,
            if (batteryLevel != null && batteryScale != null && batteryLevel >= 0 && batteryScale > 0)
                (batteryLevel * 100 / batteryScale).coerceIn(0, 100) else null,
            battery?.takeIf { it.hasExtra(BatteryManager.EXTRA_TEMPERATURE) }?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.div(10.0),
            if (Build.VERSION.SDK_INT >= 29) read { power.currentThermalStatus } else null)
        DeviceDiagnosticReport(System.currentTimeMillis(), resources, security)
    }

    fun hasUsageAccess(context: Context): Boolean = read {
        val ops = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    } ?: false

    /** Optional bounded launcher-app sample and recent Android daily usage records. */
    @Suppress("DEPRECATION")
    suspend fun appResources(context: Context, progress: suspend (Int, Int) -> Unit): AppResourceReport = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < 26) return@withContext AppResourceReport(
            AppStorageReport(emptyList(), 0, 0, false, System.currentTimeMillis()), null)
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
        val storage = AppStorageReport(rows.sortedByDescending { it.totalBytes }.take(8), packages.size, rows.size,
            attempted == packages.size && rows.size == packages.size, System.currentTimeMillis(), rows.sumOf { it.totalBytes })
        currentCoroutineContext().ensureActive()
        val now = System.currentTimeMillis()
        // Android may expand the requested day to complete daily buckets. Display actual record bounds.
        val usage = if (hasUsageAccess(context)) read {
            val records = context.getSystemService(UsageStatsManager::class.java)
                .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 86_400_000, now)
                ?.filter { it.packageName in packages && it.totalTimeInForeground > 0 }
            if (records.isNullOrEmpty()) null else {
                val durations = records.groupBy { it.packageName }.mapValues { (_, daily) -> daily.sumOf { it.totalTimeInForeground } }
                val largest = durations.entries.sortedByDescending { it.value }.take(8).map { entry ->
                    val label = read { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(entry.key, 0)).toString() }
                    AppUsage(label ?: "Application", entry.value)
                }
                AppUsageReport(largest, durations.size, durations.values.sum(), records.minOf { it.firstTimeStamp },
                    records.maxOf { it.lastTimeStamp }, System.currentTimeMillis())
            }
        } else null
        AppResourceReport(storage, usage)
    }

    @RequiresApi(26)
    @Suppress("DEPRECATION")
    private fun packageStorage(context: Context, name: String, label: String): AppStorage {
        val info = context.packageManager.getApplicationInfo(name, 0)
        val stats = context.getSystemService(StorageStatsManager::class.java).queryStatsForPackage(info.storageUuid ?: StorageManager.UUID_DEFAULT, name, Process.myUserHandle())
        return AppStorage(label, name, stats.appBytes, stats.dataBytes, stats.cacheBytes)
    }

    /** Allowlist: no app/VPN names, addresses, identifiers, credentials or browser history. */
    fun export(report: DeviceDiagnosticReport, apps: AppStorageReport?, startup: StartupAppReport?): JSONObject {
        val r = report.resources; val s = report.security
        val result = JSONObject().put("schemaVersion", 3).put("collectedAt", report.collectedAt)
            .put("appVersion", BuildConfig.VERSION_NAME).put("appVersionCode", BuildConfig.VERSION_CODE)
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
                .putNullable("usbDebuggingEnabled", s.usbDebugging).putNullable("developerOptionsEnabled", s.developerOptions))
            .put("limits", "Local observations only. Per-app CPU/RAM outside VirtuVPN is unavailable. App storage is a bounded visible-app sample and does not cover the whole filesystem. Startup checks cover visible metadata, not observed boot execution or malware detection. No data is deleted.")
        apps?.let { a -> result.put("appStorageSample", JSONObject().put("collectedAt", a.collectedAt).put("visibleApps", a.visible)
            .put("measuredApps", a.measured).put("complete", a.complete).put("measuredTotalBytes", a.measuredTotalBytes).put("largestMeasured", JSONArray().also { j ->
                a.apps.forEachIndexed { i, app -> j.put(JSONObject().put("index", i + 1).put("totalBytes", app.totalBytes).put("cacheBytes", app.cacheBytes)) } })) }
        startup?.let { sample -> result.put("startupSummary", JSONObject().put("collectedAt", sample.collectedAt)
            .put("visibleApps", sample.visibleApps).put("identified", sample.identified).put("checked", sample.checked).put("complete", sample.complete)
            .put("enabledBootReceivers", sample.apps.count { it.status == StartupAppStatus.ENABLED_RECEIVER })
            .put("unverified", sample.apps.count { it.status == StartupAppStatus.UNVERIFIED || it.status == StartupAppStatus.PERMISSION_ONLY })) }
        return result
    }

    private fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
    private inline fun <T> read(block: () -> T): T? = try { block() } catch (_: Exception) { null }
}
