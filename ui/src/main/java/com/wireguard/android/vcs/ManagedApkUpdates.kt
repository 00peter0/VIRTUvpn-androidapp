package com.wireguard.android.vcs

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.wireguard.android.BuildConfig
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/** Persisted DownloadManager identity and immutable expected release metadata. No URL fallback. */
object ManagedApkUpdates {
    private fun prefs(context: Context) = context.getSharedPreferences("managed_apk_updates", Context.MODE_PRIVATE)
    fun pruneInstalled(context: Context) {
        val storage = prefs(context)
        val manifest = runCatching { JSONObject(storage.getString("manifest", "{}") ?: "{}") }.getOrNull()
        if (manifest != null && manifest.optLong("latestVersionCode", Long.MAX_VALUE) <= BuildConfig.VERSION_CODE) {
            val id = pendingId(context)
            if (id > 0) context.getSystemService(DownloadManager::class.java)?.remove(id)
            forget(context)
        }
        File(context.cacheDir, "managed-update").listFiles()?.filter { file ->
            Regex("verified-[0-9]+\\.apk").matches(file.name) &&
                (file.name.removePrefix("verified-").removeSuffix(".apk").toLongOrNull() ?: Long.MAX_VALUE) <= BuildConfig.VERSION_CODE
        }?.forEach { it.delete() }
    }
    fun pendingId(context: Context): Long = prefs(context).getLong("download_id", -1L)
    fun manifest(context: Context): JSONObject? = runCatching {
        JSONObject(prefs(context).getString("manifest", null) ?: return null)
    }.getOrNull()
    data class Download(val status: Int, val bytes: Long, val total: Long, val reason: Int)
    fun download(context: Context): Download? {
        val id = pendingId(context)
        if (id <= 0) return null
        val manager = context.getSystemService(DownloadManager::class.java) ?: return null
        return manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            fun value(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
            Download(value(DownloadManager.COLUMN_STATUS).toInt(), value(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR),
                value(DownloadManager.COLUMN_TOTAL_SIZE_BYTES), value(DownloadManager.COLUMN_REASON).toInt())
        }
    }
    fun matches(context: Context, manifest: JSONObject): Boolean = runCatching {
        val stored = JSONObject(prefs(context).getString("manifest", null) ?: return false)
        stored.optLong("latestVersionCode") == manifest.optLong("latestVersionCode") &&
            stored.optJSONObject("integrity")?.optString("sha256") == manifest.optJSONObject("integrity")?.optString("sha256")
    }.getOrDefault(false)
    @android.annotation.SuppressLint("ApplySharedPref") // Do not recover a rejected download after process death.
    fun forget(context: Context) { prefs(context).edit().remove("download_id").remove("manifest").commit() }
    fun remember(context: Context, id: Long, manifest: JSONObject) {
        check(prefs(context).edit().putLong("download_id", id).putString("manifest", manifest.toString()).commit())
    }
    fun validManifest(manifest: JSONObject): Boolean {
        val integrity = manifest.optJSONObject("integrity") ?: return false
        return manifest.optString("packageName") == BuildConfig.APPLICATION_ID &&
            manifest.optLong("latestVersionCode") > BuildConfig.VERSION_CODE &&
            integrity.optLong("sizeBytes") in 1..67_108_864 &&
            Regex("[a-f0-9]{64}").matches(integrity.optString("sha256")) &&
            Regex("[a-f0-9]{64}").matches(integrity.optString("signerSha256"))
    }
    fun progress(context: Context): String {
        ManagedUpdateFlow.summary()?.let { return it }
        val id = pendingId(context)
        if (id < 0) return "No update download"
        val manager = context.getSystemService(DownloadManager::class.java) ?: return "Download service unavailable"
        manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return "Download missing — retry available"
            fun value(key: String) = cursor.getLong(cursor.getColumnIndexOrThrow(key))
            return when (value(DownloadManager.COLUMN_STATUS).toInt()) {
                DownloadManager.STATUS_SUCCESSFUL -> "Downloaded — verification required before installation"
                DownloadManager.STATUS_FAILED -> "Download failed — retry available"
                DownloadManager.STATUS_PAUSED -> "Download paused — waiting for network/storage"
                else -> {
                    val total = value(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    val done = value(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    if (total > 0) "Downloading update: ${(100 * done / total).coerceIn(0, 100)}%" else "Downloading update"
                }
            }
        }
        return "Download status unavailable"
    }
    @Suppress("DEPRECATION")
    private fun signerHashes(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures.orEmpty().map { sha256(it.toByteArray()) }.toSet()
    }
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Must run off the UI thread; returns only an app-owned, fully verified APK. */
    @Suppress("DEPRECATION")
    fun verify(context: Context, manager: DownloadManager, id: Long, onProgress: (Int) -> Unit = {}): File {
        val storage = prefs(context)
        check(id == pendingId(context)) { "Update identity does not match" }
        val manifest = JSONObject(storage.getString("manifest", null) ?: error("Update manifest is missing"))
        check(validManifest(manifest)) { "Update manifest is invalid" }
        val integrity = manifest.getJSONObject("integrity")
        val expectedSize = integrity.getLong("sizeBytes")
        val uri = manager.getUriForDownloadedFile(id) ?: error("Download is not complete")
        val directory = File(context.cacheDir, "managed-update").apply { mkdirs() }
        val temporary = File.createTempFile("verify-", ".apk", directory)
        try {
            val hash = MessageDigest.getInstance("SHA-256")
            var count = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        count += read
                        check(count <= expectedSize) { "Update exceeds its declared size" }
                        hash.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress((count * 100 / expectedSize).toInt().coerceIn(0, 100))
                    }
                    output.fd.sync()
                }
            } ?: error("Cannot read downloaded update")
            val actualHash = hash.digest().joinToString("") { "%02x".format(it) }
            check(count == expectedSize && actualHash == integrity.getString("sha256")) { "Update checksum/size mismatch" }
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = context.packageManager.getPackageArchiveInfo(temporary.absolutePath, flags) ?: error("Invalid APK")
            val installed = context.packageManager.getPackageInfo(context.packageName, flags)
            val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            check(archive.packageName == context.packageName && version == manifest.getLong("latestVersionCode") &&
                archive.versionName == manifest.getString("latestVersionName")) { "Update package/version mismatch" }
            val expectedSigner = setOf(integrity.getString("signerSha256"))
            check(signerHashes(archive) == expectedSigner && signerHashes(installed) == expectedSigner) { "Update signing identity mismatch" }
            val verified = File(directory, "verified-$version.apk")
            check(temporary.renameTo(verified)) { "Cannot save verified update" }
            // Retain only this app's current verified candidate, never arbitrary Downloads files.
            directory.listFiles()?.filter { it != verified && it.name.startsWith("verified-") && it.name.endsWith(".apk") }?.forEach { it.delete() }
            return verified
        } finally { temporary.delete() }
    }
}
