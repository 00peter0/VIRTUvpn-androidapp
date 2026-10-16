package com.wireguard.android.vcs

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.wireguard.android.BuildConfig
import com.wireguard.android.activity.ManagedUpdateActivity
import java.io.File
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.UUID

/** Explicit test runner: creates an empty, uncommitted owned session; never installs an APK. */
class UpdateRecoveryInstrumentation : Instrumentation() {
    private lateinit var arguments: Bundle
    private val checks = mutableListOf<String>()
    private val backup get() = File(targetContext.noBackupFilesDir, "update-recovery-test-state.bin")
    private val prefs get() = targetContext.getSharedPreferences("managed_update_flow", 0)

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        this.arguments = arguments ?: Bundle()
        start()
    }

    override fun onStart() {
        super.onStart()
        val result = Bundle()
        try {
            if (arguments.getString("restoreOnly") == "true") restoreBackup()
            else exerciseRecovery()
            result.putString("checks", checks.joinToString("\n"))
            result.putString("result", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (error: Throwable) {
            result.putString("result", "FAIL")
            result.putString("error", error.stackTraceToString())
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    private fun record(name: String, condition: Boolean) {
        check(condition) { name }
        checks += name
    }

    private fun restoreBackup() {
        if (!backup.exists()) return
        @Suppress("UNCHECKED_CAST")
        val original = ObjectInputStream(backup.inputStream()).use { it.readObject() as Map<String, *> }
        val editor = prefs.edit().clear()
        original.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Set<*> -> {
                    check(value.all { it is String })
                    editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
                else -> error("Unsupported saved preference type")
            }
        }
        check(editor.commit())
        ManagedUpdateFlow.restore(targetContext)
        check(backup.delete())
    }

    private fun fixture(id: Int, phase: UpdatePhase, delayed: Boolean = false, target: Long = BuildConfig.VERSION_CODE.toLong() + 1) {
        val now = SystemClock.elapsedRealtime()
        check(now > UpdateInstallSafety.DELAY_MILLIS)
        val boot = Settings.Global.getInt(targetContext.contentResolver, Settings.Global.BOOT_COUNT, -1)
        check(prefs.edit().clear().putString("phase", phase.name).putLong("version_code", target)
            .putString("version_name", "Controlled recovery test").putInt("session_id", id)
            .putInt("watch_session", id).putInt("watch_boot", boot).putInt("watch_progress", 99)
            .putLong("watch_since", if (delayed) now - UpdateInstallSafety.DELAY_MILLIS - 1000 else now)
            .putBoolean("commit_attempted", false).commit())
        ManagedUpdateFlow.restore(targetContext)
    }

    private fun result(id: Int, status: Int) = Intent(targetContext.packageName + ".UPDATE_RESULT")
        .putExtra(PackageInstaller.EXTRA_SESSION_ID, id).putExtra(PackageInstaller.EXTRA_STATUS, status)

    private fun node(id: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (SystemClock.elapsedRealtime() < deadline) {
            val found = uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId(targetContext.packageName + ":id/" + id)
            if (!found.isNullOrEmpty()) return found.first()
            Thread.sleep(100)
        }
        error("UI node unavailable: $id")
    }

    private fun storageSystemUi(): Boolean {
        val packageName = uiAutomation.rootInActiveWindow?.packageName?.toString() ?: return false
        if (packageName == targetContext.packageName) return false
        return runCatching {
            val info = targetContext.packageManager.getApplicationInfo(packageName, 0)
            info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        }.getOrDefault(false)
    }

    private fun exerciseRecovery() {
        check(!backup.exists()) { "Restore the previous test snapshot with restoreOnly before rerunning" }
        check(targetContext.packageName == "com.virtuvpn.android")
        check(!ManagedUpdateFlow.hasPendingWork(targetContext)) { "A real update is pending; test refused" }
        val original = HashMap(prefs.all)
        ObjectOutputStream(backup.outputStream()).use { it.writeObject(original) }
        val canary = File(targetContext.filesDir, "update-recovery-canary-${UUID.randomUUID()}")
        val contents = "Existing app data must survive update recovery"
        canary.writeText(contents)
        val installer = targetContext.packageManager.packageInstaller
        val originalSessions = installer.mySessions.map { it.sessionId }.toSet()
        val originalDownload = ManagedApkUpdates.pendingId(targetContext)
        var id = -1
        var open: PackageInstaller.Session? = null
        var activity: Activity? = null
        try {
            id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(targetContext.packageName)
            })
            open = installer.openSession(id) // This explicit empty test session is active, but never committed.
            record("real Android session is owned and active", installer.mySessions.any { it.sessionId == id && it.isActive })
            fixture(id, UpdatePhase.INSTALLING, delayed = true)
            ManagedUpdateFlow.refreshInstallation(targetContext)
            record("unchanged progress becomes unconfirmed instead of success", ManagedUpdateFlow.state.value.phase == UpdatePhase.INSTALLATION_DELAYED)
            record("active session is retained", installer.getSessionInfo(id)?.isActive == true)
            ManagedUpdateFlow.restore(targetContext)
            ManagedUpdateFlow.refreshInstallation(targetContext)
            record("reopening retains the waiting period", ManagedUpdateFlow.state.value.phase == UpdatePhase.INSTALLATION_DELAYED)

            val service = uiAutomation.serviceInfo
            service.flags = service.flags or AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            uiAutomation.serviceInfo = service
            activity = startActivitySync(Intent(targetContext, ManagedUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            record("delayed UI offers Check installation, not another install", node("update_action").text.toString() == "Check installation")
            record("delayed UI explains the unconfirmed result", node("update_status").text.toString().contains("not confirmed"))
            record("storage settings action is visible", node("update_storage").isVisibleToUser)
            record("available storage is shown", node("update_storage_info").text.toString().contains("Available on app storage"))
            node("update_action").performAction(AccessibilityNodeInfo.ACTION_CLICK)
            record("checking again does not abandon the session", installer.getSessionInfo(id)?.isActive == true)
            check(node("update_storage").performAction(AccessibilityNodeInfo.ACTION_CLICK))
            val deadline = SystemClock.elapsedRealtime() + 8000
            while (!storageSystemUi() && SystemClock.elapsedRealtime() < deadline)
                Thread.sleep(100)
            // Samsung may delegate this system action to My Files / Analyze Storage.
            record("storage action opens a system storage handler", storageSystemUi())
            runOnMainSync { activity?.finish() }
            activity = null

            open.close(); open = null
            fixture(id, UpdatePhase.INSTALLING)
            ManagedUpdateFlow.refreshInstallation(targetContext)
            record("inactive uncommitted installation offers retry", ManagedUpdateFlow.state.value.phase == UpdatePhase.FAILED)
            record("interruption explains retained data", ManagedUpdateFlow.state.value.message?.contains("app data and VPN tunnels are preserved") == true)
            record("observation alone does not remove the stale session", installer.getSessionInfo(id) != null)
            activity = startActivitySync(Intent(targetContext, ManagedUpdateActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            record("interrupted UI exposes Retry", node("update_action").text.toString() == "Retry")

            ManagedUpdateFlow.result(targetContext, result(id, PackageInstaller.STATUS_FAILURE_STORAGE))
            record("storage failure offers storage help", ManagedUpdateFlow.state.value.phase == UpdatePhase.FAILED && ManagedUpdateFlow.state.value.storageHelp)
            ManagedUpdateFlow.restore(targetContext)
            record("storage help survives state restoration", ManagedUpdateFlow.state.value.storageHelp)
            val failed = ManagedUpdateFlow.state.value
            ManagedUpdateFlow.result(targetContext, result(-1, PackageInstaller.STATUS_SUCCESS))
            record("foreign callback is ignored", ManagedUpdateFlow.state.value == failed)
            ManagedUpdateFlow.result(targetContext, result(id, PackageInstaller.STATUS_SUCCESS))
            record("success callback cannot prove a future version installed", ManagedUpdateFlow.state.value.phase != UpdatePhase.COMPLETED)
            fixture(id, UpdatePhase.INSTALLING, target = BuildConfig.VERSION_CODE.toLong())
            ManagedUpdateFlow.result(targetContext, result(id, PackageInstaller.STATUS_FAILURE_ABORTED))
            record("late failure cannot undo confirmed installed version", ManagedUpdateFlow.state.value.phase == UpdatePhase.COMPLETED)
            record("retained update download identity is unchanged", ManagedApkUpdates.pendingId(targetContext) == originalDownload)
            record("existing app data survives", canary.readText() == contents)
        } finally {
            try {
                runOnMainSync { activity?.finish() }
                open?.close()
                if (id >= 0 && installer.mySessions.any { it.sessionId == id }) installer.abandonSession(id)
            } finally {
                restoreBackup()
                check(canary.delete())
            }
            check(prefs.all == original) { "Update state was not restored exactly" }
            check(installer.mySessions.map { it.sessionId }.toSet() == originalSessions) { "Original installer sessions changed" }
        }
        checks += "original preferences and installer sessions restored exactly"
    }
}
