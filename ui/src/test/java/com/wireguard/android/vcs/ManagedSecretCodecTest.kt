package com.wireguard.android.vcs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ManagedSecretCodecTest {
    @Test
    fun encryptedValueRoundTripsWithPrefix() {
        val codec = ManagedSecretCodec(FakeAead())

        val encrypted = codec.encrypt("device-token")

        assertTrue(ManagedSecretCodec.isEncryptedValue(encrypted))
        assertEquals("device-token", codec.decrypt(encrypted))
    }

    @Test
    fun plaintextValueIsNotMarkedEncrypted() {
        assertFalse(ManagedSecretCodec.isEncryptedValue("legacy-token"))
    }

    @Test(expected = IllegalStateException::class)
    fun decryptRejectsMalformedEncryptedValue() {
        ManagedSecretCodec(FakeAead()).decrypt("enc:v1:not-valid")
    }

    @Test(expected = IllegalStateException::class)
    fun decryptFailureFailsClosedToCaller() {
        val codec = ManagedSecretCodec(
            object : ManagedSecretAead {
                override fun encrypt(plaintext: ByteArray): ManagedSecretCiphertext {
                    return ManagedSecretCiphertext(byteArrayOf(1), plaintext)
                }

                override fun decrypt(ciphertext: ManagedSecretCiphertext): ByteArray {
                    error("decrypt failed")
                }
            }
        )

        codec.decrypt(codec.encrypt("token"))
    }

    @Test
    fun migratorReturnsEncryptedHitWithoutRewriting() {
        val codec = ManagedSecretCodec(FakeAead())
        val migrator = ManagedSecretMigrator(codec)
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
        val codec = ManagedSecretCodec(FakeAead())
        val migrator = ManagedSecretMigrator(codec)
        var storedEncrypted: String? = null
        var clears = 0

        val value = migrator.readSecret(
            stored = "legacy-token",
            writeEncrypted = { storedEncrypted = it },
            clearSecrets = { clears += 1 }
        )

        assertEquals("legacy-token", value)
        val encrypted = storedEncrypted ?: error("legacy token was not migrated")
        assertTrue(ManagedSecretCodec.isEncryptedValue(encrypted))
        assertEquals("legacy-token", codec.decrypt(encrypted))
        assertEquals(0, clears)
    }

    @Test
    fun migratorClearsSecretsOnDecryptFailure() {
        val goodCodec = ManagedSecretCodec(FakeAead())
        val failingMigrator = ManagedSecretMigrator(
            ManagedSecretCodec(
                object : ManagedSecretAead {
                    override fun encrypt(plaintext: ByteArray): ManagedSecretCiphertext {
                        return ManagedSecretCiphertext(byteArrayOf(1), plaintext)
                    }

                    override fun decrypt(ciphertext: ManagedSecretCiphertext): ByteArray {
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
        val failingMigrator = ManagedSecretMigrator(
            ManagedSecretCodec(
                object : ManagedSecretAead {
                    override fun encrypt(plaintext: ByteArray): ManagedSecretCiphertext {
                        error("encrypt failed")
                    }

                    override fun decrypt(ciphertext: ManagedSecretCiphertext): ByteArray {
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

    private class FakeAead : ManagedSecretAead {
        override fun encrypt(plaintext: ByteArray): ManagedSecretCiphertext {
            return ManagedSecretCiphertext(byteArrayOf(7, 8, 9), plaintext.reversedArray())
        }

        override fun decrypt(ciphertext: ManagedSecretCiphertext): ByteArray {
            require(ciphertext.iv.contentEquals(byteArrayOf(7, 8, 9))) { "bad iv" }
            return ciphertext.ciphertext.reversedArray().toString(StandardCharsets.UTF_8).toByteArray(StandardCharsets.UTF_8)
        }
    }
}
