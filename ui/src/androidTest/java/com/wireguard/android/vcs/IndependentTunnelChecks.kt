package com.wireguard.android.vcs

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.wireguard.android.Application
import com.wireguard.android.backend.Tunnel
import com.wireguard.android.model.ObservableTunnel
import com.wireguard.android.util.QrCodeFromFileScanner
import com.wireguard.android.util.TunnelImporter
import com.wireguard.config.Config
import com.wireguard.crypto.KeyPair
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.UUID

/** Creates only inactive, uniquely named fixtures; never connects or changes account identity. */
internal object IndependentTunnelChecks {
    fun verify(context: Context): List<String> = runBlocking {
        val manager = Application.getTunnelManager()
        val original = manager.getTunnels().map { it.name }.toSet()
        val scope = VcsManagedClient.tunnelAccountScope(context)
        check(scope.valid)
        val backend = Application.getBackend()
        val running = backend.runningTunnelNames.toSet()
        val digest: (ByteArray) -> String = { MessageDigest.getInstance("SHA-256").digest(it).joinToString("") { b -> "%02x".format(b) } }
        fun inventory() = context.filesDir.listFiles().orEmpty().filter { it.name.endsWith(".conf") }.associate { it.name to digest(it.readBytes()) }
        val before = inventory()
        val prefix = "ot" + UUID.randomUUID().toString().replace("-", "").take(7)
        val names = mutableSetOf<String>()
        val uris = mutableListOf<Uri>()
        val privateKey = KeyPair().privateKey.toBase64()
        val configText = """
            [Interface]
            PrivateKey = $privateKey
            Address = 192.0.2.10/32
            [Peer]
            PublicKey = ${KeyPair().publicKey.toBase64()}
            AllowedIPs = 192.0.2.0/24
            Endpoint = 192.0.2.1:51820
        """.trimIndent() + "\n"
        val config = Config.parse(ByteArrayInputStream(configText.toByteArray()))
        fun fixtureUri(name: String, mime: String, collection: Uri): Uri {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH,
                    if (mime == "image/png") Environment.DIRECTORY_PICTURES + "/VirtuVPN-test-919"
                    else Environment.DIRECTORY_DOWNLOADS + "/VirtuVPN-test-919")
            }
            return checkNotNull(context.contentResolver.insert(collection, values)).also { uris += it }
        }
        try {
            val manualName = prefix + "m"
            check(manualName !in original)
            val manual = manager.create(manualName, config, independentOnly = true).also { names += it.name }
            check(VcsManagedClient.tunnelAccountScope(context).isIndependent(manual.name))
            check(manual.state == Tunnel.State.DOWN && manual.getConfigAsync().toWgQuickString() == config.toWgQuickString())
            check(!context.getFileStreamPath(manual.name + ".conf").readText().contains(privateKey))
            val manualHash = inventory()[manual.name + ".conf"]
            check(runCatching { manager.create(manual.name, config, independentOnly = true) }.isFailure)
            check(inventory()[manual.name + ".conf"] == manualHash)

            val fileName = prefix + "f"
            val uri = fixtureUri(fileName + ".conf", "application/octet-stream", MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            context.contentResolver.openOutputStream(uri)!!.use { it.write(configText.toByteArray()) }
            var imported = emptyList<ObservableTunnel>()
            TunnelImporter.importTunnel(context.contentResolver, uri, {}) { imported = it.toList(); names += it.map { t -> t.name } }
            check(imported.single().name == fileName) { "Imported file name changed" }
            check(imported.single().getConfigAsync().toWgQuickString() == config.toWgQuickString()) { "Imported configuration values changed" }

            val qrUri = fixtureUri(prefix + ".png", "image/png", MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            val matrix = QRCodeWriter().encode(configText, BarcodeFormat.QR_CODE, 768, 768)
            val bitmap = Bitmap.createBitmap(768, 768, Bitmap.Config.ARGB_8888)
            for (y in 0 until 768) for (x in 0 until 768) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            context.contentResolver.openOutputStream(qrUri)!!.use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            bitmap.recycle()
            check(QrCodeFromFileScanner.validContentType(context.contentResolver, qrUri))
            val decoded = QrCodeFromFileScanner(context.contentResolver, QRCodeReader()).scan(qrUri).text
            check(decoded == configText)
            val qrName = prefix + "q"
            val qr = manager.create(qrName, Config.parse(ByteArrayInputStream(decoded.toByteArray())), independentOnly = true).also { names += it.name }
            check(qr.state == Tunnel.State.DOWN)
            check(VcsManagedClient.supportsInternetDiagnostics(context, qr.name))

            for (tunnel in manager.getTunnels().filter { it.name in scope.owners }) {
                check(runCatching { tunnel.deleteAsync() }.isFailure) { "A managed tunnel allowed manual deletion" }
                check(runCatching { manager.create(tunnel.name, config, independentOnly = true) }.isFailure)
            }
            for (name in names.toList()) {
                val tunnel = checkNotNull(manager.getTunnels()[name])
                tunnel.deleteAsync()
                check(manager.getTunnels()[name] == null && !manager.hasExistingConfig(name))
                names -= name
            }
            check(inventory() == before) { "Existing profile files changed" }
            check(manager.getTunnels().map { it.name }.toSet() == original)
            check(VcsManagedClient.tunnelAccountScope(context) == scope)
            check(backend.runningTunnelNames.toSet() == running)
            listOf(
                "Manual local creation is inactive, encrypted and cannot overwrite a duplicate name",
                "Native importer reads a real content:// WireGuard file with exact configuration preservation",
                "QR image is decoded from real content:// data and its configuration is preserved",
                "Current and foreign managed profiles reject manual deletion and independent creation",
                "Independent profiles can be deleted without VCS synchronization",
                "Original profile files, ownership, active VPN and account identity remain unchanged"
            )
        } finally {
            for (name in names) manager.getTunnels()[name]?.let { check(VcsManagedClient.tunnelAccountScope(context).isIndependent(name)); it.deleteAsync() }
            uris.forEach { context.contentResolver.delete(it, null, null) }
        }
    }
}
