package com.wireguard.android.vcs

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Binds a legacy device to this installation after fresh account authentication.
 * The proof cannot be used as an account, device access or refresh token. */
internal object LegacyInstallationHandoff {
    fun proof(deviceId: String, installationId: String, installationSecret: String, refreshToken: String): String {
        require(deviceId.matches(Regex("[A-Za-z0-9_-]{1,128}")))
        require(installationId.matches(Regex("[0-9a-fA-F-]{36}")))
        require(installationSecret.matches(Regex("[0-9a-fA-F]{64}")))
        require(refreshToken.isNotBlank())
        val verifier = sha256(refreshToken)
        val message = listOf("vcs.android.installation-handoff.v1", deviceId, installationId, sha256(installationSecret)).joinToString("\n")
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(verifier.toByteArray(UTF_8), "HmacSHA256"))
            doFinal(message.toByteArray(UTF_8)).hex()
        }
    }

    private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)).hex()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
