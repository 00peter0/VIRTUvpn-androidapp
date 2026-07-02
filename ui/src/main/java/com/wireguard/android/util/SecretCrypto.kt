package com.wireguard.android.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface SecretAead {
    fun encrypt(plaintext: ByteArray): SecretCiphertext
    fun decrypt(ciphertext: SecretCiphertext): ByteArray
}

internal data class SecretCiphertext(val iv: ByteArray, val ciphertext: ByteArray)

internal class EncryptedSecretCodec(private val aead: SecretAead) {
    fun encrypt(value: String): String {
        val encrypted = aead.encrypt(value.toByteArray(StandardCharsets.UTF_8))
        return "$PREFIX${b64(encrypted.iv)}:${b64(encrypted.ciphertext)}"
    }

    fun decrypt(value: String): String {
        if (!isEncryptedValue(value)) error("Secret value is not encrypted")
        val encoded = value.removePrefix(PREFIX)
        val separator = encoded.indexOf(':')
        if (separator <= 0 || separator == encoded.lastIndex) error("Encrypted secret value is malformed")
        val iv = unb64(encoded.substring(0, separator))
        val ciphertext = unb64(encoded.substring(separator + 1))
        return aead.decrypt(SecretCiphertext(iv, ciphertext)).toString(StandardCharsets.UTF_8)
    }

    companion object {
        private const val PREFIX = "enc:v1:"

        fun isEncryptedValue(value: String): Boolean = value.startsWith(PREFIX)

        private fun b64(value: ByteArray): String = Base64.getEncoder().encodeToString(value)
        private fun unb64(value: String): ByteArray = Base64.getDecoder().decode(value)
    }
}

internal class EncryptedSecretMigrator(private val codec: EncryptedSecretCodec) {
    fun readSecret(
        stored: String?,
        writeEncrypted: (String) -> Unit,
        clearSecrets: () -> Unit
    ): String? {
        stored ?: return null
        if (EncryptedSecretCodec.isEncryptedValue(stored)) {
            return runCatching { codec.decrypt(stored) }
                .getOrElse {
                    clearSecrets()
                    null
                }
        }
        return runCatching {
            writeEncrypted(codec.encrypt(stored))
            stored
        }.getOrElse {
            clearSecrets()
            null
        }
    }
}

internal class AndroidKeystoreAead(private val alias: String) : SecretAead {
    override fun encrypt(plaintext: ByteArray): SecretCiphertext {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return SecretCiphertext(cipher.iv, cipher.doFinal(plaintext))
    }

    override fun decrypt(ciphertext: SecretCiphertext): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, ciphertext.iv))
        return cipher.doFinal(ciphertext.ciphertext)
    }

    @Synchronized
    private fun secretKey(): SecretKey {
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setUserAuthenticationRequired(false)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private companion object {
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}
