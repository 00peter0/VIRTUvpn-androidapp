package com.wireguard.android.vcs

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Explicit, local read of metadata visible to this app. Never sends a boot broadcast. */
object StartupDiagnostics {
    @Suppress("DEPRECATION")
    suspend fun collect(context: Context, progress: suspend (Int, Int) -> Unit): StartupAppReport = withContext(Dispatchers.IO) {
        val manager = context.packageManager
        val deadline = SystemClock.elapsedRealtime() + 10_000
        val flags = PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DIRECT_BOOT_AWARE or PackageManager.MATCH_DIRECT_BOOT_UNAWARE
        val actions = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED)
        var receiverQueriesComplete = true
        val receivers = actions.associateWith { action ->
            currentCoroutineContext().ensureActive()
            read {
                manager.queryBroadcastReceivers(Intent(action).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES), flags)
                    .mapNotNull { it.activityInfo }
            } ?: emptyList<ActivityInfo>().also { receiverQueriesComplete = false }
        }
        val byPackage = receivers.values.flatten().groupBy { it.packageName }
        // Use the existing launcher visibility declaration and actually observed receivers.
        // Do not request QUERY_ALL_PACKAGES or imply that hidden apps were examined.
        val names = (manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), flags)
            .map { it.activityInfo.packageName } + byPackage.keys + context.packageName).distinct().sorted()
        val sample = names.take(512)
        val rows = mutableListOf<StartupApp>()
        var checked = 0
        var metadataComplete = true
        for (name in sample) {
            currentCoroutineContext().ensureActive()
            if (SystemClock.elapsedRealtime() >= deadline) break
            val info = read { manager.getPackageInfo(name, PackageManager.GET_PERMISSIONS or flags) }
            checked++
            progress(checked, sample.size)
            if (info == null) { metadataComplete = false; continue }
            if (info.requestedPermissions?.contains(Manifest.permission.RECEIVE_BOOT_COMPLETED) != true && name !in byPackage) continue
            val application = info.applicationInfo
            val enabledReceivers = byPackage[info.packageName].orEmpty().distinctBy { it.name }.map { receiver ->
                read {
                    when (manager.getComponentEnabledSetting(ComponentName(receiver.packageName, receiver.name))) {
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> receiver.enabled
                        else -> false
                    }
                }
            }
            val permissionGranted = read {
                manager.checkPermission(Manifest.permission.RECEIVE_BOOT_COMPLETED, info.packageName) == PackageManager.PERMISSION_GRANTED
            }
            rows += StartupApp(
                read { application?.let { manager.getApplicationLabel(it).toString() } } ?: info.packageName,
                info.packageName, application?.flags?.and(ApplicationInfo.FLAG_SYSTEM)?.let { it != 0 },
                StartupDiagnosticPolicy.status(application?.enabled,
                    application?.let { it.flags and ApplicationInfo.FLAG_STOPPED != 0 }, permissionGranted, enabledReceivers),
                receivers[Intent.ACTION_BOOT_COMPLETED].orEmpty().any { it.packageName == info.packageName },
                receivers[Intent.ACTION_LOCKED_BOOT_COMPLETED].orEmpty().any { it.packageName == info.packageName }
            )
        }
        val ordered = rows.sortedWith(compareBy<StartupApp> { it.systemApp == true }.thenBy { it.label.lowercase(java.util.Locale.ROOT) })
        StartupAppReport(ordered, names.size, checked,
            receiverQueriesComplete && metadataComplete && checked == names.size && rows.none { it.status == StartupAppStatus.UNVERIFIED },
            System.currentTimeMillis(), rows.size)
    }

    private inline fun <T> read(block: () -> T): T? = try { block() }
        catch (e: CancellationException) { throw e } catch (_: Exception) { null }
}
