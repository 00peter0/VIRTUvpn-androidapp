package com.wireguard.android.vcs

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.wireguard.android.Application
import com.wireguard.android.BuildConfig
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
import java.util.Locale

object VcsManagedClient {
    private const val PREFS = "vcs_managed_client"
    private const val KEY_API_BASE = "api_base"
    private const val KEY_ACCESS_TOKEN = "access_token"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_ASSIGNMENTS = "assignments"
    private const val KEY_LAST_UPDATE_PROMPT = "last_update_prompt"
    private const val KEY_LAST_UPDATE_URL = "last_update_url"
    private const val TIMEOUT_MS = 15_000

    data class SyncResult(val imported: Int, val assigned: Int, val deviceName: String?)

    suspend fun handleEnrollmentPayload(context: Context, payload: String): SyncResult = withContext(Dispatchers.IO) {
        val parsed = parseEnrollmentPayload(payload)
        completeEnrollment(context, parsed.apiBaseUrl, parsed.enrollmentToken)
        syncManagedTunnels(context)
    }

    suspend fun handleEnrollmentUri(context: Context, uri: Uri): SyncResult? = withContext(Dispatchers.IO) {
        if (uri.scheme != "virtuvpn" || uri.host != "enroll") return@withContext null
        val token = uri.getQueryParameter("token") ?: return@withContext null
        val api = uri.getQueryParameter("api") ?: return@withContext null
        completeEnrollment(context, api, token)
        syncManagedTunnels(context)
    }

    suspend fun syncManagedTunnels(context: Context): SyncResult = withContext(Dispatchers.IO) {
        val session = requireSession(context)
        val syncUrl = "${session.apiBase}/api/mobile/android/sync?appVersion=${urlEncode(BuildConfig.VERSION_NAME)}&androidVersion=${urlEncode(Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())}"
        val sync = requestJson("GET", syncUrl, null, session.token)
        handleUpdateAvailable(context, sync.optJSONObject("update"))
        val assignments = sync.optJSONArray("assignments") ?: JSONArray()
        val bundledAssignmentIds = mutableSetOf<String>()
        var imported = 0
        val bundle = sync.optJSONObject("bundle")
        if (bundle != null && bundle.optString("config").isNotBlank()) {
            val localTunnelName = importManagedBundle(context, bundle)
            val ids = bundle.optJSONArray("assignmentIds") ?: JSONArray()
            for (i in 0 until ids.length()) bundledAssignmentIds.add(ids.getString(i))
            for (i in 0 until assignments.length()) {
                val assignment = assignments.getJSONObject(i)
                if (bundledAssignmentIds.contains(assignment.optString("id"))) assignment.put("localTunnelName", localTunnelName)
            }
            imported += 1
        }
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            if (assignment.optString("status") != "ACTIVE" && assignment.optString("status") != "REISSUE_REQUIRED") continue
            if (bundledAssignmentIds.contains(assignment.optString("id"))) continue
            if (assignment.optString("kind") != "VPN_ROUTE" && assignment.optString("kind") != "AGENT_GATEWAY_PROFILE") continue
            val assignmentId = assignment.getString("id")
            val provision = requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/$assignmentId/provision", JSONObject(), session.token)
            val localTunnelName = importManagedConfig(context, session, provision)
            assignment.put("localTunnelName", localTunnelName)
            imported += 1
        }
        storeAssignments(context, assignments)
        val device = sync.optJSONObject("device")
        SyncResult(imported, assignments.length(), device?.optString("deviceName")?.takeIf { it.isNotBlank() })
    }


    private fun handleUpdateAvailable(context: Context, update: JSONObject?) {
        if (update == null || !update.optBoolean("updateAvailable", false)) return
        val latestVersion = update.optString("latestVersionName").takeIf { it.isNotBlank() } ?: return
        val apkUrl = update.optString("apkUrl").takeIf { it.isNotBlank() } ?: return
        openUpdateUrl(context, latestVersion, apkUrl, respectPromptMemory = true)
    }

    fun openManagedUpdate(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val latestVersion = prefs.getString(KEY_LAST_UPDATE_PROMPT, null) ?: return false
        val apkUrl = prefs.getString(KEY_LAST_UPDATE_URL, null) ?: return false
        return openUpdateUrl(context, latestVersion, apkUrl, respectPromptMemory = false)
    }

    private fun openUpdateUrl(context: Context, latestVersion: String, apkUrl: String, respectPromptMemory: Boolean): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (respectPromptMemory && prefs.getString(KEY_LAST_UPDATE_PROMPT, null) == latestVersion) return false
        prefs.edit().putString(KEY_LAST_UPDATE_URL, apkUrl).apply()
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(apkUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            prefs.edit().putString(KEY_LAST_UPDATE_PROMPT, latestVersion).putString(KEY_LAST_UPDATE_URL, apkUrl).apply()
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun urlEncode(value: String): String = java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.name())

    suspend fun reportCurrentStates(context: Context) = withContext(Dispatchers.IO) {
        val session = loadSession(context) ?: return@withContext
        val assignments = loadAssignments(context)
        val tunnels = Application.getTunnelManager().getTunnels()
        for (i in 0 until assignments.length()) {
            val assignment = assignments.getJSONObject(i)
            val tunnelName = assignment.optString("localTunnelName")
            val tunnel = tunnels[tunnelName] ?: continue
            val stats = runCatching { tunnel.getStatisticsAsync() }.getOrNull()
            val body = JSONObject()
                .put("state", tunnel.state.name.lowercase(Locale.US))
                .put("rxBytes", stats?.totalRx())
                .put("txBytes", stats?.totalTx())
                .put("metadata", JSONObject().put("localTunnelName", tunnel.name))
            requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/${assignment.getString("id")}/state", body, session.token)
        }
    }


    private suspend fun importManagedBundle(context: Context, bundle: JSONObject): String {
        val configText = bundle.getString("config")
        val preferredName = sanitizeTunnelName(bundle.optString("configFilename", bundle.optString("displayName", "VCS Managed Access")))
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        withContext(Dispatchers.Main.immediate) {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val existing = tunnels[preferredName]
            if (existing == null) {
                manager.create(preferredName, config)
            } else {
                existing.setConfigAsync(config)
            }
        }
        return preferredName
    }

    private suspend fun importManagedConfig(context: Context, session: Session, provision: JSONObject): String {
        val configText = provision.getString("config")
        val assignmentId = provision.getString("assignmentId")
        val configVersion = provision.optInt("configVersion", 1)
        val preferredName = sanitizeTunnelName(provision.optString("configFilename", provision.optString("displayName", "vcs-$assignmentId")))
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray(StandardCharsets.UTF_8)))
        withContext(Dispatchers.Main.immediate) {
            val manager = Application.getTunnelManager()
            val tunnels = manager.getTunnels()
            val existing = tunnels[preferredName]
            if (existing == null) {
                manager.create(preferredName, config)
            } else {
                existing.setConfigAsync(config)
            }
        }
        val body = JSONObject()
            .put("localTunnelName", preferredName)
            .put("configVersion", configVersion)
        requestJson("POST", "${session.apiBase}/api/mobile/android/tunnels/$assignmentId/ack-imported", body, session.token)
        return preferredName
    }

    private fun completeEnrollment(context: Context, apiBaseUrl: String, enrollmentToken: String) {
        val body = JSONObject()
            .put("enrollmentToken", enrollmentToken)
            .put("deviceName", Build.MODEL ?: "Android device")
            .put("appVersion", BuildConfig.VERSION_NAME)
            .put("androidVersion", Build.VERSION.RELEASE ?: Build.VERSION.SDK_INT.toString())
            .put("devicePublicKey", JSONObject.NULL)
            .put("metadata", JSONObject().put("manufacturer", Build.MANUFACTURER).put("sdk", Build.VERSION.SDK_INT))
        val response = requestJson("POST", "${apiBaseUrl.trimEnd('/')}/api/mobile/android/enroll/complete", body, null)
        val device = response.getJSONObject("device")
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_API_BASE, apiBaseUrl.trimEnd('/'))
            .putString(KEY_ACCESS_TOKEN, response.getString("accessToken"))
            .putString(KEY_DEVICE_ID, device.getString("id"))
            .apply()
    }

    private fun parseEnrollmentPayload(payload: String): EnrollmentPayload {
        val trimmed = payload.trim()
        if (trimmed.startsWith("virtuvpn://")) {
            val uri = Uri.parse(trimmed)
            return EnrollmentPayload(
                uri.getQueryParameter("api") ?: error("Enrollment link is missing api"),
                uri.getQueryParameter("token") ?: error("Enrollment link is missing token"),
            )
        }
        val json = JSONObject(trimmed)
        if (json.optString("type") != "vcs_android_enrollment") error("QR is not a VCS Android enrollment payload")
        return EnrollmentPayload(json.getString("apiBaseUrl"), json.getString("enrollmentToken"))
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

    private fun requireSession(context: Context): Session = loadSession(context) ?: error("Enroll this device from VCS App first")

    private fun loadSession(context: Context): Session? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val apiBase = prefs.getString(KEY_API_BASE, null) ?: return null
        val token = prefs.getString(KEY_ACCESS_TOKEN, null) ?: return null
        return Session(apiBase, token, prefs.getString(KEY_DEVICE_ID, null))
    }

    private fun storeAssignments(context: Context, assignments: JSONArray) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ASSIGNMENTS, assignments.toString()).apply()
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
    private data class Session(val apiBase: String, val token: String, val deviceId: String?)
}
