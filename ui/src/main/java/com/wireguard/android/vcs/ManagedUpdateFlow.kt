package com.wireguard.android.vcs

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import com.wireguard.android.BuildConfig
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/** One application-owned workflow. UI dismissal never cancels a download or launches background install UI. */
object ManagedUpdateFlow {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val mutableState = MutableStateFlow(UpdateFlowState())
    val state = mutableState.asStateFlow()
    @Volatile private var confirmation: Intent? = null
    private var verified: File? = null
    private fun prefs(context: Context) = context.getSharedPreferences("managed_update_flow", Context.MODE_PRIVATE)
    private fun sessionId(context: Context) = prefs(context).getInt("session_id", -1)
    @android.annotation.SuppressLint("UseKtx") // KTX edit returns Unit; durable state must check the commit result.
    private fun commit(context: Context, block: android.content.SharedPreferences.Editor.() -> Unit) {
        check(prefs(context).edit().apply(block).commit()) { "Cannot persist update state" }
    }
    fun hasPendingWork(context: Context) = ManagedApkUpdates.pendingId(context) > 0 ||
        state.value.phase in setOf(UpdatePhase.DOWNLOADING, UpdatePhase.PAUSED, UpdatePhase.VERIFYING,
            UpdatePhase.READY, UpdatePhase.STAGING, UpdatePhase.CONFIRMING, UpdatePhase.INSTALLING)
    fun summary(): String? = when (state.value.phase) {
        UpdatePhase.CHECKING -> null
        UpdatePhase.DOWNLOADING -> "Downloading update${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.PAUSED -> "Update download paused — waiting for network/storage"
        UpdatePhase.VERIFYING -> "Verifying update${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.READY -> "Update verified — reopen Check updates to continue installation"
        UpdatePhase.STAGING -> "Preparing update for Android${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.CONFIRMING -> "Update is waiting for Android installation confirmation"
        UpdatePhase.INSTALLING -> "Android is installing the update${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.COMPLETED -> "Update installed successfully"
        UpdatePhase.UP_TO_DATE -> "VirtuVPN is up to date"
        UpdatePhase.FAILED -> "Update interrupted — reopen Check updates to retry"
    }
    private fun save(context: Context, value: UpdateFlowState) {
        commit(context) { putString("phase", value.phase.name); putString("version_name", value.versionName); putString("message", value.message) }
        mutableState.value = value
    }
    fun restore(context: Context) {
        val p = prefs(context)
        val target = p.getLong("version_code", 0)
        val phase = if (UpdateFlowState.installed(BuildConfig.VERSION_CODE.toLong(), target)) UpdatePhase.COMPLETED
            else runCatching { UpdatePhase.valueOf(p.getString("phase", "CHECKING") ?: "CHECKING") }.getOrDefault(UpdatePhase.CHECKING)
        mutableState.value = UpdateFlowState(phase, p.getString("version_name", null), message = p.getString("message", null))
    }
    @Synchronized
    fun start(context: Context, retry: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        restore(app)
        if (mutableState.value.phase in setOf(UpdatePhase.CONFIRMING, UpdatePhase.INSTALLING)) { refreshInstallation(app); return }
        if (!retry && mutableState.value.phase == UpdatePhase.FAILED) return
        job = scope.launch {
            try {
                var manifest = ManagedApkUpdates.manifest(app)
                val download = ManagedApkUpdates.download(app)
                if (manifest == null || !ManagedApkUpdates.validManifest(manifest) || download == null || download.status == DownloadManager.STATUS_FAILED) {
                    save(app, UpdateFlowState(UpdatePhase.CHECKING))
                    val available = VcsManagedClient.checkForManagedUpdate(app)
                    if (!available.available) { save(app, UpdateFlowState(UpdatePhase.UP_TO_DATE)); return@launch }
                    check(VcsManagedClient.openManagedUpdate(app) != null) { "Update download could not start" }
                    manifest = ManagedApkUpdates.manifest(app) ?: error("Update manifest missing")
                }
                commit(app) { putLong("version_code", manifest.getLong("latestVersionCode")) }
                val version = manifest.getString("latestVersionName")
                save(app, UpdateFlowState(UpdatePhase.DOWNLOADING, version))
                while (true) {
                    val download = ManagedApkUpdates.download(app) ?: error("Download no longer available")
                    if (download.status == DownloadManager.STATUS_SUCCESSFUL) break
                    check(download.status != DownloadManager.STATUS_FAILED) { "Download failed (${download.reason})" }
                    val phase = if (download.status == DownloadManager.STATUS_PAUSED) UpdatePhase.PAUSED else UpdatePhase.DOWNLOADING
                    val value = UpdateFlowState(phase, version, UpdateFlowState.percent(download.bytes, download.total), download.bytes, download.total)
                    if (mutableState.value.phase != phase) save(app, value) else mutableState.value = value
                    delay(500)
                }
                save(app, UpdateFlowState(UpdatePhase.VERIFYING, version))
                try {
                    verified = ManagedApkUpdates.verify(app, app.getSystemService(DownloadManager::class.java), ManagedApkUpdates.pendingId(app)) { percent ->
                        mutableState.value = UpdateFlowState(UpdatePhase.VERIFYING, version, percent)
                    }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    app.getSystemService(DownloadManager::class.java)?.remove(ManagedApkUpdates.pendingId(app))
                    ManagedApkUpdates.forget(app)
                    throw error
                }
                save(app, UpdateFlowState(UpdatePhase.READY, version))
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(app, when (state.value.phase) {
                    UpdatePhase.VERIFYING -> "Update security verification failed. The rejected download was removed. Retry to download a fresh verified update."
                    UpdatePhase.CHECKING -> "Unable to check updates. Check your connection and VCS device access, then retry."
                    else -> "Download could not complete. Check your connection and free storage, then retry."
                })
            }
        }
    }
    fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    private fun sender(context: Context, id: Int): IntentSender {
        val intent = Intent(context, UpdateInstallReceiver::class.java).setAction(context.packageName + ".UPDATE_RESULT")
            .setData("virtuvpn-update://session/$id".toUri())
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, id, intent, flags).intentSender
    }
    @Synchronized
    fun install(context: Context) {
        if (job?.isActive == true || !canInstall(context) || mutableState.value.phase != UpdatePhase.READY) return
        val app = context.applicationContext
        job = scope.launch {
            var created = -1
            try {
                val manifest = ManagedApkUpdates.manifest(app) ?: error("Update manifest missing")
                check(ManagedApkUpdates.validManifest(manifest))
                val file = verified ?: error("Reopen the update to verify the download")
                val installer = app.packageManager.packageInstaller
                // Remove only our recorded stale session, never another package's/session's work.
                val old = installer.mySessions.firstOrNull { it.sessionId == sessionId(app) }
                if (old != null) {
                    check(!old.isActive) { "An installation is already active" }
                    installer.abandonSession(old.sessionId)
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName); setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                created = installer.createSession(params)
                commit(app) { putInt("session_id", created) }
                confirmation = null
                save(app, state.value.copy(phase = UpdatePhase.STAGING, percent = 0))
                installer.openSession(created).use { session ->
                    val hash = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input -> session.openWrite("base.apk", 0, file.length()).use { output ->
                        val buffer = ByteArray(32 * 1024); var count = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            output.write(buffer, 0, n); hash.update(buffer, 0, n); count += n
                            val progress = UpdateFlowState.percent(count, file.length()) ?: 0
                            session.setStagingProgress(progress / 100f)
                            mutableState.value = state.value.copy(percent = progress)
                        }
                        session.fsync(output)
                    } }
                    check(hash.digest().joinToString("") { "%02x".format(it) } == manifest.getJSONObject("integrity").getString("sha256")) { "Verified APK changed" }
                    save(app, state.value.copy(phase = UpdatePhase.CONFIRMING, percent = null))
                    session.commit(sender(app, created))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (created >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(created) }
                fail(app, "Android could not prepare this installation. Your downloaded update is retained; retry to verify it again.")
            }
        }
    }
    @Synchronized fun takeConfirmation(): Intent? = confirmation.also { confirmation = null }
    @Synchronized fun restoreConfirmation(context: Context, intent: Intent) { confirmation = intent }
    @Synchronized
    fun continueInstallation(context: Context) {
        if (job?.isActive == true || state.value.phase != UpdatePhase.CONFIRMING) return
        val app = context.applicationContext; val id = sessionId(app)
        job = scope.launch {
            try { app.packageManager.packageInstaller.openSession(id).use { it.commit(sender(app, id)) } }
            catch (error: Exception) {
                if (error is CancellationException) throw error
                fail(app, "Installation confirmation could not resume. Retry to prepare the retained download again.")
            }
        }
    }
    fun installerReturned(context: Context) {
        if (state.value.phase == UpdatePhase.CONFIRMING && context.packageManager.packageInstaller.getSessionInfo(sessionId(context)) != null)
            save(context, state.value.copy(phase = UpdatePhase.INSTALLING, percent = null))
        refreshInstallation(context)
    }
    @Suppress("DEPRECATION")
    fun refreshInstallation(context: Context) {
        if (state.value.phase !in setOf(UpdatePhase.CONFIRMING, UpdatePhase.INSTALLING)) return
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (UpdateFlowState.installed(code, prefs(context).getLong("version_code", 0))) {
            save(context, state.value.copy(phase = UpdatePhase.COMPLETED, percent = 100)); return
        }
        val info = context.packageManager.packageInstaller.getSessionInfo(sessionId(context))
        if (info == null) { fail(context, "Installation was cancelled or interrupted. Retry to use the retained download."); return }
        if (state.value.phase == UpdatePhase.INSTALLING) mutableState.value = state.value.copy(percent = (info.progress * 100).toInt().coerceIn(0, 99))
    }
    @Suppress("DEPRECATION")
    fun result(context: Context, intent: Intent) {
        if (intent.action != context.packageName + ".UPDATE_RESULT" ||
            !UpdateFlowState.acceptsResult(sessionId(context), intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))) return
        restore(context)
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) fail(context, "Android installation confirmation is unavailable. Retry the update.")
                else save(context, state.value.copy(phase = UpdatePhase.CONFIRMING, percent = null))
            }
            PackageInstaller.STATUS_SUCCESS -> save(context, state.value.copy(phase = UpdatePhase.COMPLETED, percent = 100))
            else -> {
                confirmation = null
                fail(context, when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled. Your verified download is retained."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Android needs more free storage to install the update."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked installation. Check device installation permissions or policy."
                    else -> "Android could not install the update. Your downloaded update is retained; retry."
                })
            }
        }
    }
    private fun fail(context: Context, message: String) = save(context, state.value.copy(phase = UpdatePhase.FAILED, percent = null, message = message))
}
