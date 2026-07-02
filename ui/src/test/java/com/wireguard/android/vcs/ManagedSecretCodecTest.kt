package com.wireguard.android.vcs

import com.wireguard.android.util.EncryptedSecretCodec
import com.wireguard.android.util.EncryptedSecretMigrator
import com.wireguard.android.util.SecretAead
import com.wireguard.android.util.SecretCiphertext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ManagedSecretCodecTest {
    @Test
    fun encryptedValueRoundTripsWithPrefix() {
        val codec = EncryptedSecretCodec(FakeAead())

        val encrypted = codec.encrypt("device-token")

        assertTrue(EncryptedSecretCodec.isEncryptedValue(encrypted))
        assertEquals("device-token", codec.decrypt(encrypted))
    }

    @Test
    fun plaintextValueIsNotMarkedEncrypted() {
        assertFalse(EncryptedSecretCodec.isEncryptedValue("legacy-token"))
    }

    @Test(expected = IllegalStateException::class)
    fun decryptRejectsMalformedEncryptedValue() {
        EncryptedSecretCodec(FakeAead()).decrypt("enc:v1:not-valid")
    }

    @Test(expected = IllegalStateException::class)
    fun decryptFailureFailsClosedToCaller() {
        val codec = EncryptedSecretCodec(
            object : SecretAead {
                override fun encrypt(plaintext: ByteArray): SecretCiphertext {
                    return SecretCiphertext(byteArrayOf(1), plaintext)
                }

                override fun decrypt(ciphertext: SecretCiphertext): ByteArray {
                    error("decrypt failed")
                }
            }
        )

        codec.decrypt(codec.encrypt("token"))
    }

    @Test
    fun migratorReturnsEncryptedHitWithoutRewriting() {
        val codec = EncryptedSecretCodec(FakeAead())
        val migrator = EncryptedSecretMigrator(codec)
        var writes = 0
        var clears = 0

        val value = migrator.readSecret(
            stored = codec.encrypt("account-token"),
            writeEncrypted = { writes += 1 },
            clearSecrets = { clears += 1 }
        )

        assertEquals("account-token", value)
        assertEquals(0, writes)
        assertEquals(0, clears)
    }

    @Test
    fun migratorEncryptsLegacyPlaintextOnRead() {
        val codec = EncryptedSecretCodec(FakeAead())
        val migrator = EncryptedSecretMigrator(codec)
        var storedEncrypted: String? = null
        var clears = 0

        val value = migrator.readSecret(
            stored = "legacy-token",
            writeEncrypted = { storedEncrypted = it },
            clearSecrets = { clears += 1 }
        )

        assertEquals("legacy-token", value)
        val encrypted = storedEncrypted ?: error("legacy token was not migrated")
        assertTrue(EncryptedSecretCodec.isEncryptedValue(encrypted))
        assertEquals("legacy-token", codec.decrypt(encrypted))
        assertEquals(0, clears)
    }

    @Test
    fun migratorClearsSecretsOnDecryptFailure() {
        val goodCodec = EncryptedSecretCodec(FakeAead())
        val failingMigrator = EncryptedSecretMigrator(
            EncryptedSecretCodec(
                object : SecretAead {
                    override fun encrypt(plaintext: ByteArray): SecretCiphertext {
                        return SecretCiphertext(byteArrayOf(1), plaintext)
                    }

                    override fun decrypt(ciphertext: SecretCiphertext): ByteArray {
                        error("decrypt failed")
                    }
                }
            )
        )
        var clears = 0

        val value = failingMigrator.readSecret(
            stored = goodCodec.encrypt("device-token"),
            writeEncrypted = { error("must not rewrite encrypted value") },
            clearSecrets = { clears += 1 }
        )

        assertNull(value)
        assertEquals(1, clears)
    }

    @Test
    fun migratorClearsSecretsOnLegacyEncryptionFailure() {
        val failingMigrator = EncryptedSecretMigrator(
            EncryptedSecretCodec(
                object : SecretAead {
                    override fun encrypt(plaintext: ByteArray): SecretCiphertext {
                        error("encrypt failed")
                    }

                    override fun decrypt(ciphertext: SecretCiphertext): ByteArray {
                        return ciphertext.ciphertext
                    }
                }
            )
        )
        var clears = 0

        val value = failingMigrator.readSecret(
            stored = "legacy-token",
            writeEncrypted = { error("must not write failed encryption") },
            clearSecrets = { clears += 1 }
        )

        assertNull(value)
        assertEquals(1, clears)
    }

    private class FakeAead : SecretAead {
        override fun encrypt(plaintext: ByteArray): SecretCiphertext {
            return SecretCiphertext(byteArrayOf(7, 8, 9), plaintext.reversedArray())
        }

        override fun decrypt(ciphertext: SecretCiphertext): ByteArray {
            require(ciphertext.iv.contentEquals(byteArrayOf(7, 8, 9))) { "bad iv" }
            return ciphertext.ciphertext.reversedArray().toString(StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8)
        }
    }
}
