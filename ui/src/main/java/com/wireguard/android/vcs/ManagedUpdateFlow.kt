package com.wireguard.android.vcs

import android.app.DownloadManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
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
            UpdatePhase.READY, UpdatePhase.STAGING, UpdatePhase.CONFIRMING, UpdatePhase.INSTALLING, UpdatePhase.INSTALLATION_DELAYED)
    fun summary(): String? = when (state.value.phase) {
        UpdatePhase.CHECKING -> null
        UpdatePhase.DOWNLOADING -> "Downloading update${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.PAUSED -> "Update download paused — waiting for network/storage"
        UpdatePhase.VERIFYING -> "Verifying update${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.READY -> "Update verified — reopen Check updates to continue installation"
        UpdatePhase.STAGING -> "Preparing update for Android${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.CONFIRMING -> "Update is waiting for Android installation confirmation"
        UpdatePhase.INSTALLING -> "Android is installing the update${state.value.percent?.let { ": $it%" } ?: ""}"
        UpdatePhase.INSTALLATION_DELAYED -> "Android has not confirmed installation — reopen Check updates to check its status"
        UpdatePhase.COMPLETED -> "Update installed successfully"
        UpdatePhase.UP_TO_DATE -> "VirtuVPN is up to date"
        UpdatePhase.FAILED -> "Update interrupted — reopen Check updates to retry"
    }
    private fun save(context: Context, value: UpdateFlowState) {
        commit(context) {
            putString("phase", value.phase.name); putString("version_name", value.versionName); putString("message", value.message)
            putBoolean("storage_help", value.storageHelp)
        }
        mutableState.value = value
    }
    fun restore(context: Context) {
        val p = prefs(context)
        val target = p.getLong("version_code", 0)
        val phase = if (UpdateFlowState.installed(BuildConfig.VERSION_CODE.toLong(), target)) UpdatePhase.COMPLETED
            else runCatching { UpdatePhase.valueOf(p.getString("phase", "CHECKING") ?: "CHECKING") }.getOrDefault(UpdatePhase.CHECKING)
        mutableState.value = UpdateFlowState(phase, p.getString("version_name", null),
            message = if (phase == UpdatePhase.COMPLETED) null else p.getString("message", null),
            storageHelp = phase != UpdatePhase.COMPLETED && p.getBoolean("storage_help", false))
    }
    @Synchronized
    fun start(context: Context, retry: Boolean = false) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        restore(app)
        if (mutableState.value.phase in setOf(UpdatePhase.CONFIRMING, UpdatePhase.INSTALLING, UpdatePhase.INSTALLATION_DELAYED)) {
            refreshInstallation(app); return
        }
        if (!retry && mutableState.value.phase == UpdatePhase.FAILED) return
        job = scope.launch {
            try {
                // A recovered APK can already be installed while Android retains its old
                // committed confirmation session. Retire it before replacing version_code
                // with the next download's target, not on a timeout or progress percentage.
                try { retireInstalledSession(app) }
                catch (error: Exception) {
                    if (error is CancellationException) throw error
                    fail(app, app.getString(R.string.vcs_update_previous_session_unavailable))
                    return@launch
                }
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
                val old = ownedSession(app)
                if (old != null) {
                    if (!replaceable(app, old)) {
                        save(app, state.value.copy(phase = UpdatePhase.INSTALLATION_DELAYED, percent = null,
                            message = app.getString(R.string.vcs_update_install_busy)))
                        return@launch
                    }
                    // Retire its callback identity before abandon can deliver an aborted result.
                    commit(app) { remove("session_id") }
                    try { installer.abandonSession(old.sessionId) }
                    catch (error: Exception) {
                        commit(app) { putInt("session_id", old.sessionId) }
                        throw error
                    }
                }
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(app.packageName); setSize(file.length())
                    if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
                }
                created = installer.createSession(params)
                commit(app) {
                    putInt("session_id", created)
                    putLong("session_version_code", manifest.getLong("latestVersionCode"))
                    putBoolean("commit_attempted", false)
                    remove("watch_session"); remove("watch_boot"); remove("watch_since"); remove("watch_progress")
                }
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
                    commit(app) { putBoolean("commit_attempted", true) }
                    session.commit(sender(app, created))
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                // A thrown commit may still have reached Android. Never cancel it based on the exception alone.
                val current = runCatching { ownedSession(app) }.getOrNull()
                if (created >= 0 && current?.sessionId == created &&
                    !prefs(app).getBoolean("commit_attempted", true) && replaceable(app, current))
                    runCatching { app.packageManager.packageInstaller.abandonSession(created) }
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
            try {
                check(ownedSession(app)?.sessionId == id) { "Installation session unavailable" }
                commit(app) { putBoolean("commit_attempted", true) }
                app.packageManager.packageInstaller.openSession(id).use { it.commit(sender(app, id)) }
            }
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
    private fun ownedSession(context: Context): PackageInstaller.SessionInfo? =
        context.packageManager.packageInstaller.mySessions.firstOrNull {
            it.sessionId == sessionId(context) && it.installerPackageName == context.packageName &&
                (it.appPackageName == null || it.appPackageName == context.packageName)
        }

    /** Only our recorded session, whose own target is already installed, may be retired. */
    @Synchronized
    internal fun retireInstalledSession(context: Context): Boolean {
        val p = prefs(context)
        val id = sessionId(context)
        if (id < 0 || !UpdateInstallSafety.installedSession(BuildConfig.VERSION_CODE.toLong(),
                if (p.contains("session_version_code")) p.getLong("session_version_code", 0) else null,
                p.getLong("version_code", 0))) return false
        val info = ownedSession(context) // A failed query is not evidence of a missing session.
        val target = p.getLong("session_version_code", p.getLong("version_code", 0))
        val attempted = if (p.contains("commit_attempted")) p.getBoolean("commit_attempted", true) else null
        // Retire callback identity before Android can send STATUS_FAILURE_ABORTED.
        commit(context) {
            remove("session_id"); remove("session_version_code"); remove("commit_attempted")
        }
        confirmation = null
        try {
            if (info != null) context.packageManager.packageInstaller.abandonSession(info.sessionId)
        } catch (error: Exception) {
            // Keep the verified target binding so the next explicit retry can finish cleanup.
            commit(context) {
                putInt("session_id", id); putLong("session_version_code", target)
                attempted?.let { putBoolean("commit_attempted", it) }
            }
            throw error
        }
        commit(context) {
            remove("watch_session"); remove("watch_boot"); remove("watch_since"); remove("watch_progress")
        }
        return true
    }
    private fun committed(info: PackageInstaller.SessionInfo): Boolean? =
        if (Build.VERSION.SDK_INT >= 29) info.isCommitted else null
    private fun replaceable(context: Context, info: PackageInstaller.SessionInfo): Boolean {
        val p = prefs(context)
        return UpdateInstallSafety.canReplace(info.isActive, committed(info),
            if (Build.VERSION.SDK_INT >= 26) info.isSealed else null,
            if (p.contains("commit_attempted")) p.getBoolean("commit_attempted", true) else null)
    }
    private fun watch(context: Context, info: PackageInstaller.SessionInfo): UpdateInstallWatch {
        val p = prefs(context)
        val now = SystemClock.elapsedRealtime()
        val boot = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
        val percent = (info.progress * 100).toInt().coerceIn(0, 99)
        val previous = UpdateInstallWatch(p.getInt("watch_session", -1), p.getInt("watch_boot", -1),
            p.getLong("watch_since", now), p.getInt("watch_progress", -1))
        val next = previous.observe(info.sessionId, boot, now, percent)
        if (next != previous) commit(context) {
            putInt("watch_session", next.sessionId); putInt("watch_boot", next.boot)
            putLong("watch_since", next.since); putInt("watch_progress", next.progress)
        }
        return next
    }
    @Synchronized
    @Suppress("DEPRECATION")
    fun refreshInstallation(context: Context) {
        if (job?.isActive == true || state.value.phase !in
            setOf(UpdatePhase.CONFIRMING, UpdatePhase.INSTALLING, UpdatePhase.INSTALLATION_DELAYED)) return
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (UpdateFlowState.installed(code, prefs(context).getLong("version_code", 0))) {
            save(context, state.value.copy(phase = UpdatePhase.COMPLETED, percent = 100, message = null, storageHelp = false)); return
        }
        val info = try { ownedSession(context) }
        catch (_: Exception) {
            if (state.value.phase != UpdatePhase.INSTALLATION_DELAYED)
                save(context, state.value.copy(phase = UpdatePhase.INSTALLATION_DELAYED, percent = null,
                    message = context.getString(R.string.vcs_update_install_status_unavailable)))
            return // An unavailable query does not prove that the session is absent or safe to replace.
        }
        if (info == null) { fail(context, "Installation was cancelled or interrupted. Retry to use the retained download."); return }
        if (UpdateInstallSafety.interrupted(state.value.phase, info.isActive, committed(info))) {
            confirmation = null
            fail(context, context.getString(R.string.vcs_update_install_interrupted)); return
        }
        if (state.value.phase != UpdatePhase.CONFIRMING) {
            val observation = watch(context, info)
            val phase = if (observation.delayed(SystemClock.elapsedRealtime())) UpdatePhase.INSTALLATION_DELAYED else UpdatePhase.INSTALLING
            val value = state.value.copy(phase = phase,
                percent = if (phase == UpdatePhase.INSTALLATION_DELAYED) null else (info.progress * 100).toInt().coerceIn(0, 99), message = null)
            if (phase != state.value.phase) save(context, value) else mutableState.value = value
        }
    }
    @Suppress("DEPRECATION")
    fun result(context: Context, intent: Intent) {
        if (intent.action != context.packageName + ".UPDATE_RESULT" ||
            !UpdateFlowState.acceptsResult(sessionId(context), intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))) return
        restore(context)
        if (state.value.phase == UpdatePhase.COMPLETED) {
            confirmation = null
            save(context, state.value.copy(percent = 100, message = null, storageHelp = false))
            return
        }
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                confirmation = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (confirmation == null) fail(context, "Android installation confirmation is unavailable. Retry the update.")
                else save(context, state.value.copy(phase = UpdatePhase.CONFIRMING, percent = null))
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // The installed package version, not a callback or a progress percentage, proves completion.
                save(context, state.value.copy(phase = UpdatePhase.INSTALLING, percent = null, message = null, storageHelp = false))
                refreshInstallation(context)
            }
            else -> {
                confirmation = null
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)
                fail(context, when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation was cancelled. Your verified download is retained."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Android needs more free storage to install the update."
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Android blocked installation. Check device installation permissions or policy."
                    else -> "Android could not install the update. Your downloaded update is retained; retry."
                }, storageHelp = status == PackageInstaller.STATUS_FAILURE_STORAGE)
            }
        }
    }
    private fun fail(context: Context, message: String, storageHelp: Boolean = false) =
        save(context, state.value.copy(phase = UpdatePhase.FAILED, percent = null, message = message, storageHelp = storageHelp))
}
