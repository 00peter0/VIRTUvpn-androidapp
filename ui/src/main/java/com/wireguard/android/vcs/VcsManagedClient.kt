package com.wireguard.android.vcs

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
import com.wireguard.android.R
import com.wireguard.android.activity.MainActivity
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Locale

object VcsManagedClient {
    private const val PREFS = "vcs_managed_client"
    private const val DEFAULT_API_BASE = "https://vcs.virtucomputing.com"
    private const val KEY_ACCOUNT_API_BASE = "account_api_base"
    private const val KEY_ACCOUNT_ACCESS_TOKEN = "account_access_token"
    private const val KEY_ACCOUNT_EXPIRES_AT = "account_expires_at"
    private const val KEY_ACCOUNT_EMAIL = "account_email"
    private const val KEY_ACCOUNT_NAME = "account_name"
    private const val KEY_ACCOUNT_ROLE = "account_role"
    private const val KEY_ACCOUNT_TENANT_NAME = "account_tenant_name"
    private const val KEY_EXTERNAL_VPN_MESH_TUNNELS = "external_vpn_mesh_tunnels"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ASSIGNMENTS = "assignments"
    private const val KEY_PENDING_BUNDLE_ASSIGNMENTS = "pending_bundle_assignments"
    private const val KEY_PENDING_TUNNEL_ACTIVATIONS = "pending_tunnel_activations"
    private const val KEY_LAST_UPDATE_PROMPT = "last_update_prompt"
    private const val KEY_LAST_UPDATE_URL = "last_update_url"
    private const val TIMEOUT_MS = 15_000

    data class SyncResult(
        val imported: Int,
        val assigned: Int,
        val deviceName: String?,
        val updateVersionName: String?,
        val skippedRunning: Int,
        val pendingBundleAssignments: Int
    )
    data class UpdateCheck(val available: Boolean, val versionName: String?)
    data class UpdateDownloadStart(val downloadId: Long, val fileName: String)
    data class SessionInfo(val apiBase: String, val deviceId: String?)
    data class AccountInfo(
        val apiBase: String,
        val email: String?,
        val name: String?,
        val role: String?,
        val tenantName: String?,
        val expiresAtMillis: Long
    )
    private data class ImportResult(val localTunnelName: String, val applied: Boolean, val current: Boolean)
    private data class AccountSession(
        val apiBase: String,
        val token: String,
        val expiresAtMillis: Long,
        val email: String?,
        val name: String?,
        val role: String?,
        val tenantName: String?
    )

    fun hasSession(context: Context): Boolean = loadSession(context) != null

    fun hasAccountSession(context: Context): Boolean = loadAccountSession(context) != null

    fun accountInfo(context: Context): AccountInfo? {
        return loadAccountSession(context)?.toInfo()
    }

    fun sessionInfo(context: Context): SessionInfo? {
        return loadSession(context)?.let { SessionInfo(it.apiBase, it.deviceId) }
    }

    suspend fun signInAccount(context: Context, apiBaseUrl: String, email: String, password: String): AccountInfo = withContext(Dispatchers.IO) {
        val normalizedBase = normalizeApiBase(apiBaseUrl)
        val body = deviceRegistrationBody()
            .put("email", email.trim())
            .put("password", password)
        val response = requestJson("POST", "$normalizedBase/api/mobile/android/auth/login", body, null)
        storeAccountSession(context, normalizedBase, response)
    }

    fun clearAccountSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_ACCOUNT_API_BASE)
            .remove(KEY_ACCOUNT_ACCESS_TOKEN)
            .remove(KEY_ACCOUNT_EXPIRES_AT)
            .remove(KEY_ACCOUNT_EMAIL)
            .remove(KEY_ACCOUNT_NAME)
            .remove(KEY_ACCOUNT_ROLE)
            .remove(KEY_ACCOUNT_TENANT_NAME)
            .apply()
    }

    fun clearAllVcsState(context: Context) {
        clearAccountSession(context)
        clearSession(context)
    }

    fun clearSession(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_API_BASE)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_DEVICE_ID)
            .remove(KEY_ASSIGNMENTS)
            .remove(KEY_PENDING_BUNDLE_ASSIGNMENTS)
            .remove(KEY_LAST_UPDATE_PROMPT)
            .remove(KEY_LAST_UPDATE_URL)
            .apply()
    }

    fun isEnrollmentUri(uri: Uri?): Boolean {
        if (uri == null) return false
        val scheme = uri.scheme?.lowercase(Locale.US)
        return scheme == "virtuvpn" && uri.host == "enroll" ||
            (scheme == "https" || scheme == "http") && uri.path == "/api/mobile/android/enroll/open"
    }

    suspend fun handleEnrollmentPayload(context: Context, payload: String): EnrollResult = withContext(Dispatchers.IO) {
        val parsed = parseEnrollmentPayload(payload)
        completeEnrollment(context, parsed.apiBaseUrl, parsed.enrollmentToken)
    }

    suspend fun handleEnrollmentUri(context: Context, uri: Uri): EnrollResult? = withContext(Dispatchers.IO) {
        val parsed = parseEnrollmentUri(uri) ?: return@withContext null
        completeEnrollment(context, parsed.apiBaseUrl, parsed.enrollmentToken)
    }

    suspend fun syncManagedTunnels(context: Context): SyncResult = withContext(Dispatchers.IO) {
        val session = requireSession(context)
        val syncUrl = "${session.apiBase}/api/mobile/android/sync?appVersion=${urlEncode(BuildConfig.VERSION_NAME)}&appVersionCode=${BuildConfig.VERSION_CODE}&androidVersion=${urlEncode(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())}"
        val sync = requestJson("GET", syncUrl, null, session.token)
        val updateVersionName = rememberUpdateAvailable(context, sync.optJSONObject("update"))
        val assignments = sync.optJSONArray("assignments") ?: JSONArray()
        val bundleAssignments = sync.optJSONArray("bundleAssignments") ?: JSONArray()
        val bundleState = sync.optJSONObject("bundleState")
        val pendingBundleAssignments = if (bundleState?.optString("status") == "PENDING_BUILD") {
            bundleState.optInt("selectedCount", bundleAssignments.length())
        } else {
            0
        }
        val commands = sync.optJSONArray("commands") ?: JSONArray()
        val bundledAssignmentIds = mutableSetOf<String>()
        var imported = 0
        var skippedRunning = 0
        val bundle = sync.optJSONObject("bundle")
        var bundleLocalTunnelName: String? = null
        if (bundle != null && bundle.optString("config").isNotBlank()) {
            val bundleImport = importManagedBundle(context, bundle)
            val localTunnelName = bundleImport.localTunnelName
            bundleLocalTunnelName = localTunnelName
            val ids = bundle.optJSONArray("assignmentIds") ?: JSONArray()
            for (i in 0 until ids.length()) bundledAssignmentIds.add(ids.getString(i))
            for (i in 0 until assignments.length()) {
                val assignment = assignments.getJSONObject(i)
                if (bundledAssignmentIds.contains(assignment.optString("id"))) assignment.put("bundleLocalTunnelName", localTunnelName)
            }
            for (i in 0 until bundleAssignments.length()) {
                val assignment = bundleAssignments.getJSONObject(i)
                assignment.put("bundleLocalTunnelName", localTunnelName)
                assignment.put("localTunnelName", localTunnelName)
            }
            if (bundleImport.current) {
                ackManagedBundleImport(session, bundle, localTunnelName)
            }
            if (bundleImport.applied) imported += 1
            if (!bundleImport.current) skippedRunning += 1
        }
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            if (assignment.optString("status") != "ACTIVE" && assignment.optString("status") != "REISSUE_REQUIRED") continue
            if (assignment.optString("kind") != "VPN_ROUTE" && assignment.optString("kind") != "AGENT_GATEWAY_PROFILE") continue
            val assignmentId = assignment.getString("id")
            val provision = requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/$assignmentId/provision", JSONObject(), session.token)
            val configImport = importManagedConfig(context, session, provision)
            val localTunnelName = configImport.localTunnelName
            assignment.put("localTunnelName", localTunnelName)
            if (configImport.applied) imported += 1
            if (!configImport.current) {
                skippedRunning += 1
                assignment.put("configUpdateSkipped", "tunnel_running")
            }
        }
        val storedAssignments = JSONArray()
        for (i in 0 until assignments.length()) storedAssignments.put(assignments.getJSONObject(i))
        if (bundleLocalTunnelName != null) {
            for (i in 0 until bundleAssignments.length()) storedAssignments.put(bundleAssignments.getJSONObject(i))
        }
        storeAssignments(context, storedAssignments)
        storePendingBundleAssignments(context, pendingBundleAssignments)
        processCommands(context, session, commands, storedAssignments, bundleLocalTunnelName)
        val device = sync.optJSONObject("device")
        SyncResult(
            imported,
            storedAssignments.length() + pendingBundleAssignments,
            device?.optString("deviceName")?.takeIf { it.isNotBlank() },
            updateVersionName,
            skippedRunning,
            pendingBundleAssignments
        )
    }


    private fun rememberUpdateAvailable(context: Context, update: JSONObject?): String? {
        if (update == null || !update.optBoolean("updateAvailable", false)) return null
        val latestVersion = update.optString("latestVersionName").takeIf { it.isNotBlank() } ?: return null
        val apkUrl = update.optString("apkUrl").takeIf { it.isNotBlank() } ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_UPDATE_PROMPT, latestVersion)
            .putString(KEY_LAST_UPDATE_URL, apkUrl)
            .apply()
        return latestVersion
    }

    fun openManagedUpdate(context: Context): UpdateDownloadStart? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val latestVersion = prefs.getString(KEY_LAST_UPDATE_PROMPT, null) ?: return null
        val apkUrl = prefs.getString(KEY_LAST_UPDATE_URL, null) ?: return null
        return downloadStoredUpdate(context, latestVersion, apkUrl)
    }

    suspend fun checkForManagedUpdate(context: Context): UpdateCheck = withContext(Dispatchers.IO) {
        val session = requireSession(context)
        val updateUrl = "${session.apiBase}/api/mobile/android/update?appVersion=${urlEncode(BuildConfig.VERSION_NAME)}&appVersionCode=${BuildConfig.VERSION_CODE}&androidVersion=${urlEncode(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())}"
        val update = requestJson("GET", updateUrl, null, session.token)
        val latestVersion = rememberUpdateAvailable(context, update)
        UpdateCheck(latestVersion != null, latestVersion)
    }

    suspend fun reportDeviceHeartbeat(context: Context) = withContext(Dispatchers.IO) {
        val session = requireSession(context)
        requestJson("POST", "${session.apiBase}/api/mobile/android/device/heartbeat", deviceRegistrationBody(), session.token)
    }

    fun localTunnelNamesForSection(context: Context, section: String?): Set<String> {
        if (section.isNullOrBlank()) return emptySet()
        val assignments = loadAssignments(context)
        val bundleName = managedBundleTunnelName(assignments)
        val names = linkedSetOf<String>()
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            val status = assignment.optString("status")
            if (status != "ACTIVE" && status != "REISSUE_REQUIRED") continue
            val localTunnelName = assignment.optString("localTunnelName").takeIf { it.isNotBlank() } ?: continue
            when (section) {
                MainActivity.TUNNEL_SECTION_MANAGED_ACCESS -> {
                    assignment.optString("bundleLocalTunnelName").takeIf { it.isNotBlank() }?.let { names.add(it) }
                    if (localTunnelName == bundleName) names.add(localTunnelName)
                }
                MainActivity.TUNNEL_SECTION_VPN_MESH -> if (assignment.optString("kind") == "VPN_ROUTE" && localTunnelName != bundleName) names.add(localTunnelName)
                MainActivity.TUNNEL_SECTION_AGENT_GATEWAY -> if (assignment.optString("kind") == "AGENT_GATEWAY_PROFILE" && localTunnelName != bundleName) names.add(localTunnelName)
            }
        }
        if (section == MainActivity.TUNNEL_SECTION_VPN_MESH) {
            names.addAll(loadExternalVpnMeshTunnelNames(context))
        }
        return names
    }

    fun rememberExternalVpnMeshTunnels(context: Context, tunnelNames: Collection<String>) {
        val cleanNames = tunnelNames.map { it.trim() }.filter { it.isNotBlank() }
        if (cleanNames.isEmpty()) return
        val names = loadExternalVpnMeshTunnelNames(context).toMutableSet()
        names.addAll(cleanNames)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY_EXTERNAL_VPN_MESH_TUNNELS, names)
            .apply()
    }

    private fun loadExternalVpnMeshTunnelNames(context: Context): Set<String> {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_EXTERNAL_VPN_MESH_TUNNELS, emptySet())
            .orEmpty()
    }

    fun pendingManagedAccessAssignments(context: Context): Int {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_PENDING_BUNDLE_ASSIGNMENTS, 0)
    }

    data class WebTerminalLink(
        val serverName: String?,
        val url: String,
        val bindIp: String?,
        val port: Int?,
        val requiredTunnelName: String?
    )

    fun webTerminalForTunnel(context: Context, tunnelName: String?): WebTerminalLink? {
        if (tunnelName.isNullOrBlank()) return null
        val assignments = loadAssignments(context)
        val fallbackBundleName = managedBundleTunnelName(assignments)
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            val status = assignment.optString("status")
            if (status != "ACTIVE" && status != "REISSUE_REQUIRED") continue
            val localName = assignment.optString("localTunnelName")
            val terminal = assignment.optJSONObject("webTerminal") ?: continue
            val bundleName = assignment.optString("bundleLocalTunnelName")
                .takeIf { it.isNotBlank() }
                ?: fallbackBundleName
            if (localName != tunnelName && bundleName != tunnelName) continue
            val url = terminal.optString("url").takeIf { it.startsWith("http://") || it.startsWith("https://") } ?: continue
            return WebTerminalLink(
                serverName = terminal.optString("serverName").takeIf { it.isNotBlank() },
                url = url,
                bindIp = terminal.optString("bindIp").takeIf { it.isNotBlank() },
                port = terminal.optInt("port").takeIf { it > 0 },
                requiredTunnelName = bundleName ?: localName.takeIf { it.isNotBlank() }
            )
        }
        return null
    }

    private fun managedBundleTunnelName(assignments: JSONArray): String? {
        for (i in 0 until assignments.length()) {
            val bundleName = assignments.getJSONObject(i).optString("bundleLocalTunnelName").takeIf { it.isNotBlank() }
            if (bundleName != null) return bundleName
        }
        val counts = mutableMapOf<String, Int>()
        for (i in 0 until assignments.length()) {
            val name = assignments.getJSONObject(i).optString("localTunnelName").takeIf { it.isNotBlank() } ?: continue
            counts[name] = (counts[name] ?: 0) + 1
        }
        return counts.entries.firstOrNull { it.value > 1 }?.key
            ?: counts.keys.firstOrNull { it.contains("Managed Access", ignoreCase = true) }
    }

    private fun downloadStoredUpdate(context: Context, latestVersion: String, apkUrl: String): UpdateDownloadStart? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val fileName = updateDownloadFileName(latestVersion)
        return try {
            val manager = context.getSystemService(DownloadManager::class.java) ?: return if (openUpdateUrl(context, apkUrl)) UpdateDownloadStart(-1L, fileName) else null
            val request = DownloadManager.Request(updateDownloadUri(apkUrl))
                .setTitle(fileName)
                .setDescription("VirtuVPN update")
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(true)
            val downloadId = manager.enqueue(request)
            observeUpdateDownload(context.applicationContext, manager, downloadId, fileName, apkUrl)
            prefs.edit().putString(KEY_LAST_UPDATE_PROMPT, latestVersion).putString(KEY_LAST_UPDATE_URL, apkUrl).apply()
            UpdateDownloadStart(downloadId, fileName)
        } catch (_: Throwable) {
            if (!openUpdateUrl(context, apkUrl)) null else {
                prefs.edit().putString(KEY_LAST_UPDATE_PROMPT, latestVersion).putString(KEY_LAST_UPDATE_URL, apkUrl).apply()
                UpdateDownloadStart(-1L, fileName)
            }
        }
    }

    private fun updateDownloadFileName(latestVersion: String): String {
        val safeVersion = latestVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return "VirtuVPN-$safeVersion-${System.currentTimeMillis()}.apk"
    }

    private fun observeUpdateDownload(context: Context, manager: DownloadManager, downloadId: Long, fileName: String, apkUrl: String) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId != downloadId) return
                runCatching { receiverContext.unregisterReceiver(this) }
                val status = updateDownloadStatus(manager, downloadId)
                val message = when (status?.first) {
                    DownloadManager.STATUS_SUCCESSFUL -> context.getString(R.string.vcs_update_download_complete, fileName)
                    DownloadManager.STATUS_FAILED -> context.getString(
                        R.string.vcs_update_download_failed_detail,
                        status.second ?: context.getString(R.string.vcs_update_download_reason_unknown)
                    )
                    else -> return
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                if (status.first == DownloadManager.STATUS_SUCCESSFUL) {
                    openDownloadedUpdate(context, manager, downloadId, apkUrl)
                } else if (status.first == DownloadManager.STATUS_FAILED) {
                    openUpdateUrl(context, apkUrl)
                }
            }
        }
        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            context.registerReceiver(receiver, filter)
        }
    }

    private fun updateDownloadStatus(manager: DownloadManager, downloadId: Long): Pair<Int, String?>? {
        manager.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
            val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
            return status to if (status == DownloadManager.STATUS_FAILED) updateDownloadFailureReason(reason) else null
        }
        return null
    }

    private fun updateDownloadFailureReason(reason: Int): String {
        return when (reason) {
            DownloadManager.ERROR_CANNOT_RESUME -> "cannot resume download"
            DownloadManager.ERROR_DEVICE_NOT_FOUND -> "download storage is unavailable"
            DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "download file already exists"
            DownloadManager.ERROR_FILE_ERROR -> "storage write failed"
            DownloadManager.ERROR_HTTP_DATA_ERROR -> "network data error"
            DownloadManager.ERROR_INSUFFICIENT_SPACE -> "not enough storage space"
            DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "too many redirects"
            DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "server rejected the download"
            DownloadManager.ERROR_UNKNOWN -> "unknown download error"
            in 400..599 -> "server returned HTTP $reason"
            else -> "download error code $reason"
        }
    }

    private fun openDownloadedUpdate(context: Context, manager: DownloadManager, downloadId: Long, apkUrl: String): Boolean {
        val uri = manager.getUriForDownloadedFile(downloadId) ?: return openUpdateUrl(context, apkUrl)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse {
            openUpdateUrl(context, apkUrl)
        }
    }

    private fun updateDownloadUri(apkUrl: String): Uri {
        val uri = Uri.parse(apkUrl)
        if (!uri.path.orEmpty().endsWith("/api/mobile/android/update/apk")) return uri
        if (uri.getQueryParameter("download") == "1") return uri
        return uri.buildUpon().appendQueryParameter("download", "1").build()
    }

    private fun openUpdateUrl(context: Context, apkUrl: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, updateDownloadUri(apkUrl))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    suspend fun reportCurrentStates(context: Context) = withContext(Dispatchers.IO) {
        val session = loadSession(context) ?: restoreManagedSessionFromAccount(context) ?: return@withContext
        val assignments = loadAssignments(context)
        val tunnels = Application.getTunnelManager().getTunnels()
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            val tunnelName = assignment.optString("localTunnelName")
            val tunnel = tunnels[tunnelName] ?: continue
            val stats = runCatching { tunnel.getStatisticsAsync() }.getOrNull()
            val latestHandshakeAt = stats?.peers()
                ?.mapNotNull { peer -> stats.peer(peer)?.latestHandshakeEpochMillis()?.takeIf { it > 0L } }
                ?.maxOrNull()
            val body = JSONObject()
                .put("state", tunnel.state.name.lowercase(Locale.US))
                .put("rxBytes", stats?.totalRx())
                .put("txBytes", stats?.totalTx())
                .put("metadata", metadataWithPendingActivation(context, tunnel.name, tunnel.state))
            if (latestHandshakeAt != null) body.put("latestHandshakeAt", Instant.ofEpochMilli(latestHandshakeAt).toString())
            runCatching {
                requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/${assignment.getString("id")}/state", body, session.token)
            }
        }
    }

    suspend fun reportTunnelTransition(
        context: Context,
        tunnelName: String,
        requestedState: Tunnel.State,
        actualState: Tunnel.State?,
        error: Throwable? = null
    ) = withContext(Dispatchers.IO) {
        val metadata = activationMetadata(tunnelName, requestedState, actualState, error)
        storePendingActivation(context, tunnelName, metadata)
        val session = loadSession(context) ?: restoreManagedSessionFromAccount(context) ?: return@withContext
        val assignments = assignmentsForLocalTunnel(context, tunnelName)
        if (assignments.isEmpty()) return@withContext
        val tunnels = Application.getTunnelManager().getTunnels()
        val tunnel = tunnels[tunnelName]
        val stats = if (tunnel != null) runCatching { tunnel.getStatisticsAsync() }.getOrNull() else null
        val latestHandshakeAt = stats?.peers()
            ?.mapNotNull { peer -> stats.peer(peer)?.latestHandshakeEpochMillis()?.takeIf { it > 0L } }
            ?.maxOrNull()
        val body = JSONObject()
            .put("state", (actualState ?: Tunnel.State.DOWN).name.lowercase(Locale.US))
            .put("appState", "tunnel_transition")
            .put("rxBytes", stats?.totalRx())
            .put("txBytes", stats?.totalTx())
            .put("metadata", metadata)
        if (error != null) body.put("error", error.message ?: error.javaClass.simpleName)
        if (latestHandshakeAt != null) body.put("latestHandshakeAt", Instant.ofEpochMilli(latestHandshakeAt).toString())
        for (assignment in assignments) {
            runCatching {
                requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/${assignment.getString("id")}/state", body, session.token)
            }
        }
    }

    private suspend fun processCommands(
        context: Context,
        session: Session,
        commands: JSONArray,
        assignments: JSONArray,
        bundleLocalTunnelName: String?
    ) {
        for (i in 0 until commands.length()) {
            val command = commands.getJSONObject(i)
            val commandId = command.getString("id")
            val action = command.optString("action")
            val assignmentId = command.optString("assignmentId").takeIf { it.isNotBlank() && it != "null" }
            val result = JSONObject()
            try {
                val localTunnelName = when (action) {
                    // managed_cluster_* are the current names; managed_bundle_* kept for backward compatibility.
                    "managed_cluster_up", "managed_cluster_down", "managed_bundle_up", "managed_bundle_down" -> bundleLocalTunnelName ?: error("Managed cluster is not imported")
                    "tunnel_up", "tunnel_down" -> localTunnelNameForAssignment(assignments, assignmentId ?: error("assignmentId is missing"))
                    "sync_now", "report_state" -> null
                    else -> error("Unsupported command action: $action")
                }
                val state = when (action) {
                    "managed_cluster_up", "managed_bundle_up", "tunnel_up" -> Tunnel.State.UP
                    "managed_cluster_down", "managed_bundle_down", "tunnel_down" -> Tunnel.State.DOWN
                    else -> null
                }
                if (localTunnelName != null && state != null) {
                    val actualState = setLocalTunnelState(localTunnelName, state)
                    result.put("state", actualState.name.lowercase(Locale.US))
                    result.put("localTunnelName", localTunnelName)
                }
                if (action == "report_state") reportCurrentStates(context)
                ackCommand(session, commandId, result.put("ok", true))
            } catch (e: Throwable) {
                ackCommand(session, commandId, result.put("ok", false).put("error", e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun localTunnelNameForAssignment(assignments: JSONArray, assignmentId: String): String {
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            if (assignment.optString("id") == assignmentId) {
                return assignment.optString("localTunnelName").takeIf { it.isNotBlank() } ?: error("Assignment tunnel is not imported")
            }
        }
        error("Assignment is not stored on this device")
    }

    private fun assignmentsForLocalTunnel(context: Context, tunnelName: String): List<JSONObject> {
        val assignments = loadAssignments(context)
        val matches = mutableListOf<JSONObject>()
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            val status = assignment.optString("status")
            if (status != "ACTIVE" && status != "REISSUE_REQUIRED") continue
            val localName = assignment.optString("localTunnelName")
            val bundleName = assignment.optString("bundleLocalTunnelName")
            if (localName == tunnelName || bundleName == tunnelName) matches.add(assignment)
        }
        return matches
    }

    private suspend fun setLocalTunnelState(localTunnelName: String, state: Tunnel.State): Tunnel.State = withContext(Dispatchers.Main.immediate) {
        val tunnel = Application.getTunnelManager().getTunnels()[localTunnelName] ?: error("Tunnel not found: $localTunnelName")
        tunnel.setStateAsync(state)
    }

    private fun activationMetadata(
        tunnelName: String,
        requestedState: Tunnel.State,
        actualState: Tunnel.State?,
        error: Throwable?
    ) = JSONObject()
        .put("localTunnelName", tunnelName)
        .put("requestedState", requestedState.name.lowercase(Locale.US))
        .put("actualState", actualState?.name?.lowercase(Locale.US) ?: "unknown")
        .put("activationSource", "android_tunnel_manager")
        .put("vpnServiceStarted", error == null && actualState == Tunnel.State.UP)
        .put("errorType", error?.javaClass?.simpleName ?: JSONObject.NULL)
        .put("errorMessage", error?.message ?: JSONObject.NULL)
        .put("appVersion", BuildConfig.VERSION_NAME)
        .put("appVersionCode", BuildConfig.VERSION_CODE)
        .put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
        .put("observedAt", Instant.now().toString())

    private fun metadataWithPendingActivation(context: Context, tunnelName: String, actualState: Tunnel.State): JSONObject {
        val metadata = JSONObject().put("localTunnelName", tunnelName)
        val activation = pendingActivation(context, tunnelName) ?: return metadata
        if (activation.optString("actualState").isBlank() || activation.optString("actualState") == "unknown") {
            activation.put("actualState", actualState.name.lowercase(Locale.US))
            activation.put("vpnServiceStarted", actualState == Tunnel.State.UP)
        }
        for (key in activation.keys()) metadata.put(key, activation.opt(key))
        return metadata
    }

    private fun storePendingActivation(context: Context, tunnelName: String, metadata: JSONObject) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val all = JSONObject(prefs.getString(KEY_PENDING_TUNNEL_ACTIVATIONS, "{}") ?: "{}")
        all.put(tunnelName, metadata)
        prefs.edit().putString(KEY_PENDING_TUNNEL_ACTIVATIONS, all.toString()).apply()
    }

    private fun pendingActivation(context: Context, tunnelName: String): JSONObject? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PENDING_TUNNEL_ACTIVATIONS, "{}") ?: "{}"
        val all = JSONObject(raw)
        val metadata = all.optJSONObject(tunnelName) ?: return null
        val observedAt = runCatching { Instant.parse(metadata.optString("observedAt")) }.getOrNull() ?: return metadata
        return if (Instant.now().minusSeconds(300).isBefore(observedAt)) metadata else null
    }

    private fun ackCommand(session: Session, commandId: String, body: JSONObject) {
        requestJson("POST", "${session.apiBase}/api/mobile/android/commands/$commandId/ack", body, session.token)
    }

    private fun ackTunnelImported(session: Session, assignmentId: String, localTunnelName: String, configVersion: Int, bundleImport: Boolean = false) {
        val body = JSONObject()
            .put("localTunnelName", localTunnelName)
            .put("configVersion", configVersion)
        if (bundleImport) body.put("bundleImport", true)
        requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/$assignmentId/ack-imported", body, session.token)
    }

    private fun ackManagedBundleImport(session: Session, bundle: JSONObject, localTunnelName: String) {
        val configVersion = bundle.optInt("configVersion", 1)
        val ids = bundle.optJSONArray("assignmentIds") ?: return
        for (i in 0 until ids.length()) {
            val assignmentId = ids.optString(i).takeIf { it.isNotBlank() } ?: continue
            ackTunnelImported(session, assignmentId, localTunnelName, configVersion, bundleImport = true)
        }
    }

    private suspend fun importManagedBundle(context: Context, bundle: JSONObject): ImportResult {
        val configText = bundle.getString("config")
        val preferredName = sanitizeTunnelName(bundle.optString("configFilename", bundle.optString("displayName", "VCS Managed Access")))
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        val applied = withContext(Dispatchers.Main.immediate) {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val existing = tunnels[preferredName]
            if (existing == null) {
                manager.create(preferredName, config)
                ImportResult(preferredName, applied = true, current = true)
            } else if (existing.getConfigAsync() == config) {
                ImportResult(preferredName, applied = false, current = true)
            } else if (existing.state == Tunnel.State.UP) {
                ImportResult(preferredName, applied = false, current = false)
            } else {
                existing.setConfigAsync(config)
                ImportResult(preferredName, applied = true, current = true)
            }
        }
        return applied
    }

    private suspend fun importManagedConfig(context: Context, session: Session, provision: JSONObject): ImportResult {
        val configText = provision.getString("config")
        val assignmentId = provision.getString("assignmentId")
        val configVersion = provision.optInt("configVersion", 1)
        val preferredName = sanitizeTunnelName(provision.optString("configFilename", provision.optString("displayName", "vcs-$assignmentId")))
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        val applied = withContext(Dispatchers.Main.immediate) {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val existing = tunnels[preferredName]
            if (existing == null) {
                manager.create(preferredName, config)
                ImportResult(preferredName, applied = true, current = true)
            } else if (existing.getConfigAsync() == config) {
                ImportResult(preferredName, applied = false, current = true)
            } else if (existing.state == Tunnel.State.UP) {
                ImportResult(preferredName, applied = false, current = false)
            } else {
                existing.setConfigAsync(config)
                ImportResult(preferredName, applied = true, current = true)
            }
        }
        if (applied.current) {
            ackTunnelImported(session, assignmentId, preferredName, configVersion)
        }
        return applied
    }

    private fun completeEnrollment(context: Context, apiBaseUrl: String, enrollmentToken: String): EnrollResult {
        val body = deviceRegistrationBody()
            .put("enrollmentToken", enrollmentToken)
            .put("devicePublicKey", JSONObject.NULL)
        val response = requestJson("POST", "${apiBaseUrl.trimEnd('/')}/api/mobile/android/enroll/complete", body, null)
        val device = response.getJSONObject("device")
        storeManagedDeviceSession(context, apiBaseUrl.trimEnd('/'), response.getString("accessToken"), device.getString("id"))
        return EnrollResult(device.optString("deviceName").ifBlank { null })
    }

    private fun parseEnrollmentPayload(payload: String): EnrollmentPayload {
        val trimmed = payload.trim()
        if (trimmed.startsWith("virtuvpn://") || trimmed.startsWith("https://") || trimmed.startsWith("http://")) {
            val uri = Uri.parse(trimmed)
            return parseEnrollmentUri(uri) ?: error("Enrollment link is not supported")
        }
        val json = JSONObject(trimmed)
        if (json.optString("type") != "vcs_android_enrollment") error("QR is not a VCS Android enrollment payload")
        return EnrollmentPayload(json.getString("apiBaseUrl"), json.getString("enrollmentToken"))
    }

    private fun parseEnrollmentUri(uri: Uri): EnrollmentPayload? {
        if (uri.scheme == "virtuvpn" && uri.host == "enroll") {
            return EnrollmentPayload(
                uri.getQueryParameter("api") ?: error("Enrollment link is missing api"),
                uri.getQueryParameter("token") ?: error("Enrollment link is missing token"),
            )
        }
        if ((uri.scheme == "https" || uri.scheme == "http") && uri.path == "/api/mobile/android/enroll/open") {
            val token = uri.getQueryParameter("token") ?: error("Enrollment link is missing token")
            val apiBaseUrl = "${uri.scheme}://${uri.authority}"
            return EnrollmentPayload(apiBaseUrl, token)
        }
        return null
    }

    private fun requestJson(method: String, url: String, body: JSONObject?, bearerToken: String?): JSONObject {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            if (bearerToken != null) setRequestProperty("Authorization", "Bearer $bearerToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            }
        }
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (status !in 200..299) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() } ?: "HTTP $status"
            error(message)
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun requireSession(context: Context): Session =
        loadSession(context)
            ?: restoreManagedSessionFromAccount(context)
            ?: error("Sign in to VCS to use Virtu VPN.")

    private fun deviceRegistrationBody(): JSONObject {
        return JSONObject()
            .put("deviceName", Build.MODEL ?: "Android device")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            .put("devicePublicKey", JSONObject.NULL)
            .put("deviceModel", Build.MODEL ?: "Android device")
            .put("metadata", JSONObject().put("manufacturer", Build.MANUFACTURER).put("sdk", Build.VERSION.SDK_INT))
    }

    private fun normalizeApiBase(value: String): String {
        val trimmed = value.trim().ifBlank { DEFAULT_API_BASE }
        val withScheme = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        return withScheme.trimEnd('/')
    }

    private fun storeAccountSession(context: Context, apiBase: String, response: JSONObject): AccountInfo {
        val user = response.optJSONObject("user")
        val tenant = response.optJSONObject("tenant")
        val expiresIn = response.optLong("expiresIn", 60 * 60 * 24).coerceAtLeast(60)
        val expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000
        val account = AccountSession(
            apiBase = apiBase,
            token = response.getString("accessToken"),
            expiresAtMillis = expiresAtMillis,
            email = user?.optString("email")?.takeIf { it.isNotBlank() },
            name = user?.optString("name")?.takeIf { it.isNotBlank() && it != "null" },
            role = user?.optString("role")?.takeIf { it.isNotBlank() },
            tenantName = tenant?.optString("name")?.takeIf { it.isNotBlank() }
        )
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_ACCOUNT_API_BASE, account.apiBase)
            .putString(KEY_ACCOUNT_ACCESS_TOKEN, account.token)
            .putLong(KEY_ACCOUNT_EXPIRES_AT, account.expiresAtMillis)
            .putString(KEY_ACCOUNT_EMAIL, account.email)
            .putString(KEY_ACCOUNT_NAME, account.name)
            .putString(KEY_ACCOUNT_ROLE, account.role)
            .putString(KEY_ACCOUNT_TENANT_NAME, account.tenantName)
            .apply()
        storeManagedDeviceSessionFromResponse(context, apiBase, response)
        return account.toInfo()
    }

    private fun AccountSession.toInfo(): AccountInfo {
        return AccountInfo(apiBase, email, name, role, tenantName, expiresAtMillis)
    }

    private fun loadAccountSession(context: Context): AccountSession? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_ACCOUNT_ACCESS_TOKEN, null) ?: return null
        val expiresAt = prefs.getLong(KEY_ACCOUNT_EXPIRES_AT, 0)
        if (expiresAt > 0 && expiresAt <= System.currentTimeMillis()) {
            clearAccountSession(context)
            return null
        }
        return AccountSession(
            apiBase = prefs.getString(KEY_ACCOUNT_API_BASE, DEFAULT_API_BASE) ?: DEFAULT_API_BASE,
            token = token,
            expiresAtMillis = expiresAt,
            email = prefs.getString(KEY_ACCOUNT_EMAIL, null),
            name = prefs.getString(KEY_ACCOUNT_NAME, null),
            role = prefs.getString(KEY_ACCOUNT_ROLE, null),
            tenantName = prefs.getString(KEY_ACCOUNT_TENANT_NAME, null)
        )
    }

    private fun loadSession(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiBase = prefs.getString(KEY_API_BASE, null) ?: return null
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return Session(apiBase, token, prefs.getString(KEY_DEVICE_ID, null))
    }

    private fun restoreManagedSessionFromAccount(context: Context): Session? {
        val account = loadAccountSession(context) ?: return null
        val response = requestJson("POST", "${account.apiBase}/api/mobile/android/auth/device", deviceRegistrationBody(), account.token)
        return storeManagedDeviceSessionFromResponse(context, account.apiBase, response)
    }

    private fun storeManagedDeviceSessionFromResponse(context: Context, apiBase: String, response: JSONObject): Session? {
        val accessToken = response.optString("deviceAccessToken").takeIf { it.isNotBlank() }
            ?: response.optString("accessToken").takeIf { it.isNotBlank() }
            ?: return null
        val deviceId = response.optJSONObject("device")?.optString("id")?.takeIf { it.isNotBlank() }
        storeManagedDeviceSession(context, apiBase, accessToken, deviceId)
        return Session(apiBase, accessToken, deviceId)
    }

    private fun storeManagedDeviceSession(context: Context, apiBase: String, accessToken: String, deviceId: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_BASE, apiBase.trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_DEVICE_ID, deviceId)
            .apply()
    }

    private fun storeAssignments(context: Context, assignments: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASSIGNMENTS, assignments.toString()).apply()
    }

    private fun storePendingBundleAssignments(context: Context, count: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_PENDING_BUNDLE_ASSIGNMENTS, count).apply()
    }

    private fun loadAssignments(context: Context): JSONArray {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_ASSIGNMENTS, "[]") ?: "[]"
        return JSONArray(raw)
    }

    private fun sanitizeTunnelName(value: String): String {
        val base = value.removeSuffix(".conf").replace(Regex("[^A-Za-z0-9_=+.-]"), "-").trim('-')
        return base.take(15).ifBlank { "vcs-mobile" }
    }

    private data class EnrollmentPayload(val apiBaseUrl: String, val enrollmentToken: String)

    data class EnrollResult(val deviceName: String?)
    private data class Session(val apiBase: String, val token: String, val deviceId: String?)
}
